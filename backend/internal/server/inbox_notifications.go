package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) inbox(w http.ResponseWriter,r *http.Request) {
	_ = s.processDueScheduledRoomMessages(r.Context())
	userID:=userIDFromContext(r.Context())
	archivedParam:=strings.ToLower(strings.TrimSpace(r.URL.Query().Get("archived")))
	archived:=archivedParam=="1" || archivedParam=="true" || archivedParam=="yes"

	rows,err:=s.db.Query(r.Context(),`
		SELECT rm.id::text,
		       CASE WHEN rm.room_type='dm' THEN COALESCE(otherp.display_name,rm.name) ELSE rm.name END,
		       rm.topic,rm.room_type,rm.member_count,
		       COALESCE(otherp.user_id::text,''),
		       COALESCE(otherp.username::text,''),
		       COALESCE(otherp.avatar_url,''),
		       COALESCE(lastm.body,''),
		       lastm.created_at,
		       (
		         SELECT COUNT(*)
		           FROM messages um
		          WHERE um.room_id=rm.id
		            AND um.deleted_at IS NULL
		            AND um.author_user_id<>$1
		            AND um.created_at>COALESCE(rr.last_read_at,to_timestamp(0))
		       ) AS unread,
		       mine.notification_level,
		       (mine.archived_at IS NOT NULL)
		  FROM room_members mine
		  JOIN rooms rm ON rm.id=mine.room_id
		  LEFT JOIN room_reads rr ON rr.room_id=rm.id AND rr.user_id=$1
		  LEFT JOIN LATERAL (
		    SELECT p.user_id,p.username,p.display_name,p.avatar_url
		      FROM room_members om
		      JOIN profiles p ON p.user_id=om.user_id
		     WHERE om.room_id=rm.id AND om.user_id<>$1
		     ORDER BY om.joined_at ASC
		     LIMIT 1
		  ) otherp ON true
		  LEFT JOIN LATERAL (
		    SELECT CASE
		             WHEN btrim(m.body)<>'' THEN m.body
		             WHEN m.message_type='voice' THEN '🎙 پیام صوتی'
		             WHEN m.message_type='image' THEN '🖼 تصویر'
		             WHEN m.message_type='video' THEN '🎬 ویدیو'
		             WHEN m.message_type='document' THEN '📎 فایل'
		             WHEN m.message_type='location' THEN '📍 موقعیت مکانی'
		             WHEN m.message_type='contact' THEN '👤 مخاطب'
		             ELSE 'پیام'
		           END AS body,
		           m.created_at
		      FROM messages m
		     WHERE m.room_id=rm.id AND m.deleted_at IS NULL
		     ORDER BY m.created_at DESC
		     LIMIT 1
		  ) lastm ON true
		 WHERE mine.user_id=$1
		   AND (mine.archived_at IS NOT NULL)=$2
		 ORDER BY lastm.created_at DESC NULLS LAST,rm.created_at DESC
		 LIMIT 100
	`,userID,archived)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,title,topic,typ,otherID,otherUsername,avatar,lastMessage,notificationLevel string
		var members,unread int64
		var lastAt *time.Time
		var isArchived bool
		if err:=rows.Scan(
			&id,&title,&topic,&typ,&members,
			&otherID,&otherUsername,&avatar,
			&lastMessage,&lastAt,&unread,
			&notificationLevel,&isArchived,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"id":id,"title":title,"topic":topic,"type":typ,"members":members,
			"otherUserId":otherID,"otherUsername":otherUsername,"avatarUrl":avatar,
			"lastMessage":lastMessage,"lastMessageAt":lastAt,"unread":unread,
			"notificationLevel":notificationLevel,"archived":isArchived,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items,"archived":archived})
}

