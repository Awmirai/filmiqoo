package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

type roomMessagePayloadV6 struct {
	Body             string
	Type             string
	Attachment       map[string]any
	Spoiler          bool
	ReplyToMessageID *string
}

func validateRoomMessagePayloadV6(p roomMessagePayloadV6) error {
	p.Body=strings.TrimSpace(p.Body)
	if p.Type=="" { p.Type="text" }
	switch p.Type {
	case "text","image","video","voice","document","location","contact","reel","movie","episode":
	default:
		return errors.New("unsupported message type")
	}
	if len([]rune(p.Body))>4000 {
		return errors.New("message is too long")
	}
	if p.Type=="text" && p.Body=="" {
		return errors.New("message is empty")
	}
	switch p.Type {
	case "image","video","voice","document":
		url,_:=p.Attachment["url"].(string)
		if strings.TrimSpace(url)=="" {
			return errors.New("attachment url is required")
		}
	case "location":
		lat,latOK:=numberFromAny(p.Attachment["latitude"])
		lng,lngOK:=numberFromAny(p.Attachment["longitude"])
		if !latOK || !lngOK || lat < -90 || lat > 90 || lng < -180 || lng > 180 {
			return errors.New("invalid location attachment")
		}
	case "contact":
		name,_:=p.Attachment["name"].(string)
		phone,_:=p.Attachment["phone"].(string)
		if strings.TrimSpace(name)=="" || strings.TrimSpace(phone)=="" {
			return errors.New("contact name and phone are required")
		}
	}
	return nil
}

func numberFromAny(v any) (float64,bool) {
	switch x:=v.(type) {
	case float64:
		return x,true
	case float32:
		return float64(x),true
	case int:
		return float64(x),true
	case int64:
		return float64(x),true
	case json.Number:
		n,err:=x.Float64()
		return n,err==nil
	default:
		return 0,false
	}
}

func (s *Server) insertRoomMessageV6(
	ctx context.Context,
	roomID string,
	userID string,
	p roomMessagePayloadV6,
	forwardedFromMessageID *string,
) (string,time.Time,error) {
	attachment,err:=json.Marshal(p.Attachment)
	if err!=nil { return "",time.Time{},err }

	var id string
	var created time.Time
	err=s.db.QueryRow(ctx,`
		INSERT INTO messages (
			room_id,author_user_id,reply_to_message_id,body,message_type,
			attachment,spoiler,forwarded_from_message_id
		) VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7,$8)
		RETURNING id::text,created_at
	`,
		roomID,userID,p.ReplyToMessageID,strings.TrimSpace(p.Body),p.Type,
		string(attachment),p.Spoiler,forwardedFromMessageID,
	).Scan(&id,&created)
	if err!=nil { return "",time.Time{},err }

	payload:=map[string]any{
		"type":"message.created",
		"roomId":roomID,
		"message":map[string]any{
			"id":id,
			"body":strings.TrimSpace(p.Body),
			"messageType":p.Type,
			"attachment":p.Attachment,
			"spoiler":p.Spoiler,
			"authorUserId":userID,
			"replyToMessageId":p.ReplyToMessageID,
			"forwardedFromMessageId":forwardedFromMessageID,
			"createdAt":created,
		},
	}
	s.publishRoomMutation(ctx,roomID,payload)
	s.notifyRoomMessage(
		ctx,
		roomID,
		userID,
		strings.TrimSpace(p.Body),
		p.Type,
		p.ReplyToMessageID,
	)
	return id,created,nil
}

