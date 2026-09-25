package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) submitReport(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	var body struct {
		TargetType string `json:"targetType"`
		TargetID string `json:"targetId"`
		Reason string `json:"reason"`
		Detail string `json:"detail"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}

	body.TargetType=strings.ToLower(strings.TrimSpace(body.TargetType))
	body.TargetID=strings.TrimSpace(body.TargetID)
	body.Reason=strings.ToLower(strings.TrimSpace(body.Reason))
	body.Detail=strings.TrimSpace(body.Detail)

	switch body.TargetType {
	case "user","post","reel","story","message","channel","review","moment":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"unsupported report target"})
		return
	}
	switch body.Reason {
	case "spam","harassment","hate","sexual","violence","spoiler","copyright","impersonation","misinformation","other":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid report reason"})
		return
	}
	if body.TargetID=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"targetId is required"})
		return
	}
	if len([]rune(body.Detail))>1200 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"report detail is too long"})
		return
	}

	priority:=reportBasePriority(body.Reason)
	var id,status string
	err:=s.db.QueryRow(r.Context(),`
		INSERT INTO reports (
			reporter_user_id,target_type,target_id,reason,detail,status,priority,updated_at
		)
		VALUES ($1,$2,$3,$4,$5,'open',$6,now())
		ON CONFLICT (reporter_user_id,target_type,target_id,reason)
		WHERE status IN ('open','reviewing')
		DO UPDATE SET
		  detail=CASE
		    WHEN EXCLUDED.detail<>'' THEN EXCLUDED.detail
		    ELSE reports.detail
		  END,
		  priority=GREATEST(reports.priority,EXCLUDED.priority),
		  updated_at=now()
		RETURNING id::text,status,priority
	`,userID,body.TargetType,body.TargetID,body.Reason,body.Detail,priority).
		Scan(&id,&status,&priority)
	if err!=nil {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid report target"})
		return
	}

	var independentReporters int
	_ = s.db.QueryRow(r.Context(),`
		SELECT COUNT(DISTINCT reporter_user_id)
		  FROM reports
		 WHERE target_type=$1
		   AND target_id=$2
		   AND status IN ('open','reviewing')
		   AND created_at>=now()-interval '24 hours'
	`,body.TargetType,body.TargetID).Scan(&independentReporters)

	escalated:=priority
	switch {
	case independentReporters>=7:
		escalated=100
	case independentReporters>=4 && escalated<90:
		escalated=90
	case independentReporters>=2 && escalated<70:
		escalated=70
	}
	if escalated>priority {
		_,_=s.db.Exec(r.Context(),`
			UPDATE reports
			   SET priority=GREATEST(priority,$3),
			       updated_at=now()
			 WHERE target_type=$1
			   AND target_id=$2
			   AND status IN ('open','reviewing')
		`,body.TargetType,body.TargetID,escalated)
		priority=escalated
	}

	writeJSON(w,http.StatusCreated,map[string]any{
		"id":id,
		"status":status,
		"priority":priority,
		"independentReporters24h":independentReporters,
	})
}

func reportBasePriority(reason string) int {
	switch reason {
	case "violence","sexual","hate":
		return 80
	case "harassment","impersonation":
		return 65
	case "copyright":
		return 55
	case "spam","misinformation":
		return 45
	case "spoiler":
		return 25
	default:
		return 40
	}
}

func (s *Server) toggleUserBlock(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	targetID:=chi.URLParam(r,"id")
	if targetID==userID {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"cannot block yourself"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var targetExists bool
	if err:=tx.QueryRow(r.Context(),
		"SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)",targetID).Scan(&targetExists); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	if !targetExists {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"user not found"}); return
	}

	var exists bool
	if err:=tx.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM blocks WHERE blocker_user_id=$1 AND blocked_user_id=$2
		)
	`,userID,targetID).Scan(&exists); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	if exists {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM blocks WHERE blocker_user_id=$1 AND blocked_user_id=$2
		`,userID,targetID)
	} else {
		_,err=tx.Exec(r.Context(),`
			INSERT INTO blocks (blocker_user_id,blocked_user_id)
			VALUES ($1,$2) ON CONFLICT DO NOTHING
		`,userID,targetID)
		if err==nil {
			_,err=tx.Exec(r.Context(),`
				DELETE FROM user_follows
				 WHERE (follower_user_id=$1 AND followed_user_id=$2)
				    OR (follower_user_id=$2 AND followed_user_id=$1)
			`,userID,targetID)
		}
		if err==nil {
			_,err=tx.Exec(r.Context(),`
				DELETE FROM user_mutes
				 WHERE muter_user_id=$1 AND muted_user_id=$2
			`,userID,targetID)
		}
	}

	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if !exists {
		_,_=tx.Exec(r.Context(),`
			UPDATE profiles p SET
			  follower_count=(SELECT COUNT(*) FROM user_follows WHERE followed_user_id=p.user_id),
			  following_count=(SELECT COUNT(*) FROM user_follows WHERE follower_user_id=p.user_id),
			  updated_at=now()
			 WHERE p.user_id IN ($1,$2)
		`,userID,targetID)
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	writeJSON(w,http.StatusOK,map[string]any{"blocked":!exists})
}

func (s *Server) toggleUserMute(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	targetID:=chi.URLParam(r,"id")
	if targetID==userID {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"cannot mute yourself"}); return
	}

	var blocked bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM blocks WHERE blocker_user_id=$1 AND blocked_user_id=$2
		)
	`,userID,targetID).Scan(&blocked)
	if blocked {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"blocked users cannot also be muted"}); return
	}

	var exists bool
	if err:=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM user_mutes WHERE muter_user_id=$1 AND muted_user_id=$2
		)
	`,userID,targetID).Scan(&exists); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	var err error
	if exists {
		_,err=s.db.Exec(r.Context(),`
			DELETE FROM user_mutes WHERE muter_user_id=$1 AND muted_user_id=$2
		`,userID,targetID)
	} else {
		_,err=s.db.Exec(r.Context(),`
			INSERT INTO user_mutes (muter_user_id,muted_user_id)
			SELECT $1,id FROM users WHERE id=$2
			ON CONFLICT DO NOTHING
		`,userID,targetID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{"muted":!exists})
}

func (s *Server) safetyState(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	blocked:=make([]map[string]any,0)
	blockRows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,b.created_at
		  FROM blocks b
		  JOIN profiles p ON p.user_id=b.blocked_user_id
		 WHERE b.blocker_user_id=$1
		 ORDER BY b.created_at DESC
		 LIMIT 300
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	for blockRows.Next() {
		var id,username,displayName,avatar string
		var verified bool
		var created time.Time
		if blockRows.Scan(&id,&username,&displayName,&avatar,&verified,&created)==nil {
			blocked=append(blocked,map[string]any{
				"id":id,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,"createdAt":created,
			})
		}
	}
	blockRows.Close()

	muted:=make([]map[string]any,0)
	muteRows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,m.created_at
		  FROM user_mutes m
		  JOIN profiles p ON p.user_id=m.muted_user_id
		 WHERE m.muter_user_id=$1
		 ORDER BY m.created_at DESC
		 LIMIT 300
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	for muteRows.Next() {
		var id,username,displayName,avatar string
		var verified bool
		var created time.Time
		if muteRows.Scan(&id,&username,&displayName,&avatar,&verified,&created)==nil {
			muted=append(muted,map[string]any{
				"id":id,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,"createdAt":created,
			})
		}
	}
	muteRows.Close()

	writeJSON(w,http.StatusOK,map[string]any{
		"blocked":blocked,
		"muted":muted,
	})
}
