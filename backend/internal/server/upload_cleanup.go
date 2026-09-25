package server

import (
	"context"
	"time"
)

func (s *Server) cleanupStaleUploads(ctx context.Context) error {
	if s.objects==nil { return nil }

	rows,err:=s.db.Query(ctx,`
		SELECT id::text,object_key,status
		  FROM ugc_uploads
		 WHERE (
		   (status='presigned' AND created_at<now()-interval '2 hours')
		   OR (status='failed' AND created_at<now()-interval '1 hour')
		 )
		 ORDER BY created_at ASC
		 LIMIT 200
	`)
	if err!=nil { return err }
	defer rows.Close()

	type staleUpload struct {
		id string
		key string
		status string
	}
	items:=make([]staleUpload,0)
	for rows.Next() {
		var item staleUpload
		if rows.Scan(&item.id,&item.key,&item.status)==nil {
			items=append(items,item)
		}
	}
	if err:=rows.Err(); err!=nil { return err }

	for _,item:=range items {
		deleteCtx,cancel:=context.WithTimeout(ctx,15*time.Second)
		err:=s.objects.Delete(deleteCtx,item.key)
		cancel()
		if err!=nil { continue }

		_,_=s.db.Exec(ctx,`
			UPDATE ugc_uploads
			   SET status='deleted'
			 WHERE id=$1
			   AND status=$2
		`,item.id,item.status)
	}
	return nil
}

func (s *Server) runUploadCleanupWorker(ctx context.Context) {
	timer:=time.NewTimer(2*time.Minute)
	defer timer.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			runCtx,cancel:=context.WithTimeout(ctx,2*time.Minute)
			_ = s.cleanupStaleUploads(runCtx)
			cancel()
			timer.Reset(30*time.Minute)
		}
	}
}
