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

func (s *Server) watchPartyRealtime(w http.ResponseWriter, r *http.Request) {
	partyID := strings.TrimSpace(chi.URLParam(r, "id"))
	userID := userIDFromContext(r.Context())
	if partyID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "watch party id is required"})
		return
	}

	var member bool
	_ = s.db.QueryRow(r.Context(), `
        SELECT EXISTS(
            SELECT 1 FROM watch_party_members
             WHERE watch_party_id=$1 AND user_id=$2
        )
    `, partyID, userID).Scan(&member)
	if !member {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "watch party access required"})
		return
	}

	slot, ok, slotErr := s.acquireRealtimeSlot(r.Context(), userID)
	if slotErr != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "realtime capacity check unavailable"})
		return
	}
	if !ok {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": s.realtimeLimitMessage()})
		return
	}
	defer s.releaseRealtimeSlot(context.Background(), userID, slot)

	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	conn.SetReadLimit(8 * 1024)
	defer conn.CloseNow()

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	refreshTicker := time.NewTicker(15 * time.Second)
	defer refreshTicker.Stop()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-refreshTicker.C:
				s.refreshRealtimeSlot(context.Background(), userID, slot)
				var allowed bool
				err := s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM watch_party_members wpm JOIN watch_parties wp ON wp.id=wpm.watch_party_id WHERE wp.id=$1 AND wpm.user_id=$2 AND wp.state NOT IN ('ended','cancelled'))`, partyID, userID).Scan(&allowed)
				if err != nil || !allowed {
					cancel()
					return
				}
				_, _ = s.db.Exec(ctx, `UPDATE watch_party_members SET last_seen_at=now() WHERE watch_party_id=$1 AND user_id=$2`, partyID, userID)
			}
		}
	}()

	pubsub := s.redis.Subscribe(ctx, "watchparty:"+partyID)
	defer pubsub.Close()
	subscriptionCtx, stopSubscription := context.WithTimeout(ctx, 5*time.Second)
	_, subscribeErr := pubsub.Receive(subscriptionCtx)
	stopSubscription()
	if subscribeErr != nil {
		return
	}

	var writeMu sync.Mutex
	writeSocket := func(data []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		writeCtx, cancelWrite := context.WithTimeout(ctx, 5*time.Second)
		defer cancelWrite()
		return conn.Write(writeCtx, websocket.MessageText, data)
	}
	if writeSocket([]byte(`{"type":"connected"}`)) != nil {
		return
	}
	// Every reconnect starts with an authoritative snapshot, even when no host command occurs.
	var state string
	var position, revision int64
	var playing bool
	var controller *string
	err = s.db.QueryRow(ctx, `SELECT state,playback_position_ms+CASE WHEN state='live' AND is_playing THEN GREATEST(0,(EXTRACT(EPOCH FROM now()-playback_updated_at)*1000)::bigint) ELSE 0 END,is_playing,playback_revision,playback_controller_user_id::text FROM watch_parties WHERE id=$1`, partyID).Scan(&state, &position, &playing, &revision, &controller)
	if err != nil {
		return
	}
	snapshot, _ := json.Marshal(map[string]any{"type": "watchparty.state", "watchPartyId": partyID, "state": state, "positionMs": position, "isPlaying": playing, "revision": revision, "controllerUserId": controller, "serverTime": time.Now().UTC()})
	if writeSocket(snapshot) != nil {
		return
	}

	errCh := make(chan error, 2)
	go func() {
		ch := pubsub.Channel()
		for {
			select {
			case <-ctx.Done():
				errCh <- ctx.Err()
				return
			case msg, ok := <-ch:
				if !ok {
					errCh <- context.Canceled
					return
				}
				if err := writeSocket([]byte(msg.Payload)); err != nil {
					errCh <- err
					return
				}
			}
		}
	}()

	go func() {
		for {
			_, _, err := conn.Read(ctx)
			if err != nil {
				errCh <- err
				return
			}
			_ = s.touchOnlinePresence(ctx, userID)
		}
	}()

	<-errCh
	cancel()
}