func (s *Server) ensureDM(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	targetID:=chi.URLParam(r,"userID")
	if userID==targetID {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"cannot message yourself"})
		return
	}

	var blocked bool
	_ = s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM blocks
			 WHERE (blocker_user_id=$1 AND blocked_user_id=$2)
			    OR (blocker_user_id=$2 AND blocked_user_id=$1)
		)
	`,userID,targetID).Scan(&blocked)
	if blocked {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"direct messages are unavailable because one of these accounts has blocked the other"})
		return
	}

	var displayName string
	if err:=s.db.QueryRow(r.Context(),
		"SELECT display_name FROM profiles WHERE user_id=$1",
		targetID).Scan(&displayName); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"user not found"})
		return
	}

	var existing string
	err:=s.db.QueryRow(r.Context(),`
		SELECT rm.id::text
		  FROM rooms rm
		  JOIN room_members a ON a.room_id=rm.id AND a.user_id=$1
		  JOIN room_members b ON b.room_id=rm.id AND b.user_id=$2
		 WHERE rm.room_type='dm'
		 ORDER BY rm.created_at DESC
		 LIMIT 1
	`,userID,targetID).Scan(&existing)
	if err==nil && existing!="" {
		writeJSON(w,http.StatusOK,map[string]any{"id":existing,"title":displayName,"existing":true})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var id string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO rooms (owner_user_id,name,topic,room_type,visibility,member_count)
		VALUES ($1,'Direct message','','dm','private',2)
		RETURNING id::text
	`,userID).Scan(&id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		INSERT INTO room_members (room_id,user_id,role)
		VALUES ($1,$2,'member'),($1,$3,'member')
	`,id,userID,targetID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,_=tx.Exec(r.Context(),`
		INSERT INTO room_reads (room_id,user_id,last_read_at)
		VALUES ($1,$2,now()),($1,$3,now())
		ON CONFLICT (room_id,user_id) DO UPDATE SET last_read_at=EXCLUDED.last_read_at
	`,id,userID,targetID)

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	writeJSON(w,http.StatusCreated,map[string]any{"id":id,"title":displayName,"existing":false})
}

func (s *Server) markRoomRead(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	_,err:=s.db.Exec(r.Context(),`
		INSERT INTO room_reads (room_id,user_id,last_read_at)
		SELECT $1,$2,now()
		 WHERE EXISTS (
		   SELECT 1
		     FROM rooms room
		    WHERE room.id=$1
		      AND (
		        room.visibility='public'
		        OR EXISTS(
		          SELECT 1 FROM room_members rm
		           WHERE rm.room_id=room.id AND rm.user_id=$2
		        )
		      )
		 )
		ON CONFLICT (room_id,user_id)
		DO UPDATE SET last_read_at=EXCLUDED.last_read_at
	`,roomID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) notifications(w http.ResponseWriter,r *http.Request) {
	_ = s.processDueScheduledRoomMessages(r.Context())
	userID:=userIDFromContext(r.Context())
	if err:=s.processDueReleaseReminders(r.Context(),userID); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if err:=s.processDueSeriesAlerts(r.Context(),userID); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if err:=s.processDueAvailabilityAlerts(r.Context(),userID); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if err:=s.processDueWatchPartyReminders(r.Context(),userID); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	rows,err:=s.db.Query(r.Context(),`
		SELECT n.id::text,n.notification_type,n.entity_type,n.entity_id::text,
		       n.title,n.body,n.read_at,n.created_at,
		       COALESCE(p.user_id::text,''),COALESCE(p.username::text,''),
		       COALESCE(p.display_name,''),COALESCE(p.avatar_url,''),COALESCE(p.verified,false),
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
		       mv.id::text,mv.quality_label,mv.stream_ready
		  FROM notifications n
		  LEFT JOIN profiles p ON p.user_id=n.actor_user_id
		  LEFT JOIN media_titles mt
		    ON n.entity_type IN ('release','series','availability') AND mt.id=n.entity_id
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label,stream_ready
		      FROM media_versions
		     WHERE media_title_id=mt.id
		     ORDER BY preferred DESC,stream_ready DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) mv ON true
		 WHERE n.user_id=$1
		 ORDER BY n.created_at DESC
		 LIMIT 100
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	var unread int64
	for rows.Next() {
		var id,typ,entityType,title,body,actorID,username,displayName,avatar string
		var entityID *string
		var readAt *time.Time
		var created time.Time
		var verified bool

		var mediaID,kind,mediaTitle,originalTitle,overview,poster,backdrop *string
		var tmdbID *int64
		var year *int
		var rating *float64
		var versionID,quality *string
		var streamReady *bool

		if err:=rows.Scan(
			&id,&typ,&entityType,&entityID,&title,&body,&readAt,&created,
			&actorID,&username,&displayName,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&mediaTitle,&originalTitle,&overview,
			&poster,&backdrop,&year,&rating,&versionID,&quality,&streamReady,
		); err!=nil { continue }

		if readAt==nil { unread++ }
		items=append(items,map[string]any{
			"id":id,"type":typ,"entityType":entityType,"entityId":entityID,
			"title":title,"body":body,"read":readAt!=nil,"createdAt":created,
			"actor":map[string]any{
				"id":actorID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":mediaTitle,
				"originalTitle":originalTitle,"overview":overview,
				"posterUrl":poster,"backdropUrl":backdrop,
				"year":year,"rating":rating,
				"mediaVersionId":versionID,"quality":quality,"streamReady":streamReady,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items,"unread":unread})
}
func (s *Server) markNotificationRead(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")
	_,err:=s.db.Exec(r.Context(),
		"UPDATE notifications SET read_at=COALESCE(read_at,now()) WHERE id=$1 AND user_id=$2",
		id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) markAllNotificationsRead(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	_,err:=s.db.Exec(r.Context(),
		"UPDATE notifications SET read_at=COALESCE(read_at,now()) WHERE user_id=$1",
		userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) sendDMNotification(w http.ResponseWriter,r *http.Request) {
	// Reserved endpoint placeholder intentionally not routed. Message notifications
	// are generated server-side by sendRoomMessage for DM rooms.
	_ = json.NewEncoder(w).Encode(map[string]any{"ok":true})
}

func normalizeMessagePreview(value string) string {
	value=strings.TrimSpace(value)
	runes:=[]rune(value)
	if len(runes)>120 { return string(runes[:120])+"…" }
	return value
}
