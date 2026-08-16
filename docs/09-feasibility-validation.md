# 阶段 0：技术可行性验证记录

版本：1.0  
状态：进行中  
开始日期：2026-08-16

## 1. 阶段目标

在正式编码前确认短信监听、后台补发、Gmail 通知和现有 VPS 环境能够形成可靠闭环。本阶段只做资料核对和最小实验，不处理真实短信，也不要求用户提供密码、验证码或服务器私钥。

## 2. 当前结论

| 编号 | 项目 | 状态 | 当前结论 |
|---|---|---|---|
| F-001 | Android 新短信监听 | 资料确认通过 | 可通过 `SMS_RECEIVED_ACTION` 接收，需用户授予 `RECEIVE_SMS` 权限。 |
| F-002 | 后台接收广播 | 资料确认通过 | `SMS_RECEIVED_ACTION` 是 Android 后台隐式广播限制的例外。 |
| F-003 | 断网可靠补发 | 资料确认通过 | WorkManager 支持网络约束、失败重试和退避策略。 |
| F-004 | OPPO 双卡/SIM 标识 | 条件通过 | SIM 2 已成功接收，系统返回第二卡槽元数据；160 字符短信的3个分段已合并。SIM 1 因运营商封停无法实测，但实现不得依赖固定卡槽。 |
| F-005 | ColorOS 锁屏和省电 | 通过 | 后台开关、锁屏、划掉任务、首次解锁前自启、重启后接收均已完成真机验证。 |
| F-006 | Gmail 发件授权 | 通过 | 两步验证已开启，应用专用密码功能可用。 |
| F-007 | Gmail 自发自收通知 | 通过 | 邮件进入收件箱并在 iPhone 弹出通知，实测延迟约15秒。 |
| F-008 | VPS 内存和磁盘 | 通过 | 961 MiB 内存、590 MiB available、15 GB 可用磁盘足够 Go + SQLite。 |
| F-009 | VPS 系统、端口和 HTTPS | DNS 已创建，TLS 证书待自动签发 | Ubuntu 24.04.2 x86_64；80/443 未监听；Cloudflare 代理 A 记录已创建。Universal SSL 已由“DNSSEC 校验错误”转为正常 TXT 待验证，Cloudflare 提示无需人工操作。 |

## 3. 官方资料依据

### Android

- Android API 将 `SMS_RECEIVED_ACTION` 定义为新文本短信广播，所有注册接收器可收到通知；接收要求 `RECEIVE_SMS` 权限：<https://developer.android.com/reference/android/provider/Telephony.Sms.Intents>
- Android 后台广播限制明确将 `SMS_RECEIVED_ACTION` 和 `BOOT_COMPLETED` 列为例外：<https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions>
- Android 警告广播接收器不适合长时间任务，因此实现必须先快速落盘，再安排后台上传：<https://developer.android.com/develop/background-work/background-tasks/broadcasts>
- WorkManager 支持网络约束、`Result.retry()` 以及线性/指数退避：<https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work>

### OPPO / ColorOS

- OPPO 官方说明 ColorOS 12 基于 Android 12，并包含权限和后台耗电管理：<https://www.oppo.com/in/coloros12/>
- OPPO 官方设备资料对需要持续后台工作的应用建议允许后台活动和自动启动；实际菜单名称可能随地区版本变化，因此仍以目标手机为准。

### Gmail / iPhone

- Google 官方说明：应用专用密码要求开启两步验证，部分组织账号、高级保护或仅安全密钥配置可能无法使用：<https://support.google.com/accounts/answer/185833>
- Google 官方说明：iPhone Gmail App 可为指定账号设置“所有新邮件”通知：<https://support.google.com/a/users/answer/9308767>

### 无域名 HTTPS

- Let's Encrypt 已于 2026 年开放公网 IP 证书；Certbot 5.4 及以上可通过 `--ip-address` 和 `shortlived` profile 申请：<https://letsencrypt.org/2026/03/11/shorter-certs-certbot.html>
- IP 证书有效期为6天，必须自动续期并在续期后重载服务。
- Cloudflare 官方要求生产环境的已发布 Tunnel 应用使用 Cloudflare 上的域名；随机 `trycloudflare.com` Quick Tunnel 仅用于开发测试：<https://developers.cloudflare.com/tunnel/setup/>

