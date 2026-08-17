package mailer

import (
	"strings"
	"testing"
	"time"

	"omnisms/internal/model"
)

func TestMessageContainsRequiredFields(t *testing.T) {
	loc, _ := time.LoadLocation("Asia/Shanghai")
	slot := 1
	m := SMTP{From: "sender@example.test", To: "receiver@example.test", Location: loc}
	received := time.Date(2026, 8, 17, 0, 0, 0, 0, time.UTC)
	forwarded := received.Add(2 * time.Hour)
	raw := m.message(model.Delivery{DeviceID: "device-test", MessageID: "message-example", Sender: "Example Bank", Body: "Fictional verification code: 123456", ReceivedAt: received, SIMSlot: &slot, QueuedOffline: true}, forwarded)
	for _, want := range []string{"Subject: =?UTF-8?", "multipart/alternative", "短信接收时间：", "实际转发时间：", "断网/失败后补发", "完整短信内容：", "Fictional verification code: 123456", "Message-ID:", "收到一条新短信"} {
		if !strings.Contains(raw, want) {
			t.Errorf("message missing %q", want)
		}
	}
}

func TestStableMessageID(t *testing.T) {
	a := stableMessageID("device", "message")
	b := stableMessageID("device", "message")
	if a != b || !strings.HasSuffix(a, "@omnisms.local") {
		t.Fatalf("unexpected stable id %q / %q", a, b)
	}
}
