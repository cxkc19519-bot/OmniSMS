package mailer

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"io"
	"mime"
	"net"
	"net/smtp"
	"strconv"
	"strings"
	"time"

	"omnisms/internal/model"
)

type Sender interface {
	Send(context.Context, model.Delivery, time.Time) error
}

type SMTP struct {
	Host                         string
	Port                         int
	Username, Password, From, To string
	Location                     *time.Location
	Timeout                      time.Duration
}

func (s SMTP) Send(ctx context.Context, d model.Delivery, forwardedAt time.Time) error {
	address := net.JoinHostPort(s.Host, strconv.Itoa(s.Port))
	conn, err := (&net.Dialer{Timeout: s.Timeout}).DialContext(ctx, "tcp", address)
	if err != nil {
		return fmt.Errorf("smtp_connect: %w", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(s.Timeout))
	client, err := smtp.NewClient(conn, s.Host)
	if err != nil {
		return fmt.Errorf("smtp_greeting: %w", err)
	}
	defer client.Close()
	if ok, _ := client.Extension("STARTTLS"); !ok {
		return fmt.Errorf("smtp_tls_required")
	}
	if err = client.StartTLS(&tls.Config{ServerName: s.Host, MinVersion: tls.VersionTLS12}); err != nil {
		return fmt.Errorf("smtp_tls: %w", err)
	}
	if err = client.Auth(smtp.PlainAuth("", s.Username, s.Password, s.Host)); err != nil {
		return fmt.Errorf("smtp_auth: %w", err)
	}
	if err = client.Mail(s.From); err != nil {
		return fmt.Errorf("smtp_from: %w", err)
	}
	if err = client.Rcpt(s.To); err != nil {
		return fmt.Errorf("smtp_to: %w", err)
	}
	w, err := client.Data()
	if err != nil {
		return fmt.Errorf("smtp_data: %w", err)
	}
	if _, err = io.WriteString(w, s.message(d, forwardedAt)); err != nil {
		_ = w.Close()
		return fmt.Errorf("smtp_write: %w", err)
	}
	if err = w.Close(); err != nil {
		return fmt.Errorf("smtp_commit: %w", err)
	}
	if err = client.Quit(); err != nil {
		return fmt.Errorf("smtp_quit: %w", err)
	}
	return nil
}

func (s SMTP) message(d model.Delivery, forwardedAt time.Time) string {
	sender := sanitizeHeader(d.Sender)
	if sender == "" {
		sender = "未知发送方"
	}
	subject := mime.QEncoding.Encode("UTF-8", "[短信转发] 来自 "+sender)
	messageID := stableMessageID(d.DeviceID, d.MessageID)
	received := d.ReceivedAt.In(s.Location).Format("2006-01-02 15:04:05 MST")
	forwarded := forwardedAt.In(s.Location).Format("2006-01-02 15:04:05 MST")
	sim := strings.TrimSpace(d.SIMLabel)
	if sim == "" && d.SIMSlot != nil {
		sim = fmt.Sprintf("SIM %d", *d.SIMSlot+1)
	}
	if sim == "" {
		sim = "未知"
	}
	deliveryType := "实时转发"
	if d.QueuedOffline || forwardedAt.Sub(d.ReceivedAt) > time.Minute {
		deliveryType = "断网/失败后补发"
	}
	body := fmt.Sprintf("发送方：%s\r\nSIM：%s\r\n短信接收时间：%s\r\n实际转发时间：%s\r\n投递类型：%s\r\n\r\n完整短信内容：\r\n%s\r\n", d.Sender, sim, received, forwarded, deliveryType, d.Body)
	return fmt.Sprintf("From: %s\r\nTo: %s\r\nSubject: %s\r\nDate: %s\r\nMessage-ID: <%s>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n%s", sanitizeHeader(s.From), sanitizeHeader(s.To), subject, forwardedAt.Format(time.RFC1123Z), messageID, normalizeBody(body))
}

func stableMessageID(deviceID, messageID string) string {
	sum := sha256.Sum256([]byte(deviceID + "\x00" + messageID))
	return hex.EncodeToString(sum[:16]) + "@omnisms.local"
}
func sanitizeHeader(v string) string {
	return strings.TrimSpace(strings.NewReplacer("\r", "", "\n", "").Replace(v))
}
func normalizeBody(v string) string {
	v = strings.ReplaceAll(v, "\r\n", "\n")
	v = strings.ReplaceAll(v, "\r", "\n")
	return strings.ReplaceAll(v, "\n", "\r\n")
}
