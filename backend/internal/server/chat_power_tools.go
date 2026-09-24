package server

import (
    "context"
    "encoding/json"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) roomMembershipRole(
    ctx context.Context,
    roomID string,
    userID string,
) (string,string,error) {
    var roomType,role string
    err:=s.db.QueryRow(ctx,`
        SELECT r.room_type,rm.role
          FROM rooms r
          JOIN room_members rm ON rm.room_id=r.id
         WHERE r.id=$1 AND rm.user_id=$2
    `,roomID,userID).Scan(&roomType,&role)
    return roomType,role,err
}

func (s *Server) editRoomMessage(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    roomID:=chi.URLParam(r,"id")
    messageID:=chi.URLParam(r,"messageID")

    var body struct {
        Body string `json:"body"`
        Spoiler *bool `json:"spoiler"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    body.Body=strings.TrimSpace(body.Body)
    if body.Body=="" || len([]rune(body.Body))>4000 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"message body must be 1-4000 characters"}); return
    }

    var authorID,typ string
    var deletedAt *time.Time
    err:=s.db.QueryRow(r.Context(),`
        SELECT author_user_id::text,message_type,deleted_at
          FROM messages
         WHERE id=$1 AND room_id=$2
    `,messageID,roomID).Scan(&authorID,&typ,&deletedAt)
    if err!=nil || deletedAt!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"message not found"}); return
    }
    if authorID!=userID {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"only the author can edit this message"}); return
    }
    if typ!="text" {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"only text messages can be edited"}); return
    }

    _,err=s.db.Exec(r.Context(),`
        UPDATE messages SET
          body=$3,
          spoiler=COALESCE($4,spoiler),
          edited_at=now()
         WHERE id=$1 AND room_id=$2
    `,messageID,roomID,body.Body,body.Spoiler)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    s.publishRoomMutation(r.Context(),roomID,map[string]any{
        "type":"message.edited",
        "roomId":roomID,
        "messageId":messageID,
        "body":body.Body,
    })
    writeJSON(w,http.StatusOK,map[string]any{
        "id":messageID,
        "body":body.Body,
        "edited":true,
    })
}

func (s *Server) deleteRoomMessage(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    roomID:=chi.URLParam(r,"id")
    messageID:=chi.URLParam(r,"messageID")

    var authorID string
    var alreadyDeleted bool
    err:=s.db.QueryRow(r.Context(),`
        SELECT author_user_id::text,(deleted_at IS NOT NULL)
          FROM messages
         WHERE id=$1 AND room_id=$2
    `,messageID,roomID).Scan(&authorID,&alreadyDeleted)
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"message not found"}); return
    }
    if alreadyDeleted {
        writeJSON(w,http.StatusOK,map[string]any{"deleted":true}); return
    }

    _,role,roleErr:=s.roomMembershipRole(r.Context(),roomID,userID)
    canModerate:=roleErr==nil && (role=="owner" || role=="admin" || role=="moderator")
    if authorID!=userID && !canModerate {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"message delete permission required"}); return
    }

    tx,err:=s.db.Begin(r.Context())
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer tx.Rollback(r.Context())

    _,err=tx.Exec(r.Context(),`
        UPDATE messages SET
          deleted_at=now(),
          body='',
          attachment='{}'::jsonb
         WHERE id=$1 AND room_id=$2
    `,messageID,roomID)
    if err==nil {
        _,err=tx.Exec(r.Context(),`
            DELETE FROM room_message_pins
             WHERE room_id=$1 AND message_id=$2
        `,roomID,messageID)
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    if err:=tx.Commit(r.Context()); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    s.publishRoomMutation(r.Context(),roomID,map[string]any{
        "type":"message.deleted",
        "roomId":roomID,
        "messageId":messageID,
    })
    writeJSON(w,http.StatusOK,map[string]any{"deleted":true})
}

func (s *Server) togglePinRoomMessage(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    roomID:=chi.URLParam(r,"id")
    messageID:=chi.URLParam(r,"messageID")

    roomType,role,err:=s.roomMembershipRole(r.Context(),roomID,userID)
    if err!=nil {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"room membership required"}); return
    }
    if roomType!="dm" && role!="owner" && role!="admin" && role!="moderator" {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"moderator permission required"}); return
    }

    var existsMessage bool
    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM messages
             WHERE id=$1 AND room_id=$2 AND deleted_at IS NULL
        )
    `,messageID,roomID).Scan(&existsMessage)
    if !existsMessage {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"message not found"}); return
    }

    var pinned bool
    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM room_message_pins
             WHERE room_id=$1 AND message_id=$2
        )
    `,roomID,messageID).Scan(&pinned)

    if pinned {
        _,err=s.db.Exec(r.Context(),`
            DELETE FROM room_message_pins
             WHERE room_id=$1 AND message_id=$2
        `,roomID,messageID)
    } else {
        _,err=s.db.Exec(r.Context(),`
            INSERT INTO room_message_pins (room_id,message_id,pinned_by_user_id)
            VALUES ($1,$2,$3)
            ON CONFLICT DO NOTHING
        `,roomID,messageID,userID)
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    s.publishRoomMutation(r.Context(),roomID,map[string]any{
        "type":"message.pin_changed",
        "roomId":roomID,
        "messageId":messageID,
        "pinned":!pinned,
    })
    writeJSON(w,http.StatusOK,map[string]any{"pinned":!pinned})
}

func (s *Server) pinnedRoomMessages(w http.ResponseWriter,r *http.Request) {
    roomID:=chi.URLParam(r,"id")
    items,err:=s.queryRoomMessages(
        r.Context(),
        `
        WHERE m.room_id=$1
          AND m.deleted_at IS NULL
          AND EXISTS(
            SELECT 1 FROM room_message_pins rp
             WHERE rp.room_id=m.room_id AND rp.message_id=m.id
          )
        ORDER BY (
            SELECT rp.pinned_at FROM room_message_pins rp
             WHERE rp.room_id=m.room_id AND rp.message_id=m.id
        ) DESC
        LIMIT 50
        `,
        roomID,
    )
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) searchRoomMessages(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    roomID:=chi.URLParam(r,"id")
    q:=strings.TrimSpace(r.URL.Query().Get("q"))
    if len([]rune(q))<2 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"query must be at least 2 characters"}); return
    }
    if len([]rune(q))>120 { q=string([]rune(q)[:120]) }

    var allowed bool
    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1
              FROM rooms r
             WHERE r.id=$1 AND (
               r.visibility='public' OR EXISTS(
                 SELECT 1 FROM room_members rm
                  WHERE rm.room_id=r.id AND rm.user_id=$2
               )
             )
        )
    `,roomID,userID).Scan(&allowed)
    if !allowed {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"room access required"}); return
    }

    items,err:=s.queryRoomMessages(
        r.Context(),
        `
        WHERE m.room_id=$1
          AND m.deleted_at IS NULL
          AND m.body ILIKE '%' || $2 || '%'
        ORDER BY m.created_at DESC
        LIMIT 100
        `,
        roomID,q,
    )
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) queryRoomMessages(
    ctx context.Context,
    suffix string,
    args ...any,
) ([]map[string]any,error) {
    rows,err:=s.db.Query(ctx,`
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
               ),'{}'::jsonb),
               m.edited_at,
               COALESCE((
                 SELECT COUNT(*)
                   FROM room_reads rr
                  WHERE rr.room_id=m.room_id
                    AND rr.user_id<>m.author_user_id
                    AND rr.last_read_at>=m.created_at
               ),0),
               EXISTS(
                 SELECT 1 FROM room_message_pins rp
                  WHERE rp.room_id=m.room_id AND rp.message_id=m.id
               ),
               m.forwarded_from_message_id::text,
               forwarded.body,
               forwarded.message_type,
               forwarded_author.display_name
          FROM messages m
          JOIN profiles p ON p.user_id=m.author_user_id
          LEFT JOIN messages reply ON reply.id=m.reply_to_message_id
          LEFT JOIN profiles reply_author ON reply_author.user_id=reply.author_user_id
          LEFT JOIN messages forwarded ON forwarded.id=m.forwarded_from_message_id
          LEFT JOIN profiles forwarded_author ON forwarded_author.user_id=forwarded.author_user_id
    `+suffix,args...)
    if err!=nil { return nil,err }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,body,typ,userID,username,displayName,avatar string
        var attachment,reactions []byte
        var spoiler,verified,pinned bool
        var created time.Time
        var editedAt *time.Time
        var seenBy int64
        var replyID,replyBody,replyAuthor *string
        var forwardedID,forwardedBody,forwardedType,forwardedAuthor *string
        if err:=rows.Scan(
            &id,&body,&typ,&attachment,&spoiler,&created,
            &userID,&username,&displayName,&avatar,&verified,
            &replyID,&replyBody,&replyAuthor,&reactions,
            &editedAt,&seenBy,&pinned,
            &forwardedID,&forwardedBody,&forwardedType,&forwardedAuthor,
        ); err!=nil { continue }

        var forwardedFrom any
        if forwardedID!=nil {
            forwardedFrom=map[string]any{
                "id":forwardedID,
                "body":forwardedBody,
                "type":forwardedType,
                "author":forwardedAuthor,
            }
        }

        items=append(items,map[string]any{
            "id":id,
            "body":body,
            "type":typ,
            "attachment":decodeJSONOrEmptyObject(attachment),
            "spoiler":spoiler,
            "createdAt":created,
            "editedAt":editedAt,
            "seenBy":seenBy,
            "pinned":pinned,
            "replyTo":map[string]any{
                "id":replyID,"body":replyBody,"author":replyAuthor,
            },
            "forwardedFrom":forwardedFrom,
            "reactions":decodeJSONOrEmptyObject(reactions),
            "author":map[string]any{
                "id":userID,"username":username,"displayName":displayName,
                "avatarUrl":avatar,"verified":verified,
            },
        })
    }
    return items,rows.Err()
}

func (s *Server) publishRoomMutation(ctx context.Context,roomID string,payload map[string]any) {
    raw,_:=json.Marshal(payload)
    _=s.redis.Publish(ctx,"room:"+roomID,raw).Err()
}
