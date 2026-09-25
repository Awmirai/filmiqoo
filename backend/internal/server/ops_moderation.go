package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) opsModerationQueue(w http.ResponseWriter,r *http.Request) {
	if !s.validOpsSecret(r.Header.Get("X-Filmiqoo-Ops-Secret")) {
		writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"invalid operations secret"})
		return
	}

	rows,err:=s.db.Query(r.Context(),`
		SELECT target_type,target_id::text,
		       COUNT(*) AS reports,
		       COUNT(DISTINCT reporter_user_id) AS reporters,
		       MAX(priority) AS priority,
		       MIN(created_at) AS first_reported_at,
		       MAX(updated_at) AS last_updated_at,
		       ARRAY_AGG(DISTINCT reason ORDER BY reason) AS reasons
		  FROM reports
		 WHERE status IN ('open','reviewing')
		 GROUP BY target_type,target_id
		 ORDER BY MAX(priority) DESC,
		          COUNT(DISTINCT reporter_user_id) DESC,
		          MIN(created_at) ASC
		 LIMIT 200
	`)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var targetType,targetID string
		var reports,reporters int64
		var priority int
		var first,last time.Time
		var reasons []string
		if err:=rows.Scan(
			&targetType,&targetID,&reports,&reporters,&priority,
			&first,&last,&reasons,
		); err!=nil {
			continue
		}
		items=append(items,map[string]any{
			"targetType":targetType,
			"targetId":targetID,
			"reports":reports,
			"independentReporters":reporters,
			"priority":priority,
			"reasons":reasons,
			"firstReportedAt":first,
			"lastUpdatedAt":last,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) opsResolveModeration(w http.ResponseWriter,r *http.Request) {
	if !s.validOpsSecret(r.Header.Get("X-Filmiqoo-Ops-Secret")) {
		writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"invalid operations secret"})
		return
	}

	reportID:=strings.TrimSpace(chi.URLParam(r,"id"))
	if reportID=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"report id is required"})
		return
	}

	var body struct {
		Status string `json:"status"`
		Note string `json:"note"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	body.Status=strings.ToLower(strings.TrimSpace(body.Status))
	body.Note=strings.TrimSpace(body.Note)
	switch body.Status {
	case "reviewing","resolved","dismissed":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid moderation status"})
		return
	}
	if len([]rune(body.Note))>1500 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"moderation note is too long"})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer tx.Rollback(r.Context())

	var targetType,targetID string
	err=tx.QueryRow(r.Context(),`
		SELECT target_type,target_id::text
		  FROM reports
		 WHERE id=$1
	`,reportID).Scan(&targetType,&targetID)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"report not found"})
		return
	}

	resolvedAt:="NULL"
	if body.Status=="resolved" || body.Status=="dismissed" {
		resolvedAt="now()"
	}
	query:=`
		UPDATE reports
		   SET status=$3,
		       resolved_at=`+resolvedAt+`,
		       updated_at=now()
		 WHERE target_type=$1
		   AND target_id=$2
		   AND status IN ('open','reviewing')
	`
	tag,err:=tx.Exec(r.Context(),query,targetType,targetID,body.Status)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	meta,_:=json.Marshal(map[string]any{
		"source":"ops",
		"reportId":reportID,
		"reportsUpdated":tag.RowsAffected(),
	})
	_,err=tx.Exec(r.Context(),`
		INSERT INTO moderation_actions (
			moderator_user_id,target_type,target_id,action,reason,metadata
		) VALUES (NULL,$1,$2,$3,$4,$5::jsonb)
	`,
		targetType,
		targetID,
		"reports_"+body.Status,
		body.Note,
		string(meta),
	)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"targetType":targetType,
		"targetId":targetID,
		"status":body.Status,
		"reportsUpdated":tag.RowsAffected(),
	})
}