## 4. 用户验证 A：Gmail 自发自收提醒

目的：确定能否只用现有 Gmail 同时作为发件人和收件人。

步骤：

1. 在 iPhone 打开 Gmail App。
2. 进入“设置 → 你的账号 → 电子邮件通知”，选择“所有新邮件”。
3. 在 iPhone 系统“设置 → 通知 → Gmail”中允许通知。
4. 用电脑浏览器登录同一个 Gmail。
5. 给同一个邮箱地址发送一封主题为 `[OmniSMS测试] 自发自收通知` 的邮件。
6. 发送后锁定 iPhone 屏幕并等待最多2分钟。
7. 记录：邮件是否进入收件箱、是否弹出通知、延迟约多少秒。

判定：

- 邮件进入收件箱且连续3次均通知：通过，可以继续验证 SMTP 投递。
- 邮件进入但不通知：失败，需要用户批准独立发件地址或其他发件服务。
- 邮件不进入收件箱：失败，不能采用同账号自发自收方案。

测试内容必须使用上述虚构文本，不得使用真实验证码。

## 5. 用户验证 B：Gmail 授权条件

只检查设置是否存在，不要把任何密码发到聊天或写入仓库。

1. 确认 Google 账号是否已经开启“两步验证”。
2. 登录 Google 账号的“应用专用密码”页面。
3. 记录页面是“可以创建”，还是提示该功能不可用。
4. 本阶段不要把生成的16位密码粘贴到任何文档或聊天中。

判定：

- 可以创建：SMTP 方案具备条件；真正凭据在部署时只写入 VPS 受限配置。
- 不可创建：评估 Gmail OAuth；若需要外部邮件服务，必须先由用户确认。

## 6. 用户验证 C：VPS 环境

在 VPS 终端运行以下只读命令并粘贴输出。命令不会修改服务器：

```bash
cat /etc/os-release
uname -m
ss -lntp
systemctl --type=service --state=running --no-pager
```

还需用文字回答：

- 是否有一个已经解析到这台 VPS 的域名或子域名？只需回答“有”或“没有”，暂时不要提供真实域名。
- 当前代理节点是否使用 Nginx、Caddy、Apache、Xray、sing-box 或其他面板？若不清楚，可根据上面命令输出判断。

检查重点：

- Linux 发行版和 CPU 架构能否运行 Go 二进制。
- 80/443 端口是否已占用，以及能否复用现有反向代理。
- OmniSMS 必须避免影响现有代理节点。
- 当前没有 Swap，部署前建议增加 512 MiB 至 1 GiB Swap；这不是阶段 0 的阻塞项。

## 7. 用户验证 D：OPPO 基础信息

暂不安装测试 APK，只记录以下信息：

1. “设置 → 关于本机”中的 Android 版本。
2. ColorOS 12.1 的完整版本号/版本字符串。
3. 是否能够进入“应用管理 → 权限管理”，并看到短信权限分类。
4. “电池”或“应用耗电管理”中是否存在“允许后台活动”和“允许自动启动”一类选项。

不要提供手机序列号、IMEI、手机号或 SIM 卡号码。

双卡广播、多段短信和重启恢复需要阶段 0 的最小测试 APK 才能实测。该 APK 只在手机本地显示脱敏测试结果，不上传短信；开始制作前需确认上述环境信息。

## 8. 阶段退出条件

- F-001 至 F-009 全部得到明确结论。
- Gmail 发件通道和 iPhone 通知路径确定。
- VPS HTTPS 入口方案确定，且不会破坏现有代理节点。
- OPPO 双卡监听、后台和重启行为完成最小真机实验。
- 更新 `02-technical-architecture.md` 中所有相关待决策项。
- 用户确认最终技术方案后，才进入阶段 1 正式开发。

## 9. 当前需要用户提供的结果

已收到 Gmail、VPS 和 OPPO 基础信息。当前还需要：

1. 在 Cloudflare 创建 OmniSMS 专用子域名记录；不得在仓库中保存域名、服务器 IP、账号密码、API Token、私钥或 Gmail 应用专用密码。
2. Android 真机实验、HTTPS 方案选择和域名准备均已完成；待验证 DNS、TLS 与 VPS 入口。

### 2026-08-16：Cloudflare DNS 与 TLS 实测

