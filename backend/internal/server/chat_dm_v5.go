package server

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) roomConversationSettings(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")

	var id,name,topic,roomType,visibility,channelID,myRole,notificationLevel string
	var memberCount int64
	var slowMode int
	var archived bool
	err:=s.db.QueryRow(r.Context(),`
		SELECT rm.id::text,rm.name,rm.topic,rm.room_type,rm.visibility,
		       rm.member_count,rm.slow_mode_seconds,
		       COALESCE(rm.channel_id::text,''),
		       COALESCE(member.role,''),
		       COALESCE(member.notification_level,'all'),
		       (member.archived_at IS NOT NULL)
		  FROM rooms rm
		  LEFT JOIN room_members member
		    ON member.room_id=rm.id AND member.user_id=$2
		 WHERE rm.id=$1
	`,roomID,userID).Scan(
		&id,&name,&topic,&roomType,&visibility,&memberCount,&slowMode,
		&channelID,&myRole,&notificationLevel,&archived,
	)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"room not found"})
		return
	}
	if visibility!="public" && myRole=="" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room membership required"})
		return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"id":id,
		"name":name,
		"topic":topic,
		"type":roomType,
		"visibility":visibility,
		"members":memberCount,
		"slowModeSeconds":slowMode,
		"channelId":channelID,
		"myRole":myRole,
		"notificationLevel":notificationLevel,
		"archived":archived,
		"canManage":myRole=="owner" || myRole=="admin",
	})
}

