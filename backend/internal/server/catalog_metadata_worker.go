package server

import (
	"context"
	"github.com/Awmirai/filmiqoo/backend/internal/catalogindex"
	"log"
	"time"
)

func (s *Server) refreshCatalogMetadata(ctx context.Context) error {
	if s.tmdb == nil || !s.tmdb.Enabled() {
		return nil
	}
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id::text,kind,tmdb_id FROM media_titles
 WHERE tmdb_id IS NOT NULL AND tmdb_id>0
 AND (metadata_attempted_at IS NULL OR metadata_attempted_at<now()-interval '1 hour')
 AND (metadata_indexed_at IS NULL OR metadata_indexed_at<now()-CASE WHEN kind<>'movie' AND series_status='Returning Series' THEN interval '24 hours' ELSE interval '7 days' END)
 ORDER BY metadata_attempted_at NULLS FIRST,id LIMIT 10 FOR UPDATE SKIP LOCKED`)
	if err != nil {
		return err
	}
	type target struct {
		id, kind string
		tmdbID   int64
	}
	targets := []target{}
	for rows.Next() {
		var item target
		if err = rows.Scan(&item.id, &item.kind, &item.tmdbID); err != nil {
			rows.Close()
			return err
		}
		targets = append(targets, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	// Claim the batch quickly; no database transaction is held during HTTP requests.
	for _, item := range targets {
		if _, err = tx.Exec(ctx, `UPDATE media_titles SET metadata_attempted_at=now() WHERE id=$1`, item.id); err != nil {
			return err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return err
	}
	for _, item := range targets {
		meta, fetchErr := s.tmdb.Metadata(ctx, item.kind, item.tmdbID)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if fetchErr != nil {
			continue
		}
		if err = catalogindex.StoreMetadataWithRefresh(ctx, s.db, item.id, meta, true); err != nil {
			return err
		}
	}
	return nil
}
func (s *Server) runCatalogMetadataWorker(ctx context.Context) {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()
	for {
		batchCtx, cancel := context.WithTimeout(ctx, 130*time.Second)
		err := s.refreshCatalogMetadata(batchCtx)
		cancel()
		if err != nil && ctx.Err() == nil {
			log.Printf("catalog metadata maintenance: %v", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
