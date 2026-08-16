package api

import (
	"bytes"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"omnisms/internal/auth"
	"omnisms/internal/store"
)

func TestMessageEndpointAcceptsAndDeduplicates(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "api.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	secret := []byte("01234567890123456789012345678901")
	now := time.Date(2026, 8, 17, 1, 2, 3, 0, time.UTC)
	s := &Server{Store: db, DeviceID: "device-test", DeviceSecret: secret, MaxSkew: 5 * time.Minute, Retention: 24 * time.Hour, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Now: func() time.Time { return now }}
	body := []byte(`{"messageId":"message-example-100","sender":"Example Service","body":"Fictional code 123456","receivedAt":"2026-08-17T01:01:03Z","simSlot":1,"simLabel":"SIM 2","queuedOffline":false,"appVersion":"test"}`)
	call := func(nonce string) *httptest.ResponseRecorder {
		timestamp := now.Format(time.RFC3339)
		r := httptest.NewRequest(http.MethodPost, "https://sms.example.test/v1/messages", bytes.NewReader(body))
		r.Header.Set("X-Device-Id", "device-test")
		r.Header.Set("X-Timestamp", timestamp)
		r.Header.Set("X-Nonce", nonce)
		r.Header.Set("Idempotency-Key", "message-example-100")
		r.Header.Set("X-Signature", auth.EncodeSignature(auth.Sign(http.MethodPost, "/v1/messages", timestamp, nonce, "message-example-100", body, secret)))
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		return w
	}
	if got := call("nonce-example-1000").Code; got != http.StatusAccepted {
		t.Fatalf("first status=%d", got)
	}
	if got := call("nonce-example-1001").Code; got != http.StatusOK {
		t.Fatalf("duplicate status=%d", got)
	}
}

func TestMessageEndpointDoesNotEchoSensitiveBody(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "api.sqlite3"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{Store: db, DeviceID: "device-test", DeviceSecret: []byte("01234567890123456789012345678901"), MaxSkew: time.Minute, Retention: 24 * time.Hour, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Now: time.Now}
	r := httptest.NewRequest(http.MethodPost, "https://sms.example.test/v1/messages", bytes.NewBufferString(`{"body":"secret fictional code 987654"}`))
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if bytes.Contains(w.Body.Bytes(), []byte("987654")) {
		t.Fatal("response echoed message body")
	}
}
