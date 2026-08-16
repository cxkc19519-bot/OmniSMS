# 技术架构方案

状态：已确认，实施中

## 1. 架构概览

```text
Android SMS_RECEIVED
        │
        ▼
短信接收器 → 本地加密队列 → 后台投递任务
                              │ HTTPS
                              ▼
                       VPS 接收 API
                              │
                    幂等校验 / 临时队列
                              │
                              ▼
                        Gmail 邮件通道
                              │
                              ▼
                    iPhone Gmail 通知
```

## 2. 推荐技术选型

### 2.1 安卓端

- 语言：Kotlin。
- UI：Jetpack Compose，单 Activity 架构。
- 短信入口：声明式 `BroadcastReceiver` 接收系统短信广播。
- 常驻保障：使用不展示短信内容的前台服务和持续通知；ColorOS 上纯广播方案在用户划掉任务后不可靠。
- 重启恢复：接收 `LOCKED_BOOT_COMPLETED` 和 `BOOT_COMPLETED`，关键启动状态使用设备加密存储，使首次解锁前即可恢复服务。
- 权限：运行时申请 `RECEIVE_SMS`；仅在确有恢复/诊断需求时申请 `READ_SMS`，默认不读取历史短信。
- 本地队列：Room/SQLite。
- 后台重试：WorkManager，使用网络约束和指数退避。
- 设置存储：DataStore。
- 敏感配置：Android Keystore 加密保存设备密钥。
- 网络：成熟的 HTTPS 客户端；配置明确的连接、读取和整体超时。

选择原生 Kotlin 是为了可靠处理 Android 广播、权限、后台任务和 ColorOS 兼容性，不引入跨平台运行时。

### 2.2 VPS 服务端

- 推荐语言：Go，编译为单个可执行文件，资源占用适合 1 GB VPS。
- HTTP API：轻量路由器或标准库。
- 数据库：SQLite，开启 WAL；单用户场景不需要独立数据库服务。
- 进程管理：systemd。
- HTTPS：使用用户准备的稳定域名并自动管理 TLS；不采用公网 IP 短期证书方案。
- 邮件：优先验证 Gmail SMTP + 应用专用密码；凭据仅保存在 VPS。
- 定时清理：服务内定时任务或 systemd timer，每小时删除超过24小时的数据。

当前实现使用 Go 标准库 HTTP 服务并监听 `127.0.0.1:8088`，由现有 Nginx 提供外部入口；SQLite 使用纯 Go 驱动，便于交叉编译为单个 Linux x86_64 文件。生产部署前仍需检查 Nginx 现有配置并执行 `nginx -t`，不得覆盖代理节点配置。

用户已于 2026-08-16 确认采用稳定域名，域名正在准备。TLS 证书必须自动签发和续期，不得以关闭证书校验、长期自签名证书或公网 IP 六日证书替代已确认方案。

## 3. 数据流

1. 接收器从广播中合并多段短信。
2. App 生成不可预测的消息 ID，并写入本地数据库后立即结束广播处理。
3. WorkManager 读取待发送项，构造请求并通过 HTTPS 上传。
4. 服务端验证时间戳、请求随机数和签名，拒绝重放或无效请求。
5. 服务端按 `device_id + message_id` 执行幂等插入。
6. 服务端使用发送 Gmail 的 SMTP 授权生成纯文本邮件，并投递到独立的接收邮箱。
7. 成功后返回稳定状态；App 将本地项标为已发送。
8. App 和 VPS 定期清理超过24小时的内容或状态。

## 4. API 初步约定

### `POST /v1/messages`

请求头：

- `X-Device-Id`
- `X-Timestamp`
- `X-Nonce`
- `X-Signature`
- `Idempotency-Key`

请求体字段：

```json
{
  "messageId": "随机且稳定的消息标识",
  "sender": "10690000",
  "body": "示例短信内容",
  "receivedAt": "2026-08-16T06:30:15Z",
  "simSlot": 1,
  "simLabel": "SIM 1",
  "queuedOffline": false,
  "appVersion": "0.1.0"
}
```

响应语义：

- `202`：已接受，可能仍在投递邮件。
- `200`：相同幂等键已处理，客户端视为成功。
- `400`：数据无效，不应无限重试。
- `401/403`：认证失败，停止自动重试并提醒用户。
- `429/5xx`：临时失败，按退避策略重试。

实际开发时需补充 API schema、最大正文长度、错误码和版本兼容策略。

## 5. 数据模型

### 安卓 `outbox_message`

