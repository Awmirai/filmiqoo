package server

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) touchOnlinePresence(ctx context.Context,userID string) error {
	if strings.TrimSpace(userID)=="" { return nil }
	_,err:=s.db.Exec(ctx,`
		INSERT INTO user_presence (
			user_id,state,visible_until,last_seen_at,updated_at
		) VALUES ($1,'online',now()+interval '90 seconds',now(),now())
		ON CONFLICT (user_id)
		DO UPDATE SET
			state=CASE
				WHEN user_presence.state='watching'
				 AND user_presence.visible_until>now()
				THEN 'watching'
				ELSE 'online'
			END,
			visible_until=CASE
				WHEN user_presence.state='watching'
				 AND user_presence.visible_until>now()
				THEN user_presence.visible_until
				ELSE now()+interval '90 seconds'
			END,
			last_seen_at=now(),
			updated_at=now()
	`,userID)
	return err
}

func (s *Server) presenceHeartbeat(w http.ResponseWriter,r *http.Request) {
	if err:=s.touchOnlinePresence(r.Context(),userIDFromContext(r.Context())); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) roomMembers(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")

	var roomType,visibility,myRole string
	err:=s.db.QueryRow(r.Context(),`
		SELECT r.room_type,r.visibility,COALESCE(me.role,'')
		  FROM rooms r
		  LEFT JOIN room_members me
		    ON me.room_id=r.id AND me.user_id=$2
		 WHERE r.id=$1
	`,roomID,userID).Scan(&roomType,&visibility,&myRole)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"room not found"})
		return
	}
	if visibility!="public" && myRole=="" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room membership required"})
		return
	}

	rows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       rm.role,rm.joined_at,
		       COALESCE(up.state,'offline'),
		       up.visible_until,
		       COALESCE(up.last_seen_at,up.updated_at)
		  FROM room_members rm
		  JOIN profiles p ON p.user_id=rm.user_id
		  LEFT JOIN user_presence up ON up.user_id=rm.user_id
		 WHERE rm.room_id=$1
		 ORDER BY CASE rm.role
		    WHEN 'owner' THEN 0
		    WHEN 'admin' THEN 1
		    WHEN 'moderator' THEN 2
		    ELSE 3
		  END,
		  rm.joined_at ASC
		 LIMIT 500
	`,roomID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	now:=time.Now()
	items:=make([]map[string]any,0)
	var online int64
	for rows.Next() {
		var id,username,displayName,avatar,role,state string
		var verified bool
		var joined time.Time
		var visibleUntil,lastSeen *time.Time
		if err:=rows.Scan(
			&id,&username,&displayName,&avatar,&verified,
			&role,&joined,&state,&visibleUntil,&lastSeen,
		); err!=nil { continue }

		effectiveState:="offline"
		if visibleUntil!=nil && visibleUntil.After(now) && state!="offline" {
			effectiveState=state
			online++
		}
		items=append(items,map[string]any{
			"id":id,
			"username":username,
			"displayName":displayName,
			"avatarUrl":avatar,
			"verified":verified,
			"role":role,
			"joinedAt":joined,
			"presence":effectiveState,
			"lastSeenAt":lastSeen,
		})
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"roomId":roomID,
		"roomType":roomType,
		"myRole":myRole,
		"online":online,
		"items":items,
	})
}

func (s *Server) addRoomMember(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	roomType,role,err:=s.roomMembershipRole(r.Context(),roomID,userID)
	if err!=nil || (role!="owner" && role!="admin") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner or admin permission required"})
		return
	}
	if roomType=="dm" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"direct-message membership cannot be changed"})
		return
	}

	var exists bool
	_=s.db.QueryRow(r.Context(),
		"SELECT EXISTS(SELECT 1 FROM profiles WHERE user_id=$1)",targetID,
	).Scan(&exists)
	if !exists {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"user not found"})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	tag,err:=tx.Exec(r.Context(),`
		INSERT INTO room_members (room_id,user_id,role)
		VALUES ($1,$2,'member')
		ON CONFLICT DO NOTHING
	`,roomID,targetID)
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE rooms
			   SET member_count=(SELECT COUNT(*) FROM room_members WHERE room_id=$1)
			 WHERE id=$1
		`,roomID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if tag.RowsAffected()>0 {
		s.publishRoomMutation(r.Context(),roomID,map[string]any{
			"type":"member.joined",
			"roomId":roomID,
			"userId":targetID,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"added":tag.RowsAffected()>0})
}

