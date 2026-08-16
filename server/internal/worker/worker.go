package worker

import (
	"context"
	"log/slog"
	"math"
	"strings"
	"time"

	"omnisms/internal/mailer"
	"omnisms/internal/store"
)

type Worker struct {
	Store    *store.Store
	Mailer   mailer.Sender
	Log      *slog.Logger
	Interval time.Duration
	Now      func() time.Time
}

func (w *Worker) Run(ctx context.Context) {
	_ = w.Store.RecoverSending(ctx, w.Now().UTC().Add(-10*time.Minute))
	w.process(ctx)
	ticker := time.NewTicker(w.Interval)
	defer ticker.Stop()
	cleanup := time.NewTicker(time.Hour)
	defer cleanup.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.process(ctx)
		case <-cleanup.C:
			w.cleanup(ctx)
		}
	}
}
func (w *Worker) process(ctx context.Context) {
	for i := 0; i < 20; i++ {
		now := w.Now().UTC()
		d, ok, err := w.Store.NextDue(ctx, now)
		if err != nil {
			w.Log.Error("delivery lookup failed", "error", err)
			return
		}
		if !ok {
			return
		}
		if err = w.Store.BeginAttempt(ctx, d.DeviceID, d.MessageID); err != nil {
			w.Log.Error("delivery state update failed", "error", err)
			return
		}
		err = w.Mailer.Send(ctx, d, now)
		if err == nil {
			if e := w.Store.MarkSent(ctx, d.DeviceID, d.MessageID, now); e != nil {
				w.Log.Error("delivery completion persistence failed", "error", e)
			} else {
				w.Log.Info("message delivered", "message", shortID(d.MessageID))
			}
			continue
		}
		attempt := d.AttemptCount + 1
		code := mailErrorCode(err)
		if permanentMailError(code) {
			if e := w.Store.MarkPermanentFailure(ctx, d.DeviceID, d.MessageID, code); e != nil {
				w.Log.Error("failure persistence failed", "error", e)
			} else {
				w.Log.Error("message delivery requires configuration repair", "message", shortID(d.MessageID), "code", code)
			}
			return
		}
		delay := backoff(attempt)
		if e := w.Store.MarkRetry(ctx, d.DeviceID, d.MessageID, code, now.Add(delay)); e != nil {
			w.Log.Error("retry persistence failed", "error", e)
			return
		}
		w.Log.Warn("message delivery deferred", "message", shortID(d.MessageID), "code", code, "attempt", attempt)
	}
}
func (w *Worker) cleanup(ctx context.Context) {
	count, err := w.Store.Cleanup(ctx, w.Now().UTC())
	if err != nil {
		w.Log.Error("cleanup failed", "error", err)
		return
	}
	w.Log.Info("cleanup completed", "deleted", count)
}
func backoff(attempt int) time.Duration {
	minutes := math.Pow(2, float64(min(attempt-1, 6)))
	return time.Duration(minutes) * time.Minute
}
func mailErrorCode(err error) string {
	v := err.Error()
	for _, code := range []string{"smtp_auth", "smtp_tls_required", "smtp_tls", "smtp_connect", "smtp_to", "smtp_from", "smtp_data", "smtp_write", "smtp_commit", "smtp_quit"} {
		if strings.HasPrefix(v, code) {
			return code
		}
	}
	return "smtp_unknown"
}
func permanentMailError(code string) bool {
	return code == "smtp_auth" || code == "smtp_tls_required" || code == "smtp_from" || code == "smtp_to"
}
func shortID(v string) string {
	if len(v) > 12 {
		return v[:12]
	}
	return v
}
