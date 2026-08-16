package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"omnisms/internal/api"
	"omnisms/internal/config"
	"omnisms/internal/mailer"
	"omnisms/internal/store"
	"omnisms/internal/worker"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	cfg, err := config.Load()
	if err != nil {
		log.Error("configuration invalid", "error", err)
		os.Exit(2)
	}
	db, err := store.Open(cfg.DatabasePath)
	if err != nil {
		log.Error("database open failed", "error", err)
		os.Exit(2)
	}
	defer db.Close()
	now := func() time.Time { return time.Now() }
	mail := mailer.SMTP{Host: cfg.SMTPHost, Port: cfg.SMTPPort, Username: cfg.SMTPUsername, Password: cfg.SMTPPassword, From: cfg.MailFrom, To: cfg.MailTo, Location: cfg.Timezone, Timeout: cfg.SMTPTimeout}
	work := &worker.Worker{Store: db, Mailer: mail, Log: log, Interval: cfg.WorkerInterval, Now: now}
	handler := (&api.Server{Store: db, DeviceID: cfg.DeviceID, DeviceSecret: cfg.DeviceSecret, MaxSkew: cfg.AuthMaxSkew, Retention: cfg.Retention, Log: log, Now: now}).Handler()
	httpServer := &http.Server{Addr: cfg.Listen, Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go work.Run(ctx)
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = httpServer.Shutdown(shutdownCtx)
	}()
	log.Info("server starting", "listen", cfg.Listen)
	if err = httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("server stopped unexpectedly", "error", err)
		os.Exit(1)
	}
}
