package worker

import (
	"context"
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
