package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) toggleReelLike(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	reelID:=chi.URLParam(r,"id")
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var exists bool
	_ = tx.QueryRow(r.Context(),"SELECT EXISTS(SELECT 1 FROM reel_likes WHERE reel_id=$1 AND user_id=$2)",reelID,userID).Scan(&exists)
	if exists {
		_,err=tx.Exec(r.Context(),"DELETE FROM reel_likes WHERE reel_id=$1 AND user_id=$2",reelID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE reels SET like_count=GREATEST(like_count-1,0) WHERE id=$1",reelID) }
	} else {
		_,err=tx.Exec(r.Context(),"INSERT INTO reel_likes (reel_id,user_id) VALUES ($1,$2)",reelID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE reels SET like_count=like_count+1 WHERE id=$1",reelID) }
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if !exists {
		var creatorID string
		if scanErr:=tx.QueryRow(
			r.Context(),
			"SELECT creator_user_id::text FROM reels WHERE id=$1",
			reelID,
		).Scan(&creatorID); scanErr==nil && creatorID!=userID {
			_,_=tx.Exec(r.Context(),`
				INSERT INTO notifications (
					user_id,actor_user_id,notification_type,entity_type,entity_id,title
				)
				SELECT $1,$2,'reel_like','reel',$3,'پسند جدید روی Clip'
				WHERE NOT EXISTS (
					SELECT 1 FROM notifications
					 WHERE user_id=$1
					   AND actor_user_id=$2
					   AND notification_type='reel_like'
					   AND entity_id=$3
					   AND created_at>now()-interval '12 hours'
				)
			`,creatorID,userID,reelID)
		}
	}

	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusOK,map[string]any{"liked":!exists})
}

func (s *Server) toggleReelSave(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	reelID:=chi.URLParam(r,"id")
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var exists bool
	_ = tx.QueryRow(r.Context(),"SELECT EXISTS(SELECT 1 FROM reel_saves WHERE reel_id=$1 AND user_id=$2)",reelID,userID).Scan(&exists)
	if exists {
		_,err=tx.Exec(r.Context(),"DELETE FROM reel_saves WHERE reel_id=$1 AND user_id=$2",reelID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE reels SET save_count=GREATEST(save_count-1,0) WHERE id=$1",reelID) }
	} else {
		_,err=tx.Exec(r.Context(),"INSERT INTO reel_saves (reel_id,user_id) VALUES ($1,$2)",reelID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE reels SET save_count=save_count+1 WHERE id=$1",reelID) }
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusOK,map[string]any{"saved":!exists})
}

func (s *Server) markReelView(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	reelID:=chi.URLParam(r,"id")
	key:="reel:view:"+reelID+":"+userID
	fresh,err:=s.redis.SetNX(r.Context(),key,"1",24*time.Hour).Result()
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if fresh {
		_,_=s.db.Exec(r.Context(),"UPDATE reels SET view_count=view_count+1 WHERE id=$1",reelID)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) addReelComment(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	reelID:=chi.URLParam(r,"id")
	var body struct {
		Body string `json:"body"`
		Spoiler bool `json:"spoiler"`
		ParentCommentID *string `json:"parentCommentId"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	body.Body=strings.TrimSpace(body.Body)
	if body.Body=="" || len([]rune(body.Body))>2000 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"comment must be 1-2000 characters"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())
	var id string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO comments (reel_id,parent_comment_id,author_user_id,body,spoiler)
		VALUES ($1,$2,$3,$4,$5) RETURNING id::text
	`,reelID,body.ParentCommentID,userID,body.Body,body.Spoiler).Scan(&id)
	if err==nil { _,err=tx.Exec(r.Context(),"UPDATE reels SET comment_count=comment_count+1 WHERE id=$1",reelID) }
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var recipientID string
	if scanErr:=tx.QueryRow(r.Context(),`
		SELECT COALESCE(
			(
				SELECT author_user_id::text
				  FROM comments
				 WHERE id=$1
			),
			rl.creator_user_id::text
		)
		  FROM reels rl
		 WHERE rl.id=$2
	`,body.ParentCommentID,reelID).Scan(&recipientID); scanErr==nil && recipientID!=userID {
		preview:=normalizeMessagePreview(body.Body)
		if body.Spoiler { preview="کامنت اسپویلردار" }
		_,_=tx.Exec(r.Context(),`
			INSERT INTO notifications (
				user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
			) VALUES ($1,$2,'reel_comment','reel',$3,'کامنت جدید روی Clip',$4)
		`,recipientID,userID,reelID,preview)
	}

	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusCreated,map[string]any{"id":id})
}

func (s *Server) reelComments(w http.ResponseWriter,r *http.Request) {
	reelID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT c.id::text,c.parent_comment_id::text,c.body,c.spoiler,c.like_count,c.created_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified
		  FROM comments c JOIN profiles p ON p.user_id=c.author_user_id
		 WHERE c.reel_id=$1
		 ORDER BY c.created_at ASC
		 LIMIT 300
	`,reelID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,body,userID,username,displayName,avatar string
		var parent *string
		var spoiler,verified bool
		var likes int64
		var created time.Time
		if err:=rows.Scan(&id,&parent,&body,&spoiler,&likes,&created,&userID,&username,&displayName,&avatar,&verified); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"parentCommentId":parent,"body":body,"spoiler":spoiler,"likes":likes,"createdAt":created,
			"author":map[string]any{"id":userID,"username":username,"displayName":displayName,"avatarUrl":avatar,"verified":verified},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
