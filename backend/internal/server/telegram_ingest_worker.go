package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type telegramRetryItem struct {
	id string
	attempt int
}

func telegramSourceFingerprint(body telegramIngestRequest) string {
	raw,_:=json.Marshal([]any{
		body.ChatID,
		body.MessageID,
		strings.TrimSpace(body.FileID),
		strings.TrimSpace(body.FileUniqueID),
		body.FileNumericID,
		strings.TrimSpace(body.FileName),
		body.FileSizeBytes,
		strings.TrimSpace(body.MimeType),
		strings.TrimSpace(body.Caption),
		strings.TrimSpace(body.StreamHash),
	})
	sum:=sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func (s *Server) claimTelegramRetryBatch(
	ctx context.Context,
	limit int,
) ([]telegramRetryItem,error) {
	if limit<=0 { limit=20 }
	rows,err:=s.db.Query(ctx,`
		UPDATE telegram_ingest_items
		   SET status='resolving',
		       attempt_count=attempt_count+1,
		       last_attempt_at=now(),
		       error_text='',
		       updated_at=now()
		 WHERE id IN (
		   SELECT id
		     FROM telegram_ingest_items
		    WHERE (
		      status IN ('pending_metadata','failed')
		      OR (
		        status='resolving'
		        AND last_attempt_at<now()-interval '2 minutes'
		      )
		    )
		      AND next_attempt_at<=now()
		      AND attempt_count<$1
		    ORDER BY next_attempt_at ASC,received_at ASC
		    FOR UPDATE SKIP LOCKED
		    LIMIT $2
		 )
		 RETURNING id::text,attempt_count
	`,s.cfg.TelegramIngestMaxAttempts,limit)
	if err!=nil { return nil,err }
	defer rows.Close()

	items:=make([]telegramRetryItem,0)
	for rows.Next() {
		var item telegramRetryItem
		if rows.Scan(&item.id,&item.attempt)==nil {
			items=append(items,item)
		}
	}
	return items,rows.Err()
}

func (s *Server) claimTelegramRetryOne(
	ctx context.Context,
	id string,
	reset bool,
) (telegramRetryItem,error) {
	if reset {
		_,err:=s.db.Exec(ctx,`
			UPDATE telegram_ingest_items
			   SET status='pending_metadata',
			       attempt_count=0,
			       next_attempt_at=now(),
			       dead_lettered_at=NULL,
			       error_text='',
			       updated_at=now()
			 WHERE id=$1
		`,id)
		if err!=nil { return telegramRetryItem{},err }
	}

	var item telegramRetryItem
	err:=s.db.QueryRow(ctx,`
		UPDATE telegram_ingest_items
		   SET status='resolving',
		       attempt_count=attempt_count+1,
		       last_attempt_at=now(),
		       error_text='',
		       updated_at=now()
		 WHERE id=$1
		   AND status<>'ready'
		   AND attempt_count<$2
		 RETURNING id::text,attempt_count
	`,id,s.cfg.TelegramIngestMaxAttempts).Scan(&item.id,&item.attempt)
	if err!=nil { return telegramRetryItem{},err }
	return item,nil
}

func (s *Server) finishTelegramResolveAttempt(
	ctx context.Context,
	item telegramRetryItem,
	resolveErr error,
) error {
	if resolveErr==nil {
		_,err:=s.db.Exec(ctx,`
			UPDATE telegram_ingest_items
			   SET status='ready',
			       next_attempt_at=now(),
			       dead_lettered_at=NULL,
			       error_text='',
			       updated_at=now()
			 WHERE id=$1
		`,item.id)
		return err
	}

	maxAttempts:=s.cfg.TelegramIngestMaxAttempts
	if maxAttempts<=0 { maxAttempts=8 }
	status:="failed"
	deadLetteredAt:="NULL"
	if item.attempt>=maxAttempts {
		status="dead_letter"
		deadLetteredAt="now()"
	}

	delay:=telegramRetryDelay(
		item.attempt,
		s.cfg.TelegramIngestRetryBaseSeconds,
	)
	query:=`
		UPDATE telegram_ingest_items
		   SET status=$2,
		       next_attempt_at=now()+($3::text || ' seconds')::interval,
		       dead_lettered_at=`+deadLetteredAt+`,
		       error_text=$4,
		       updated_at=now()
		 WHERE id=$1
	`
	_,err:=s.db.Exec(
		ctx,
		query,
		item.id,
		status,
		strconv.FormatInt(int64(delay/time.Second),10),
		truncateWorkerError(resolveErr.Error()),
	)
	return err
}

func (s *Server) attemptTelegramResolve(
	ctx context.Context,
	id string,
	reset bool,
) error {
	item,err:=s.claimTelegramRetryOne(ctx,id,reset)
	if err!=nil {
		if errors.Is(err,pgx.ErrNoRows) {
			var status string
			if scanErr:=s.db.QueryRow(
				ctx,
				"SELECT status FROM telegram_ingest_items WHERE id=$1",
				id,
			).Scan(&status); scanErr==nil && status=="ready" {
				return nil
			}
		}
		return err
	}

	resolveErr:=s.retryTelegramResolve(ctx,item.id)
	finishErr:=s.finishTelegramResolveAttempt(ctx,item,resolveErr)
	if resolveErr!=nil { return resolveErr }
	return finishErr
}

func (s *Server) processTelegramIngestRetries(ctx context.Context) error {
	if s.tmdb==nil || !s.tmdb.Enabled() { return nil }
	items,err:=s.claimTelegramRetryBatch(ctx,20)
	if err!=nil { return err }

	for _,item:=range items {
		resolveCtx,cancel:=context.WithTimeout(ctx,25*time.Second)
		resolveErr:=s.retryTelegramResolve(resolveCtx,item.id)
		finishErr:=s.finishTelegramResolveAttempt(resolveCtx,item,resolveErr)
		cancel()
		if finishErr!=nil { return finishErr }
	}
	return nil
}

func telegramRetryDelay(attempt int,baseSeconds int) time.Duration {
	if baseSeconds<=0 { baseSeconds=30 }
	if attempt<1 { attempt=1 }
	if attempt>10 { attempt=10 }
	delay:=time.Duration(baseSeconds)*time.Second*time.Duration(1<<(attempt-1))
	if delay>6*time.Hour { return 6*time.Hour }
	return delay
}

func (s *Server) runTelegramIngestWorker(ctx context.Context) {
	ticker:=time.NewTicker(15*time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runCtx,cancel:=context.WithTimeout(ctx,40*time.Second)
			_ = s.processTelegramIngestRetries(runCtx)
			cancel()
		}
	}
}
