package worker

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"omnisms/internal/model"
	"omnisms/internal/store"
)

type countingMailer struct{ count int }

func (m *countingMailer) Send(context.Context, model.Delivery, time.Time) error {
	m.count++
	return nil
}

type failingMailer struct {
	count int
	err   error
}

func (m *failingMailer) Send(context.Context, model.Delivery, time.Time) error {
	m.count++
	return m.err
}

func TestProcessDeliversAcceptedMessageOnlyOnce(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "worker.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Date(2026, 8, 17, 1, 2, 3, 0, time.UTC)
	delivery := model.Delivery{DeviceID: "device-test", MessageID: "message-example-300", Sender: "Example Sender", Body: "Fictional body", ReceivedAt: now.Add(-time.Minute), AcceptedAt: now, AppVersion: "test"}
	if _, err = db.Accept(context.Background(), delivery, delivery.MessageID, "nonce-example-300", now.Add(time.Hour), now.Add(24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	mail := &countingMailer{}
	w := &Worker{Store: db, Mailer: mail, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Interval: time.Second, Now: func() time.Time { return now }}
	w.process(context.Background())
	w.process(context.Background())
	if mail.count != 1 {
		t.Fatalf("mail count=%d, want 1", mail.count)
	}
	duplicate, err := db.Accept(context.Background(), delivery, delivery.MessageID, "nonce-example-301", now.Add(time.Hour), now.Add(24*time.Hour))
	if err != nil || !duplicate {
		t.Fatalf("duplicate Accept()=%v,%v", duplicate, err)
	}
	w.process(context.Background())
	if mail.count != 1 {
		t.Fatalf("mail count after duplicate=%d, want 1", mail.count)
	}
}

func TestTemporaryMailFailureUsesBackoff(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "worker-retry.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	delivery := model.Delivery{DeviceID: "device-test", MessageID: "message-example-retry", Sender: "Example Sender", Body: "Fictional body", ReceivedAt: now.Add(-time.Minute), AcceptedAt: now, AppVersion: "test"}
	if _, err = db.Accept(context.Background(), delivery, delivery.MessageID, "nonce-example-retry", now.Add(time.Hour), now.Add(24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	mail := &failingMailer{err: errors.New("smtp_connect: temporary test failure")}
	w := &Worker{Store: db, Mailer: mail, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Interval: time.Second, Now: func() time.Time { return now }}
	w.process(context.Background())
	if mail.count != 1 {
		t.Fatalf("mail count=%d, want 1", mail.count)
	}
	if _, due, err := db.NextDue(context.Background(), now.Add(59*time.Second)); err != nil || due {
		t.Fatalf("delivery due before backoff elapsed=%v, err=%v", due, err)
	}
	d, due, err := db.NextDue(context.Background(), now.Add(time.Minute))
	if err != nil || !due || d.AttemptCount != 1 {
		t.Fatalf("delivery after backoff due=%v attempt=%d err=%v", due, d.AttemptCount, err)
	}
}

func TestPermanentMailFailureStopsRetrying(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "worker-permanent.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	delivery := model.Delivery{DeviceID: "device-test", MessageID: "message-example-permanent", Sender: "Example Sender", Body: "Fictional body", ReceivedAt: now.Add(-time.Minute), AcceptedAt: now, AppVersion: "test"}
	if _, err = db.Accept(context.Background(), delivery, delivery.MessageID, "nonce-example-permanent", now.Add(time.Hour), now.Add(24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	mail := &failingMailer{err: errors.New("smtp_auth: permanent test failure")}
	w := &Worker{Store: db, Mailer: mail, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Interval: time.Second, Now: func() time.Time { return now }}
	w.process(context.Background())
	if mail.count != 1 {
		t.Fatalf("mail count=%d, want 1", mail.count)
	}
	if _, due, err := db.NextDue(context.Background(), now.Add(48*time.Hour)); err != nil || due {
		t.Fatalf("permanent failure became due again=%v, err=%v", due, err)
	}
}