func (s *Server) updateRoomMemberRole(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	roomType,role,err:=s.roomMembershipRole(r.Context(),roomID,userID)
	if err!=nil || role!="owner" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner permission required"})
		return
	}
	if roomType=="dm" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"direct-message roles cannot be changed"})
		return
	}

	var body struct { Role string `json:"role"` }
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Role=strings.ToLower(strings.TrimSpace(body.Role))
	switch body.Role {
	case "admin","moderator","member":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid role"}); return
	}

	var targetRole string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM room_members WHERE room_id=$1 AND user_id=$2
	`,roomID,targetID).Scan(&targetRole); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"room member not found"}); return
	}
	if targetRole=="owner" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"owner role cannot be changed here"}); return
	}

	if _,err:=s.db.Exec(r.Context(),`
		UPDATE room_members SET role=$3
		 WHERE room_id=$1 AND user_id=$2
	`,roomID,targetID,body.Role); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	s.publishRoomMutation(r.Context(),roomID,map[string]any{
		"type":"member.role_changed",
		"roomId":roomID,
		"userId":targetID,
		"role":body.Role,
	})
	writeJSON(w,http.StatusOK,map[string]any{"userId":targetID,"role":body.Role})
}

func (s *Server) removeRoomMember(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	roomType,role,err:=s.roomMembershipRole(r.Context(),roomID,userID)
	if err!=nil || (role!="owner" && role!="admin") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner or admin permission required"})
		return
	}
	if roomType=="dm" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"direct-message membership cannot be changed"})
		return
	}

	var targetRole string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM room_members WHERE room_id=$1 AND user_id=$2
	`,roomID,targetID).Scan(&targetRole); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"room member not found"}); return
	}
	if targetRole=="owner" || (role=="admin" && targetRole=="admin") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"cannot remove this member"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())
	_,err=tx.Exec(r.Context(),
		"DELETE FROM room_members WHERE room_id=$1 AND user_id=$2",
		roomID,targetID,
	)
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE rooms
			   SET member_count=(SELECT COUNT(*) FROM room_members WHERE room_id=$1)
			 WHERE id=$1
		`,roomID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	s.publishRoomMutation(r.Context(),roomID,map[string]any{
		"type":"member.removed",
		"roomId":roomID,
		"userId":targetID,
	})
	writeJSON(w,http.StatusOK,map[string]any{"removed":true})
}

func (s *Server) forwardRoomMessage(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	sourceRoomID:=chi.URLParam(r,"id")
	messageID:=chi.URLParam(r,"messageID")

	var body struct { TargetRoomID string `json:"targetRoomId"` }
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.TargetRoomID=strings.TrimSpace(body.TargetRoomID)
	if body.TargetRoomID=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"targetRoomId is required"}); return
	}

	var sourceAllowed,targetAllowed bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM rooms r
			 WHERE r.id=$1
			   AND (r.visibility='public' OR EXISTS(
			      SELECT 1 FROM room_members rm
			       WHERE rm.room_id=r.id AND rm.user_id=$3
			   ))
		),
		EXISTS(
			SELECT 1 FROM room_members rm
			 WHERE rm.room_id=$2 AND rm.user_id=$3
		)
	`,sourceRoomID,body.TargetRoomID,userID).Scan(&sourceAllowed,&targetAllowed)
	if !sourceAllowed {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"source room access required"}); return
	}
	if !targetAllowed {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"target room membership required"}); return
	}

	var msgBody,msgType string
	var attachment []byte
	var spoiler bool
	err:=s.db.QueryRow(r.Context(),`
		SELECT body,message_type,attachment,spoiler
		  FROM messages
		 WHERE id=$1 AND room_id=$2 AND deleted_at IS NULL
	`,messageID,sourceRoomID).Scan(&msgBody,&msgType,&attachment,&spoiler)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"message not found"}); return
	}

	var id string
	var created time.Time
	err=s.db.QueryRow(r.Context(),`
		INSERT INTO messages (
			room_id,author_user_id,body,message_type,attachment,spoiler,
			forwarded_from_message_id
		) VALUES ($1,$2,$3,$4,$5,$6,$7)
		RETURNING id::text,created_at
	`,body.TargetRoomID,userID,msgBody,msgType,attachment,spoiler,messageID).Scan(&id,&created)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	payload:=map[string]any{
		"type":"message.created",
		"roomId":body.TargetRoomID,
		"message":map[string]any{
			"id":id,
			"body":msgBody,
			"messageType":msgType,
			"attachment":decodeJSONOrEmptyObject(attachment),
			"spoiler":spoiler,
			"authorUserId":userID,
			"forwardedFromMessageId":messageID,
			"createdAt":created,
		},
	}
	s.publishRoomMutation(r.Context(),body.TargetRoomID,payload)

	var targetType string
	if s.db.QueryRow(r.Context(),
		"SELECT room_type FROM rooms WHERE id=$1",body.TargetRoomID,
	).Scan(&targetType)==nil && targetType=="dm" {
		preview:=strings.TrimSpace(msgBody)
		if preview=="" {
			switch msgType {
			case "voice": preview="🎙 پیام صوتی"
			case "image": preview="🖼 تصویر"
			case "video": preview="🎬 ویدیو"
			default: preview="پیام فوروارد شده"
			}
		}
		if len([]rune(preview))>120 { preview=string([]rune(preview)[:120])+"…" }
		_,_=s.db.Exec(r.Context(),`
			INSERT INTO notifications (
				user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
			)
			SELECT member.user_id,$2,'dm_message','room',$1,'پیام فوروارد شده',$3
			  FROM room_members member
			 WHERE member.room_id=$1 AND member.user_id<>$2
		`,body.TargetRoomID,userID,preview)
	}

	writeJSON(w,http.StatusCreated,payload["message"])
}
