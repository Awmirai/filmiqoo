package server

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
)

func (s *Server) reactToStory(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	storyID:=chi.URLParam(r,"id")

	var body struct {
		Reaction string `json:"reaction"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	body.Reaction=strings.TrimSpace(body.Reaction)
	if body.Reaction=="" || len([]rune(body.Reaction))>16 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid reaction"})
		return
	}

	authorID,allowed:=s.storyAuthorIfAccessible(r.Context(),storyID,userID)
	if !allowed {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"story not found"})
		return
	}

	_,err:=s.db.Exec(r.Context(),`
		INSERT INTO story_reactions (story_id,user_id,reaction)
		VALUES ($1,$2,$3)
		ON CONFLICT (story_id,user_id)
		DO UPDATE SET reaction=EXCLUDED.reaction,created_at=now()
	`,storyID,userID,body.Reaction)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	if authorID!=userID {
		_,_=s.db.Exec(r.Context(),`
			INSERT INTO notifications (
				user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
			) VALUES ($1,$2,'story_reaction','story',$3,'واکنش جدید به استوری',$4)
		`,authorID,userID,storyID,body.Reaction)
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"storyId":storyID,
		"reaction":body.Reaction,
	})
}

func (s *Server) replyToStory(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	storyID:=chi.URLParam(r,"id")

	var body struct {
		Body string `json:"body"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	body.Body=strings.TrimSpace(body.Body)
	if body.Body=="" || len([]rune(body.Body))>1000 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"reply must be 1-1000 characters"})
		return
	}

	authorID,allowed:=s.storyAuthorIfAccessible(r.Context(),storyID,userID)
	if !allowed {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"story not found"})
		return
	}

	var id string
	err:=s.db.QueryRow(r.Context(),`
		INSERT INTO story_replies (story_id,sender_user_id,body)
		VALUES ($1,$2,$3)
		RETURNING id::text
	`,storyID,userID,body.Body).Scan(&id)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	if authorID!=userID {
		_,_=s.db.Exec(r.Context(),`
			INSERT INTO notifications (
				user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
			) VALUES ($1,$2,'story_reply','story',$3,'پاسخ جدید به استوری',$4)
		`,authorID,userID,storyID,body.Body)
	}

	writeJSON(w,http.StatusCreated,map[string]any{
		"id":id,
		"storyId":storyID,
	})
}
