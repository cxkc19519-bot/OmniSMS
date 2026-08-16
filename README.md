# OmniSMS

OmniSMS 是个人自用的安卓短信转发系统。目标手机收到新短信后，App 将完整内容安全上传到个人 VPS，再由 VPS 转发到现有 Gmail，使 iPhone 通过 Gmail 通知提醒用户。

项目包含 Android 客户端、Go 服务端、Nginx/systemd 部署模板以及完整的需求、安全、测试和运维文档。生产地址、邮箱、设备密钥和 Gmail 凭据均不写入仓库。

当前状态：VPS、Cloudflare HTTPS、Gmail 投递及 ColorOS 12.1 真机联调已经完成，真实短信、断网补发、手机/VPS 重启恢复和 iPhone 通知均已验证。24小时锁屏待机和发布签名包仍在验收，因此当前版本不是最终生产发布版。

项目规范入口见 `Codex.md`，详细需求、架构、安全、测试和部署标准见 `docs/`。
