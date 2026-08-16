package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

	"omnisms/internal/auth"
	"omnisms/internal/config"
	"omnisms/internal/model"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "configuration invalid")
		os.Exit(2)
	}
	now := time.Now().UTC()
	messageID := fmt.Sprintf("smoketest-%d", now.UnixNano())
	nonce := fmt.Sprintf("smoketest-nonce-%d", now.UnixNano())
	payload := model.MessageRequest{MessageID: messageID, Sender: "OmniSMS 测试", Body: "这是一条固定的虚构测试短信，不包含真实短信或验证码。", ReceivedAt: now.Format(time.RFC3339), SIMLabel: "测试", AppVersion: "server-smoketest"}
	body, err := json.Marshal(payload)
	if err != nil {
		os.Exit(2)
	}
	req, err := http.NewRequest(http.MethodPost, "http://127.0.0.1:8088/v1/messages", bytes.NewReader(body))
	if err != nil {
		os.Exit(2)
	}
	timestamp := now.Format(time.RFC3339)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Device-Id", cfg.DeviceID)
	req.Header.Set("X-Timestamp", timestamp)
	req.Header.Set("X-Nonce", nonce)
	req.Header.Set("Idempotency-Key", messageID)
	req.Header.Set("X-Signature", auth.EncodeSignature(auth.Sign(http.MethodPost, "/v1/messages", timestamp, nonce, messageID, body, cfg.DeviceSecret)))
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Fprintln(os.Stderr, "test request failed")
		os.Exit(1)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusAccepted && resp.StatusCode != http.StatusOK {
		fmt.Fprintf(os.Stderr, "test request returned HTTP %d\n", resp.StatusCode)
		os.Exit(1)
	}
	fmt.Println("虚构测试消息已安全提交，请检查 Gmail。")
}