func (s *Server) updateRoomConversationSettings(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")

	roomType,role,err:=s.roomMembershipRole(r.Context(),roomID,userID)
	if err!=nil || (role!="owner" && role!="admin") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner or admin permission required"})
		return
	}
	if roomType!="group" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"only group rooms can be edited here"})
		return
	}

	var body struct {
		Name *string `json:"name"`
		Topic *string `json:"topic"`
		Visibility *string `json:"visibility"`
		SlowModeSeconds *int `json:"slowModeSeconds"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}

	if body.Name!=nil {
		v:=strings.TrimSpace(*body.Name)
		if len([]rune(v))<2 || len([]rune(v))>100 {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"room name must be 2-100 characters"})
			return
		}
		body.Name=&v
	}
	if body.Topic!=nil {
		v:=strings.TrimSpace(*body.Topic)
		if len([]rune(v))>300 {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"topic is too long"})
			return
		}
		body.Topic=&v
	}
	if body.Visibility!=nil {
		v:=strings.ToLower(strings.TrimSpace(*body.Visibility))
		switch v {
		case "public","private","invite":
		default:
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"})
			return
		}
		body.Visibility=&v
	}
	if body.SlowModeSeconds!=nil {
		if *body.SlowModeSeconds<0 || *body.SlowModeSeconds>3600 {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"slowModeSeconds must be 0-3600"})
			return
		}
	}

	var name,topic,visibility string
	var slowMode int
	err=s.db.QueryRow(r.Context(),`
		UPDATE rooms
		   SET name=COALESCE($2,name),
		       topic=COALESCE($3,topic),
		       visibility=COALESCE($4,visibility),
		       slow_mode_seconds=COALESCE($5,slow_mode_seconds),
		       updated_at=now()
		 WHERE id=$1
		 RETURNING name,topic,visibility,slow_mode_seconds
	`,roomID,body.Name,body.Topic,body.Visibility,body.SlowModeSeconds).
		Scan(&name,&topic,&visibility,&slowMode)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	s.publishRoomMutation(r.Context(),roomID,map[string]any{
		"type":"room.settings_changed",
		"roomId":roomID,
		"name":name,
		"topic":topic,
		"visibility":visibility,
		"slowModeSeconds":slowMode,
	})
	writeJSON(w,http.StatusOK,map[string]any{
		"name":name,
		"topic":topic,
		"visibility":visibility,
		"slowModeSeconds":slowMode,
	})
}

func (s *Server) updateRoomPreferences(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")

	var body struct {
		NotificationLevel *string `json:"notificationLevel"`
		Archived *bool `json:"archived"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}

	var level *string
	if body.NotificationLevel!=nil {
		v:=strings.ToLower(strings.TrimSpace(*body.NotificationLevel))
		switch v {
		case "all","mentions","off":
		default:
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid notification level"})
			return
		}
		level=&v
	}

	var exists bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM room_members
			 WHERE room_id=$1 AND user_id=$2
		)
	`,roomID,userID).Scan(&exists)
	if !exists {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room membership required"})
		return
	}

	if level!=nil {
		if _,err:=s.db.Exec(r.Context(),`
			UPDATE room_members
			   SET notification_level=$3
			 WHERE room_id=$1 AND user_id=$2
		`,roomID,userID,*level); err!=nil {
			writeError(w,http.StatusInternalServerError,err)
			return
		}
	}
	if body.Archived!=nil {
		var archiveErr error
		if *body.Archived {
			_,archiveErr=s.db.Exec(r.Context(),`
				UPDATE room_members
				   SET archived_at=COALESCE(archived_at,now())
				 WHERE room_id=$1 AND user_id=$2
			`,roomID,userID)
		} else {
			_,archiveErr=s.db.Exec(r.Context(),`
				UPDATE room_members
				   SET archived_at=NULL
				 WHERE room_id=$1 AND user_id=$2
			`,roomID,userID)
		}
		if archiveErr!=nil {
			writeError(w,http.StatusInternalServerError,archiveErr)
			return
		}
	}

	var currentLevel string
	var archived bool
	if err:=s.db.QueryRow(r.Context(),`
		SELECT notification_level,(archived_at IS NOT NULL)
		  FROM room_members
		 WHERE room_id=$1 AND user_id=$2
	`,roomID,userID).Scan(&currentLevel,&archived); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	writeJSON(w,http.StatusOK,map[string]any{
		"notificationLevel":currentLevel,
		"archived":archived,
	})
}

func (s *Server) roomInviteInfo(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	if !s.canManageRoomInvite(r.Context(),roomID,userID) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner or admin permission required"})
		return
	}

	code,err:=randomHex(12)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	var inviteCode string
	var usageCount int64
	var expiresAt *time.Time
	err=s.db.QueryRow(r.Context(),`
		INSERT INTO room_invites (
			room_id,invite_code,created_by_user_id,enabled
		) VALUES ($1,$2,$3,true)
		ON CONFLICT (room_id)
		DO UPDATE SET enabled=true,updated_at=now()
		RETURNING invite_code,usage_count,expires_at
	`,roomID,code,userID).Scan(&inviteCode,&usageCount,&expiresAt)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"code":inviteCode,
		"deepLink":"filmiqoo://room-invite/"+inviteCode,
		"usageCount":usageCount,
		"expiresAt":expiresAt,
	})
}

func (s *Server) regenerateRoomInvite(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	if !s.canManageRoomInvite(r.Context(),roomID,userID) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner or admin permission required"})
		return
	}

	code,err:=randomHex(12)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	_,err=s.db.Exec(r.Context(),`
		INSERT INTO room_invites (
			room_id,invite_code,created_by_user_id,enabled,usage_count,updated_at
		) VALUES ($1,$2,$3,true,0,now())
		ON CONFLICT (room_id)
		DO UPDATE SET
			invite_code=EXCLUDED.invite_code,
			created_by_user_id=EXCLUDED.created_by_user_id,
			enabled=true,
			usage_count=0,
			expires_at=NULL,
			updated_at=now()
	`,roomID,code,userID)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"code":code,
		"deepLink":"filmiqoo://room-invite/"+code,
		"usageCount":0,
	})
}

func (s *Server) canManageRoomInvite(ctx context.Context,roomID,userID string) bool {
	var roomType,role string
	err:=s.db.QueryRow(ctx,`
		SELECT r.room_type,rm.role
		  FROM rooms r
		  JOIN room_members rm ON rm.room_id=r.id
		 WHERE r.id=$1 AND rm.user_id=$2
	`,roomID,userID).Scan(&roomType,&role)
	return err==nil && roomType=="group" && (role=="owner" || role=="admin")
}

func (s *Server) joinRoomInvite(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	code:=strings.TrimSpace(chi.URLParam(r,"code"))
	if code=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invite code is required"})
		return
	}

	var roomID,name,roomType string
	var expiresAt *time.Time
	err:=s.db.QueryRow(r.Context(),`
		SELECT r.id::text,r.name,r.room_type,ri.expires_at
		  FROM room_invites ri
		  JOIN rooms r ON r.id=ri.room_id
		 WHERE ri.invite_code=$1
		   AND ri.enabled=true
		   AND (ri.expires_at IS NULL OR ri.expires_at>now())
	`,code).Scan(&roomID,&name,&roomType,&expiresAt)
	if err!=nil || roomType!="group" {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"invite is invalid or expired"})
		return
	}
	_ = expiresAt

	var already bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM room_members
			 WHERE room_id=$1 AND user_id=$2
		)
	`,roomID,userID).Scan(&already)
	if already {
		_,_=s.db.Exec(r.Context(),`
			UPDATE room_members SET archived_at=NULL
			 WHERE room_id=$1 AND user_id=$2
		`,roomID,userID)
		writeJSON(w,http.StatusOK,map[string]any{
			"id":roomID,"title":name,"existing":true,
		})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer tx.Rollback(r.Context())

	_,err=tx.Exec(r.Context(),`
		INSERT INTO room_members (room_id,user_id,role,notification_level)
		VALUES ($1,$2,'member','all')
	`,roomID,userID)
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE rooms
			   SET member_count=(SELECT COUNT(*) FROM room_members WHERE room_id=$1),
			       updated_at=now()
			 WHERE id=$1
		`,roomID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE room_invites
			   SET usage_count=usage_count+1,updated_at=now()
			 WHERE room_id=$1
		`,roomID)
	}
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	s.publishRoomMutation(r.Context(),roomID,map[string]any{
		"type":"member.joined",
		"roomId":roomID,
		"userId":userID,
	})
	writeJSON(w,http.StatusCreated,map[string]any{
		"id":roomID,"title":name,"existing":false,
	})
}

