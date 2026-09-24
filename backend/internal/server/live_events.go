package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) liveEvents(w http.ResponseWriter,r *http.Request) {
	rows,err:=s.db.Query(r.Context(),`
		SELECT le.id::text,le.event_type,le.title,le.description,le.visibility,le.state,
		       le.playback_url,le.cover_url,le.allow_chat,le.scheduled_at,le.started_at,le.ended_at,
		       le.viewer_count,le.peak_viewer_count,le.room_id::text,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating
		  FROM live_events le
		  JOIN profiles p ON p.user_id=le.host_user_id
		  LEFT JOIN media_titles mt ON mt.id=le.media_title_id
		 WHERE le.visibility='public'
		   AND le.state IN ('scheduled','live')
		 ORDER BY
		   CASE WHEN le.state='live' THEN 0 ELSE 1 END,
		   le.scheduled_at ASC NULLS LAST,
		   le.created_at DESC
		 LIMIT 100
	`)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	writeJSON(w,http.StatusOK,map[string]any{"items":scanLiveEvents(rows)})
}

func (s *Server) liveEventDetail(w http.ResponseWriter,r *http.Request) {
	id:=chi.URLParam(r,"id")
	row:=s.db.QueryRow(r.Context(),`
		SELECT le.id::text,le.event_type,le.title,le.description,le.visibility,le.state,
		       le.playback_url,le.cover_url,le.allow_chat,le.scheduled_at,le.started_at,le.ended_at,
		       le.viewer_count,le.peak_viewer_count,le.room_id::text,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating
		  FROM live_events le
		  JOIN profiles p ON p.user_id=le.host_user_id
		  LEFT JOIN media_titles mt ON mt.id=le.media_title_id
		 WHERE le.id=$1
	`,id)

	item,err:=scanLiveEventRow(row)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"live event not found"})
		return
	}
	writeJSON(w,http.StatusOK,item)
}

