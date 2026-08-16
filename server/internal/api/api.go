package api

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"omnisms/internal/auth"
	"omnisms/internal/model"
	"omnisms/internal/store"
)

const maxBodyBytes = 64 * 1024

type Server struct {
	Store              *store.Store
	DeviceID           string
	DeviceSecret       []byte
	MaxSkew, Retention time.Duration
	Log                *slog.Logger
	Now                func() time.Time
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", s.live)
	mux.HandleFunc("GET /health/ready", s.ready)
	mux.HandleFunc("POST /v1/messages", s.messages)
	return securityHeaders(mux)
}
func (s *Server) live(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
func (s *Server) ready(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	if err := s.Store.Ping(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, "not_ready")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

func (s *Server) messages(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "body_too_large")
		return
	}
	now := s.Now().UTC()
	a, err := auth.Verify(r, body, s.DeviceSecret, s.DeviceID, now, s.MaxSkew)
	if err != nil {
		s.Log.Warn("request rejected", "code", "authentication_failed")
		writeError(w, http.StatusUnauthorized, "authentication_failed")
		return
	}
	var req model.MessageRequest
	decErr := json.Unmarshal(body, &req)
	if decErr != nil {
		writeError(w, http.StatusBadRequest, "invalid_json")
		return
	}
	receivedAt, code := validate(req, now)
	if code != "" {
		writeError(w, http.StatusBadRequest, code)
		return
	}
	d := model.Delivery{DeviceID: a.DeviceID, MessageID: req.MessageID, Sender: req.Sender, Body: req.Body, ReceivedAt: receivedAt, AcceptedAt: now, SIMSlot: req.SIMSlot, SIMLabel: req.SIMLabel, QueuedOffline: req.QueuedOffline, AppVersion: req.AppVersion}
	duplicate, err := s.Store.Accept(r.Context(), d, a.IdempotencyKey, a.Nonce, now.Add(2*s.MaxSkew), now.Add(s.Retention))
	if errors.Is(err, store.ErrNonceReplay) {
		writeError(w, http.StatusUnauthorized, "replayed_request")
		return
	}
	if err != nil {
		s.Log.Error("message persistence failed", "error", err)
		writeError(w, http.StatusInternalServerError, "temporary_failure")
		return
	}
	if duplicate {
		writeJSON(w, http.StatusOK, map[string]string{"status": "already_accepted"})
		return
	}
	s.Log.Info("message accepted", "message", shortID(req.MessageID), "queued_offline", req.QueuedOffline)
	writeJSON(w, http.StatusAccepted, map[string]string{"status": "accepted"})
}

func validate(req model.MessageRequest, now time.Time) (time.Time, string) {
	if !validToken(req.MessageID, 16, 128) {
		return time.Time{}, "invalid_message_id"
	}
	if strings.TrimSpace(req.Sender) == "" || utf8.RuneCountInString(req.Sender) > 256 {
		return time.Time{}, "invalid_sender"
	}
	if req.Body == "" || !utf8.ValidString(req.Body) || len([]byte(req.Body)) > 48*1024 {
		return time.Time{}, "invalid_body"
	}
	received, err := time.Parse(time.RFC3339, req.ReceivedAt)
	if err != nil || received.After(now.Add(5*time.Minute)) || received.Before(now.Add(-30*24*time.Hour)) {
		return time.Time{}, "invalid_received_at"
	}
	if req.SIMSlot != nil && (*req.SIMSlot < 0 || *req.SIMSlot > 7) {
		return time.Time{}, "invalid_sim_slot"
	}
	if utf8.RuneCountInString(req.SIMLabel) > 128 || utf8.RuneCountInString(req.AppVersion) > 64 {
		return time.Time{}, "invalid_metadata"
	}
	return received.UTC(), ""
}
func validToken(v string, min, max int) bool {
	if len(v) < min || len(v) > max {
		return false
	}
	for _, r := range v {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_') {
			return false
		}
	}
	return true
}
func shortID(v string) string {
	if len(v) > 12 {
		return v[:12]
	}
	return v
}
func writeError(w http.ResponseWriter, status int, code string) {
	writeJSONStatus(w, status, map[string]string{"error": code})
}
func writeJSON(w http.ResponseWriter, status int, v any) { writeJSONStatus(w, status, v) }
func writeJSONStatus(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		next.ServeHTTP(w, r)
	})
}
