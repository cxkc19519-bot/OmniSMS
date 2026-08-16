package config

import (
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/mail"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Listen         string
	DatabasePath   string
	DeviceID       string
	DeviceSecret   []byte
	SMTPHost       string
	SMTPPort       int
	SMTPUsername   string
	SMTPPassword   string
	MailFrom       string
	MailTo         string
	Timezone       *time.Location
	Retention      time.Duration
	AuthMaxSkew    time.Duration
	WorkerInterval time.Duration
	SMTPTimeout    time.Duration
}

func Load() (Config, error) {
	var cfg Config
	var errs []error
	cfg.Listen = value("OMNISMS_LISTEN", "127.0.0.1:8088")
	cfg.DatabasePath = strings.TrimSpace(os.Getenv("OMNISMS_DATABASE_PATH"))
	cfg.DeviceID = strings.TrimSpace(os.Getenv("OMNISMS_DEVICE_ID"))
	cfg.SMTPHost = value("OMNISMS_SMTP_HOST", "smtp.gmail.com")
	cfg.SMTPUsername = strings.TrimSpace(os.Getenv("OMNISMS_SMTP_USERNAME"))
	cfg.SMTPPassword = strings.TrimSpace(os.Getenv("OMNISMS_SMTP_APP_PASSWORD"))
	cfg.MailFrom = strings.TrimSpace(os.Getenv("OMNISMS_MAIL_FROM"))
	cfg.MailTo = strings.TrimSpace(os.Getenv("OMNISMS_MAIL_TO"))

	if cfg.DatabasePath == "" {
		errs = append(errs, errors.New("OMNISMS_DATABASE_PATH is required"))
	}
	if cfg.DeviceID == "" {
		errs = append(errs, errors.New("OMNISMS_DEVICE_ID is required"))
	}
	if cfg.SMTPUsername == "" {
		errs = append(errs, errors.New("OMNISMS_SMTP_USERNAME is required"))
	}
	if cfg.SMTPPassword == "" {
		errs = append(errs, errors.New("OMNISMS_SMTP_APP_PASSWORD is required"))
	}
	if cfg.MailFrom == "" {
		errs = append(errs, errors.New("OMNISMS_MAIL_FROM is required"))
	}
	if cfg.MailTo == "" {
		errs = append(errs, errors.New("OMNISMS_MAIL_TO is required"))
	}

	secretText := strings.TrimSpace(os.Getenv("OMNISMS_DEVICE_SECRET"))
	if secretText == "" {
		errs = append(errs, errors.New("OMNISMS_DEVICE_SECRET is required"))
	} else if decoded, err := base64.RawURLEncoding.DecodeString(secretText); err != nil {
		errs = append(errs, errors.New("OMNISMS_DEVICE_SECRET must be unpadded base64url"))
	} else if len(decoded) < 32 {
		errs = append(errs, errors.New("OMNISMS_DEVICE_SECRET must decode to at least 32 bytes"))
	} else {
		cfg.DeviceSecret = decoded
	}

	cfg.SMTPPort = intValue("OMNISMS_SMTP_PORT", 587, &errs)
	cfg.Retention = durationValue("OMNISMS_RETENTION", 24*time.Hour, &errs)
	cfg.AuthMaxSkew = durationValue("OMNISMS_AUTH_MAX_SKEW", 5*time.Minute, &errs)
	cfg.WorkerInterval = durationValue("OMNISMS_WORKER_INTERVAL", 5*time.Second, &errs)
	cfg.SMTPTimeout = durationValue("OMNISMS_SMTP_TIMEOUT", 20*time.Second, &errs)

	tzName := value("OMNISMS_TIMEZONE", "Asia/Shanghai")
	if loc, err := time.LoadLocation(tzName); err != nil {
		errs = append(errs, fmt.Errorf("OMNISMS_TIMEZONE: %w", err))
	} else {
		cfg.Timezone = loc
	}

	if _, _, err := net.SplitHostPort(cfg.Listen); err != nil {
		errs = append(errs, fmt.Errorf("OMNISMS_LISTEN: %w", err))
	}
	if cfg.SMTPPort < 1 || cfg.SMTPPort > 65535 {
		errs = append(errs, errors.New("OMNISMS_SMTP_PORT must be between 1 and 65535"))
	}
	if cfg.Retention <= 0 || cfg.Retention > 24*time.Hour {
		errs = append(errs, errors.New("OMNISMS_RETENTION must be > 0 and <= 24h"))
	}
	if cfg.AuthMaxSkew <= 0 || cfg.AuthMaxSkew > 15*time.Minute {
		errs = append(errs, errors.New("OMNISMS_AUTH_MAX_SKEW must be > 0 and <= 15m"))
	}
	for name, address := range map[string]string{"OMNISMS_MAIL_FROM": cfg.MailFrom, "OMNISMS_MAIL_TO": cfg.MailTo} {
		if address != "" {
			if _, err := mail.ParseAddress(address); err != nil {
				errs = append(errs, fmt.Errorf("%s: invalid email address", name))
			}
		}
	}
	return cfg, errors.Join(errs...)
}

func value(name, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return fallback
}

func intValue(name string, fallback int, errs *[]error) int {
	v := value(name, strconv.Itoa(fallback))
	parsed, err := strconv.Atoi(v)
	if err != nil {
		*errs = append(*errs, fmt.Errorf("%s must be an integer", name))
		return fallback
	}
	return parsed
}

func durationValue(name string, fallback time.Duration, errs *[]error) time.Duration {
	v := value(name, fallback.String())
	parsed, err := time.ParseDuration(v)
	if err != nil {
		*errs = append(*errs, fmt.Errorf("%s must be a duration", name))
		return fallback
	}
	return parsed
}
