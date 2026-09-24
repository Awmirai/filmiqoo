package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Awmirai/filmiqoo/backend/internal/config"
	"github.com/Awmirai/filmiqoo/backend/internal/storage"
)

func main() {
	cfg:=config.Load()
	ctx,cancel:=signal.NotifyContext(
		context.Background(),
		os.Interrupt,
		syscall.SIGTERM,
	)
	defer cancel()

	db,err:=storage.OpenPostgres(ctx,cfg.DatabaseURL)
	if err!=nil { log.Fatalf("postgres: %v",err) }
	defer db.Close()

	migrationCtx,migrationCancel:=context.WithTimeout(ctx,5*time.Minute)
	defer migrationCancel()
	if err:=storage.ApplyMigrations(migrationCtx,db); err!=nil {
		log.Fatalf("migrations: %v",err)
	}
	log.Printf("database schema is up to date")
}