- Cloudflare 区域为完整 DNS 设置，原有 DNS 记录为0条。
- 已按用户明确授权创建 OmniSMS 专用子域名的代理 A 记录，TTL 为自动；域名和服务器公网 IP 不写入仓库。
- Cloudflare 账号内没有可复用的正式 Tunnel；VPS 原有 `cloudflared` 未在该账号的 Tunnel 列表中出现，因此未改动现有 Tunnel。
- Cloudflare DNS 列表确认记录状态为“已代理”。
- 首次公网 HTTPS 检查返回 `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`；当时边缘证书页面显示 Universal SSL 为“待验证（错误）”，0张证书，并报告 DNSSEC key 获取或校验失败。
- 域名提供方页面随后确认 DNSSEC 未开启且 DS 记录为0，未发现需要删除的陈旧 DS；不得手工添加未知 DS 记录。
- 再次复查时，Universal SSL 已转为“待验证（TXT）”，Cloudflare 明确提示将代为完成证书验证、无需人工操作。当前等待自动签发后再复测公网 HTTPS；签发完成前不得把 TLS 标记为通过。
- 2026-08-17 刷新复查仍为“待验证（TXT）”，0张证书，没有重新出现 DNSSEC 错误。用户确认不等待证书，先进入不依赖公网 TLS 的本地开发；生产 HTTPS 验收仍保留为未通过。

这些信息不包含账号密码、验证码、私钥、IMEI 或手机号；如输出中意外出现敏感信息，请先遮盖再发送。

## 10. 实测记录

### 2026-08-16：用户环境

- Gmail 自发自收：进入收件箱，iPhone 弹出通知，约15秒。
- Google 账号：两步验证已开启，应用专用密码功能可用。
- VPS：Ubuntu 24.04.2 LTS、x86_64。
- VPS 网络：22、35551、8881 至 8891 等端口已有服务；80/443 未监听。
- VPS 服务：`sing-box`、Nginx、Cloudflare Tunnel 等正在运行。
- 域名：目前没有。
- OPPO：Android 12、ColorOS V12.1、型号 PDYM20、版本 PDYM20_11_F.43。
- OPPO 权限：短信权限存在；耗电异常优化可手动调整。
- 隐私：未记录用户邮箱、服务器 IP、手机号、IMEI 或任何凭据。

## 11. 本地构建环境

2026-08-16 已在项目的 `.tooling`（Git 忽略）目录准备受控构建工具：Microsoft OpenJDK 17.0.20、Android command-line tools、Android SDK 36、Build Tools 36.0.0、Platform Tools 37.0.1 和 Gradle 9.4.1。下载文件均按官方摘要完成校验。

最小 APK 已构建完成：

- 文件：`artifacts/OmniSMS-SmsProbe-v0.1.0-debug.apk`
- 大小：2,515,896 字节（约 2.40 MiB）
- SHA-256：`F6D6FF3D4BCE7C6E07C9381103749AB94A2121A90E3C2743E9A53B57E08F917C`
- 构建：`assembleDebug` 成功。
- 静态检查：Android Lint 0 错误、4 个非阻塞版本/适用范围提示。
- 权限检查：仅 `RECEIVE_SMS`、`RECEIVE_BOOT_COMPLETED`，没有 `INTERNET`。
- 签名检查：Android debug 证书，APK Signature Scheme v2 验证通过；仅供阶段 0 测试。

### 2026-08-16：OPPO 真机测试步骤

1. 安装 APK，打开 App，授予短信权限。
2. 为 App 允许后台活动，并关闭耗电异常优化。
3. 分别向 SIM 1 和 SIM 2 发送普通虚构测试短信；在 App 点“刷新验证结果”，记录是否收到、分段数和 SIM 元数据。
4. 发送至少 160 个英文字符或 80 个汉字的虚构长短信，检查分段合并结果。
5. 分别测试锁屏 10 分钟、从最近任务划掉 App、手机重启后不打开 App三个场景。
6. 只反馈“收到/未收到”、分段数、SIM 元数据（如有）和大约延迟，不反馈短信正文、摘要、手机号、IMEI、验证码或含这些内容的截图。

探针没有联网权限，因此真机阶段不包含断网补发；该能力将在正式实现本地可靠队列后验证。

### 2026-08-16：USB 真机初测

