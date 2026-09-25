package server

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/go-chi/chi/v5"
)

func (s *Server) roomRealtime(w http.ResponseWriter,r *http.Request) {
	roomID:=chi.URLParam(r,"id")
	userID:=userIDFromContext(r.Context())
	if strings.TrimSpace(roomID)=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"room id is required"})
		return
	}

	var allowed bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1
			  FROM rooms rm
			 WHERE rm.id=$1
			   AND (
			     rm.visibility='public'
			     OR EXISTS(
			       SELECT 1 FROM room_members member
			        WHERE member.room_id=rm.id AND member.user_id=$2
			     )
			   )
		)
	`,roomID,userID).Scan(&allowed)
	if !allowed {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"room access required"})
		return
	}

	var displayName,username,avatar string
	var verified bool
	_=s.db.QueryRow(r.Context(),`
		SELECT display_name,username::text,avatar_url,verified
		  FROM profiles
		 WHERE user_id=$1
	`,userID).Scan(&displayName,&username,&avatar,&verified)

	_ = s.touchOnlinePresence(r.Context(),userID)

	slot,ok,slotErr:=s.acquireRealtimeSlot(r.Context(),userID)
	if slotErr!=nil {
		writeJSON(w,http.StatusServiceUnavailable,map[string]string{"error":"realtime capacity check unavailable"})
		return
	}
	if !ok {
		writeJSON(w,http.StatusTooManyRequests,map[string]string{"error":s.realtimeLimitMessage()})
		return
	}
	defer s.releaseRealtimeSlot(context.Background(),userID,slot)

	conn,err:=websocket.Accept(w,r,nil)
	if err!=nil { return }
	conn.SetReadLimit(16*1024)
	defer conn.CloseNow()

	ctx,cancel:=context.WithCancel(r.Context())
	defer cancel()

	refreshTicker:=time.NewTicker(60*time.Second)
	defer refreshTicker.Stop()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-refreshTicker.C:
				s.refreshRealtimeSlot(context.Background(),userID,slot)
			}
		}
	}()

	pubsub:=s.redis.Subscribe(ctx,"room:"+roomID)
	defer pubsub.Close()

	_ = conn.Write(ctx,websocket.MessageText,[]byte(`{"type":"connected"}`))

	var writeMu sync.Mutex
	writeJSONSocket:=func(data []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		writeCtx,cancelWrite:=context.WithTimeout(ctx,5*time.Second)
		defer cancelWrite()
		return conn.Write(writeCtx,websocket.MessageText,data)
	}

	publishTyping:=func(active bool) {
		payload:=map[string]any{
			"type":"typing.changed",
			"roomId":roomID,
			"active":active,
			"user":map[string]any{
				"id":userID,
				"displayName":displayName,
				"username":username,
				"avatarUrl":avatar,
				"verified":verified,
			},
		}
		raw,_:=json.Marshal(payload)
		_ = s.redis.Publish(context.Background(),"room:"+roomID,raw).Err()
	}
	defer publishTyping(false)

	errCh:=make(chan error,2)
	go func() {
		ch:=pubsub.Channel()
		for {
			select {
			case <-ctx.Done():
				errCh<-ctx.Err(); return
			case msg,ok:=<-ch:
				if !ok { errCh<-context.Canceled; return }
				if err:=writeJSONSocket([]byte(msg.Payload)); err!=nil { errCh<-err; return }
			}
		}
	}()

	go func() {
		for {
			typ,data,err:=conn.Read(ctx)
			if err!=nil { errCh<-err; return }
			if typ!=websocket.MessageText { continue }

			var event map[string]any
			if json.Unmarshal(data,&event)!=nil { continue }
			_ = s.touchOnlinePresence(ctx,userID)

			switch strings.ToLower(strings.TrimSpace(stringValue(event["type"],""))) {
			case "typing":
				active,_:=event["active"].(bool)
				publishTyping(active)
			case "presence","heartbeat":
				// Presence is refreshed above. No room event is necessary.
			default:
				// Do not rebroadcast arbitrary client-controlled event payloads.
			}
		}
	}()

	<-errCh
	cancel()
}

func stringValue(v any,fallback string) string {
	s,_:=v.(string)
	if strings.TrimSpace(s)=="" { return fallback }
	return s
}
