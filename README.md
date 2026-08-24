# OmniSMS

OmniSMS 是一套面向个人自用场景的 Android 短信转发系统。Android 手机收到普通 SMS 或 ColorOS 系统短信 App 中的 5G消息后，OmniSMS 将消息可靠写入本地加密队列，通过 HTTPS 上传到个人 VPS，再由 VPS 使用 Gmail SMTP 投递到指定邮箱，使其他设备可以通过 Gmail 及时查看。

本项目强调可靠性、隐私和可恢复性：短信必须先落盘再发送；断网后自动补发；客户端、服务端和 Gmail 投递均提供去重保护；真实邮箱、服务器地址、设备密钥、Gmail 应用专用密码和短信正文不得进入仓库或普通日志。

> 本项目仅用于用户本人管理自己手机上的短信。它不是多人短信平台，不提供远程发短信、短信回复、网页收件箱或其他应用通知采集。

## 当前状态

| 组件 | 当前版本/状态 | 说明 |
|---|---|---|
| Android 正式基线 | `0.3.4` | 已完成正式签名、安装、配对、断网补发和重启恢复验证。 |
| Android 候选版 | `0.3.7` / `versionCode 11` | 已完成构建、单元测试、Lint、签名校验和保留数据覆盖安装；升级后自动追回普通 SMS 与跨通道竞态漏转发。 |
| VPS 服务端 | `0.3.3` | 已部署运行，健康检查、Gmail 投递、重试和同版本回滚演练通过。 |
| HTTPS 与邮件 | 已运行 | 公网 HTTPS、Gmail SMTP、独立收件邮箱和 iPhone Gmail 通知均已验证。 |
| 发布结论 | 候选验收中 | 尚未宣布最终生产验收完成，剩余项目见 `docs/11-release-acceptance-0.3.4.md`。 |

`0.3.6` 针对 ColorOS 锁屏普通 SMS 偶发漏转发，采用“先持久化、再上传、延迟补查”的保护：

- 广播回调返回前先把完整短信写入本地加密队列，网络上传仍在后台执行。
- PDU 暂时无法解析或入队失败时，前台服务在 5 秒后再次增量扫描系统收件箱。
- WorkManager 每次重试上传前也先执行收件箱补查，避免前台服务启动受限时永久遗漏。
- 前台服务继续使用动态接收器、收件箱观察器和密集扫描合并；持久指纹避免多路径重复邮件。
- 首页检测电池优化豁免状态，并提供系统授权入口。

2026-08-24 真机复现确认 ColorOS 已向 OmniSMS 投递普通 SMS，但旧版没有形成邮件。覆盖安装 0.3.6 后，应用在未清除配对和权限的情况下自动从系统收件箱追回该短信，Gmail 收到一封新邮件。普通 SMS 锁屏“无需重启实时转发”仍需用下一条新短信独立复测。

随后复现另一条跨通道竞态：ColorOS 只更新5G消息通知，通知正文又已存在系统短信表，旧逻辑直接忽略却没有确保普通通道已经入队。0.3.7 改为直接使用匹配的系统短信记录入队，并沿用标准短信指纹去重；覆盖安装后自动追回当前通知并只生成一封邮件。

## 系统如何工作

```text
普通 SMS 系统广播 ───────────────────────┐
前台服务动态短信接收器 ──────────────────┤
系统短信收件箱增量补查 ──────────────────┤
ColorOS 5G消息通知（仅 com.android.mms）─┘
                                          │
                                          ▼
                                本地指纹去重与加密队列
                                          │
                              HTTPS + HMAC 请求签名
                                          │
                                          ▼
                                  VPS 接收 API
                                          │
                              幂等校验、SQLite 短期队列
                                          │
                                          ▼
                                    Gmail SMTP
                                          │
                                          ▼
                                Gmail 收件箱 / iPhone 通知
```

一次正常投递包含以下步骤：

1. Android 接收新短信或系统短信 App 的 5G消息通知。
2. App 合并多段短信，采集发送方、接收时间和可获得的 SIM 信息。
3. 消息使用 Android Keystore 保护的密钥加密写入本地 SQLite 队列。
4. 网络可用时立即上传；网络不可用时保留在本机并使用 WorkManager 退避重试。
5. VPS 验证设备 ID、时间戳、随机数、请求签名和幂等键。
6. 服务端短期保存待投递消息，通过 Gmail SMTP 发送邮件。
7. 邮件成功后清除服务端短信正文；状态记录和失败数据最迟 24 小时清理。

