package mailer

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"html"
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
	contentLabel := "完整短信内容"
	contentNote := ""
	if sim == "5G消息" {
		contentLabel = "系统通知提供的内容"
		contentNote = "\r\n说明：纯5G消息内容来自系统短信通知，可能比详情页精简。\r\n"
	}
	plain := fmt.Sprintf("OmniSMS · %s\r\n\r\n发送方：%s\r\nSIM：%s\r\n短信接收时间：%s\r\n实际转发时间：%s\r\n投递类型：%s\r\n\r\n%s：\r\n%s\r\n%s", deliveryType, d.Sender, sim, received, forwarded, deliveryType, contentLabel, d.Body, contentNote)
	boundary := "omnisms_" + strings.Split(messageID, "@")[0]
	return normalizeBody(fmt.Sprintf("From: %s\nTo: %s\nSubject: %s\nDate: %s\nMessage-ID: <%s>\nMIME-Version: 1.0\nContent-Type: multipart/alternative; boundary=\"%s\"\n\n--%s\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: 8bit\n\n%s\n--%s\nContent-Type: text/html; charset=UTF-8\nContent-Transfer-Encoding: 8bit\n\n%s\n--%s--\n", sanitizeHeader(s.From), sanitizeHeader(s.To), subject, forwardedAt.Format(time.RFC1123Z), messageID, boundary, boundary, plain, boundary, messageHTML(sender, sim, received, forwarded, deliveryType, contentLabel, contentNote, d.Body), boundary))
}

func messageHTML(sender, sim, received, forwarded, deliveryType, contentLabel, contentNote, body string) string {
	escape := func(v string) string { return html.EscapeString(v) }
	bodyHTML := strings.ReplaceAll(strings.ReplaceAll(escape(body), "\r\n", "\n"), "\n", "<br>")
	noteHTML := ""
	if contentNote != "" {
		noteHTML = `<div style="margin-top:9px;font-size:12px;line-height:19px;color:#70817B;">` + escape(strings.TrimSpace(contentNote)) + `</div>`
	}
	badgeColor, badgeBackground := "#0B6B53", "#DDF5E9"
	if deliveryType != "实时转发" {
		badgeColor, badgeBackground = "#9A5B00", "#FFF1D6"
	}
	return fmt.Sprintf(`<!doctype html><html lang="zh-CN"><body style="margin:0;padding:24px 12px;background:#F3F6F5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;color:#18312B;"><div style="display:none;max-height:0;overflow:hidden;opacity:0;">OmniSMS 短信通知已送达</div><table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"><tr><td align="center"><table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="max-width:620px;background:#FFFFFF;border-radius:20px;overflow:hidden;box-shadow:0 6px 24px rgba(16,57,46,.10);"><tr><td style="padding:28px 30px 25px;background:linear-gradient(135deg,#0B5E4B,#163D35);"><div style="font-size:13px;letter-spacing:1.8px;font-weight:700;color:#BFE8D8;">OMNISMS</div><div style="margin-top:9px;font-size:26px;line-height:34px;font-weight:700;color:#FFFFFF;">收到一条新短信</div><div style="margin-top:8px;font-size:14px;color:#D4EEE5;">你的手机已安全转发到此邮箱</div></td></tr><tr><td style="padding:26px 30px 30px;"><span style="display:inline-block;padding:7px 12px;border-radius:999px;background:%s;color:%s;font-size:13px;font-weight:700;">●　%s</span><div style="margin-top:24px;padding:18px 19px;border-radius:14px;background:#F5F8F7;"><div style="font-size:13px;color:#6B7D77;">发送方</div><div style="margin-top:5px;font-size:19px;font-weight:700;color:#18312B;word-break:break-all;">%s</div></div><table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="margin-top:16px;border-collapse:separate;border-spacing:0;"><tr><td width="50%%" style="padding:0 8px 0 0;"><div style="padding:15px;border:1px solid #E3ECE8;border-radius:13px;"><div style="font-size:12px;color:#70817B;">SIM 卡</div><div style="margin-top:5px;font-size:15px;font-weight:650;color:#203B33;">%s</div></div></td><td width="50%%" style="padding:0 0 0 8px;"><div style="padding:15px;border:1px solid #E3ECE8;border-radius:13px;"><div style="font-size:12px;color:#70817B;">实际转发时间</div><div style="margin-top:5px;font-size:15px;font-weight:650;color:#203B33;">%s</div></div></td></tr></table><div style="margin-top:16px;padding:15px 16px;border-left:3px solid #49A886;background:#F5FAF7;border-radius:0 12px 12px 0;"><div style="font-size:12px;color:#70817B;">短信接收时间</div><div style="margin-top:5px;font-size:15px;font-weight:650;color:#203B33;">%s</div></div><div style="margin-top:24px;font-size:13px;font-weight:700;color:#547068;letter-spacing:.3px;">%s</div><div style="margin-top:9px;padding:18px 19px;border-radius:14px;background:#102D26;color:#F5FCF8;font-size:16px;line-height:25px;word-break:break-word;">%s</div>%s<div style="margin-top:24px;padding-top:18px;border-top:1px solid #E5ECE9;font-size:12px;line-height:19px;color:#7A8B85;">此邮件由你的 OmniSMS 私人转发服务发送。请妥善保护短信和验证码内容。</div></td></tr></table></td></tr></table></body></html>`, badgeBackground, badgeColor, escape(deliveryType), escape(sender), escape(sim), escape(forwarded), escape(received), escape(contentLabel), bodyHTML, noteHTML)
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
