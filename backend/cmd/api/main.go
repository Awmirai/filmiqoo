package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Awmirai/filmiqoo/backend/internal/config"
	"github.com/Awmirai/filmiqoo/backend/internal/server"
	"github.com/Awmirai/filmiqoo/backend/internal/storage"
)

func main() {
	cfg := config.Load()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	db, err := storage.OpenPostgres(ctx, cfg.DatabaseURL)
	if err != nil { log.Fatalf("postgres: %v", err) }
	defer db.Close()

	redisClient := storage.OpenRedis(cfg.RedisAddr, cfg.RedisPassword)
	defer redisClient.Close()

	srv := server.New(cfg, db, redisClient)
	errCh := make(chan error, 1)
	go func() {
		log.Printf("Filmiqoo API listening on %s", cfg.HTTPAddr)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
	case err := <-errCh:
		if err != nil { log.Printf("http server: %v", err) }
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	_ = srv.Shutdown(shutdownCtx)
}
