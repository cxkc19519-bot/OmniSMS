# 测试安装包

`OmniSMS-SmsProbe-v0.3.0-debug.apk` 是当前推荐的阶段 0 本地短信接收探针，不是正式版。0.1.0 和 0.2.0 保留用于技术对比。

- 不含联网权限，不会把短信上传到任何服务器。
- 不保存完整正文，只保存脱敏发送方、正文长度、摘要前缀、分段数、短信时间和系统提供的 SIM 元数据。
- 使用带持续通知的前台服务和 Direct Boot，在 ColorOS 划掉任务及重启后保持本地监听；通知不显示短信内容。
- 仅使用 Android 调试证书签名，只用于 OPPO 真机可行性测试。
- 0.3.0 SHA-256：`4EE9D644BE9233B57A914531CE76DFA6C58CC6E6CCE6452D35DA47E47FFA91CA`

详细步骤见 `validation/android-sms-probe/README.md` 和 `docs/09-feasibility-validation.md`。