func (s *Server) roomReadState(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")

	var allowed bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
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
	`,roomID,userID).Scan(&allowed)
	if !allowed {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room access required"})
		return
	}

	var lastRead *time.Time
	_ = s.db.QueryRow(r.Context(),`
		SELECT last_read_at
		  FROM room_reads
		 WHERE room_id=$1 AND user_id=$2
	`,roomID,userID).Scan(&lastRead)

	var firstUnread *string
	var unread int64
	err:=s.db.QueryRow(r.Context(),`
		SELECT (
			SELECT m.id::text
			  FROM messages m
			 WHERE m.room_id=$1
			   AND m.deleted_at IS NULL
			   AND m.author_user_id<>$2
			   AND m.created_at>COALESCE($3::timestamptz,to_timestamp(0))
			 ORDER BY m.created_at ASC
			 LIMIT 1
		),
		(
			SELECT COUNT(*)
			  FROM messages m
			 WHERE m.room_id=$1
			   AND m.deleted_at IS NULL
			   AND m.author_user_id<>$2
			   AND m.created_at>COALESCE($3::timestamptz,to_timestamp(0))
		)
	`,roomID,userID,lastRead).Scan(&firstUnread,&unread)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	writeJSON(w,http.StatusOK,map[string]any{
		"lastReadAt":lastRead,
		"firstUnreadMessageId":firstUnread,
		"unread":unread,
	})
}

func (s *Server) roomDraft(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")

	var body string
	var replyID *string
	var spoiler bool
	var updated time.Time
	err:=s.db.QueryRow(r.Context(),`
		SELECT body,reply_to_message_id::text,spoiler,updated_at
		  FROM room_drafts
		 WHERE room_id=$1 AND user_id=$2
	`,roomID,userID).Scan(&body,&replyID,&spoiler,&updated)
	if err!=nil {
		writeJSON(w,http.StatusOK,map[string]any{"exists":false})
		return
	}
	writeJSON(w,http.StatusOK,map[string]any{
		"exists":true,
		"body":body,
		"replyToMessageId":replyID,
		"spoiler":spoiler,
		"updatedAt":updated,
	})
}

func (s *Server) updateRoomDraft(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	var body struct {
		Body string `json:"body"`
		ReplyToMessageID *string `json:"replyToMessageId"`
		Spoiler bool `json:"spoiler"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	if len([]rune(body.Body))>4000 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"draft is too long"})
		return
	}

	var member bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM room_members WHERE room_id=$1 AND user_id=$2
		)
	`,roomID,userID).Scan(&member)
	if !member {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room membership required"})
		return
	}

	if strings.TrimSpace(body.Body)=="" && body.ReplyToMessageID==nil && !body.Spoiler {
		_,_=s.db.Exec(r.Context(),
			"DELETE FROM room_drafts WHERE room_id=$1 AND user_id=$2",
			roomID,userID,
		)
		writeJSON(w,http.StatusOK,map[string]any{"exists":false})
		return
	}

	_,err:=s.db.Exec(r.Context(),`
		INSERT INTO room_drafts (
			room_id,user_id,body,reply_to_message_id,spoiler,updated_at
		) VALUES ($1,$2,$3,$4,$5,now())
		ON CONFLICT (room_id,user_id)
		DO UPDATE SET
			body=EXCLUDED.body,
			reply_to_message_id=EXCLUDED.reply_to_message_id,
			spoiler=EXCLUDED.spoiler,
			updated_at=now()
	`,roomID,userID,body.Body,body.ReplyToMessageID,body.Spoiler)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	writeJSON(w,http.StatusOK,map[string]any{"exists":true})
}

func (s *Server) deleteRoomDraft(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	_,err:=s.db.Exec(r.Context(),
		"DELETE FROM room_drafts WHERE room_id=$1 AND user_id=$2",
		roomID,userID,
	)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) scheduleRoomMessage(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	var body struct {
		Body string `json:"body"`
		Type string `json:"type"`
		Attachment map[string]any `json:"attachment"`
		Spoiler bool `json:"spoiler"`
		ReplyToMessageID *string `json:"replyToMessageId"`
		ScheduledAt time.Time `json:"scheduledAt"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	body.Body=strings.TrimSpace(body.Body)
	if body.Type=="" { body.Type="text" }

	p:=roomMessagePayloadV6{
		Body:body.Body,
		Type:body.Type,
		Attachment:body.Attachment,
		Spoiler:body.Spoiler,
		ReplyToMessageID:body.ReplyToMessageID,
	}
	if err:=validateRoomMessagePayloadV6(p); err!=nil {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":err.Error()})
		return
	}
	if body.ScheduledAt.Before(time.Now().Add(30*time.Second)) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"scheduled time must be at least 30 seconds in the future"})
		return
	}
	if body.ScheduledAt.After(time.Now().Add(365*24*time.Hour)) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"scheduled time is too far in the future"})
		return
	}

	var roomType,visibility,role string
	err:=s.db.QueryRow(r.Context(),`
		SELECT r.room_type,r.visibility,COALESCE(rm.role,'')
		  FROM rooms r
		  LEFT JOIN room_members rm ON rm.room_id=r.id AND rm.user_id=$2
		 WHERE r.id=$1
	`,roomID,userID).Scan(&roomType,&visibility,&role)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"room not found"})
		return
	}
	if (roomType=="group" || visibility!="public") && role=="" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room membership required"})
		return
	}
	if roomType=="dm" {
		var dmBlocked bool
		_=s.db.QueryRow(r.Context(),`
			SELECT EXISTS(
				SELECT 1
				  FROM room_members other
				  JOIN blocks b ON (
				       (b.blocker_user_id=$2 AND b.blocked_user_id=other.user_id)
				    OR (b.blocker_user_id=other.user_id AND b.blocked_user_id=$2)
				  )
				 WHERE other.room_id=$1 AND other.user_id<>$2
			)
		`,roomID,userID).Scan(&dmBlocked)
		if dmBlocked {
			writeJSON(w,http.StatusForbidden,map[string]string{"error":"direct messages are unavailable"})
			return
		}
	}

	attachment,_:=json.Marshal(body.Attachment)
	var id string
	err=s.db.QueryRow(r.Context(),`
		INSERT INTO scheduled_room_messages (
			room_id,author_user_id,reply_to_message_id,body,message_type,
			attachment,spoiler,scheduled_at
		) VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7,$8)
		RETURNING id::text
	`,
		roomID,userID,body.ReplyToMessageID,body.Body,body.Type,
		string(attachment),body.Spoiler,body.ScheduledAt,
	).Scan(&id)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	_,_=s.db.Exec(r.Context(),
		"DELETE FROM room_drafts WHERE room_id=$1 AND user_id=$2",
		roomID,userID,
	)
	writeJSON(w,http.StatusCreated,map[string]any{
		"id":id,
		"scheduledAt":body.ScheduledAt,
		"status":"scheduled",
	})
}

