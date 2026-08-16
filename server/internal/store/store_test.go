package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"omnisms/internal/model"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "test.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}
func testDelivery(now time.Time, id string) model.Delivery {
	return model.Delivery{DeviceID: "device-test", MessageID: id, Sender: "Example Sender", Body: "Your fictional code is 123456", ReceivedAt: now.Add(-time.Minute), AcceptedAt: now, SIMLabel: "SIM 2", AppVersion: "test"}
}

func TestAcceptIsIdempotent(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	d := testDelivery(now, "message-example-001")
	duplicate, err := s.Accept(ctx, d, d.MessageID, "nonce-example-001", now.Add(10*time.Minute), now.Add(24*time.Hour))
	if err != nil || duplicate {
		t.Fatalf("first Accept() = %v,%v", duplicate, err)
	}
	duplicate, err = s.Accept(ctx, d, d.MessageID, "nonce-example-002", now.Add(10*time.Minute), now.Add(24*time.Hour))
	if err != nil || !duplicate {
		t.Fatalf("second Accept() = %v,%v", duplicate, err)
	}
	var count int
	if err = s.db.QueryRow(`SELECT COUNT(*) FROM message_delivery`).Scan(&count); err != nil || count != 1 {
		t.Fatalf("message count = %d, err=%v", count, err)
	}
}

func TestNonceReplayRejectedAcrossMessages(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	nonce := "nonce-example-shared"
	_, err := s.Accept(ctx, testDelivery(now, "message-example-010"), "message-example-010", nonce, now.Add(time.Hour), now.Add(24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.Accept(ctx, testDelivery(now, "message-example-011"), "message-example-011", nonce, now.Add(time.Hour), now.Add(24*time.Hour))
	if !errors.Is(err, ErrNonceReplay) {
		t.Fatalf("Accept() error=%v, want ErrNonceReplay", err)
	}
}

func TestCleanupAndClearBodyAfterDelivery(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	d := testDelivery(now, "message-example-020")
	_, err := s.Accept(ctx, d, d.MessageID, "nonce-example-020", now.Add(time.Hour), now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if err = s.MarkSent(ctx, d.DeviceID, d.MessageID, now); err != nil {
		t.Fatal(err)
	}
	var body, state string
	if err = s.db.QueryRow(`SELECT body,state FROM message_delivery WHERE message_id=?`, d.MessageID).Scan(&body, &state); err != nil {
		t.Fatal(err)
	}
	if body != "" || state != "sent" {
		t.Fatalf("body=%q state=%q", body, state)
	}
	deleted, err := s.Cleanup(ctx, now.Add(2*time.Hour))
	if err != nil || deleted != 1 {
		t.Fatalf("Cleanup()=%d,%v", deleted, err)
	}
}
