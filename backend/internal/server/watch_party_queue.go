package server

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) watchPartyQueue(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var member bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_members
			 WHERE watch_party_id=$1 AND user_id=$2
		)
	`,partyID,userID).Scan(&member)
	if !member {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"join the watch party first"}); return
	}

	rows,err:=s.db.Query(r.Context(),`
		SELECT q.id::text,q.status,q.vote_count,q.created_at,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       EXISTS(
		         SELECT 1 FROM watch_party_queue_votes v
		          WHERE v.queue_item_id=q.id AND v.user_id=$2
		       ) AS voted
		  FROM watch_party_queue q
		  JOIN media_titles mt ON mt.id=q.media_title_id
		  JOIN profiles p ON p.user_id=q.suggested_by_user_id
		 WHERE q.watch_party_id=$1 AND q.status IN ('queued','playing')
		 ORDER BY
		   CASE WHEN q.status='playing' THEN 0 ELSE 1 END,
		   q.vote_count DESC,
		   q.created_at ASC
		 LIMIT 100
	`,partyID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,status,mediaID,kind,title,originalTitle,poster,backdrop string
		var tmdbID *int64
		var year int
		var rating *float64
		var votes int64
		var created time.Time
		var suggesterID,username,displayName,avatar string
		var verified,voted bool
		if err:=rows.Scan(
			&id,&status,&votes,&created,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,
			&suggesterID,&username,&displayName,&avatar,&verified,&voted,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"status":status,"votes":votes,"voted":voted,"createdAt":created,
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,
				"originalTitle":originalTitle,"posterUrl":poster,"backdropUrl":backdrop,
				"year":year,"rating":rating,
			},
			"suggestedBy":map[string]any{
				"id":suggesterID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) addWatchPartyQueueItem(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var body struct {
		MediaTitleID string `json:"mediaTitleId"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	var member bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_members
			 WHERE watch_party_id=$1 AND user_id=$2
		)
	`,partyID,userID).Scan(&member)
	if !member {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"join the watch party first"}); return
	}

	var valid bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM media_titles
			 WHERE id=$1 AND visibility='public'
		)
	`,body.MediaTitleID).Scan(&valid)
	if !valid {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"media not found"}); return
	}

	var duplicate bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_queue
			 WHERE watch_party_id=$1 AND media_title_id=$2 AND status IN ('queued','playing')
		)
	`,partyID,body.MediaTitleID).Scan(&duplicate)
	if duplicate {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"title is already in queue"}); return
	}

	var id string
	err:=s.db.QueryRow(r.Context(),`
		INSERT INTO watch_party_queue (
			watch_party_id,media_title_id,suggested_by_user_id,status
		) VALUES ($1,$2,$3,'queued')
		RETURNING id::text
	`,partyID,body.MediaTitleID,userID).Scan(&id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,_=s.db.Exec(r.Context(),`
		INSERT INTO watch_party_queue_votes (queue_item_id,user_id)
		VALUES ($1,$2) ON CONFLICT DO NOTHING
	`,id,userID)
	_,_=s.db.Exec(r.Context(),`
		UPDATE watch_party_queue SET vote_count=1 WHERE id=$1
	`,id)

	writeJSON(w,http.StatusCreated,map[string]any{"id":id,"votes":1,"voted":true})
}

func (s *Server) voteWatchPartyQueueItem(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")
	itemID:=chi.URLParam(r,"itemID")

	var member bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_members
			 WHERE watch_party_id=$1 AND user_id=$2
		)
	`,partyID,userID).Scan(&member)
	if !member {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"join the watch party first"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var valid bool
	_=tx.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_queue
			 WHERE id=$1 AND watch_party_id=$2 AND status='queued'
		)
	`,itemID,partyID).Scan(&valid)
	if !valid {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"queue item not found"}); return
	}

	var voted bool
	_=tx.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_queue_votes
			 WHERE queue_item_id=$1 AND user_id=$2
		)
	`,itemID,userID).Scan(&voted)

	if voted {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM watch_party_queue_votes
			 WHERE queue_item_id=$1 AND user_id=$2
		`,itemID,userID)
	} else {
		_,err=tx.Exec(r.Context(),`
			INSERT INTO watch_party_queue_votes (queue_item_id,user_id)
			VALUES ($1,$2) ON CONFLICT DO NOTHING
		`,itemID,userID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var count int64
	err=tx.QueryRow(r.Context(),`
		UPDATE watch_party_queue
		   SET vote_count=(
		     SELECT COUNT(*) FROM watch_party_queue_votes WHERE queue_item_id=$1
		   )
		 WHERE id=$1
		 RETURNING vote_count
	`,itemID).Scan(&count)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	writeJSON(w,http.StatusOK,map[string]any{"voted":!voted,"votes":count})
}

func (s *Server) playWatchPartyQueueItem(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")
	itemID:=chi.URLParam(r,"itemID")

	var role string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID).Scan(&role); err!=nil || (role!="host" && role!="cohost") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var mediaID string
	err=tx.QueryRow(r.Context(),`
		SELECT media_title_id::text
		  FROM watch_party_queue
		 WHERE id=$1 AND watch_party_id=$2 AND status='queued'
		 FOR UPDATE
	`,itemID,partyID).Scan(&mediaID)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"queue item not found"}); return
	}

	_,err=tx.Exec(r.Context(),`
		UPDATE watch_party_queue
		   SET status='played'
		 WHERE watch_party_id=$1 AND status='playing'
	`,partyID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		UPDATE watch_party_queue SET status='playing' WHERE id=$1
	`,itemID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		UPDATE watch_parties
		   SET media_title_id=$2,
		       episode_id=NULL,
		       playback_position_ms=0,
		       is_playing=false,
		       state='live'
		 WHERE id=$1
	`,partyID,mediaID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	event:=map[string]any{
		"type":"watchparty.queue.play",
		"watchPartyId":partyID,
		"queueItemId":itemID,
		"mediaTitleId":mediaID,
	}
	raw,_:=json.Marshal(event)
	_=s.redis.Publish(r.Context(),"watchparty:"+partyID,raw).Err()

	writeJSON(w,http.StatusOK,event)
}

func (s *Server) removeWatchPartyQueueItem(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")
	itemID:=chi.URLParam(r,"itemID")

	var role string
	_ = s.db.QueryRow(r.Context(),`
		SELECT role FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID).Scan(&role)

	tag,err:=s.db.Exec(r.Context(),`
		UPDATE watch_party_queue
		   SET status='removed'
		 WHERE id=$1 AND watch_party_id=$2 AND status='queued'
		   AND (
		     suggested_by_user_id=$3
		     OR $4 IN ('host','cohost','moderator')
		   )
	`,itemID,partyID,userID,role)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if tag.RowsAffected()==0 {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"cannot remove queue item"}); return
	}

	writeJSON(w,http.StatusOK,map[string]any{"removed":true})
}