func (s *Server) scheduledRoomMessages(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	_ = s.processDueScheduledRoomMessages(r.Context())

	rows,err:=s.db.Query(r.Context(),`
		SELECT id::text,body,message_type,attachment,spoiler,
		       reply_to_message_id::text,scheduled_at,status,created_at
		  FROM scheduled_room_messages
		 WHERE room_id=$1
		   AND author_user_id=$2
		   AND status IN ('scheduled','sending','failed')
		 ORDER BY scheduled_at ASC
		 LIMIT 100
	`,roomID,userID)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,body,typ,status string
		var attachment []byte
		var spoiler bool
		var replyID *string
		var scheduledAt,createdAt time.Time
		if err:=rows.Scan(
			&id,&body,&typ,&attachment,&spoiler,&replyID,
			&scheduledAt,&status,&createdAt,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,
			"body":body,
			"type":typ,
			"attachment":decodeJSONOrEmptyObject(attachment),
			"spoiler":spoiler,
			"replyToMessageId":replyID,
			"scheduledAt":scheduledAt,
			"status":status,
			"createdAt":createdAt,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) cancelScheduledRoomMessage(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	id:=chi.URLParam(r,"scheduledID")
	tag,err:=s.db.Exec(r.Context(),`
		UPDATE scheduled_room_messages
		   SET status='cancelled',updated_at=now()
		 WHERE id=$1
		   AND room_id=$2
		   AND author_user_id=$3
		   AND status IN ('scheduled','failed')
	`,id,roomID,userID)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if tag.RowsAffected()!=1 {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"scheduled message not found"})
		return
	}
	writeJSON(w,http.StatusOK,map[string]any{"cancelled":true})
}

