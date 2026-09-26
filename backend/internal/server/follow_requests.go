package server

import (
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) userRelationship(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	targetID:=chi.URLParam(r,"id")

	var following,pending,privateAccount,blocked bool
	_ = s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
		         SELECT 1 FROM user_follows
		          WHERE follower_user_id=$1 AND followed_user_id=$2
		       ),
		       EXISTS(
		         SELECT 1 FROM follow_requests
		          WHERE requester_user_id=$1 AND target_user_id=$2 AND status='pending'
		       ),
		       COALESCE((SELECT private_account FROM profiles WHERE user_id=$2),false),
		       EXISTS(
		         SELECT 1 FROM blocks
		          WHERE (blocker_user_id=$1 AND blocked_user_id=$2)
		             OR (blocker_user_id=$2 AND blocked_user_id=$1)
		       )
	`,userID,targetID).Scan(&following,&pending,&privateAccount,&blocked)

	writeJSON(w,http.StatusOK,map[string]any{
		"following":following,
		"pending":pending,
		"private":privateAccount,
		"blocked":blocked,
		"self":userID==targetID,
	})
}

func (s *Server) incomingFollowRequests(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	rows,err:=s.db.Query(r.Context(),`
		SELECT fr.requester_user_id::text,fr.created_at,
		       p.username::text,p.display_name,p.bio,p.avatar_url,p.verified,
		       p.follower_count,p.following_count
		  FROM follow_requests fr
		  JOIN profiles p ON p.user_id=fr.requester_user_id
		 WHERE fr.target_user_id=$1 AND fr.status='pending'
		 ORDER BY fr.created_at DESC
		 LIMIT 200
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,username,displayName,bio,avatar string
		var verified bool
		var followers,following int64
		var created time.Time
		if err:=rows.Scan(
			&id,&created,&username,&displayName,&bio,&avatar,&verified,
			&followers,&following,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"createdAt":created,
			"username":username,"displayName":displayName,"bio":bio,
			"avatarUrl":avatar,"verified":verified,
			"followers":followers,"following":following,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) acceptFollowRequest(w http.ResponseWriter,r *http.Request) {
	targetID:=userIDFromContext(r.Context())
	requesterID:=chi.URLParam(r,"requesterID")

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var status string
	if err:=tx.QueryRow(r.Context(),`
		SELECT status
		  FROM follow_requests
		 WHERE requester_user_id=$1 AND target_user_id=$2
		 FOR UPDATE
	`,requesterID,targetID).Scan(&status); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"follow request not found"})
		return
	}
	if status!="pending" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"follow request is not pending"})
		return
	}

	tag,err:=tx.Exec(r.Context(),`
		INSERT INTO user_follows (follower_user_id,followed_user_id)
		VALUES ($1,$2)
		ON CONFLICT DO NOTHING
	`,requesterID,targetID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if tag.RowsAffected()>0 {
		if _,err=tx.Exec(r.Context(),`
			UPDATE profiles
			   SET following_count=following_count+1,updated_at=now()
			 WHERE user_id=$1
		`,requesterID); err!=nil {
			writeError(w,http.StatusInternalServerError,err); return
		}
		if _,err=tx.Exec(r.Context(),`
			UPDATE profiles
			   SET follower_count=follower_count+1,updated_at=now()
			 WHERE user_id=$1
		`,targetID); err!=nil {
			writeError(w,http.StatusInternalServerError,err); return
		}
	}

	_,err=tx.Exec(r.Context(),`
		UPDATE follow_requests
		   SET status='accepted',updated_at=now()
		 WHERE requester_user_id=$1 AND target_user_id=$2
	`,requesterID,targetID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,_=tx.Exec(r.Context(),`
		INSERT INTO notifications (
			user_id,actor_user_id,notification_type,entity_type,entity_id,title
		) VALUES ($1,$2,'follow_accepted','user',$2,'درخواست دنبال‌کردن پذیرفته شد')
	`,requesterID,targetID)

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusOK,map[string]any{"accepted":true})
}

func (s *Server) declineFollowRequest(w http.ResponseWriter,r *http.Request) {
	targetID:=userIDFromContext(r.Context())
	requesterID:=chi.URLParam(r,"requesterID")

	tag,err:=s.db.Exec(r.Context(),`
		UPDATE follow_requests
		   SET status='declined',updated_at=now()
		 WHERE requester_user_id=$1 AND target_user_id=$2 AND status='pending'
	`,requesterID,targetID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if tag.RowsAffected()==0 {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"follow request not found"})
		return
	}
	writeJSON(w,http.StatusOK,map[string]any{"declined":true})
}