## 主要功能

### Android 客户端

- 监听两张 SIM 卡收到的新短信，不按发送方过滤。
- 合并多段 SMS，尽量保留完整正文、发送方、接收时间和 SIM 卡槽。
- 断网、本地网络异常或 VPS 暂时不可用时自动排队补发。
- 手机重启后恢复监听和未完成队列。
- 使用前台服务、短时唤醒锁、动态接收器和短信收件箱补查提高 ColorOS 锁屏可靠性。
- 可选授权通知使用权，兼容不触发标准 SMS 广播的 ColorOS 5G消息。
- 通知通道在读取任何标题或正文前严格校验包名，只允许 `com.android.mms`。
- 首页提供转发总开关、虚构测试短信、5G消息授权和电池优化授权入口。
- 前台状态通知不显示短信正文或验证码。

### VPS 服务端

- Go 单文件服务，适用于 1 vCPU、1 GB 内存的小型 VPS。
- 默认仅监听 `127.0.0.1:8088`，由 Nginx 等反向代理提供 HTTPS。
- 使用 HMAC-SHA256 验证设备请求，并限制时间偏差、防止请求重放。
- 使用 `device_id + message_id` 唯一约束保证重复上传不会产生第二封邮件。
- SQLite WAL 模式保存短期投递状态，不需要额外数据库服务。
- 区分临时错误和永久错误；临时 SMTP/网络错误自动重试。
- 提供存活和就绪健康检查。
- 投递邮件使用独立的发送 Gmail 和接收邮箱。

### 邮件内容

邮件主题默认是：

```text
[短信转发] 来自 <发送方>
```

邮件正文包含：

- 发送方。
- SIM 卡或消息来源。
- 短信接收时间。
- 实际转发时间。
- “实时转发”或“断网/失败后补发”标记。
- 普通 SMS 的完整正文，或 5G消息通知实际提供的内容。

验证码不会放进邮件标题。Gmail 中的邮件不会由 OmniSMS 自动删除。

## 重要限制

- ColorOS 的自启动、后台运行和省电限制需要用户手动确认；为获得最高可靠性，不建议从最近任务中强制划掉 OmniSMS。
- 收件箱补查可以恢复前台服务空窗期间遗漏的普通 SMS，但恢复邮件会标记为补发，不保证仍是实时到达。
- 5G消息并不一定进入 Android 标准短信数据库。OmniSMS 只能取得系统短信 App 通知实际提供的正文；通知被系统截断时，邮件也可能是精简内容。
- 双卡标识依赖 Android 和运营商提供的信息，无法取得时会显示未知来源。
- Gmail 通知速度及锁屏预览由 Gmail 和 iOS 设置决定，不属于 OmniSMS 可完全控制的范围。
- 本项目只面向单个用户和单台来源手机，不提供多用户、团队权限或公开注册。

## 仓库结构

```text
OmniSMS/
├─ android/                         Android 客户端源码与签名辅助脚本
│  └─ app/src/
│     ├─ main/                      正式客户端代码和资源
│     ├─ debug/                     仅调试构建使用的测试入口
│     └─ test/                      Android JVM 单元测试
├─ server/                          Go 服务端、配置示例和自动测试
│  ├─ cmd/omnisms-server/           服务主程序
│  ├─ cmd/omnisms-keygen/           设备密钥生成工具
│  ├─ cmd/omnisms-smoketest/        不含真实短信的冒烟测试工具
│  └─ internal/                     API、认证、存储、邮件和队列实现
├─ deploy/                          systemd、Nginx 和首次配置模板
├─ validation/android-sms-probe/    ColorOS 可行性验证 App
├─ docs/                            需求、架构、安全、测试和运维标准
├─ artifacts/                       构建产物说明，不保存生产秘密
├─ Codex.md                         仓库开发工作入口
└─ README.md                        项目总览
```

以下目录或文件只存在于开发电脑并被 Git 忽略：

- `.release/`：正式签名密钥和本地 APK。
- `android/signing.local.properties`：正式签名配置。
- 生产 `.env`、设备密钥和 Gmail 应用专用密码。

## 开发环境

### Android

- JDK 17。
- Android SDK 36。
- Android Gradle Plugin 9.2.0。
- Gradle 9.4.1 或兼容版本。

### 服务端

- Go 1.24。
- Linux `amd64` 生产目标。
- SQLite 使用纯 Go 驱动，生产构建不依赖系统 SQLite 开发包。

## 本地构建与检查

### Android 自动检查

在 `android/` 目录执行：

