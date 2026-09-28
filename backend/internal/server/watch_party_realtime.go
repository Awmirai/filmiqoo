package server

import (
    "context"
    "net/http"
    "strings"
    "sync"
    "time"

    "github.com/coder/websocket"
    "github.com/go-chi/chi/v5"
)

func (s *Server) watchPartyRealtime(w http.ResponseWriter,r *http.Request) {
    partyID:=strings.TrimSpace(chi.URLParam(r,"id"))
    userID:=userIDFromContext(r.Context())
    if partyID=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"watch party id is required"})
        return
    }

    var member bool
    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM watch_party_members
             WHERE watch_party_id=$1 AND user_id=$2
        )
    `,partyID,userID).Scan(&member)
    if !member {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"watch party access required"})
        return
    }

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
    conn.SetReadLimit(8*1024)
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

    pubsub:=s.redis.Subscribe(ctx,"watchparty:"+partyID)
    defer pubsub.Close()

    _=conn.Write(ctx,websocket.MessageText,[]byte(`{"type":"connected"}`))

    var writeMu sync.Mutex
    writeSocket:=func(data []byte) error {
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
                errCh<-ctx.Err()
                return
            case msg,ok:=<-ch:
                if !ok {
                    errCh<-context.Canceled
                    return
                }
                if err:=writeSocket([]byte(msg.Payload)); err!=nil {
                    errCh<-err
                    return
                }
            }
        }
    }()

    go func() {
        for {
            _,_,err:=conn.Read(ctx)
            if err!=nil {
                errCh<-err
                return
            }
            _=s.touchOnlinePresence(ctx,userID)
        }
    }()

    <-errCh
    cancel()
}