func (s *Server) processDueScheduledRoomMessages(ctx context.Context) error {
	rows,err:=s.db.Query(ctx,`
		UPDATE scheduled_room_messages
		   SET status='sending',updated_at=now()
		 WHERE id IN (
		   SELECT id
		     FROM scheduled_room_messages
		    WHERE status='scheduled'
		      AND scheduled_at<=now()
		    ORDER BY scheduled_at ASC
		    FOR UPDATE SKIP LOCKED
		    LIMIT 50
		 )
		 RETURNING id::text,room_id::text,author_user_id::text,
		           body,message_type,attachment,spoiler,reply_to_message_id::text
	`)
	if err!=nil { return err }
	defer rows.Close()

	type pending struct {
		id,roomID,userID,body,typ string
		attachment []byte
		spoiler bool
		replyID *string
	}
	pendingItems:=make([]pending,0)
	for rows.Next() {
		var p pending
		if err:=rows.Scan(
			&p.id,&p.roomID,&p.userID,&p.body,&p.typ,&p.attachment,
			&p.spoiler,&p.replyID,
		); err!=nil { continue }
		pendingItems=append(pendingItems,p)
	}
	if err:=rows.Err(); err!=nil { return err }

	for _,item:=range pendingItems {
		var roomType,visibility,role string
		err:=s.db.QueryRow(ctx,`
			SELECT r.room_type,r.visibility,COALESCE(rm.role,'')
			  FROM rooms r
			  LEFT JOIN room_members rm
			    ON rm.room_id=r.id AND rm.user_id=$2
			 WHERE r.id=$1
		`,item.roomID,item.userID).Scan(&roomType,&visibility,&role)
		if err!=nil || ((roomType=="group" || visibility!="public") && role=="") {
			_,_=s.db.Exec(ctx,`
				UPDATE scheduled_room_messages
				   SET status='failed',last_error='room access no longer available',updated_at=now()
				 WHERE id=$1
			`,item.id)
			continue
		}
		if roomType=="dm" {
			var blocked bool
			_=s.db.QueryRow(ctx,`
				SELECT EXISTS(
					SELECT 1
					  FROM room_members other
					  JOIN blocks b ON (
					       (b.blocker_user_id=$2 AND b.blocked_user_id=other.user_id)
					    OR (b.blocker_user_id=other.user_id AND b.blocked_user_id=$2)
					  )
					 WHERE other.room_id=$1 AND other.user_id<>$2
				)
			`,item.roomID,item.userID).Scan(&blocked)
			if blocked {
				_,_=s.db.Exec(ctx,`
					UPDATE scheduled_room_messages
					   SET status='failed',last_error='direct messages are unavailable',updated_at=now()
					 WHERE id=$1
				`,item.id)
				continue
			}
		}

		attachment:=map[string]any{}
		_ = json.Unmarshal(item.attachment,&attachment)
		payload:=roomMessagePayloadV6{
			Body:item.body,
			Type:item.typ,
			Attachment:attachment,
			Spoiler:item.spoiler,
			ReplyToMessageID:item.replyID,
		}
		if err:=validateRoomMessagePayloadV6(payload); err!=nil {
			_,_=s.db.Exec(ctx,`
				UPDATE scheduled_room_messages
				   SET status='failed',last_error=$2,updated_at=now()
				 WHERE id=$1
			`,item.id,err.Error())
			continue
		}

		messageID,_,sendErr:=s.insertRoomMessageV6(
			ctx,item.roomID,item.userID,payload,nil,
		)
		if sendErr!=nil {
			_,_=s.db.Exec(ctx,`
				UPDATE scheduled_room_messages
				   SET status='failed',last_error=$2,updated_at=now()
				 WHERE id=$1
			`,item.id,sendErr.Error())
			continue
		}
		_,_=s.db.Exec(ctx,`
			UPDATE scheduled_room_messages
			   SET status='sent',sent_message_id=$2,last_error='',updated_at=now()
			 WHERE id=$1
		`,item.id,messageID)
	}
	return nil
}

