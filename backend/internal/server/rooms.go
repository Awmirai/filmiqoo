package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) roomsList(w http.ResponseWriter,r *http.Request) {
	rows,err:=s.db.Query(r.Context(),`
		SELECT rm.id::text,rm.name,rm.topic,rm.room_type,rm.visibility,rm.member_count,
		       mt.id::text,mt.title,mt.poster_url
		  FROM rooms rm
		  LEFT JOIN media_titles mt ON mt.id=rm.media_title_id
		 WHERE rm.visibility='public'
		 ORDER BY rm.member_count DESC,rm.created_at DESC
		 LIMIT 100
	`)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,name,topic,typ,visibility string
		var members int64
		var mediaID,title,poster *string
		if err:=rows.Scan(&id,&name,&topic,&typ,&visibility,&members,&mediaID,&title,&poster); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"name":name,"topic":topic,"type":typ,"visibility":visibility,"members":members,
			"media":map[string]any{"id":mediaID,"title":title,"posterUrl":poster},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) roomMessages(w http.ResponseWriter,r *http.Request) {
	roomID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT m.id::text,m.body,m.message_type,m.attachment,m.spoiler,m.created_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       m.reply_to_message_id::text,reply.body,reply_author.display_name,
		       COALESCE((
		         SELECT jsonb_object_agg(rx.reaction,rx.cnt)
		           FROM (
		             SELECT reaction,COUNT(*) AS cnt
		               FROM message_reactions
		              WHERE message_id=m.id
		              GROUP BY reaction
		           ) rx
		       ),'{}'::jsonb)
		  FROM messages m
		  JOIN profiles p ON p.user_id=m.author_user_id
		  LEFT JOIN messages reply ON reply.id=m.reply_to_message_id
		  LEFT JOIN profiles reply_author ON reply_author.user_id=reply.author_user_id
		 WHERE m.room_id=$1 AND m.deleted_at IS NULL
		 ORDER BY m.created_at DESC
		 LIMIT 100
	`,roomID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,body,typ,userID,username,displayName,avatar string
		var attachment,reactions []byte
		var spoiler,verified bool
		var created time.Time
		var replyID,replyBody,replyAuthor *string
		if err:=rows.Scan(
			&id,&body,&typ,&attachment,&spoiler,&created,
			&userID,&username,&displayName,&avatar,&verified,
			&replyID,&replyBody,&replyAuthor,&reactions,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"body":body,"type":typ,"attachment":decodeJSONOrEmptyObject(attachment),
			"spoiler":spoiler,"createdAt":created,
			"replyTo":map[string]any{"id":replyID,"body":replyBody,"author":replyAuthor},
			"reactions":decodeJSONOrEmptyObject(reactions),
			"author":map[string]any{"id":userID,"username":username,"displayName":displayName,"avatarUrl":avatar,"verified":verified},
		})
	}
	// Reverse so clients receive oldest -> newest.
	for i,j:=0,len(items)-1;i<j;i,j=i+1,j-1 { items[i],items[j]=items[j],items[i] }
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) sendRoomMessage(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	var body struct {
		Body string `json:"body"`
		Type string `json:"type"`
		Spoiler bool `json:"spoiler"`
		Attachment map[string]any `json:"attachment"`
		ReplyToMessageID *string `json:"replyToMessageId"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	body.Body=strings.TrimSpace(body.Body)
	if body.Type=="" { body.Type="text" }
	switch body.Type {
	case "text","image","video","voice","reel","movie","episode":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"unsupported message type"}); return
	}
	if body.Type=="text" && body.Body=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"message is empty"}); return
	}
	if len([]rune(body.Body))>4000 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"message is too long"}); return
	}
	attachment,_:=json.Marshal(body.Attachment)
	var id string
	var created time.Time
	err:=s.db.QueryRow(r.Context(),`
		INSERT INTO messages (
			room_id,author_user_id,reply_to_message_id,body,message_type,attachment,spoiler
		) VALUES ($1,$2,$3,$4,$5,$6,$7)
		RETURNING id::text,created_at
	`,roomID,userID,body.ReplyToMessageID,body.Body,body.Type,attachment,body.Spoiler).Scan(&id,&created)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	payload:=map[string]any{
		"type":"message.created","roomId":roomID,"message":map[string]any{
			"id":id,"body":body.Body,"messageType":body.Type,"attachment":body.Attachment,
			"spoiler":body.Spoiler,"authorUserId":userID,"createdAt":created,
		},
	}
	raw,_:=json.Marshal(payload)
	_ = s.redis.Publish(r.Context(),"room:"+roomID,raw).Err()

	var roomType string
	if s.db.QueryRow(r.Context(),"SELECT room_type FROM rooms WHERE id=$1",roomID).Scan(&roomType)==nil && roomType=="dm" {
		preview:=body.Body
		if len([]rune(preview))>120 { preview=string([]rune(preview)[:120])+"…" }
		_,_=s.db.Exec(r.Context(),`
			INSERT INTO notifications (
				user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
			)
			SELECT member.user_id,$2,'dm_message','room',$1,'پیام جدید', $3
			  FROM room_members member
			 WHERE member.room_id=$1 AND member.user_id<>$2
		`,roomID,userID,preview)
	}

	writeJSON(w,http.StatusCreated,payload["message"])
}

