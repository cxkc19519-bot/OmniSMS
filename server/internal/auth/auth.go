package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

var (
	ErrMissingHeader = errors.New("missing authentication header")
	ErrWrongDevice   = errors.New("unknown device")
	ErrClockSkew     = errors.New("request timestamp outside allowed window")
	ErrBadSignature  = errors.New("invalid signature")
)

type RequestAuth struct{ DeviceID, Timestamp, Nonce, IdempotencyKey string }

func Verify(r *http.Request, body, secret []byte, expectedDevice string, now time.Time, maxSkew time.Duration) (RequestAuth, error) {
	a := RequestAuth{
		DeviceID: strings.TrimSpace(r.Header.Get("X-Device-Id")), Timestamp: strings.TrimSpace(r.Header.Get("X-Timestamp")),
		Nonce: strings.TrimSpace(r.Header.Get("X-Nonce")), IdempotencyKey: strings.TrimSpace(r.Header.Get("Idempotency-Key")),
	}
	signature := strings.TrimSpace(r.Header.Get("X-Signature"))
	if a.DeviceID == "" || a.Timestamp == "" || a.Nonce == "" || a.IdempotencyKey == "" || signature == "" {
		return a, ErrMissingHeader
	}
	if len(a.DeviceID) > 128 || len(a.Nonce) < 16 || len(a.Nonce) > 128 || len(a.IdempotencyKey) < 16 || len(a.IdempotencyKey) > 128 {
		return a, ErrMissingHeader
	}
	if !hmac.Equal([]byte(a.DeviceID), []byte(expectedDevice)) {
		return a, ErrWrongDevice
	}
	timestamp, err := time.Parse(time.RFC3339, a.Timestamp)
	if err != nil {
		return a, ErrClockSkew
	}
	delta := now.Sub(timestamp)
	if delta < 0 {
		delta = -delta
	}
	if delta > maxSkew {
		return a, ErrClockSkew
	}
	want := Sign(r.Method, r.URL.EscapedPath(), a.Timestamp, a.Nonce, a.IdempotencyKey, body, secret)
	got, err := base64.RawURLEncoding.DecodeString(signature)
	if err != nil || !hmac.Equal(got, want) {
		return a, ErrBadSignature
	}
	return a, nil
}

func Sign(method, path, timestamp, nonce, idempotencyKey string, body, secret []byte) []byte {
	digest := sha256.Sum256(body)
	canonical := fmt.Sprintf("%s\n%s\n%s\n%s\n%s\n%s", strings.ToUpper(method), path, timestamp, nonce, idempotencyKey, hex.EncodeToString(digest[:]))
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(canonical))
	return mac.Sum(nil)
}

func EncodeSignature(signature []byte) string { return base64.RawURLEncoding.EncodeToString(signature) }
