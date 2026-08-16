#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "请以 root 运行。" >&2
  exit 1
fi

read -r -p "发送 Gmail 地址: " gmail_sender
if [[ ! "$gmail_sender" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
  echo "发送 Gmail 地址格式不正确。" >&2
  exit 1
fi

read -r -p "接收邮件地址: " gmail_recipient
if [[ ! "$gmail_recipient" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
  echo "接收邮件地址格式不正确。" >&2
  exit 1
fi

read -r -s -p "发送 Gmail 的应用专用密码（输入时不会显示）: " gmail_password
echo
gmail_password="${gmail_password// /}"
if [[ ${#gmail_password} -lt 16 ]]; then
  echo "应用专用密码长度不正确。" >&2
  exit 1
fi

device_id="device-$(openssl rand -hex 16)"
device_secret="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"

install -d -o root -g omnisms -m 0750 /etc/omnisms
umask 0077
{
  printf 'OMNISMS_LISTEN=127.0.0.1:8088\n'
  printf 'OMNISMS_DATABASE_PATH=/var/lib/omnisms/omnisms.sqlite3\n'
  printf 'OMNISMS_DEVICE_ID=%s\n' "$device_id"
  printf 'OMNISMS_DEVICE_SECRET=%s\n' "$device_secret"
  printf 'OMNISMS_SMTP_HOST=smtp.gmail.com\n'
  printf 'OMNISMS_SMTP_PORT=587\n'
  printf 'OMNISMS_SMTP_USERNAME=%s\n' "$gmail_sender"
  printf 'OMNISMS_SMTP_APP_PASSWORD=%s\n' "$gmail_password"
  printf 'OMNISMS_MAIL_FROM=%s\n' "$gmail_sender"
  printf 'OMNISMS_MAIL_TO=%s\n' "$gmail_recipient"
  printf 'OMNISMS_TIMEZONE=Asia/Shanghai\n'
  printf 'OMNISMS_RETENTION=24h\n'
  printf 'OMNISMS_AUTH_MAX_SKEW=5m\n'
  printf 'OMNISMS_WORKER_INTERVAL=5s\n'
  printf 'OMNISMS_SMTP_TIMEOUT=20s\n'
} > /etc/omnisms/omnisms.env
chown root:omnisms /etc/omnisms/omnisms.env
chmod 0640 /etc/omnisms/omnisms.env

systemctl restart omnisms.service
sleep 2
curl --fail --silent --show-error http://127.0.0.1:8088/health/ready
echo
echo "配置已保存，服务已启动。接下来会提交一条固定虚构测试短信。"
set -a
# shellcheck disable=SC1091
source /etc/omnisms/omnisms.env
set +a
/opt/omnisms/omnisms-smoketest
echo
echo "手机配对信息（不要发到聊天或截图）："
printf '服务器地址: https://<OmniSMS 专用域名>\n设备编号: %s\n设备密钥: %s\n' "$device_id" "$device_secret"
