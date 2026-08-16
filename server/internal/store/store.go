package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	_ "modernc.org/sqlite"
	"omnisms/internal/model"
)

var ErrNonceReplay = errors.New("nonce already used")

type Store struct{ db *sql.DB }

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	s := &Store{db: db}
	if _, err = db.Exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;`); err != nil {
		db.Close()
		return nil, err
	}
	if err = s.migrate(context.Background()); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error                   { return s.db.Close() }
func (s *Store) Ping(ctx context.Context) error { return s.db.PingContext(ctx) }

func (s *Store) migrate(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO schema_version(version) VALUES (1);
CREATE TABLE IF NOT EXISTS used_nonce (
 device_id TEXT NOT NULL, nonce TEXT NOT NULL, expires_at TEXT NOT NULL,
 PRIMARY KEY(device_id, nonce)
);
CREATE TABLE IF NOT EXISTS message_delivery (
 device_id TEXT NOT NULL, message_id TEXT NOT NULL, idempotency_key TEXT NOT NULL,
 sender TEXT NOT NULL, body TEXT NOT NULL, received_at TEXT NOT NULL, accepted_at TEXT NOT NULL,
 emailed_at TEXT, sim_slot INTEGER, sim_label TEXT NOT NULL, queued_offline INTEGER NOT NULL,
 app_version TEXT NOT NULL, state TEXT NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TEXT NOT NULL, last_error_code TEXT NOT NULL DEFAULT '', expires_at TEXT NOT NULL,
 PRIMARY KEY(device_id, message_id), UNIQUE(device_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_delivery_due ON message_delivery(state, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_delivery_expiry ON message_delivery(expires_at);
`)
	return err
}

func (s *Store) Accept(ctx context.Context, d model.Delivery, idempotencyKey, nonce string, nonceExpiry, retention time.Time) (bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var exists int
	err = tx.QueryRowContext(ctx, `SELECT 1 FROM message_delivery WHERE device_id=? AND (message_id=? OR idempotency_key=?) LIMIT 1`, d.DeviceID, d.MessageID, idempotencyKey).Scan(&exists)
	if err == nil {
		return true, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return false, err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO used_nonce(device_id,nonce,expires_at) VALUES(?,?,?)`, d.DeviceID, nonce, formatTime(nonceExpiry)); err != nil {
		if isConstraint(err) {
			return false, ErrNonceReplay
		}
		return false, err
	}
	var slot any
	if d.SIMSlot != nil {
		slot = *d.SIMSlot
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO message_delivery(device_id,message_id,idempotency_key,sender,body,received_at,accepted_at,sim_slot,sim_label,queued_offline,app_version,state,next_attempt_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'pending',?,?)`,
		d.DeviceID, d.MessageID, idempotencyKey, d.Sender, d.Body, formatTime(d.ReceivedAt), formatTime(d.AcceptedAt), slot, d.SIMLabel, boolInt(d.QueuedOffline), d.AppVersion, formatTime(d.AcceptedAt), formatTime(retention))
	if err != nil {
		return false, err
	}
	return false, tx.Commit()
}

func (s *Store) NextDue(ctx context.Context, now time.Time) (model.Delivery, bool, error) {
	row := s.db.QueryRowContext(ctx, `SELECT device_id,message_id,sender,body,received_at,accepted_at,sim_slot,sim_label,queued_offline,app_version,attempt_count FROM message_delivery WHERE state IN ('pending','retry') AND next_attempt_at<=? ORDER BY accepted_at LIMIT 1`, formatTime(now))
	var d model.Delivery
	var received, accepted string
	var slot sql.NullInt64
	var queued int
	err := row.Scan(&d.DeviceID, &d.MessageID, &d.Sender, &d.Body, &received, &accepted, &slot, &d.SIMLabel, &queued, &d.AppVersion, &d.AttemptCount)
	if errors.Is(err, sql.ErrNoRows) {
		return d, false, nil
	}
	if err != nil {
		return d, false, err
	}
	d.ReceivedAt, err = parseTime(received)
	if err != nil {
		return d, false, err
	}
	d.AcceptedAt, err = parseTime(accepted)
	if err != nil {
		return d, false, err
	}
	if slot.Valid {
		v := int(slot.Int64)
		d.SIMSlot = &v
	}
	d.QueuedOffline = queued != 0
	return d, true, nil
}

func (s *Store) BeginAttempt(ctx context.Context, deviceID, messageID string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE message_delivery SET state='sending',attempt_count=attempt_count+1 WHERE device_id=? AND message_id=? AND state IN ('pending','retry')`, deviceID, messageID)
	return err
}
func (s *Store) MarkSent(ctx context.Context, deviceID, messageID string, at time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE message_delivery SET state='sent',emailed_at=?,body='',last_error_code='' WHERE device_id=? AND message_id=?`, formatTime(at), deviceID, messageID)
	return err
}
func (s *Store) MarkRetry(ctx context.Context, deviceID, messageID, code string, next time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE message_delivery SET state='retry',last_error_code=?,next_attempt_at=? WHERE device_id=? AND message_id=?`, code, formatTime(next), deviceID, messageID)
	return err
}
func (s *Store) MarkPermanentFailure(ctx context.Context, deviceID, messageID, code string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE message_delivery SET state='permanent_failed',last_error_code=? WHERE device_id=? AND message_id=?`, code, deviceID, messageID)
	return err
}
func (s *Store) RecoverSending(ctx context.Context, before time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE message_delivery SET state='retry',last_error_code='interrupted',next_attempt_at=? WHERE state='sending' AND next_attempt_at<?`, formatTime(time.Now().UTC()), formatTime(before))
	return err
}
func (s *Store) Cleanup(ctx context.Context, now time.Time) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `DELETE FROM message_delivery WHERE expires_at<=?`, formatTime(now))
	if err != nil {
		return 0, err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM used_nonce WHERE expires_at<=?`, formatTime(now)); err != nil {
		return 0, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	return count, tx.Commit()
}

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
func formatTime(t time.Time) string         { return t.UTC().Format(time.RFC3339Nano) }
func parseTime(v string) (time.Time, error) { return time.Parse(time.RFC3339Nano, v) }
func isConstraint(err error) bool {
	return err != nil && (strings.Contains(strings.ToLower(err.Error()), "constraint") || strings.Contains(err.Error(), "UNIQUE"))
}
