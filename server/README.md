# OmniSMS 服务端

服务端接收安卓 App 通过 HTTPS 上传的短信，将其写入 SQLite 短期队列并通过 Gmail SMTP 投递。服务本身只监听本机地址，由 VPS 上的反向代理提供公网入口。

## 安全边界

- 生产配置只放在 VPS 的 `/etc/omnisms/omnisms.env`，权限设为 `0600`。
- Gmail 应用专用密码、设备密钥、真实邮箱和短信不得写入仓库或日志。
- 收到成功响应后仍可能在服务端邮件队列中重试；相同消息 ID 不会建立第二条投递记录。
- 邮件成功后立即清空 SQLite 中的短信正文，其余状态最迟24小时清除。

## 本地检查

```text
go test ./...
go vet ./...
go build ./cmd/omnisms-server
```

使用 `go run ./cmd/omnisms-keygen` 生成设备密钥。输出只应写入 VPS 配置和安卓安全配对流程，不要复制到文档或聊天。

## HTTP 接口

- `GET /health/live`：进程存活检查。
- `GET /health/ready`：数据库就绪检查。
- `POST /v1/messages`：接收一条短信；请求必须携带设备签名和幂等键。

签名原文为以下六行 UTF-8 文本，最后一行是请求体的 SHA-256 小写十六进制值：

```text
POST
/v1/messages
<X-Timestamp>
<X-Nonce>
<Idempotency-Key>
<body-sha256>
```

使用设备密钥进行 HMAC-SHA256，结果按无填充 Base64URL 编码并放入 `X-Signature`。