- `message_id`
- `sender`
- `body_encrypted`
- `received_at_utc`
- `sim_slot_nullable`
- `state`：pending/sending/sent/permanent_failed
- `attempt_count`
- `last_attempt_at_utc`
- `last_error_code`
- `created_at_utc`

### 服务端 `message_delivery`

- `device_id`
- `message_id`
- 加密或短期保存的消息字段
- `received_at_utc`
- `accepted_at_utc`
- `emailed_at_utc`
- `delivery_state`
- `attempt_count`
- `expires_at_utc`

`device_id + message_id` 必须建立唯一约束。

## 6. 重试和幂等标准

- 客户端写入本地队列成功后才开始网络投递。
- 网络超时、429 和服务端 5xx 允许重试。
- 初始退避建议30秒，逐步增加并设置合理上限。
- 服务端接受消息后，即使客户端没有收到响应，再次提交也不能重复发邮件。
- 服务端邮件投递应有自己的重试状态，不依赖客户端重复上传触发。
- 永久失败必须在 App 首页和记录页可见。

## 7. 时间和双卡处理

- API 与数据库统一保存 ISO 8601 UTC 时间。
- 邮件展示短信接收时间和实际邮件投递时间，默认转换为 Asia/Shanghai。
- SIM 信息尽力采集，不因 SIM 标识缺失而拒绝转发。
- 双卡行为必须在真实 OPPO 设备上验证，模拟器结果不能代替真机验收。

## 8. 待验证与决策门

正式开发前必须完成以下验证：

1. OPPO A72 5G 上短信广播、多段短信、双卡信息和重启恢复行为。**SIM 2、多段合并、卡槽元数据和重启恢复已通过；SIM 1 因运营商封停无法实测。**
2. ColorOS 12.1 自启动、省电限制下的可靠性。**已通过：需开启完全后台、自启动、关联启动，并使用前台服务。**
3. 现有 Gmail 作为发件和收件账号时，iPhone 是否稳定弹出推送。**已通过：约15秒。**
4. Gmail SMTP 授权方式是否可用；不得使用普通账户密码。**已通过：两步验证和应用专用密码可用。**
5. VPS 的 HTTPS 入口不得与现有 `sing-box`、Nginx 和 Cloudflare Tunnel 冲突。**方案已确认使用稳定域名；80/443 当前未监听，待域名准备后验证 DNS 和入口配置。**

如果第3项失败，先征得用户同意，再选择独立发件邮箱或邮件服务；不得擅自新增付费服务。

## 9. 决策记录

| 日期 | 决定 | 原因 |
|---|---|---|
| 2026-08-16 | 建议安卓原生 Kotlin | 对短信广播、权限和后台任务支持直接。 |
| 2026-08-16 | 建议 Go + SQLite | 适合低配置、单用户 VPS，部署简单。 |
| 2026-08-16 | 服务端负责邮件凭据 | 避免在 APK 内保存 Gmail 凭据。 |
| 2026-08-16 | 现有 Gmail 可自发自收 | 用户连续测试路径可进入收件箱并在 iPhone 约15秒通知。 |
| 2026-08-16 | VPS 服务使用独立本机端口 | 避免修改现有 sing-box、Nginx 35551 和 cloudflared 链路。 |
| 2026-08-16 | Android 使用持续前台服务 | 真机验证表明纯广播版在划掉任务后漏收；前台服务版在划掉任务和锁屏后约3秒接收成功。 |
| 2026-08-16 | 使用 Direct Boot 恢复监听 | `LOCKED_BOOT_COMPLETED` + 设备加密存储可在首次解锁前自动恢复前台服务，重启后短信实测通过。 |
| 2026-08-16 | HTTPS 使用稳定域名 | 用户确认域名容易准备；相比公网 IP 六日证书，长期续期和运维更简单。 |
| 2026-08-17 | 进入正式开发 | 用户明确同意证书等待期间先开发，阶段 1 安全骨架开始实施。 |
| 2026-08-17 | 服务端监听本机 8088，由现有 Nginx 反代 | 避免占用 sing-box 和现有 Nginx 35551；部署前仍需只读核查配置。 |
| 2026-08-17 | 首版采用手工安全配对信息 | 先打通单用户闭环；二维码配对放在界面完善阶段，密钥始终由 Android Keystore 保护。 |
| 2026-08-17 | 发送 Gmail 与接收 Gmail 分离 | 降低自发自收规则影响；只有发送账号需要在 VPS 保存应用专用密码。 |
