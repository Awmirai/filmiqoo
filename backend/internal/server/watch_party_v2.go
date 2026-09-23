package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) watchParties(w http.ResponseWriter,r *http.Request) {
	rows,err:=s.db.Query(r.Context(),`
		SELECT wp.id::text,wp.title,wp.state,wp.visibility,wp.scheduled_at,
		       wp.playback_position_ms,wp.is_playing,wp.participant_count,wp.room_id::text,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.title,mt.poster_url,mt.backdrop_url,
		       mv.id::text,mv.quality_label
		  FROM watch_parties wp
		  JOIN profiles p ON p.user_id=wp.host_user_id
		  LEFT JOIN media_titles mt ON mt.id=wp.media_title_id
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label
		      FROM media_versions
		     WHERE media_title_id=wp.media_title_id
		     ORDER BY preferred DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) mv ON true
		 WHERE wp.visibility='public' AND wp.state IN ('scheduled','live')
		 ORDER BY CASE WHEN wp.state='live' THEN 0 ELSE 1 END,
		          wp.participant_count DESC,wp.scheduled_at ASC NULLS LAST,wp.created_at DESC
		 LIMIT 50
	`)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()
	items:=make([]map[string]any,0)
	for rows.Next() {
		item,err:=scanWatchParty(rows)
		if err==nil { items=append(items,item) }
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanWatchParty(row rowScanner) (map[string]any,error) {
	var id,title,state,visibility,roomID,hostID,username,displayName,avatar string
	var scheduled *time.Time
	var position,participants int64
	var playing,verified bool
	var mediaID,mediaTitle,poster,backdrop,versionID,quality *string
	err:=row.Scan(
		&id,&title,&state,&visibility,&scheduled,&position,&playing,&participants,&roomID,
		&hostID,&username,&displayName,&avatar,&verified,
		&mediaID,&mediaTitle,&poster,&backdrop,&versionID,&quality,
	)
	if err!=nil { return nil,err }
	return map[string]any{
		"id":id,"title":title,"state":state,"visibility":visibility,"scheduledAt":scheduled,
		"positionMs":position,"isPlaying":playing,"participants":participants,"roomId":roomID,
		"host":map[string]any{
			"id":hostID,"username":username,"displayName":displayName,
			"avatarUrl":avatar,"verified":verified,
		},
		"media":map[string]any{
			"id":mediaID,"title":mediaTitle,"posterUrl":poster,"backdropUrl":backdrop,
			"mediaVersionId":versionID,"quality":quality,
		},
	},nil
}

func (s *Server) watchPartyDetail(w http.ResponseWriter,r *http.Request) {
	id:=chi.URLParam(r,"id")
	row:=s.db.QueryRow(r.Context(),`
		SELECT wp.id::text,wp.title,wp.state,wp.visibility,wp.scheduled_at,
		       wp.playback_position_ms,wp.is_playing,wp.participant_count,wp.room_id::text,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.title,mt.poster_url,mt.backdrop_url,
		       COALESCE(epv.id::text,mv.id::text),COALESCE(epv.quality_label,mv.quality_label)
		  FROM watch_parties wp
		  JOIN profiles p ON p.user_id=wp.host_user_id
		  LEFT JOIN media_titles mt ON mt.id=wp.media_title_id
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label
		      FROM media_versions
		     WHERE media_title_id=wp.media_title_id
		     ORDER BY preferred DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) mv ON true
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label
		      FROM media_versions
		     WHERE episode_id=wp.episode_id
		     ORDER BY preferred DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) epv ON true
		 WHERE wp.id=$1
	`,id)
	item,err:=scanWatchParty(row)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"watch party not found"})
		return
	}
	writeJSON(w,http.StatusOK,item)
}

