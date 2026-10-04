package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Exercise the real PostgreSQL queries: mock SQL tests cannot detect ambiguous
// parameter types or references to episode columns which do not exist.
func TestCatalogAndPlaybackQueriesAgainstMigratedDatabase(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("set FILMIQOO_E2E=1 to run")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{db: db}
	insert := func(query string, args ...any) string {
		t.Helper()
		var id string
		if err := db.QueryRow(ctx, query, args...).Scan(&id); err != nil {
			t.Fatal(err)
		}
		return id
	}
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := db.Exec(ctx, query, args...); err != nil {
			t.Fatal(err)
		}
	}
	series := insert("INSERT INTO media_titles(kind,title,created_at) VALUES ('series','Regression series','2000-01-01') RETURNING id::text")
	defer db.Exec(context.Background(), "DELETE FROM media_titles WHERE id=$1", series)
	movie := insert("INSERT INTO media_titles(kind,title,created_at) VALUES ('movie','Regression movie','2020-01-01') RETURNING id::text")
	defer db.Exec(context.Background(), "DELETE FROM media_titles WHERE id=$1", movie)
	season := insert("INSERT INTO seasons(media_title_id,season_number) VALUES ($1,1) RETURNING id::text", series)
	episode := insert("INSERT INTO episodes(season_id,episode_number,name) VALUES ($1,2,'Second episode') RETURNING id::text", season)
	version := insert("INSERT INTO media_versions(episode_id,source_ref,stream_ready) VALUES ($1,'test-series',true) RETURNING id::text", episode)
	insert("INSERT INTO media_versions(media_title_id,source_ref,stream_ready,created_at) VALUES ($1,'test-movie',true,'2020-01-01') RETURNING id::text", movie)
	w := httptest.NewRecorder()
	s.catalogHome(w, httptest.NewRequest(http.MethodGet, "/v1/catalog/home", nil).WithContext(ctx))
	if w.Code != 200 {
		t.Fatalf("catalog: %d %s", w.Code, w.Body)
	}
	var catalog struct {
		Items []struct {
			ID      string `json:"id"`
			Version string `json:"mediaVersionId"`
			Ready   bool   `json:"streamReady"`
		}
	}
	if err := json.Unmarshal(w.Body.Bytes(), &catalog); err != nil {
		t.Fatal(err)
	}
	seriesIndex, movieIndex := -1, -1
	for i, item := range catalog.Items {
		if item.ID == series {
			seriesIndex = i
			if !item.Ready || item.Version != version {
				t.Fatalf("episode is missing from catalog: %+v", item)
			}
		}
		if item.ID == movie {
			movieIndex = i
		}
	}
	if seriesIndex < 0 || movieIndex < 0 || seriesIndex >= movieIndex {
		t.Fatalf("new episode must surface before older movie: %s", w.Body)
	}

	viewer := insert("INSERT INTO users(status) VALUES ('active') RETURNING id::text")
	defer db.Exec(context.Background(), "DELETE FROM users WHERE id=$1", viewer)
	creator := insert("INSERT INTO users(status) VALUES ('active') RETURNING id::text")
	defer db.Exec(context.Background(), "DELETE FROM users WHERE id=$1", creator)
	exec("INSERT INTO profiles(user_id,username,display_name) VALUES ($1,$2,'Regression creator')", creator, "regression-"+creator)
	for _, visitor := range []string{"", viewer} {
		allowed, exists, err := s.canViewUserSocialContent(ctx, visitor, creator)
		if err != nil || !allowed || !exists {
			t.Fatalf("public profile visitor=%q allowed=%v exists=%v err=%v", visitor, allowed, exists, err)
		}
		_, _, _, _, err = s.canViewChannelSocialContent(ctx, visitor, series)
		if err != nil {
			t.Fatalf("channel access query: %v", err)
		}
	}
	exec("INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2)", creator, viewer)
	allowed, exists, err := s.canViewUserSocialContent(ctx, viewer, creator)
	if err != nil || allowed || !exists {
		t.Fatalf("block must still apply: %v %v %v", allowed, exists, err)
	}
	exec("DELETE FROM blocks WHERE blocker_user_id=$1 AND blocked_user_id=$2", creator, viewer)
	exec("INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES ($1,$2)", viewer, creator)
	if err := s.setWatchingPresence(ctx, creator, "", version, 1000); err != nil {
		t.Fatal(err)
	}
	userCtx := context.WithValue(ctx, userKey, viewer)
	w = httptest.NewRecorder()
	s.followingWatchActivity(w, httptest.NewRequest("GET", "/", nil).WithContext(userCtx))
	if w.Code != 200 {
		t.Fatalf("watch activity: %d %s", w.Code, w.Body)
	}
	var activity map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &activity); err != nil {
		t.Fatal(err)
	}
	if items, ok := activity["items"].([]any); !ok || len(items) != 1 {
		t.Fatalf("missing watched episode: %s", w.Body)
	}
	route := chi.NewRouteContext()
	route.URLParams.Add("versionID", version)
	req := httptest.NewRequest("GET", "/?positionMs=2000&windowMs=15000", nil).WithContext(context.WithValue(userCtx, chi.RouteCtxKey, route))
	w = httptest.NewRecorder()
	s.playbackMoments(w, req)
	if w.Code != 200 {
		t.Fatalf("playback moments: %d %s", w.Code, w.Body)
	}
}
