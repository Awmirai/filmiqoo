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
	if strings.TrimSpace(roomID)=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"room id is required"})
		return
	}

	conn,err:=websocket.Accept(w,r,&websocket.AcceptOptions{OriginPatterns:[]string{"*"}})
	if err!=nil { return }
	defer conn.CloseNow()

	ctx,cancel:=context.WithCancel(r.Context())
	defer cancel()

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
			event["roomId"]=roomID
			event["type"]="client."+stringValue(event["type"],"event")
			raw,_:=json.Marshal(event)
			if err:=s.redis.Publish(ctx,"room:"+roomID,raw).Err(); err!=nil { errCh<-err; return }
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
