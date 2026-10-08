// catalogreindex examines existing filename/caption metadata; default is a read-only dry run.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"github.com/Awmirai/filmiqoo/backend/internal/catalogindex"
	"github.com/Awmirai/filmiqoo/backend/internal/config"
	"github.com/Awmirai/filmiqoo/backend/internal/storage"
	"github.com/Awmirai/filmiqoo/backend/internal/tmdb"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func main() {
	apply := flag.Bool("apply", false, "Explicitly persist metadata classifications; default dry run")
	fetch := flag.Bool("fetch-tmdb", false, "Explicitly allow TMDB metadata-only HTTP requests")
	refresh := flag.Bool("refresh-metadata", false, "With fetch-tmdb, refresh indexed title facets in bounded batches")
	limit := flag.Int("limit", 500, "Maximum versions/title metadata to inspect, 1..10000")
	after := flag.String("after-version", "", "Resume version UUID from prior report nextVersion")
	setConvention := flag.Bool("set-source-convention", false, "Explicit source rule; requires source-chat, and apply to persist")
	sourceChat := flag.Int64("source-chat", 0, "Telegram chat ID for the explicit generic marker convention")
	genericDub := flag.Bool("generic-dub-is-persian", false, "For that explicitly configured source, generic DUB denotes Persian")
	genericSub := flag.Bool("generic-sub-is-persian", false, "For that explicitly configured source, generic SUB denotes Persian")
	flag.Parse()
	if *limit < 1 || *limit > 10000 {
		log.Fatal("limit must be 1..10000")
	}
	if *refresh && !*fetch {
		log.Fatal("refresh-metadata requires fetch-tmdb")
	}
	if *setConvention && *sourceChat == 0 {
		log.Fatal("set-source-convention requires source-chat")
	}
	cfg := config.Load()
	if *fetch && strings.TrimSpace(cfg.TMDBToken) == "" {
		log.Fatal("fetch-tmdb requires configured TMDB token")
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	db, err := storage.OpenPostgres(ctx, cfg.DatabaseURL, cfg.PostgresMaxConns, cfg.PostgresMinConns)
	if err != nil {
		log.Fatalf("open database: %v", err)
	}
	defer db.Close()
	// No migration application, Telegram client, stream fetch, or media download is performed.
	if *setConvention && *apply {
		_, err = db.Exec(ctx, `INSERT INTO telegram_source_conventions(telegram_chat_id,generic_dub_is_persian,generic_sub_is_persian) VALUES($1,$2,$3) ON CONFLICT(telegram_chat_id) DO UPDATE SET generic_dub_is_persian=EXCLUDED.generic_dub_is_persian,generic_sub_is_persian=EXCLUDED.generic_sub_is_persian,updated_at=now()`, *sourceChat, *genericDub, *genericSub)
		if err != nil {
			log.Fatalf("source convention: %v", err)
		}
	}
	report, err := catalogindex.Backfill(ctx, db, tmdb.New(cfg.TMDBToken), catalogindex.BackfillOptions{Apply: *apply, FetchTMDB: *fetch, RefreshMetadata: *refresh, Limit: *limit, AfterVersion: *after})
	if err != nil {
		log.Fatalf("metadata reindex: %v", err)
	}
	output := map[string]any{"report": report, "sourceConventionRequested": *setConvention, "sourceConventionApplied": *setConvention && *apply, "videoDownloads": 0}
	if *setConvention {
		output["sourceConvention"] = map[string]any{"chatId": *sourceChat, "genericDubIsPersian": *genericDub, "genericSubIsPersian": *genericSub}
	}
	if err = json.NewEncoder(os.Stdout).Encode(output); err != nil {
		log.Fatal(err)
	}
}