- ADB 已识别目标设备：PDYM20、Android 12（API 31）、系统版本 PDYM20_11_F.43。
- APK 安装成功，短信权限状态为已授权。
- App 安装后的本地记录显示已捕获2条短信。
- 最新一条测试短信由系统提供3个分段，App 合并后的长度为160个字符。
- 系统返回 `subscription=1, phone=1`；`phone=1` 与第二卡槽相符，SIM 2 卡槽识别初步通过。
- SIM 1 为 giffgaff，当前已被运营商封停；虽然显示信号，但不能接收短信。因此 SIM 1 结果记为“环境不可测”，不判定为 App 失败。
- 未记录或导出短信正文、验证码、手机号、IMEI、正文摘要或用户邮箱。
- SIM 1 因运营商封停无法接收，记为环境不可测；其余锁屏、划掉任务和重启场景均已完成。
- 锁屏首轮：手机确认处于 `Asleep` 后发送测试短信，系统短信收件箱确认已收到，但探针在30秒观察期及唤醒后均未新增记录。短信权限仍为已授权，Android AppOps 的后台模式为默认允许；当前判断为 ColorOS 后台/自启动策略待配置和复测，尚未判定最终不可行。
- ColorOS 原因定位：该 App 的“允许完全后台行为”“允许应用自启动”“允许应用关联启动”初始均为关闭；已通过系统设置逐项开启并复核，连同原本已开启的“允许唤醒前台”，四项当前均为开启。等待第二次锁屏接收测试。
- 锁屏复测：开启上述 ColorOS 开关后，手机在 `Asleep` 状态约3秒内捕获新短信，计数从2增加到3，单分段，卡槽元数据仍为 `subscription=1, phone=1`。锁屏后台接收通过，首轮失败原因确认为 ColorOS 默认后台设置。
- 划掉任务首测（0.1.0 纯广播版）：系统短信收件箱已收到，但探针60秒内未新增记录；确认纯广播版在 ColorOS 手动划掉任务后不可靠。
- 增强方案：0.2.0 探针增加不含短信内容的持续通知和 `START_STICKY` 前台服务；仍不声明 `INTERNET` 权限。
- 划掉任务复测（0.2.0 前台服务版）：任务卡移除后前台服务和进程均保持运行；手机在 `Asleep` 状态约3秒内捕获新短信，计数从3增加到4，卡槽元数据为 `subscription=1, phone=1`。测试通过。
- 架构结论：ColorOS 12.1 正式版必须使用前台服务，并引导用户开启“允许完全后台行为”“允许应用自启动”“允许应用关联启动”；持续通知属于可靠监听的必要表现。

增强版 APK：

- 文件：`artifacts/OmniSMS-SmsProbe-v0.2.0-debug.apk`
- SHA-256：`C0A7F09A18BCBF01B537546D83DF820D4FA6E9B867AAE7684D8FE6DE04949621`
- 静态检查：Android Lint 0 错误、4 个非阻塞提示。
- 签名检查：APK Signature Scheme v2 验证通过。
- 权限：`RECEIVE_SMS`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`；仍无 `INTERNET` 权限。
- 0.3.0 首次重启前版本仅监听普通 `BOOT_COMPLETED`，ColorOS 未自动恢复服务；据此增加 Direct Boot 验证版。
- 0.3.0 使用设备加密存储并同时接收 `LOCKED_BOOT_COMPLETED` 与 `BOOT_COMPLETED`。第二次重启时，手机尚未首次解锁，前台服务和进程已经自动运行；解锁后记录到两类启动广播且无错误。
- 重启后短信复测：未手动打开 App，手机在 `Asleep` 状态约3秒捕获短信，卡槽元数据为 `subscription=1, phone=1`，测试通过。

当前推荐验证包：

- 文件：`artifacts/OmniSMS-SmsProbe-v0.3.0-debug.apk`
- SHA-256：`4EE9D644BE9233B57A914531CE76DFA6C58CC6E6CCE6452D35DA47E47FFA91CA`
- 架构能力：前台服务、持续通知、Direct Boot、设备加密存储。
- 安全边界：仍无 `INTERNET` 权限，不上传短信。
- 构建验证：`assembleDebug` 成功；Android Lint 0 错误、6 个非阻塞提示；APK v2 签名验证通过。