func (s *Server) leaveRoom(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")

	roomType,role,err:=s.roomMembershipRole(r.Context(),roomID,userID)
	if err!=nil {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room membership required"})
		return
	}
	if roomType=="dm" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"archive direct messages instead of leaving"})
		return
	}

	var count int64
	if err:=s.db.QueryRow(r.Context(),
		"SELECT COUNT(*) FROM room_members WHERE room_id=$1",roomID,
	).Scan(&count); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	if role=="owner" {
		if count>1 {
			writeJSON(w,http.StatusConflict,map[string]string{
				"error":"transfer ownership before leaving this group",
			})
			return
		}
		if _,err:=s.db.Exec(r.Context(),"DELETE FROM rooms WHERE id=$1",roomID); err!=nil {
			writeError(w,http.StatusInternalServerError,err)
			return
		}
		writeJSON(w,http.StatusOK,map[string]any{"left":true,"deleted":true})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer tx.Rollback(r.Context())
	_,err=tx.Exec(r.Context(),
		"DELETE FROM room_members WHERE room_id=$1 AND user_id=$2",
		roomID,userID,
	)
	if err==nil {
		_,_=tx.Exec(r.Context(),
			"DELETE FROM room_reads WHERE room_id=$1 AND user_id=$2",
			roomID,userID,
		)
		_,err=tx.Exec(r.Context(),`
			UPDATE rooms
			   SET member_count=(SELECT COUNT(*) FROM room_members WHERE room_id=$1),
			       updated_at=now()
			 WHERE id=$1
		`,roomID)
	}
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	s.publishRoomMutation(r.Context(),roomID,map[string]any{
		"type":"member.removed",
		"roomId":roomID,
		"userId":userID,
	})
	writeJSON(w,http.StatusOK,map[string]any{"left":true,"deleted":false})
}