func (s *Server) createWatchParty(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body struct {
		Title string `json:"title"`
		MediaTitleID *string `json:"mediaTitleId"`
		EpisodeID *string `json:"episodeId"`
		Visibility string `json:"visibility"`
		ScheduledAt *time.Time `json:"scheduledAt"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Title=strings.TrimSpace(body.Title)
	if body.Title=="" { body.Title="Watch Party" }
	if len([]rune(body.Title))>100 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"title is too long"}); return
	}
	if body.MediaTitleID==nil && body.EpisodeID==nil {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"mediaTitleId or episodeId is required"}); return
	}
	if body.Visibility=="" { body.Visibility="public" }
	switch body.Visibility { case "public","private","invite": default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"}); return
	}

	state:="live"
	if body.ScheduledAt!=nil && body.ScheduledAt.After(time.Now()) { state="scheduled" }

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var roomID string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO rooms (
			owner_user_id,media_title_id,episode_id,name,topic,room_type,visibility,member_count
		) VALUES ($1,$2,$3,$4,'Watch Party chat','watch_party',$5,1)
		RETURNING id::text
	`,userID,body.MediaTitleID,body.EpisodeID,body.Title,body.Visibility).Scan(&roomID)
	if err!=nil { writeError(w,http.StatusBadRequest,err); return }

	_,err=tx.Exec(r.Context(),
		"INSERT INTO room_members (room_id,user_id,role) VALUES ($1,$2,'owner')",
		roomID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var partyID string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO watch_parties (
			host_user_id,media_title_id,episode_id,room_id,title,visibility,state,scheduled_at,
			playback_position_ms,is_playing,participant_count
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,0,false,1)
		RETURNING id::text
	`,userID,body.MediaTitleID,body.EpisodeID,roomID,body.Title,body.Visibility,state,body.ScheduledAt).Scan(&partyID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		INSERT INTO watch_party_members (watch_party_id,user_id,role)
		VALUES ($1,$2,'host')
	`,partyID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusCreated,map[string]any{"id":partyID,"roomId":roomID,"state":state})
}

func (s *Server) joinWatchParty(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var roomID string
	if err:=tx.QueryRow(r.Context(),
		"SELECT room_id::text FROM watch_parties WHERE id=$1 AND state<>'ended' AND state<>'cancelled'",
		id).Scan(&roomID); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"watch party unavailable"}); return
	}

	tag,err:=tx.Exec(r.Context(),`
		INSERT INTO watch_party_members (watch_party_id,user_id,role)
		VALUES ($1,$2,'viewer') ON CONFLICT DO NOTHING
	`,id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if tag.RowsAffected()>0 {
		_,err=tx.Exec(r.Context(),
			"UPDATE watch_parties SET participant_count=participant_count+1 WHERE id=$1",id)
		if err==nil {
			_,err=tx.Exec(r.Context(),`
				INSERT INTO room_members (room_id,user_id,role)
				VALUES ($1,$2,'member') ON CONFLICT DO NOTHING
			`,roomID,userID)
		}
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{"joined":true,"roomId":roomID})
}

func (s *Server) updateWatchPartyState(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")
	var body struct {
		PositionMS int64 `json:"positionMs"`
		IsPlaying bool `json:"isPlaying"`
		State string `json:"state"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	if body.PositionMS<0 { body.PositionMS=0 }
	if body.State=="" { body.State="live" }
	switch body.State { case "scheduled","live","ended","cancelled": default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid state"}); return
	}

	var allowed bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_members
			 WHERE watch_party_id=$1 AND user_id=$2 AND role IN ('host','cohost')
		)
	`,id,userID).Scan(&allowed)
	if !allowed {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"}); return
	}

	_,err:=s.db.Exec(r.Context(),`
		UPDATE watch_parties
		   SET playback_position_ms=$2,is_playing=$3,state=$4
		 WHERE id=$1
	`,id,body.PositionMS,body.IsPlaying,body.State)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	event:=map[string]any{
		"type":"watchparty.state","watchPartyId":id,
		"positionMs":body.PositionMS,"isPlaying":body.IsPlaying,"state":body.State,
		"updatedAt":time.Now(),
	}
	raw,_:=json.Marshal(event)
	_=s.redis.Publish(r.Context(),"watchparty:"+id,raw).Err()

	writeJSON(w,http.StatusOK,event)
}
