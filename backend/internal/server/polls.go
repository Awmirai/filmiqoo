package server

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (s *Server) postPoll(w http.ResponseWriter,r *http.Request) {
	postID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT id::text,label,vote_count
		  FROM poll_options
		 WHERE post_id=$1
		 ORDER BY sort_order ASC,id ASC
	`,postID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	var total int64
	for rows.Next() {
		var id,label string
		var votes int64
		if err:=rows.Scan(&id,&label,&votes); err!=nil { continue }
		total+=votes
		items=append(items,map[string]any{
			"id":id,"label":label,"votes":votes,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"options":items,"totalVotes":total})
}

func (s *Server) votePoll(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	postID:=chi.URLParam(r,"id")
	var body struct {
		OptionID string `json:"optionId"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	if body.OptionID=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"optionId is required"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var valid bool
	if err:=tx.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM poll_options WHERE id=$1 AND post_id=$2
		)
	`,body.OptionID,postID).Scan(&valid); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	if !valid {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid poll option"}); return
	}

	var previous *string
	_ = tx.QueryRow(r.Context(),`
		SELECT option_id::text FROM poll_votes WHERE post_id=$1 AND user_id=$2
	`,postID,userID).Scan(&previous)

	if previous!=nil && *previous==body.OptionID {
		writeJSON(w,http.StatusOK,map[string]any{"selectedOptionId":body.OptionID,"changed":false})
		return
	}

	if previous!=nil {
		if _,err:=tx.Exec(r.Context(),`
			UPDATE poll_options SET vote_count=GREATEST(vote_count-1,0)
			 WHERE id=$1 AND post_id=$2
		`,*previous,postID); err!=nil {
			writeError(w,http.StatusInternalServerError,err); return
		}
	}

	_,err=tx.Exec(r.Context(),`
		INSERT INTO poll_votes (post_id,user_id,option_id)
		VALUES ($1,$2,$3)
		ON CONFLICT (post_id,user_id)
		DO UPDATE SET option_id=EXCLUDED.option_id,created_at=now()
	`,postID,userID,body.OptionID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if _,err:=tx.Exec(r.Context(),`
		UPDATE poll_options SET vote_count=vote_count+1
		 WHERE id=$1 AND post_id=$2
	`,body.OptionID,postID); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusOK,map[string]any{"selectedOptionId":body.OptionID,"changed":true})
}


func (s *Server) postPollSelection(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	postID:=chi.URLParam(r,"id")

	var selected *string
	err:=s.db.QueryRow(r.Context(),`
		SELECT (
			SELECT option_id::text
			  FROM poll_votes
			 WHERE post_id=$1 AND user_id=$2
			 LIMIT 1
		)
	`,postID,userID).Scan(&selected)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"selectedOptionId":selected,
	})
}
