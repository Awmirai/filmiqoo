package server

import (
	"context"
	"encoding/json"
	"github.com/jackc/pgx/v5/pgxpool"
	"net/http"
	"net/http/httptest"
	"os"
	"sort"
	"testing"
	"time"
)

// Opt-in only: a migrated disposable database, with newly created fixture rows.
func TestSavedLibraryPagesPreserveFullListsScopeAndMaturityPostgres(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated PostgreSQL")
	}
	if os.Getenv("DATABASE_URL") == "" {
		t.Fatal("DATABASE_URL required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	users, titles := []string{}, []string{}
	t.Cleanup(func() {
		c, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		for _, id := range users {
			if _, e := db.Exec(c, "DELETE FROM users WHERE id=$1", id); e != nil {
				t.Error(e)
			}
		}
		if len(titles) > 0 {
			if _, e := db.Exec(c, "DELETE FROM media_titles WHERE id::text=ANY($1::text[])", titles); e != nil {
				t.Error(e)
			}
		}
		db.Close()
	})
	insert := func(q string, args ...any) string {
		t.Helper()
		var id string
		if e := db.QueryRow(ctx, q, args...).Scan(&id); e != nil {
			t.Fatal(e)
		}
		return id
	}
	exec := func(q string, args ...any) {
		t.Helper()
		if _, e := db.Exec(ctx, q, args...); e != nil {
			t.Fatal(e)
		}
	}
	account := func() string {
		id := insert("INSERT INTO users(status)VALUES('active')RETURNING id::text")
		users = append(users, id)
		return id
	}
	alice, bob := account(), account()
	viewer := func(user, name, level string) string {
		return insert("INSERT INTO viewer_profiles(user_id,name,maturity_level,kids_mode)VALUES($1,$2,$3,$4)RETURNING id::text", user, name, level, level == "kids")
	}
	kids, sibling, foreign := viewer(alice, "Saved child", "kids"), viewer(alice, "Saved sibling", "all"), viewer(bob, "Foreign viewer", "all")
	rows, e := db.Query(ctx, `INSERT INTO media_titles(kind,title,audience_level) SELECT 'movie','Saved pagination fixture '||n,'kids' FROM generate_series(1,301) n RETURNING id::text`)
	if e != nil {
		t.Fatal(e)
	}
	for rows.Next() {
		var id string
		if e = rows.Scan(&id); e != nil {
			rows.Close()
			t.Fatal(e)
		}
		titles = append(titles, id)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		t.Fatal(e)
	}
	expected := append([]string(nil), titles...)
	sort.Strings(expected)
	if len(expected) != 301 {
		t.Fatalf("fixture size %d", len(expected))
	}
	adultTitle := insert("INSERT INTO media_titles(kind,title,audience_level)VALUES('movie','Maturity hidden saved fixture','adult')RETURNING id::text")
	bobTitle := insert("INSERT INTO media_titles(kind,title,audience_level)VALUES('movie','Foreign saved fixture','adult')RETURNING id::text")
	titles = append(titles, adultTitle, bobTitle)
	for _, table := range []string{"watchlist", "favorites"} {
		exec("INSERT INTO "+table+"(user_id,media_title_id,created_at)SELECT $1,id,'2020-01-01'::timestamptz FROM media_titles WHERE id::text=ANY($2::text[])", alice, expected)
		exec("INSERT INTO "+table+"(user_id,media_title_id)VALUES($1,$2)", bob, bobTitle)
	}
	for _, table := range []string{"viewer_watchlist", "viewer_favorites"} {
		exec("INSERT INTO "+table+"(viewer_profile_id,media_title_id,created_at)SELECT $1,id,'2020-01-01'::timestamptz FROM media_titles WHERE id::text=ANY($2::text[])", kids, expected)
		exec("INSERT INTO "+table+"(viewer_profile_id,media_title_id)VALUES($1,$2)", kids, adultTitle)
		exec("INSERT INTO "+table+"(viewer_profile_id,media_title_id)VALUES($1,$2)", foreign, bobTitle)
	}
	s := &Server{db: db}
	type envelope struct {
		Version int  `json:"libraryVersion"`
		Page    int  `json:"page"`
		More    bool `json:"hasMore"`
		Items   []struct {
			ID string `json:"id"`
		} `json:"items"`
	}
	call := func(fn http.HandlerFunc, user, view, query string) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest(http.MethodGet, "/list"+query, nil).WithContext(context.WithValue(ctx, userKey, user))
		if view != "" {
			r.Header.Set("X-Filmiqoo-Viewer-Profile", view)
		}
		w := httptest.NewRecorder()
		fn(w, r)
		return w
	}
	decode := func(w *httptest.ResponseRecorder) envelope {
		t.Helper()
		if w.Code != 200 {
			t.Fatalf("saved list: %d %s", w.Code, w.Body)
		}
		var v envelope
		if e := json.Unmarshal(w.Body.Bytes(), &v); e != nil {
			t.Fatal(e)
		}
		if v.Version != 1 || v.Items == nil {
			t.Fatalf("missing pagination/empty-array contract: %s", w.Body)
		}
		return v
	}
	for _, endpoint := range []struct {
		name string
		fn   http.HandlerFunc
		size int
	}{{"watchlist", s.watchlist, 300}, {"favorites", s.favorites, 200}} {
		for _, view := range []string{"", kids} {
			first := decode(call(endpoint.fn, alice, view, "?page=1"))
			second := decode(call(endpoint.fn, alice, view, "?page=2"))
			if first.Page != 1 || !first.More || len(first.Items) != endpoint.size || second.Page != 2 || second.More || len(second.Items) != 301-endpoint.size {
				t.Fatalf("%s pagination viewer %q: first %+v second %+v", endpoint.name, view, first, second)
			}
			ids := []string{}
			for _, item := range first.Items {
				ids = append(ids, item.ID)
			}
			for _, item := range second.Items {
				ids = append(ids, item.ID)
			}
			if len(ids) != len(expected) {
				t.Fatalf("%s incomplete total: %d", endpoint.name, len(ids))
			}
			seen := map[string]bool{}
			for i, id := range ids {
				if id != expected[i] || seen[id] {
					t.Fatalf("%s lost stable tie order/duplicated row at %d: %s expected %s", endpoint.name, i, id, expected[i])
				}
				seen[id] = true
			}
			legacy := decode(call(endpoint.fn, alice, view, ""))
			if legacy.Page != 1 || !legacy.More || len(legacy.Items) != endpoint.size {
				t.Fatalf("%s changed legacy first-page limit: %+v", endpoint.name, legacy)
			}
			exhausted := decode(call(endpoint.fn, alice, view, "?page=3"))
			if exhausted.Page != 3 || exhausted.More || len(exhausted.Items) != 0 {
				t.Fatalf("%s exhausted page: %+v", endpoint.name, exhausted)
			}
		}
		empty := decode(call(endpoint.fn, alice, sibling, "?page=1"))
		if len(empty.Items) != 0 || empty.More {
			t.Fatalf("%s sibling inherited another profile/account list: %+v", endpoint.name, empty)
		}
		for _, view := range []string{"", foreign} {
			other := decode(call(endpoint.fn, bob, view, "?page=1"))
			if other.More || len(other.Items) != 1 || other.Items[0].ID != bobTitle {
				t.Fatalf("%s foreign account/viewer scope: %+v", endpoint.name, other)
			}
		}
		forged := decode(call(endpoint.fn, alice, foreign, "?page=1"))
		for _, item := range forged.Items {
			if item.ID == bobTitle {
				t.Fatalf("%s forged viewer header exposed foreign list", endpoint.name)
			}
		}
		for _, query := range []string{"?page=0", "?page=501", "?page=invalid", "?page=", "?page=1&page=2"} {
			w := call(endpoint.fn, alice, "", query)
			if w.Code != 400 {
				t.Fatalf("%s accepted invalid page %s: %d %s", endpoint.name, query, w.Code, w.Body)
			}
		}
		unauth := httptest.NewRecorder()
		s.auth(endpoint.fn).ServeHTTP(unauth, httptest.NewRequest(http.MethodGet, "/v1/library/"+endpoint.name, nil))
		if unauth.Code != 401 {
			t.Fatalf("%s unauthenticated list: %d", endpoint.name, unauth.Code)
		}
	}
}