func (s *Server) transferRoomOwnership(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	roomType,role,err:=s.roomMembershipRole(r.Context(),roomID,userID)
	if err!=nil || role!="owner" || roomType!="group" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"group owner permission required"})
		return
	}
	if targetID==userID {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"target is already the owner"})
		return
	}

	var targetExists bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM room_members
			 WHERE room_id=$1 AND user_id=$2
		)
	`,roomID,targetID).Scan(&targetExists)
	if !targetExists {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"room member not found"})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer tx.Rollback(r.Context())
	_,err=tx.Exec(r.Context(),`
		UPDATE room_members SET role='admin'
		 WHERE room_id=$1 AND user_id=$2
	`,roomID,userID)
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE room_members SET role='owner'
			 WHERE room_id=$1 AND user_id=$2
		`,roomID,targetID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE rooms SET owner_user_id=$2,updated_at=now()
			 WHERE id=$1
		`,roomID,targetID)
	}
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	s.publishRoomMutation(r.Context(),roomID,map[string]any{
		"type":"member.owner_changed",
		"roomId":roomID,
		"previousOwnerId":userID,
		"ownerId":targetID,
	})
	writeJSON(w,http.StatusOK,map[string]any{
		"ownerId":targetID,
		"previousOwnerId":userID,
	})
}

func (s *Server) notifyRoomMessage(
	ctx context.Context,
	roomID string,
	actorUserID string,
	messageBody string,
	messageType string,
	replyToMessageID *string,
) {
	var roomType,roomName string
	if err:=s.db.QueryRow(ctx,
		"SELECT room_type,name FROM rooms WHERE id=$1",
		roomID,
	).Scan(&roomType,&roomName); err!=nil {
		return
	}
	if roomType!="dm" && roomType!="group" {
		return
	}

	preview:=messagePreview(messageBody,messageType)
	if roomType=="dm" {
		_,_=s.db.Exec(ctx,`
			INSERT INTO notifications (
				user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
			)
			SELECT member.user_id,$2,'dm_message','room',$1,'پیام جدید',$3
			  FROM room_members member
			 WHERE member.room_id=$1
			   AND member.user_id<>$2
			   AND member.notification_level<>'off'
		`,roomID,actorUserID,preview)
		return
	}

	_,_=s.db.Exec(ctx,`
		INSERT INTO notifications (
			user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
		)
		SELECT member.user_id,$2,'room_message','room',$1,$3,$4
		  FROM room_members member
		  JOIN profiles p ON p.user_id=member.user_id
		 WHERE member.room_id=$1
		   AND member.user_id<>$2
		   AND (
		     member.notification_level='all'
		     OR (
		       member.notification_level='mentions'
		       AND (
		         lower($5) LIKE ('%@' || lower(p.username::text) || '%')
		         OR (
		           $6::uuid IS NOT NULL
		           AND EXISTS(
		             SELECT 1 FROM messages replied
		              WHERE replied.id=$6::uuid
		                AND replied.author_user_id=member.user_id
		           )
		         )
		       )
		     )
		   )
	`,roomID,actorUserID,roomName,preview,messageBody,replyToMessageID)
}

func messagePreview(body,typ string) string {
	value:=strings.TrimSpace(body)
	if value=="" {
		switch typ {
		case "voice":
			value="🎙 پیام صوتی"
		case "image":
			value="🖼 تصویر"
		case "video":
			value="🎬 ویدیو"
		default:
			value="پیام جدید"
		}
	}
	runes:=[]rune(value)
	if len(runes)>120 {
		value=string(runes[:120])+"…"
	}
	return value
}