func (s *Server) createRoom(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body struct {
		Name string `json:"name"`
		Topic string `json:"topic"`
		Type string `json:"type"`
		Visibility string `json:"visibility"`
		ChannelID *string `json:"channelId"`
		MediaTitleID *string `json:"mediaTitleId"`
		EpisodeID *string `json:"episodeId"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	body.Name=strings.TrimSpace(body.Name)
	if len([]rune(body.Name))<2 || len([]rune(body.Name))>100 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"room name must be 2-100 characters"}); return
	}
	if body.Type=="" { body.Type="group" }
	if body.Visibility=="" { body.Visibility="public" }

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())
	var id string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO rooms (owner_user_id,channel_id,media_title_id,episode_id,name,topic,room_type,visibility,member_count)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,1)
		RETURNING id::text
	`,userID,body.ChannelID,body.MediaTitleID,body.EpisodeID,body.Name,body.Topic,body.Type,body.Visibility).Scan(&id)
	if err!=nil { writeError(w,http.StatusBadRequest,err); return }
	_,err=tx.Exec(r.Context(),"INSERT INTO room_members (room_id,user_id,role) VALUES ($1,$2,'owner')",id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusCreated,map[string]any{"id":id})
}

func decodeJSONOrEmptyObject(raw []byte) any {
	if len(raw)==0 { return map[string]any{} }
	var value any
	if err:=json.Unmarshal(raw,&value); err!=nil { return map[string]any{} }
	return value
}


func (s *Server) toggleMessageReaction(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	roomID:=chi.URLParam(r,"id")
	messageID:=chi.URLParam(r,"messageID")
	var body struct {
		Reaction string `json:"reaction"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Reaction=strings.TrimSpace(body.Reaction)
	if body.Reaction=="" || len([]rune(body.Reaction))>16 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid reaction"}); return
	}

	var belongs bool
	_=s.db.QueryRow(r.Context(),
		"SELECT EXISTS(SELECT 1 FROM messages WHERE id=$1 AND room_id=$2 AND deleted_at IS NULL)",
		messageID,roomID).Scan(&belongs)
	if !belongs {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"message not found"}); return
	}

	var exists bool
	_=s.db.QueryRow(r.Context(),
		"SELECT EXISTS(SELECT 1 FROM message_reactions WHERE message_id=$1 AND user_id=$2 AND reaction=$3)",
		messageID,userID,body.Reaction).Scan(&exists)

	var err error
	if exists {
		_,err=s.db.Exec(r.Context(),
			"DELETE FROM message_reactions WHERE message_id=$1 AND user_id=$2 AND reaction=$3",
			messageID,userID,body.Reaction)
	} else {
		_,err=s.db.Exec(r.Context(),
			"INSERT INTO message_reactions (message_id,user_id,reaction) VALUES ($1,$2,$3)",
			messageID,userID,body.Reaction)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{"active":!exists,"reaction":body.Reaction})
}