func (s *Server) createLiveEvent(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	var body struct {
		EventType string `json:"eventType"`
		Title string `json:"title"`
		Description string `json:"description"`
		Visibility string `json:"visibility"`
		PlaybackURL string `json:"playbackUrl"`
		CoverURL string `json:"coverUrl"`
		AllowChat *bool `json:"allowChat"`
		ScheduledAt *time.Time `json:"scheduledAt"`
		ChannelID *string `json:"channelId"`
		MediaTitleID *string `json:"mediaTitleId"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	body.EventType=strings.ToLower(strings.TrimSpace(body.EventType))
	body.Title=strings.TrimSpace(body.Title)
	body.Description=strings.TrimSpace(body.Description)
	body.Visibility=strings.ToLower(strings.TrimSpace(body.Visibility))
	body.PlaybackURL=strings.TrimSpace(body.PlaybackURL)
	body.CoverURL=strings.TrimSpace(body.CoverURL)

	if body.EventType!="live" && body.EventType!="premiere" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"eventType must be live or premiere"}); return
	}
	if len([]rune(body.Title))<2 || len([]rune(body.Title))>120 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"title must be 2-120 characters"}); return
	}
	if len([]rune(body.Description))>2000 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"description is too long"}); return
	}
	if body.Visibility=="" { body.Visibility="public" }
	switch body.Visibility {
	case "public","private","invite":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"}); return
	}

	allowChat:=true
	if body.AllowChat!=nil { allowChat=*body.AllowChat }

	if body.EventType=="premiere" {
		if body.MediaTitleID==nil || strings.TrimSpace(*body.MediaTitleID)=="" {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"premiere requires mediaTitleId"}); return
		}
		if body.PlaybackURL=="" {
			_ = s.db.QueryRow(r.Context(),`
				SELECT playback_url
				  FROM media_versions
				 WHERE media_title_id=$1 AND stream_ready=true AND playback_url<>''
				 ORDER BY preferred DESC,height DESC,file_size_bytes DESC
				 LIMIT 1
			`,strings.TrimSpace(*body.MediaTitleID)).Scan(&body.PlaybackURL)
		}
		if body.PlaybackURL=="" {
			writeJSON(w,http.StatusConflict,map[string]string{"error":"premiere media has no stream-ready version"}); return
		}
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var roomID string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO rooms (
			owner_user_id,channel_id,media_title_id,name,topic,room_type,visibility,member_count
		) VALUES ($1,$2,$3,$4,$5,'live',$6,1)
		RETURNING id::text
	`,
		userID,body.ChannelID,body.MediaTitleID,
		body.Title,"Live chat • "+body.Title,body.Visibility,
	).Scan(&roomID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		INSERT INTO room_members (room_id,user_id,role)
		VALUES ($1,$2,'owner')
	`,roomID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var id string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO live_events (
			host_user_id,channel_id,media_title_id,room_id,event_type,title,description,
			visibility,playback_url,cover_url,allow_chat,scheduled_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		RETURNING id::text
	`,
		userID,body.ChannelID,body.MediaTitleID,roomID,body.EventType,body.Title,body.Description,
		body.Visibility,body.PlaybackURL,body.CoverURL,allowChat,body.ScheduledAt,
	).Scan(&id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if body.ChannelID!=nil {
		_,_=tx.Exec(r.Context(),`
			INSERT INTO notifications (
				user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
			)
			SELECT cf.user_id,$2,'live_scheduled','live',$3,$4,$5
			  FROM channel_followers cf
			 WHERE cf.channel_id=$1 AND cf.user_id<>$2
		`,*body.ChannelID,userID,id,"رویداد جدید: "+body.Title,"یک Live/Premiere جدید زمان‌بندی شد.")
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	writeJSON(w,http.StatusCreated,map[string]any{"id":id,"roomId":roomID})
}

func (s *Server) joinLiveEvent(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var roomID *string
	var state string
	err=tx.QueryRow(r.Context(),`
		SELECT room_id::text,state FROM live_events WHERE id=$1
	`,id).Scan(&roomID,&state)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"live event not found"}); return
	}
	if state=="ended" || state=="cancelled" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"live event is not active"}); return
	}

	_,err=tx.Exec(r.Context(),`
		INSERT INTO live_event_viewers (live_event_id,user_id,active,last_seen_at)
		VALUES ($1,$2,true,now())
		ON CONFLICT (live_event_id,user_id)
		DO UPDATE SET active=true,last_seen_at=now()
	`,id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if roomID!=nil {
		_,_=tx.Exec(r.Context(),`
			INSERT INTO room_members (room_id,user_id,role)
			VALUES ($1,$2,'member') ON CONFLICT DO NOTHING
		`,*roomID,userID)
	}

	var current int64
	err=tx.QueryRow(r.Context(),`
		SELECT COUNT(*) FROM live_event_viewers
		 WHERE live_event_id=$1 AND active=true AND last_seen_at>now()-interval '90 seconds'
	`,id).Scan(&current)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		UPDATE live_events SET
		  viewer_count=$2,
		  peak_viewer_count=GREATEST(peak_viewer_count,$2),
		  updated_at=now()
		 WHERE id=$1
	`,id,current)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusOK,map[string]any{"joined":true,"viewers":current,"roomId":roomID})
}

func (s *Server) liveHeartbeat(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")

	tag,err:=s.db.Exec(r.Context(),`
		UPDATE live_event_viewers
		   SET active=true,last_seen_at=now()
		 WHERE live_event_id=$1 AND user_id=$2
	`,id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if tag.RowsAffected()==0 {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"viewer session not found"}); return
	}

	var current int64
	_ = s.db.QueryRow(r.Context(),`
		SELECT COUNT(*) FROM live_event_viewers
		 WHERE live_event_id=$1 AND active=true AND last_seen_at>now()-interval '90 seconds'
	`,id).Scan(&current)
	_,_=s.db.Exec(r.Context(),`
		UPDATE live_events SET viewer_count=$2,peak_viewer_count=GREATEST(peak_viewer_count,$2),updated_at=now()
		 WHERE id=$1
	`,id,current)

	writeJSON(w,http.StatusOK,map[string]any{"viewers":current})
}

func (s *Server) leaveLiveEvent(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")

	_,err:=s.db.Exec(r.Context(),`
		UPDATE live_event_viewers SET active=false,last_seen_at=now()
		 WHERE live_event_id=$1 AND user_id=$2
	`,id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var current int64
	_ = s.db.QueryRow(r.Context(),`
		SELECT COUNT(*) FROM live_event_viewers
		 WHERE live_event_id=$1 AND active=true AND last_seen_at>now()-interval '90 seconds'
	`,id).Scan(&current)
	_,_=s.db.Exec(r.Context(),"UPDATE live_events SET viewer_count=$2,updated_at=now() WHERE id=$1",id,current)

	writeJSON(w,http.StatusOK,map[string]any{"left":true,"viewers":current})
}

func (s *Server) updateLiveEventState(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")

	var body struct {
		State string `json:"state"`
		PlaybackURL *string `json:"playbackUrl"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.State=strings.ToLower(strings.TrimSpace(body.State))
	switch body.State {
	case "scheduled","live","ended","cancelled":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid live state"}); return
	}

	var hostID,eventType,currentPlayback string
	err:=s.db.QueryRow(r.Context(),`
		SELECT host_user_id::text,event_type,playback_url
		  FROM live_events WHERE id=$1
	`,id).Scan(&hostID,&eventType,&currentPlayback)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"live event not found"}); return
	}
	if hostID!=userID {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"}); return
	}

	playback:=currentPlayback
	if body.PlaybackURL!=nil {
		playback=strings.TrimSpace(*body.PlaybackURL)
	}
	if body.State=="live" && playback=="" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"playback source is required before going live"}); return
	}

	_,err=s.db.Exec(r.Context(),`
		UPDATE live_events SET
		  state=$2,
		  playback_url=$3,
		  started_at=CASE WHEN $2='live' THEN COALESCE(started_at,now()) ELSE started_at END,
		  ended_at=CASE WHEN $2 IN ('ended','cancelled') THEN now() ELSE ended_at END,
		  viewer_count=CASE WHEN $2 IN ('ended','cancelled') THEN 0 ELSE viewer_count END,
		  updated_at=now()
		 WHERE id=$1
	`,id,body.State,playback)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if body.State=="ended" || body.State=="cancelled" {
		_,_=s.db.Exec(r.Context(),`
			UPDATE live_event_viewers SET active=false WHERE live_event_id=$1
		`,id)
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"id":id,"state":body.State,"playbackUrl":playback,
	})
}

