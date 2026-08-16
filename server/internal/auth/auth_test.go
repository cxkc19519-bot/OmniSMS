package auth

import (
	"net/http/httptest"
	"testing"
	"time"
)

func TestVerifySignedRequest(t *testing.T) {
	secret := []byte("01234567890123456789012345678901")
	body := []byte(`{"messageId":"example-message-001"}`)
	now := time.Date(2026, 8, 17, 1, 2, 3, 0, time.UTC)
	timestamp := now.Format(time.RFC3339)
	nonce := "example-nonce-0001"
	key := "example-message-001"
	r := httptest.NewRequest("POST", "https://sms.example.test/v1/messages", nil)
	r.Header.Set("X-Device-Id", "device-test")
	r.Header.Set("X-Timestamp", timestamp)
	r.Header.Set("X-Nonce", nonce)
	r.Header.Set("Idempotency-Key", key)
	r.Header.Set("X-Signature", EncodeSignature(Sign("POST", "/v1/messages", timestamp, nonce, key, body, secret)))
	if _, err := Verify(r, body, secret, "device-test", now, 5*time.Minute); err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	r.Header.Set("X-Signature", "invalid")
	if _, err := Verify(r, body, secret, "device-test", now, 5*time.Minute); err != ErrBadSignature {
		t.Fatalf("Verify() error = %v, want ErrBadSignature", err)
	}
}

func TestVerifyRejectsOldTimestamp(t *testing.T) {
	secret := []byte("01234567890123456789012345678901")
	body := []byte("{}")
	now := time.Now().UTC()
	timestamp := now.Add(-10 * time.Minute).Format(time.RFC3339)
	nonce := "example-nonce-0002"
	key := "example-message-002"
	r := httptest.NewRequest("POST", "https://sms.example.test/v1/messages", nil)
	r.Header.Set("X-Device-Id", "device-test")
	r.Header.Set("X-Timestamp", timestamp)
	r.Header.Set("X-Nonce", nonce)
	r.Header.Set("Idempotency-Key", key)
	r.Header.Set("X-Signature", EncodeSignature(Sign("POST", "/v1/messages", timestamp, nonce, key, body, secret)))
	if _, err := Verify(r, body, secret, "device-test", now, 5*time.Minute); err != ErrClockSkew {
		t.Fatalf("Verify() error = %v, want ErrClockSkew", err)
	}
}

func TestSignatureMatchesAndroidVector(t *testing.T) {
	secret := []byte("01234567890123456789012345678901")
	got := EncodeSignature(Sign("POST", "/v1/messages", "2026-08-17T01:02:03Z", "nonce-example-001", "message-example-001", []byte("{}"), secret))
	if want := "PgwXTuhgEo8yzAVmS2YRGSf5rgvR8P7PSrP8iORICI0"; got != want {
		t.Fatalf("signature=%q, want %q", got, want)
	}
}
