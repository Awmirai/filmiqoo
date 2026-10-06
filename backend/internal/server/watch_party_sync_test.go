package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

// Uses two persisted accounts and real PostgreSQL/Redis/websockets, never production data.
func TestWatchPartyTwoUsersSyncInvitesReadinessAndReconnect(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated PostgreSQL and Redis")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	addr := os.Getenv("REDIS_ADDR")
	if addr == "" {
		addr = "localhost:6379"
	}
	cache := redis.NewClient(&redis.Options{Addr: addr})
	defer cache.Close()
	if err = cache.Ping(ctx).Err(); err != nil {
		t.Fatal(err)
	}
	s := &Server{db: db, redis: cache}
	s.cfg.RealtimeConnectionsPerUser = 8
	insert := func(query string, args ...any) string {
		t.Helper()
		var id string
		if err := db.QueryRow(ctx, query, args...).Scan(&id); err != nil {
			t.Fatal(err)
		}
		return id
	}
	account := func(name string) string {
		id := insert(`INSERT INTO users(status) VALUES ('active') RETURNING id::text`)
		if _, err = db.Exec(ctx, `INSERT INTO profiles(user_id,username,display_name) VALUES ($1,$2,$3)`, id, "party-"+id, name); err != nil {
			t.Fatal(err)
		}
		return id
	}
	alice, bob := account("Alice"), account("Bob")
	defer db.Exec(context.Background(), `DELETE FROM users WHERE id=$1 OR id=$2`, alice, bob)
	movie := insert(`INSERT INTO media_titles(kind,title,original_title,year) VALUES ('movie','Party test','Party test',2026) RETURNING id::text`)
	defer db.Exec(context.Background(), `DELETE FROM media_titles WHERE id=$1`, movie)
	insert(`INSERT INTO media_versions(media_title_id,source_ref,stream_ready,duration_ms) VALUES ($1,'test-only',true,120000) RETURNING id::text`, movie)
	call := func(fn http.HandlerFunc, who, id, target, body string) *httptest.ResponseRecorder {
		t.Helper()
		request := httptest.NewRequest("POST", "/test", strings.NewReader(body))
		rc := chi.NewRouteContext()
		rc.URLParams.Add("id", id)
		rc.URLParams.Add("userID", target)
		request = request.WithContext(context.WithValue(context.WithValue(ctx, chi.RouteCtxKey, rc), userKey, who))
		response := httptest.NewRecorder()
		fn(response, request)
		return response
	}
	expect := func(w *httptest.ResponseRecorder, status int) map[string]any {
		t.Helper()
		if w.Code != status {
			t.Fatalf("got %d want %d: %s", w.Code, status, w.Body)
		}
		var body map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		return body
	}
	created := expect(call(s.createWatchParty, alice, "", "", fmt.Sprintf(`{"mediaTitleId":%q,"title":"Friends","visibility":"invite"}`, movie)), 201)
	party := created["id"].(string)
	room := created["roomId"].(string)
	invite := created["inviteCode"].(string)
	expect(call(s.roomMessages, bob, room, "", ""), 403)
	expect(call(s.sendRoomMessage, bob, room, "", `{"body":"outsider must not write","type":"text"}`), 403)
	expect(call(s.recentWatchPartyReactions, bob, party, "", ""), 403)
	expect(call(s.joinWatchParty, bob, party, "", `{"inviteCode":"wrong"}`), 403)
	expect(call(s.joinWatchParty, bob, party, "", fmt.Sprintf(`{"inviteCode":%q}`, invite)), 200)
	expect(call(s.joinWatchParty, bob, party, "", `{}`), 200)
	lobby := expect(call(s.watchPartyLobby, alice, party, "", ""), 200)
	if lobby["participantCount"] != float64(2) || lobby["onlineCount"] != float64(2) {
		t.Fatal("duplicate join or incorrect presence", lobby)
	}
	expect(call(s.updateWatchPartyState, bob, party, "", `{"positionMs":1,"isPlaying":true}`), 403)
	expect(call(s.updateWatchPartyMemberRole, bob, party, alice, `{"role":"viewer"}`), 403)
	expect(call(s.updateWatchPartyMemberRole, alice, party, alice, `{"role":"viewer"}`), 409)
	expect(call(s.reactWatchParty, bob, party, "", `{"emoji":"🔥"}`), 201)
	if result := expect(call(s.recentWatchPartyReactions, alice, party, "", ""), 200); !strings.Contains(fmt.Sprint(result), "🔥") {
		t.Fatal("other member cannot see reaction", result)
	}
	expect(call(s.sendRoomMessage, bob, room, "", `{"body":"همزمان تماشا می‌کنیم","type":"text"}`), 201)
	if result := expect(call(s.roomMessages, alice, room, "", ""), 200); !strings.Contains(fmt.Sprint(result), "همزمان تماشا می‌کنیم") {
		t.Fatal("other member cannot read message", result)
	}

	router := chi.NewRouter()
	router.Get("/live/{id}", func(w http.ResponseWriter, r *http.Request) {
		who := r.URL.Query().Get("testUser")
		if who != alice && who != bob {
			http.Error(w, "test account required", 403)
			return
		}
		s.watchPartyRealtime(w, r.WithContext(context.WithValue(r.Context(), userKey, who)))
	})
	ts := httptest.NewServer(router)
	defer ts.Close()
	connect := func(who string) *websocket.Conn {
		t.Helper()
		conn, _, err := websocket.Dial(ctx, strings.Replace(ts.URL, "http://", "ws://", 1)+"/live/"+party+"?testUser="+who, nil)
		if err != nil {
			t.Fatal(err)
		}
		return conn
	}
	readState := func(conn *websocket.Conn) map[string]any {
		t.Helper()
		readCtx, stop := context.WithTimeout(ctx, 5*time.Second)
		defer stop()
		for {
			_, raw, err := conn.Read(readCtx)
			if err != nil {
				t.Fatal(err)
			}
			var event map[string]any
			if err = json.Unmarshal(raw, &event); err != nil {
				t.Fatal(err)
			}
			if event["type"] == "watchparty.state" {
				return event
			}
		}
	}
	aliceSocket, bobSocket := connect(alice), connect(bob)
	defer aliceSocket.CloseNow()
	defer bobSocket.CloseNow()
	if readState(aliceSocket)["revision"] != float64(0) || readState(bobSocket)["revision"] != float64(0) {
		t.Fatal("missing initial snapshot")
	}
	event := expect(call(s.updateWatchPartyState, alice, party, "", `{"positionMs":10000,"isPlaying":true,"state":"live","expectedRevision":0}`), 200)
	if event["revision"] != float64(1) || event["controllerUserId"] != alice {
		t.Fatal("missing authoritative controller", event)
	}
	for _, conn := range []*websocket.Conn{aliceSocket, bobSocket} {
		state := readState(conn)
		if state["positionMs"] != float64(10000) || state["isPlaying"] != true {
			t.Fatal("users did not receive same play state", state)
		}
	}
	// A late join/reconnect receives projected elapsed time, rather than the old stored timestamp.
	if _, err = db.Exec(ctx, `UPDATE watch_parties SET playback_updated_at=now()-interval '5 seconds' WHERE id=$1`, party); err != nil {
		t.Fatal(err)
	}
	detail := expect(call(s.watchPartyDetail, bob, party, "", ""), 200)
	if position := detail["positionMs"].(float64); position < 15000 || position > 17000 {
		t.Fatal("late join rewound", detail)
	}
	reconnected := connect(bob)
	defer reconnected.CloseNow()
	if position := readState(reconnected)["positionMs"].(float64); position < 15000 || position > 18000 {
		t.Fatal("reconnect did not receive current position", position)
	}
	expect(call(s.updateWatchPartyMemberRole, alice, party, bob, `{"role":"cohost"}`), 200)
	expect(call(s.updateWatchPartyState, bob, party, "", `{"positionMs":45000,"isPlaying":false,"expectedRevision":1}`), 200)
	// An old host heartbeat cannot overwrite a cohost pause/seek.
	expect(call(s.updateWatchPartyState, alice, party, "", `{"positionMs":12000,"isPlaying":true,"expectedRevision":1}`), 409)
	detail = expect(call(s.watchPartyDetail, alice, party, "", ""), 200)
	if detail["positionMs"] != float64(45000) || detail["isPlaying"] != false || detail["controllerUserId"] != bob {
		t.Fatal("controller pause lost", detail)
	}
	expect(call(s.updateWatchPartyState, alice, party, "", `{"positionMs":46000,"isPlaying":true,"state":"ended","expectedRevision":2}`), 200)
	detail = expect(call(s.watchPartyDetail, bob, party, "", ""), 200)
	if detail["isPlaying"] != false {
		t.Fatal("ended party keeps playing", detail)
	}
	expect(call(s.updateWatchPartyState, alice, party, "", `{"positionMs":0,"isPlaying":true}`), 409)
	expect(call(s.joinWatchParty, bob, party, "", `{}`), 404)

	scheduled := expect(call(s.createWatchParty, alice, "", "", fmt.Sprintf(`{"mediaTitleId":%q,"title":"Later","visibility":"public","scheduledAt":%q}`, movie, time.Now().Add(time.Hour).UTC().Format(time.RFC3339))), 201)["id"].(string)
	expect(call(s.joinWatchParty, bob, scheduled, "", `{}`), 200)
	expect(call(s.setWatchPartyReadyCheck, alice, scheduled, "", `{"enabled":true}`), 200)
	expect(call(s.updateWatchPartyState, alice, scheduled, "", `{"positionMs":0,"isPlaying":true}`), 409)
	expect(call(s.toggleWatchPartyReady, bob, scheduled, "", `{}`), 200)
	expect(call(s.updateWatchPartyState, alice, scheduled, "", `{"positionMs":0,"isPlaying":true}`), 200)
	expect(call(s.leaveWatchParty, bob, scheduled, "", `{}`), 200)
	expect(call(s.recentWatchPartyReactions, bob, scheduled, "", ""), 403)
	expect(call(s.leaveWatchParty, alice, scheduled, "", `{}`), 409)
	// A blocked host cannot be reached by a public join path either.
	if _, err = db.Exec(ctx, `INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2)`, bob, alice); err != nil {
		t.Fatal(err)
	}
	expect(call(s.joinWatchParty, bob, scheduled, "", `{}`), 403)
	if result := expect(call(s.watchParties, bob, "", "", ""), 200); strings.Contains(fmt.Sprint(result), scheduled) {
		t.Fatal("blocked host leaked through public discovery", result)
	}
}
