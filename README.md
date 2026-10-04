<div align="center">

# 📨 OmniSMS

**把 Android 手机上的重要短信，安全、可靠地转发到你的 Gmail。**

[![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#运行环境) [![Android 0.4.4](https://img.shields.io/badge/Android-0.4.4-2878B9)](#当前状态) [![Server 0.3.3](https://img.shields.io/badge/Server-0.3.3-00ADD8?logo=go&logoColor=white)](#当前状态) [![Go 1.24](https://img.shields.io/badge/Go-1.24-00ADD8?logo=go&logoColor=white)](#开发与构建) [![Personal Use](https://img.shields.io/badge/用途-个人自用-f0a030)](#使用边界)

普通 SMS · 双卡 · ColorOS 5G消息 · 断网补发 · 锁屏运行 · Gmail 通知

</div>

---

OmniSMS 是一套面向个人自用场景的短信转发系统。Android 手机收到短信后，App 会先将消息安全写入本地加密队列，再通过 HTTPS 上传到你的私人 VPS，最后由 VPS 使用 Gmail SMTP 投递到指定邮箱。

它适合这样的场景：Android 手机留在家中或其他地点，你希望在 iPhone、电脑或平板上及时获取验证码和其他重要短信。

> [!IMPORTANT]
> 本项目只用于用户本人管理自己手机上的短信。它不是公共短信平台，不支持远程发送短信、短信回复、多人账号、公开注册或任意应用通知采集。

## 目录

- [项目亮点](#项目亮点)
- [当前状态](#当前状态)
- [系统架构](#系统架构)
- [运行环境](#运行环境)
- [开始使用](#开始使用)
- [ColorOS 后台设置](#coloros-后台设置)
- [VPS 部署概要](#vps-部署概要)
- [Android 配置与发布](#android-配置与发布)
- [安全与隐私](#安全与隐私)
- [可靠性设计](#可靠性设计)
- [开发与构建](#开发与构建)
- [测试与验收](#测试与验收)
- [常见问题](#常见问题)
- [项目结构](#项目结构)
- [项目文档](#项目文档)

## 项目亮点

| 能力 | 说明 |
|---|---|
| 双卡短信监听 | 监听两张 SIM 卡的新短信，不按发送方过滤；尽力保留卡槽信息。 |
| 完整短信转发 | 普通 SMS 会转发完整正文、发送方、SIM、接收时间和实际转发时间。 |
| ColorOS 5G消息 | 可选使用通知读取权限，兼容未进入标准 SMS 数据库的 5G消息。 |
| 锁屏可靠运行 | 前台服务、动态接收器、短时唤醒锁和收件箱补查共同降低锁屏漏转发概率。 |
| 断网自动补发 | 网络中断或 VPS 暂时不可用时先进入本地加密队列，恢复后自动重试。 |
| 防止重复邮件 | Android 消息指纹、请求幂等键和服务端唯一约束提供多层去重。 |
| 私人部署 | App、VPS 和 Gmail 都由用户本人控制，不依赖公共短信中转平台。 |
| 低资源占用 | 服务端为 Go 单进程程序，使用 SQLite，适合小型 VPS。 |

## 当前状态

| 组件 | 版本或状态 | 验证结果 |
|---|---|---|
| Android 正式基线 | `0.3.4` | 已完成正式签名、配对、断网补发与重启恢复验证。 |
| Android 候选版 | `0.4.4` / `versionCode 18` | 已完成单元测试、Lint、正式签名覆盖安装和锁屏普通 SMS 实投。 |
| VPS 服务端 | `0.3.3` | 已部署；健康检查、Gmail 投递、失败重试和同版本回滚演练通过。 |
| HTTPS | 已运行 | 公网证书、反向代理与健康接口验证通过。 |
| Gmail | 已运行 | 独立发件 Gmail、接收 Gmail 和 iPhone 通知均已验证。 |
| 发布结论 | 候选验收中 | 主链路可用，但剩余发布门槛尚未全部关闭。 |

### 最近一次锁屏验证

在 OPPO A72 5G、Android 12、ColorOS 12.1 上：

- 手机全过程保持锁屏和休眠状态；
- 普通 SMS 完整正文成功转发；
- 短信接收至 Gmail 收件约 16 秒；
- 邮件标记为“实时转发”，SIM 2 和两个时间字段正确；
- 0.4.4 不再常驻持有 CPU 唤醒锁，只在接收和上传期间短暂持有。

详细证据与未完成项见 [`docs/10-development-status.md`](docs/10-development-status.md)。

## 系统架构

```text
┌──────────────────────── Android 手机 ────────────────────────┐
│                                                              │
│  普通 SMS 广播 ───────────────┐                              │
│  前台服务动态接收器 ───────────┤                              │
│  系统收件箱增量补查 ───────────┼─→ 去重 → 本地加密队列        │
│  ColorOS 5G消息通知 ──────────┘                              │
│                                      │                       │
└──────────────────────────────────────┼───────────────────────┘
                                       │ HTTPS + HMAC-SHA256
                                       ▼
┌────────────────────────── 私人 VPS ───────────────────────────┐
│  Nginx / HTTPS → OmniSMS API → 幂等校验 → SQLite 投递队列     │
└──────────────────────────────────────┼───────────────────────┘
                                       │ Gmail SMTP
                                       ▼
                         Gmail 收件箱 / iPhone 通知
```

一次正常投递包含以下步骤：

1. Android 接收普通 SMS 或系统短信 App 发布的 5G消息通知。
2. App 合并多段短信，采集发送方、消息时间和可获得的 SIM 信息。
3. 完整消息先加密写入本地 SQLite 队列，落盘成功后才开始上传。
4. App 使用 HTTPS 和 HMAC 签名向 VPS 提交消息。
5. VPS 校验设备、时间戳、随机数、签名和幂等键。
6. 服务端通过 Gmail SMTP 发送响应式 HTML 邮件及纯文本版本。
7. 投递成功后清除服务端短信正文；临时状态和失败正文最长保留 24 小时。

## 运行环境

### 已验证设备

| 角色 | 环境 |
|---|---|
| 短信来源手机 | OPPO A72 5G（PDYM20） |
| Android 系统 | Android 12 / ColorOS 12.1 |
| 接收设备 | iPhone 15 Pro Max，使用 Gmail App |
| VPS | Ubuntu 24.04 LTS，Linux `amd64` |
| VPS 规格 | 1 vCPU / 1 GB RAM / 20 GB 存储可正常运行 |

### 软件要求

- Android 12（API 31）或更高版本；其他厂商系统尚未完成同等真机验证。
- 一个可配置 HTTPS 的域名或子域名。
- 一台可以运行 Linux `amd64` 程序的 VPS。
- 一个用于 SMTP 发件的 Gmail，需开启两步验证并创建应用专用密码。
- 一个接收邮件的邮箱；可以与发件邮箱不同。

## 开始使用

### 新手路线

如果你只想把系统部署起来，按下面顺序进行：

1. 准备 VPS、域名、发送 Gmail 和接收邮箱。
2. 在本地构建服务端并部署到 VPS。
3. 配置 Nginx 与有效 HTTPS 证书。
4. 生成设备 ID 和设备密钥。
5. 使用正式签名构建 Android APK。
6. 安装 APK，填写服务器地址、设备 ID 和设备密钥。
7. 授予短信权限并开启转发。
8. 完成 [ColorOS 后台设置](#coloros-后台设置)。
9. 使用 App 内置的虚构测试验证 HTTPS 与 Gmail。
10. 依次验证锁屏短信、断网补发、重启恢复和 Gmail 通知。

> [!CAUTION]
> 不要把真实 Gmail 应用专用密码、设备密钥、服务器生产配置、短信正文或验证码提交到 GitHub，也不要粘贴到公开 Issue。

### 配置关系

```text
Android App
├─ 服务器地址：例如 https://sms.example.com
├─ 设备编号：服务端配置中的设备 ID
└─ 设备密钥：与服务端相同的随机密钥

VPS
├─ 设备 ID 与设备密钥
├─ Gmail SMTP 应用专用密码
├─ 发件邮箱
├─ 收件邮箱
└─ SQLite 数据文件
```

## ColorOS 后台设置

这是 OPPO/ColorOS 上最重要的安装步骤。**仅关闭 Android 电池优化并不足够。**

进入：

```text
设置 → 应用管理 → OmniSMS → 耗电管理
```

确认以下四项全部开启：

- [x] 允许唤醒前台
- [x] 允许完全后台行为
- [x] 允许应用自启动
- [x] 允许应用关联启动

然后再确认：

- OmniSMS 已获得“接收短信”和“读取短信”权限；
- 系统通知栏中存在“OmniSMS 正在运行”的持续通知；
- Android 电池优化已对 OmniSMS 豁免；
- 如果需要兼容 5G消息，已授予通知使用权；
- 不要主动“强行停止”应用；
- 为获得最高可靠性，不建议从最近任务中上划清理 OmniSMS。

如果缺少“允许完全后台行为”“自启动”或“关联启动”，ColorOS 可能把短信回调冻结到亮屏后才交给 App，表现为手机已收到短信，但 Gmail 数分钟后甚至解锁后才收到。

## VPS 部署概要

### 推荐目录

```text
/opt/omnisms/       程序文件
/etc/omnisms/       生产配置与秘密
/var/lib/omnisms/   SQLite 数据
```

### 服务组成

| 组件 | 用途 |
|---|---|
| `omnisms-server` | 接收 Android 上传、维护投递队列并发送邮件。 |
| systemd | 保证进程开机启动和异常恢复。 |
| Nginx | 提供公网 HTTPS 入口并反向代理到本机服务。 |
| SQLite | 保存短期队列和幂等状态。 |
| Gmail SMTP | 将短信内容投递到目标邮箱。 |

服务默认只应监听：

```text
127.0.0.1:8088
```

不要把内部 HTTP 服务直接暴露到公网。公网请求应先经过 Nginx 和有效 HTTPS 证书。

### 配置

配置模板位于 [`server/.env.example`](server/.env.example)。生产环境需要配置：

- 本机监听地址；
- SQLite 数据库路径；
- 设备 ID 与设备密钥；
- Gmail SMTP 地址、端口和应用专用密码；
- 发件人与收件人地址；
- 展示时区；
- 24 小时数据保留期；
- 认证允许的时间偏差与重试周期。

生产配置建议保存到：

```text
/etc/omnisms/omnisms.env
```

文件权限必须限制为 `0600`。Gmail 必须使用应用专用密码，不得使用 Gmail 普通登录密码。

### 健康接口

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/health/live` | 检查服务进程是否存活。 |
| `GET` | `/health/ready` | 检查数据库和服务是否已就绪。 |
| `POST` | `/v1/messages` | 接收经过签名和幂等保护的短信消息。 |

部署、升级、备份、回滚和 Nginx 示例见 [`docs/08-deployment-and-operations.md`](docs/08-deployment-and-operations.md)。

## Android 配置与发布

### 首次配置

1. 安装使用正式私钥签名的 APK。
2. 打开“设置”页，填写 HTTPS 服务地址、设备 ID 和设备密钥。
3. 授予“接收短信”和“读取短信”权限。
4. 开启短信转发总开关。
5. 完成 [ColorOS 后台设置](#coloros-后台设置)。
6. 如需转发 5G消息，单独授予通知使用权。
7. 发送 App 内置的固定虚构测试消息。
8. 确认 Gmail 只收到一封测试邮件，且没有真实短信内容。

### 覆盖升级

覆盖安装必须同时满足：

- 使用与已安装版本相同的签名证书；
- 新 APK 的 `versionCode` 高于旧版本；
- 使用 `adb install -r` 或正常系统升级安装；
- 升级后重新检查前台服务、短信权限和 ColorOS 后台设置。

正常覆盖安装会保留配对信息和本地待发队列。卸载后重装会清除 App 数据，需要重新配对。

### 正式签名

首次创建签名密钥：

```powershell
powershell -ExecutionPolicy Bypass -File .\android\create-release-keystore.ps1
```

生成后必须分别加密备份：

1. `.release/omnisms-release.jks`；
2. 签名密码；
3. 恢复说明与证书指纹。

签名密钥和密码不能保存在同一位置，也不得提交到 Git。签名密钥丢失后，无法继续以升级方式安装相同包名的新版本。

## 安全与隐私

### 数据保护

- Android 使用 Android Keystore 保护本地敏感配置和短信队列。
- App 只允许通过有效 HTTPS 连接 VPS，不提供忽略证书错误或回退 HTTP 的开关。
- 每个请求包含时间戳、随机数、幂等键和 HMAC-SHA256 签名。
- 服务端使用 `device_id + message_id` 唯一约束抵御重复提交。
- 邮件成功发送后，服务端立即清除短信正文。
- 未投递正文和状态记录最长保留 24 小时。
- Gmail 中已经收到的邮件不会被 OmniSMS 自动删除。

### 日志规则

以下内容不得写入普通日志、崩溃报告或 Git：

- 完整短信正文和验证码；
- 发件或收件邮箱地址；
- 手机号、服务器地址和设备密钥；
- Gmail 应用专用密码；
- Android 正式签名私钥及密码。

日志只允许记录匿名消息标识、队列数量、状态和不含敏感数据的错误码。

### 通知读取权限

Android 的通知使用权在系统层面可以访问所有通知。OmniSMS 会在解析标题或正文前先检查来源包名，只允许 OPPO 系统短信 App `com.android.mms`，其他应用通知直接忽略。

完整要求见 [`docs/03-security-and-privacy.md`](docs/03-security-and-privacy.md)。

## 可靠性设计

### 先落盘，再上传

普通 SMS 在广播回调返回前同步写入本地加密队列。网络 I/O 在后台执行，即使上传失败，短信也不会因为进程结束而直接丢失。

### 多入口补偿

Android 端同时使用：

1. 清单 `BroadcastReceiver`，用于进程未运行时接收系统短信广播；
2. 前台服务动态接收器，规避部分 ColorOS 清单广播限制；
3. 系统短信收件箱观察器与递增游标补查；
4. 通知监听器，兼容不进入标准短信表的 5G消息。

所有入口共享不可逆消息指纹和本地唯一约束，避免重复邮件。

### 短时唤醒

0.4.4 不常驻持有 CPU 唤醒锁：

- SMS 广播交接：最长 15 秒；
- 5G消息通知合并：最长 10 秒；
- 上传处理：最长 30 秒。

处理完成后立即释放。可靠运行依赖正确的 ColorOS 后台设置，而不是让 CPU 永不休眠。

### 两级重试

- Android：WorkManager 在网络恢复后按指数退避重试。
- VPS：区分临时 SMTP 错误和永久配置错误；临时错误自动重试，永久错误停止无意义重试。

## 开发与构建

### 依赖版本

| 部分 | 要求 |
|---|---|
| JDK | 17 |
| Android SDK | 36 |
| Android Gradle Plugin | 9.2.0 |
| Gradle | 9.4.1 或兼容版本 |
| Go | 1.24 |
| 服务端目标 | Linux `amd64` |

### Android 自动检查

在 `android/` 目录执行：

```powershell
gradle testDebugUnitTest lintRelease assembleRelease
```

主要输出：

| 产物 | 路径 |
|---|---|
| 单元测试报告 | `android/app/build/reports/tests/testDebugUnitTest/` |
| Lint 报告 | `android/app/build/reports/lint-results-release.html` |
| 发布 APK | `android/app/build/outputs/apk/release/app-release.apk` |

正式发布前验证签名：

```powershell
apksigner verify --verbose --print-certs app-release.apk
```

没有配置正式签名时，`assembleRelease` 的结果不能视为可覆盖生产安装的正式包。

### Go 服务端检查

在 `server/` 目录执行：

```bash
go test ./...
go vet ./...
go build ./cmd/omnisms-server
```

构建 Linux `amd64` 单文件：

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -o build/omnisms-server-linux-amd64 ./cmd/omnisms-server
```

生成设备密钥：

```bash
go run ./cmd/omnisms-keygen
```

密钥输出只能进入 VPS 生产配置和 Android 安全配对流程，不应复制到源码、文档或公开聊天记录。

## 测试与验收

每个候选版本至少执行：

- Android 单元测试、发布版 Lint、正式签名构建和 APK 签名校验；
- Go 单元测试、`go vet`、Linux 生产构建和健康检查；
- 普通 SMS、长短信、中文与特殊字符测试；
- 双卡卡槽信息和重复抑制测试；
- ColorOS 锁屏、上划清理与手机重启测试；
- 断网排队和恢复补发测试；
- 5G消息通知合并与其他应用通知隔离测试；
- VPS 重启、备份恢复和二进制回滚测试；
- Gmail 实投、iPhone 通知和两个时间字段检查；
- 日志敏感信息和 24 小时清理检查。

测试矩阵见 [`docs/07-testing-and-acceptance.md`](docs/07-testing-and-acceptance.md)。在剩余发布门槛完成前，不应将候选版本描述为最终生产验收完成。

## 常见问题

<details>
<summary><strong>手机收到了短信，但 Gmail 没有邮件</strong></summary>

依次检查：

1. OmniSMS 转发总开关是否开启；
2. “接收短信”和“读取短信”权限是否已授予；
3. 前台状态通知是否存在；
4. Android 电池优化是否已豁免；
5. ColorOS 四个后台开关是否全部开启；
6. App 首页是否显示待发送或永久失败项目；
7. 手机是否可以访问配置的 HTTPS 地址；
8. VPS 的 `/health/ready` 是否正常；
9. Gmail 是否把邮件归入垃圾邮件或其他分类。

先发送 App 内置虚构测试，可以区分“手机未采集到短信”和“服务器/Gmail 投递失败”。

</details>

<details>
<summary><strong>为什么锁屏后只有亮屏才转发？</strong></summary>

这通常是 ColorOS 厂商级后台冻结导致的。即使 Android 标准电池白名单已经生效，只要“允许完全后台行为”“允许应用自启动”或“允许应用关联启动”仍关闭，系统就可能把短信回调延迟到亮屏。请按 [ColorOS 后台设置](#coloros-后台设置) 逐项检查。

</details>

<details>
<summary><strong>断网期间收到短信怎么办？</strong></summary>

消息会先进入 Android 本地加密队列。网络恢复后，前台服务和 WorkManager 会自动重试。邮件会同时显示原始短信接收时间、实际转发时间，并标记为“断网/失败后补发”。

</details>

<details>
<summary><strong>为什么邮件内容比短信详情页短？</strong></summary>

这通常说明消息走的是 ColorOS 5G消息通知入口。系统通知提供多少正文，OmniSMS 才能读取多少；如果通知只显示“点击查看详情”，App 无法绕过系统限制读取详情页隐藏内容。普通 SMS 路径会优先保留完整正文。

</details>

<details>
<summary><strong>为什么升级后补发了旧短信？</strong></summary>

OmniSMS 保存系统短信数据库的递增游标，并最多补查 24 小时内、游标之后的新记录。前台服务曾被系统终止或广播被跳过时，服务恢复可能发现并补发遗漏项；首次授权不会批量上传更早的历史短信。

</details>

<details>
<summary><strong>可以从最近任务中划掉 OmniSMS 吗？</strong></summary>

不建议。ColorOS 可能把上划清理视为强制结束，即使前台通知存在，也可能产生实时监听空窗。0.4.4 提供收件箱补查和恢复机制，但补发不能替代即时转发。

</details>

<details>
<summary><strong>Gmail 邮件会在 24 小时后自动删除吗？</strong></summary>

不会。24 小时限制只适用于 Android/VPS 的临时正文和状态数据。进入 Gmail 后，邮件由用户自己的 Gmail 保留和删除规则管理。

</details>

## 使用边界

- 单个用户、单台 Android 来源手机；
- 只监听用户本人设备上的 SMS 和受限的系统短信通知；
- 不支持从 Gmail 或网页回复短信；
- 不支持远程控制手机发送短信；
- 不提供网页短信收件箱；
- 不提供多人权限、公开注册或 SaaS 托管；
- SIM 1 当前因运营商环境原因未完成真实接收验证，不能将 SIM 2 结果自动视为 SIM 1 已通过。

## 项目结构

```text
OmniSMS/
├─ android/                         Android 客户端与签名辅助脚本
│  └─ app/src/
│     ├─ main/                      正式客户端代码和资源
│     ├─ debug/                     仅调试构建使用的测试入口
│     └─ test/                      Android JVM 单元测试
├─ server/                          Go 服务端、配置示例和自动测试
│  ├─ cmd/omnisms-server/           服务主程序
│  ├─ cmd/omnisms-keygen/           设备密钥生成工具
│  ├─ cmd/omnisms-smoketest/        固定虚构数据冒烟测试
│  └─ internal/                     API、认证、存储、邮件和队列
├─ deploy/                          systemd、Nginx 与首次配置模板
├─ validation/android-sms-probe/    ColorOS 可行性验证 App
├─ docs/                            需求、架构、安全、测试和运维文档
├─ artifacts/                       构建产物说明，不保存生产秘密
├─ Codex.md                         仓库开发工作入口
└─ README.md                        项目首页
```

下列文件仅存在于开发电脑并被 Git 忽略：

- `.release/`：正式签名密钥和本地发布产物；
- `android/signing.local.properties`：本地签名配置；
- 生产 `.env`、设备密钥和 Gmail 应用专用密码。

## 项目文档

| 文档 | 用途 |
|---|---|
| [`Codex.md`](Codex.md) | 仓库工作入口和强制规则。 |
| [`docs/README.md`](docs/README.md) | 文档导航、状态和维护规则。 |
| [`docs/01-product-requirements.md`](docs/01-product-requirements.md) | 已确认需求、范围和验收目标。 |
| [`docs/02-technical-architecture.md`](docs/02-technical-architecture.md) | Android、VPS、数据流、API 和架构决策。 |
| [`docs/03-security-and-privacy.md`](docs/03-security-and-privacy.md) | 短信、凭据、日志和数据保留标准。 |
| [`docs/04-ui-ux-specification.md`](docs/04-ui-ux-specification.md) | Android 页面、状态与权限引导。 |
| [`docs/05-development-standards.md`](docs/05-development-standards.md) | 代码、Git、配置和错误处理规范。 |
| [`docs/06-implementation-plan.md`](docs/06-implementation-plan.md) | 阶段计划与完成定义。 |
| [`docs/07-testing-and-acceptance.md`](docs/07-testing-and-acceptance.md) | 测试矩阵与发布门槛。 |
| [`docs/08-deployment-and-operations.md`](docs/08-deployment-and-operations.md) | VPS 部署、升级、备份和故障处理。 |
| [`docs/09-feasibility-validation.md`](docs/09-feasibility-validation.md) | ColorOS 真机可行性验证。 |
| [`docs/10-development-status.md`](docs/10-development-status.md) | 当前实现、验证证据、缺口与下一步。 |
| [`docs/11-release-acceptance-0.3.4.md`](docs/11-release-acceptance-0.3.4.md) | 正式签名版本的逐项验收记录。 |

---

<div align="center">

**个人自用 · 私人部署 · 请妥善保护短信、验证码与签名材料**

</div>