func (s *Server) bulkDeleteRoomMessages(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	var body struct {
		MessageIDs []string `json:"messageIds"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	body.MessageIDs=uniqueNonEmptyStrings(body.MessageIDs,50)
	if len(body.MessageIDs)==0 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"messageIds are required"})
		return
	}

	_,role,roleErr:=s.roomMembershipRole(r.Context(),roomID,userID)
	canModerate:=roleErr==nil && (role=="owner" || role=="admin" || role=="moderator")

	tx,err:=s.db.Begin(r.Context())
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer tx.Rollback(r.Context())

	for _,id:=range body.MessageIDs {
		var authorID string
		var deleted bool
		err=tx.QueryRow(r.Context(),`
			SELECT author_user_id::text,(deleted_at IS NOT NULL)
			  FROM messages
			 WHERE id=$1 AND room_id=$2
		`,id,roomID).Scan(&authorID,&deleted)
		if err!=nil {
			writeJSON(w,http.StatusNotFound,map[string]string{"error":"one or more messages were not found"})
			return
		}
		if authorID!=userID && !canModerate {
			writeJSON(w,http.StatusForbidden,map[string]string{"error":"message delete permission required"})
			return
		}
		if deleted { continue }

		_,err=tx.Exec(r.Context(),`
			UPDATE messages SET
			  deleted_at=now(),
			  body='',
			  attachment='{}'::jsonb
			 WHERE id=$1 AND room_id=$2
		`,id,roomID)
		if err==nil {
			_,err=tx.Exec(r.Context(),`
				DELETE FROM room_message_pins
				 WHERE room_id=$1 AND message_id=$2
			`,roomID,id)
		}
		if err!=nil {
			writeError(w,http.StatusInternalServerError,err)
			return
		}
	}
	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	s.publishRoomMutation(r.Context(),roomID,map[string]any{
		"type":"messages.bulk_deleted",
		"roomId":roomID,
		"messageIds":body.MessageIDs,
	})
	writeJSON(w,http.StatusOK,map[string]any{
		"deleted":len(body.MessageIDs),
		"messageIds":body.MessageIDs,
	})
}

func (s *Server) bulkForwardRoomMessages(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	sourceRoomID:=chi.URLParam(r,"id")
	var body struct {
		TargetRoomID string `json:"targetRoomId"`
		MessageIDs []string `json:"messageIds"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	body.TargetRoomID=strings.TrimSpace(body.TargetRoomID)
	body.MessageIDs=uniqueNonEmptyStrings(body.MessageIDs,20)
	if body.TargetRoomID=="" || len(body.MessageIDs)==0 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"targetRoomId and messageIds are required"})
		return
	}

	var sourceAllowed bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
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
	`,sourceRoomID,userID).Scan(&sourceAllowed)
	if !sourceAllowed {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"source room access required"})
		return
	}

	var targetType,targetVisibility,targetRole string
	err:=s.db.QueryRow(r.Context(),`
		SELECT r.room_type,r.visibility,COALESCE(rm.role,'')
		  FROM rooms r
		  LEFT JOIN room_members rm
		    ON rm.room_id=r.id AND rm.user_id=$2
		 WHERE r.id=$1
	`,body.TargetRoomID,userID).Scan(&targetType,&targetVisibility,&targetRole)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"target room not found"})
		return
	}
	if (targetType=="group" || targetVisibility!="public") && targetRole=="" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"target room membership required"})
		return
	}

	ids:=make([]string,0,len(body.MessageIDs))
	for _,messageID:=range body.MessageIDs {
		var msgBody,msgType string
		var attachment []byte
		var spoiler bool
		var originalForwarded *string
		err=s.db.QueryRow(r.Context(),`
			SELECT body,message_type,attachment,spoiler,forwarded_from_message_id::text
			  FROM messages
			 WHERE id=$1 AND room_id=$2 AND deleted_at IS NULL
		`,messageID,sourceRoomID).Scan(
			&msgBody,&msgType,&attachment,&spoiler,&originalForwarded,
		)
		if err!=nil {
			writeJSON(w,http.StatusNotFound,map[string]string{"error":"one or more messages were not found"})
			return
		}
		attachmentMap:=map[string]any{}
		_ = json.Unmarshal(attachment,&attachmentMap)
		forwardedFrom:=messageID
		if originalForwarded!=nil && strings.TrimSpace(*originalForwarded)!="" {
			forwardedFrom=*originalForwarded
		}

		id,_,err:=s.insertRoomMessageV6(
			r.Context(),
			body.TargetRoomID,
			userID,
			roomMessagePayloadV6{
				Body:msgBody,
				Type:msgType,
				Attachment:attachmentMap,
				Spoiler:spoiler,
			},
			&forwardedFrom,
		)
		if err!=nil {
			writeError(w,http.StatusInternalServerError,err)
			return
		}
		ids=append(ids,id)
	}
	writeJSON(w,http.StatusCreated,map[string]any{
		"ids":ids,
		"count":len(ids),
	})
}

func uniqueNonEmptyStrings(values []string,max int) []string {
	if max<=0 { max=1 }
	seen:=map[string]struct{}{}
	out:=make([]string,0,len(values))
	for _,value:=range values {
		value=strings.TrimSpace(value)
		if value=="" { continue }
		if _,exists:=seen[value]; exists { continue }
		seen[value]=struct{}{}
		out=append(out,value)
		if len(out)>=max { break }
	}
	return out
}

func formatScheduledRoomMessageError(err error) string {
	if err==nil { return "" }
	return fmt.Sprintf("%v",err)
}