type liveRowScanner interface {
	Scan(dest ...any) error
}

func scanLiveEventRow(row liveRowScanner) (map[string]any,error) {
	var id,eventType,title,description,visibility,state,playback,cover,hostID,username,displayName,avatar string
	var allowChat,verified bool
	var scheduled,started,ended *time.Time
	var viewers,peak int64
	var roomID *string
	var mediaID,kind,mediaTitle,originalTitle,poster,backdrop *string
	var tmdbID *int64
	var year *int
	var rating *float64

	err:=row.Scan(
		&id,&eventType,&title,&description,&visibility,&state,
		&playback,&cover,&allowChat,&scheduled,&started,&ended,
		&viewers,&peak,&roomID,
		&hostID,&username,&displayName,&avatar,&verified,
		&mediaID,&tmdbID,&kind,&mediaTitle,&originalTitle,&poster,&backdrop,&year,&rating,
	)
	if err!=nil { return nil,err }

	return map[string]any{
		"id":id,"eventType":eventType,"title":title,"description":description,
		"visibility":visibility,"state":state,"playbackUrl":playback,"coverUrl":cover,
		"allowChat":allowChat,"scheduledAt":scheduled,"startedAt":started,"endedAt":ended,
		"viewers":viewers,"peakViewers":peak,"roomId":roomID,
		"host":map[string]any{
			"id":hostID,"username":username,"displayName":displayName,
			"avatarUrl":avatar,"verified":verified,
		},
		"media":map[string]any{
			"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":mediaTitle,
			"originalTitle":originalTitle,"posterUrl":poster,"backdropUrl":backdrop,
			"year":year,"rating":rating,
		},
	},nil
}

func scanLiveEvents(rows interface{ Next() bool; Scan(...any) error }) []map[string]any {
	items:=make([]map[string]any,0)
	for rows.Next() {
		item,err:=scanLiveEventRow(rows)
		if err==nil { items=append(items,item) }
	}
	return items
}