```text
gradle testDebugUnitTest lintRelease assembleRelease
```

主要输出：

- 单元测试报告：`android/app/build/reports/tests/testDebugUnitTest/`
- Lint 报告：`android/app/build/reports/lint-results-release.html`
- 发布 APK：`android/app/build/outputs/apk/release/app-release.apk`

没有配置正式签名时，发布任务不能视为可升级的正式包。不要用调试签名包覆盖生产安装。

### 首次创建正式签名

仅在确定尚未创建过签名密钥时运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\android\create-release-keystore.ps1
```

生成后必须分别加密备份密钥文件和密码。签名密钥丢失后，将无法以升级方式安装相同包名的新版本。不得把 `.release/`、签名密码或 `signing.local.properties` 提交到 GitHub。

发布前至少执行：

```text
apksigner verify --verbose --print-certs app-release.apk
```

并确认版本号、APK SHA-256 和签名证书 SHA-256 与发布记录一致。

### Go 服务端检查

在 `server/` 目录执行：

```text
go test ./...
go vet ./...
go build ./cmd/omnisms-server
```

构建 Linux x86_64 单文件：

```text
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o build/omnisms-server-linux-amd64 ./cmd/omnisms-server
```

生成设备密钥：

```text
go run ./cmd/omnisms-keygen
```

密钥输出只能写入 VPS 生产配置和 Android 安全配对流程，不要复制到文档、聊天记录或 Git 提交。

## 服务端配置

配置模板位于 `server/.env.example`，包含以下类别：

- 本机监听地址和 SQLite 路径。
- 设备 ID 与设备密钥。
- Gmail SMTP 地址、端口和应用专用密码。
- 发件人与收件人地址。
- 展示时区、24 小时保留期、认证时间偏差和重试周期。

生产配置建议保存为：

```text
/etc/omnisms/omnisms.env
```

文件权限必须为 `0600`，且不得回传到开发电脑或提交仓库。Gmail 必须启用两步验证并使用应用专用密码，不能保存 Gmail 普通登录密码。

服务端接口：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/health/live` | 检查进程是否存活。 |
| `GET` | `/health/ready` | 检查数据库和服务是否就绪。 |
| `POST` | `/v1/messages` | 接收经过签名和幂等保护的短信消息。 |

完整请求签名格式和响应语义见 `server/README.md` 与 `docs/02-technical-architecture.md`。

## VPS 部署摘要

推荐目录：

```text
/opt/omnisms/       程序文件
/etc/omnisms/       生产配置和秘密
/var/lib/omnisms/   SQLite 数据
```

推荐执行顺序：

1. 在本地完成 Go 测试、静态检查和 Linux `amd64` 构建。
2. 在 VPS 创建专用非 root 用户及程序、配置、数据目录。
3. 上传版本化服务二进制，并保存上一版本作为回滚材料。
4. 从 `server/.env.example` 创建 VPS 本地生产配置，设置严格权限。
5. 安装 `deploy/omnisms.service`，先检查本机健康接口。
6. 参考 `deploy/nginx-omnisms.conf.example` 添加独立反向代理配置。
7. 执行 `nginx -t` 成功后才平滑重载，不能覆盖现有代理节点配置。
8. 从公网确认 HTTPS 证书有效，并使用固定虚构短信完成 Gmail 冒烟测试。

详细命令、回滚要求和故障处理见 `docs/08-deployment-and-operations.md`。生产部署不得通过关闭 HTTPS、证书校验、请求认证或数据加密来绕过错误。

## Android 首次配置

1. 安装使用正式私钥签名的 APK。
2. 在 App 中保存 HTTPS 服务地址、设备 ID 和设备密钥。
3. 授予“接收短信”和“读取短信”权限。
4. 开启 OmniSMS 转发总开关。
5. 允许电池优化豁免，并在 ColorOS 中允许自启动、后台活动和关联启动。
6. 如需接收 ColorOS 5G消息，单独授予通知使用权。
7. 使用 App 内置的固定虚构测试消息验证 HTTPS 和 Gmail 链路。
8. 再进行锁屏普通 SMS、5G消息、断网补发和手机重启测试。

覆盖升级正式版时，必须使用同一签名证书且提高 `versionCode`。正常的覆盖安装会保留配对信息和本地待发队列；卸载后重装会清除 App 数据，需要重新配对。

## 安全与隐私

