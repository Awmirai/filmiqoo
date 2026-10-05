package server

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestDiscussionCursorRejectsMalformedInput(t *testing.T) {
	for _, raw := range []string{"%", "dG9tb3Jyb3d8bm90LXV1aWQ"} {
		if _, _, ok := discussionCursor(raw); ok {
			t.Fatal("accepted", raw)
		}
	}
	if _, _, ok := discussionCursor(""); !ok {
		t.Fatal("empty cursor")
	}
}

func TestTitleDiscussionPersistenceAndAuthorization(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated PostgreSQL")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{db: db}
	s.cfg.OpsSecret = "discussion-test-only-operations-secret"
	user := func() string {
		var id string
		if err := db.QueryRow(ctx, `INSERT INTO users(status) VALUES ('active') RETURNING id::text`).Scan(&id); err != nil {
			t.Fatal(err)
		}
		if _, err := db.Exec(ctx, `INSERT INTO profiles(user_id,username,display_name) VALUES ($1,$2,'Cinema fan')`, id, "discussion-"+id); err != nil {
			t.Fatal(err)
		}
		return id
	}
	alice, bob := user(), user()
	defer db.Exec(context.Background(), "DELETE FROM users WHERE id=$1 OR id=$2", alice, bob)
	call := func(handler http.HandlerFunc, who, scope, id, action, query, body string) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest("POST", "/test"+query, strings.NewReader(body))
		if who == "ops" {
			r.Header.Set("X-Filmiqoo-Ops-Secret", s.cfg.OpsSecret)
		}
		rc := chi.NewRouteContext()
		rc.URLParams.Add("scope", scope)
		rc.URLParams.Add("id", id)
		rc.URLParams.Add("action", action)
		c := context.WithValue(ctx, chi.RouteCtxKey, rc)
		c = context.WithValue(c, userKey, who)
		w := httptest.NewRecorder()
		handler(w, r.WithContext(c))
		return w
	}
	scope := "catalog:" + alice
	post := func(who, scope, client, parent, body, sticker, upload string) *httptest.ResponseRecorder {
		b, _ := json.Marshal(map[string]any{"clientId": client, "parentId": parent, "body": body, "sticker": sticker, "uploadId": upload})
		return call(s.addTitleComment, who, scope, "", "", "", string(b))
	}
	uuid := func(n int) string { return fmt.Sprintf("12345678-1234-4234-8234-%012d", n) }
	w := post(alice, scope, uuid(1), "", "دوستش داشتم", "popcorn", "")
	if w.Code != 201 {
		t.Fatalf("create %d %s", w.Code, w.Body)
	}
	var created map[string]string
	json.Unmarshal(w.Body.Bytes(), &created)
	id := created["id"]
	// The club is a projection of the same records, not a second comment store.
	w = call(s.titleComments, bob, "feed", "", "", "", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), id) || !strings.Contains(w.Body.String(), scope) {
		t.Fatalf("shared club identity %d %s", w.Code, w.Body)
	}
	if w = call(s.titleCommentAction, bob, "", id, "edit", "", `{"body":"not mine"}`); w.Code != 404 {
		t.Fatalf("other user edited %d %s", w.Code, w.Body)
	}
	if w = call(s.titleCommentAction, alice, "", id, "edit", "", `{"body":"ویرایش مالک","spoiler":true}`); w.Code != 200 {
		t.Fatalf("owner edit %d %s", w.Code, w.Body)
	}
	w = call(s.titleComments, alice, "feed", "", "", "", "")
	if !strings.Contains(w.Body.String(), "ویرایش مالک") || !strings.Contains(w.Body.String(), `"spoiler":true`) {
		t.Fatal("club edit did not update shared content", w.Body)
	}
	episodeScope := "series:90407:s1:e2"
	w = post(alice, episodeScope, uuid(501), "", "episode-specific", "", "")
	if w.Code != 201 {
		t.Fatalf("episode create %d %s", w.Code, w.Body)
	}
	var episodeComment map[string]string
	json.Unmarshal(w.Body.Bytes(), &episodeComment)
	w = call(s.titleComments, alice, "series:90407", "", "", "", "")
	if strings.Contains(w.Body.String(), episodeComment["id"]) {
		t.Fatal("episode leaked into series overview")
	}
	w = call(s.titleComments, alice, episodeScope, "", "", "", "")
	if !strings.Contains(w.Body.String(), episodeComment["id"]) {
		t.Fatal("episode discussion missing")
	}
	if w = post(bob, "series:90407", uuid(502), episodeComment["id"], "wrong scope", "", ""); w.Code != 404 {
		t.Fatal("cross episode reply accepted", w.Code)
	}
	w = post(alice, scope, uuid(1), "", "دوستش داشتم", "popcorn", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), id) {
		t.Fatalf("retry: %d %s", w.Code, w.Body)
	}
	if w = post(bob, "movie:999", uuid(2), id, "wrong title", "", ""); w.Code != 404 {
		t.Fatalf("cross title %d %s", w.Code, w.Body)
	}
	if w = post(bob, scope, uuid(2), "", "bad upload", "", uuid(900)); w.Code != 400 {
		t.Fatalf("upload ownership %d %s", w.Code, w.Body)
	}
	if w = post(bob, scope, uuid(3), id, "پاسخ", "", ""); w.Code != 201 {
		t.Fatalf("reply %d %s", w.Code, w.Body)
	}
	if w = call(s.titleCommentAction, bob, "", id, "remove", "", "{}"); w.Code != 404 {
		t.Fatalf("other user deleted %d", w.Code)
	}
	for i := 0; i < 2; i++ {
		if w = call(s.titleCommentAction, bob, "", id, "like", "", `{"liked":true}`); w.Code != 200 {
			t.Fatalf("like %d %s", w.Code, w.Body)
		}
	}
	w = call(s.titleComments, bob, scope, "", "", "", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"likes":1`) || !strings.Contains(w.Body.String(), `"replies":1`) {
		t.Fatalf("list %d %s", w.Code, w.Body)
	}
	if _, err = db.Exec(ctx, `INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2)`, bob, alice); err != nil {
		t.Fatal(err)
	}
	w = call(s.titleComments, bob, scope, "", "", "", "")
	if w.Code != 200 || strings.Contains(w.Body.String(), id) {
		t.Fatalf("blocked comment visible %s", w.Body)
	}
	w = call(s.titleComments, bob, "feed", "", "", "", "")
	if w.Code != 200 || strings.Contains(w.Body.String(), id) || strings.Contains(w.Body.String(), episodeComment["id"]) {
		t.Fatalf("block bypass through club %d %s", w.Code, w.Body)
	}
	if w = post(bob, scope, uuid(4), id, "blocked reply", "", ""); w.Code != 404 {
		t.Fatalf("block bypass %d %s", w.Code, w.Body)
	}
	for i := 10; i < 32; i++ {
		if w = post(alice, scope, uuid(i), "", "page", "", ""); w.Code != 201 {
			t.Fatalf("page seed %s", w.Body)
		}
	}
	w = call(s.titleComments, alice, scope, "", "", "", "")
	var page struct {
		Items []struct {
			ID string `json:"id"`
		}
		Next string `json:"nextCursor"`
	}
	json.Unmarshal(w.Body.Bytes(), &page)
	if len(page.Items) != 20 || page.Next == "" {
		t.Fatalf("page boundary %s", w.Body)
	}
	seen := map[string]bool{}
	for _, item := range page.Items {
		seen[item.ID] = true
	}
	w = call(s.titleComments, alice, scope, "", "", "?cursor="+page.Next, "")
	json.Unmarshal(w.Body.Bytes(), &page)
	for _, item := range page.Items {
		if seen[item.ID] {
			t.Fatal("duplicate across pages")
		}
	}
	if w = call(s.titleCommentAction, alice, "", id, "remove", "", "{}"); w.Code != 200 {
		t.Fatalf("owner delete %d %s", w.Code, w.Body)
	}
	var remaining int
	if err = db.QueryRow(ctx, "SELECT count(*) FROM title_comments WHERE parent_id=$1", id).Scan(&remaining); err != nil || remaining != 1 {
		t.Fatal("replies lost", remaining, err)
	}
	// Reports flow to the existing operations queue; only authenticated operations
	// may remove content, and removal retains replies and creates an audit record.
	w = post(alice, scope, uuid(100), "", "moderation target", "", "")
	json.Unmarshal(w.Body.Bytes(), &created)
	target := created["id"]
	w = call(s.submitReport, bob, "", "", "", "", fmt.Sprintf(`{"targetType":"discussion","targetId":"%s","reason":"harassment"}`, target))
	if w.Code != 201 {
		t.Fatalf("report %d %s", w.Code, w.Body)
	}
	var report struct {
		ID string `json:"id"`
	}
	json.Unmarshal(w.Body.Bytes(), &report)
	if w = call(s.opsResolveModeration, alice, "", report.ID, "", "", `{"status":"resolved","note":"reviewed","removeContent":true}`); w.Code != 401 {
		t.Fatal("unprivileged moderation", w.Code)
	}
	if w = call(s.opsResolveModeration, "ops", "", report.ID, "", "", `{"status":"resolved","note":"reviewed","removeContent":true}`); w.Code != 200 {
		t.Fatalf("moderation %d %s", w.Code, w.Body)
	}
	var removed bool
	if err = db.QueryRow(ctx, "SELECT deleted FROM title_comments WHERE id=$1", target).Scan(&removed); err != nil || !removed {
		t.Fatal("moderation did not remove content", err)
	}
	defer db.Exec(context.Background(), "DELETE FROM moderation_actions WHERE target_type='discussion' AND target_id=$1", target)
	defer db.Exec(context.Background(), "DELETE FROM reports WHERE target_type='discussion' AND target_id=$1", target)
}

func TestDiscussionScopeIncludesCatalogEpisodes(t *testing.T) {
	for _, scope := range []string{"movie:77", "series:100", "series:100:s0:e2", "catalog:12345678-1234-4234-8234-000000000001:s1:e2"} {
		if !discussionScope.MatchString(scope) {
			t.Fatal("valid scope rejected", scope)
		}
	}
	for _, scope := range []string{"feed", "series:0", "series:100:s1:e0", "movie:77:s1:e2", "series:100:s1:e2:extra", "catalog:------------------------------------"} {
		if discussionScope.MatchString(scope) {
			t.Fatal("invalid mutation scope accepted", scope)
		}
	}
}