- 完整短信、验证码、设备密钥和 Gmail 应用专用密码属于最高敏感数据，不进入源码、Git、普通日志或崩溃报告。
- Android 本地正文使用 Android Keystore 保护的密钥加密。
- App 到 VPS 只允许 HTTPS，不提供忽略证书错误或回退 HTTP 的选项。
- 请求使用时间戳、随机数、幂等键和 HMAC-SHA256 签名防止伪造与重放。
- 通知使用权虽然在系统层面可访问所有通知，但实现只允许 OPPO 系统短信 App；其他应用在解析前立即丢弃。
- VPS 只为投递和重试短期保存正文，最长保留 24 小时；Gmail 中已收到的邮件不受该清理规则影响。
- 测试必须使用固定虚构内容，不应复制真实验证码到测试代码、文档或问题报告。

完整安全要求见 `docs/03-security-and-privacy.md`。

## 测试与验收

每个发布候选至少执行：

- Android 单元测试、发布版 Lint、正式签名构建和 APK 签名校验。
- Go 单元测试、`go vet`、生产构建和健康检查。
- 普通 SMS、5G消息、多段短信、双卡元数据和重复抑制测试。
- 锁屏、断网、手机重启、VPS 重启和服务端回滚测试。
- Gmail 实投、iPhone 通知和时间字段检查。
- 日志敏感信息检查、24 小时清理和备份恢复演练。

当前逐项结果和环境例外见：

- `docs/07-testing-and-acceptance.md`
- `docs/10-development-status.md`
- `docs/11-release-acceptance-0.3.4.md`

在剩余发布门槛完成前，不应把候选版描述为最终生产验收完成。

## 常见问题

### 手机收到短信但 Gmail 没有邮件

依次检查 App 总开关、短信权限、前台状态通知、电池优化豁免、ColorOS 自启动/后台运行、待发送数量和网络状态。使用 App 内置虚构测试区分“手机没有采集到短信”和“服务器或 Gmail 投递失败”。

### 断网期间收到短信怎么办

消息会先进入本地加密队列。网络恢复后 WorkManager 和前台服务会自动重试，邮件中同时显示原始接收时间与实际转发时间，并标记为补发。

### 为什么邮件内容比短信详情页短

这通常表示消息走的是 ColorOS 5G消息通知入口。系统通知提供多少正文，OmniSMS 才能取得多少；如果通知写着“点击查看详情”，App 无法绕过系统限制读取详情页隐藏内容。

### 为什么升级后突然补发旧短信

OmniSMS 会保存系统短信数据库的递增游标，并最多补查 24 小时内、游标之后的新记录。前台服务曾被 ColorOS 结束或广播被跳过时，升级/恢复服务可能发现并补发遗漏项，但不会在首次授权时批量上传更早的历史短信。

### 能不能把 OmniSMS 从最近任务中划掉

ColorOS 可能把上划清理视为强制结束，即使前台通知存在也可能停止实时服务。`0.3.7` 增加了同步可靠入队、动态接收、延迟恢复补查和跨通道可靠接管，但为获得最高可靠性，仍建议保留前台服务通知并避免主动划掉 App。

### Gmail 邮件会在 24 小时后删除吗

不会。24 小时限制只适用于 Android/VPS 的临时正文和状态数据。进入 Gmail 后，邮件按照用户自己的 Gmail 保留和删除规则管理。

## 项目文档

| 文档 | 用途 |
|---|---|
| `Codex.md` | 仓库开发工作入口和强制规则。 |
| `docs/01-product-requirements.md` | 已确认需求、范围和验收结果。 |
| `docs/02-technical-architecture.md` | Android、VPS、数据流、API 和架构决定。 |
| `docs/03-security-and-privacy.md` | 短信、凭据、日志和数据保留标准。 |
| `docs/04-ui-ux-specification.md` | Android 页面、状态和权限引导。 |
| `docs/05-development-standards.md` | 代码、Git、配置和错误处理规范。 |
| `docs/06-implementation-plan.md` | 阶段计划和完成定义。 |
| `docs/07-testing-and-acceptance.md` | 测试矩阵和发布门槛。 |
| `docs/08-deployment-and-operations.md` | VPS 部署、升级、备份和故障处理。 |
| `docs/09-feasibility-validation.md` | ColorOS 真机可行性验证过程。 |
| `docs/10-development-status.md` | 当前实现、证据、缺口和下一步。 |
| `docs/11-release-acceptance-0.3.4.md` | 正式签名版本的逐项验收记录。 |

文档和实现不一致时，任务不算完成。需求变化、架构决定、部署步骤或发布结果发生改变时，应同步更新相关文档。
