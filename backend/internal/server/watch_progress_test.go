package server

import (
	"bytes"
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

func TestContinueWatchingE2E(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("set FILMIQOO_E2E=1 with a migrated PostgreSQL database")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{db: db}
	insertID := func(query string, args ...any) string {
		t.Helper()
		var id string
		if err := db.QueryRow(ctx, query+" RETURNING id::text", args...).Scan(&id); err != nil {
			t.Fatal(err)
		}
		return id
	}
	user := insertID("INSERT INTO users (status) VALUES ('active')")
	defer db.Exec(context.Background(), "DELETE FROM users WHERE id=$1", user)
	viewerA := insertID("INSERT INTO viewer_profiles (user_id,name) VALUES ($1,'A')", user)
	viewerB := insertID("INSERT INTO viewer_profiles (user_id,name) VALUES ($1,'B')", user)
	movie := insertID("INSERT INTO media_titles (kind,title,audience_level) VALUES ('movie','Continue test','kids')")
	defer db.Exec(context.Background(), "DELETE FROM media_titles WHERE id=$1", movie)
	series := insertID("INSERT INTO media_titles (kind,title,audience_level) VALUES ('series','Series test','kids')")
	defer db.Exec(context.Background(), "DELETE FROM media_titles WHERE id=$1", series)
	season := insertID("INSERT INTO seasons (media_title_id,season_number) VALUES ($1,1)", series)
	episode1 := insertID("INSERT INTO episodes (season_id,episode_number) VALUES ($1,1)", season)
	episode2 := insertID("INSERT INTO episodes (season_id,episode_number) VALUES ($1,2)", season)
	version := func(titleID, episodeID any, quality string) string {
		return insertID(`INSERT INTO media_versions
			(media_title_id,episode_id,source_ref,quality_label,stream_ready)
			VALUES ($1,$2,gen_random_uuid()::text,$3,true)`, titleID, episodeID, quality)
	}
	movieLow := version(movie, nil, "720p")
	movieHigh := version(movie, nil, "1080p")
	episodeLow := version(nil, episode1, "720p")
	episodeHigh := version(nil, episode1, "1080p")
	episodeNext := version(nil, episode2, "1080p")
	progress := func(viewer, versionID string, position int64, completed bool, age string) {
		t.Helper()
		var err error
		if viewer == "" {
			_, err = db.Exec(ctx, `INSERT INTO watch_progress
				(user_id,media_version_id,position_ms,duration_ms,completed,updated_at)
				VALUES ($1,$2,$3,1000000,$4,now()-$5::interval)
				ON CONFLICT (user_id,media_version_id) DO UPDATE SET
				position_ms=EXCLUDED.position_ms,completed=EXCLUDED.completed,updated_at=EXCLUDED.updated_at`,
				user, versionID, position, completed, age)
		} else {
			_, err = db.Exec(ctx, `INSERT INTO viewer_watch_progress
				(viewer_profile_id,media_version_id,position_ms,duration_ms,completed,updated_at)
				VALUES ($1,$2,$3,1000000,$4,now()-$5::interval)
				ON CONFLICT (viewer_profile_id,media_version_id) DO UPDATE SET
				position_ms=EXCLUDED.position_ms,completed=EXCLUDED.completed,updated_at=EXCLUDED.updated_at`,
				viewer, versionID, position, completed, age)
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	request := func(t *testing.T, viewer, versionID string, handler http.HandlerFunc) map[string]any {
		t.Helper()
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.Header.Set("X-Filmiqoo-Viewer-Profile", viewer)
		route := chi.NewRouteContext()
		route.URLParams.Add("versionID", versionID)
		r = r.WithContext(context.WithValue(ctx, userKey, user))
		r = r.WithContext(context.WithValue(r.Context(), chi.RouteCtxKey, route))
		w := httptest.NewRecorder()
		handler(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
		}
		var result map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	items := func(t *testing.T, viewer string) []any {
		return request(t, viewer, "", s.continueWatching)["items"].([]any)
	}
	assertResume := func(t *testing.T, viewer, versionID string, want int64) {
		t.Helper()
		got := request(t, viewer, versionID, s.playbackContext)["resumePositionMs"].(float64)
		if got != float64(want) {
			t.Fatalf("viewer=%q version=%s resume=%v want=%d", viewer, versionID, got, want)
		}
	}

	progress("", movieLow, 120000, false, "1 minute")
	progress(viewerA, movieLow, 300000, false, "2 minutes")
	progress(viewerA, movieHigh, 400000, false, "1 minute")
	progress(viewerB, movieLow, 600000, false, "1 minute")
	progress(viewerA, episodeLow, 200000, false, "3 minutes")
	progress(viewerA, episodeHigh, 350000, false, "2 minutes")
	progress(viewerA, episodeNext, 500000, false, "1 minute")

	t.Run("resume isolates profiles and supports legacy accounts", func(t *testing.T) {
		assertResume(t, "", movieHigh, 120000)
		assertResume(t, viewerA, movieLow, 400000)
		assertResume(t, viewerB, movieHigh, 600000)
		assertResume(t, viewerA, episodeLow, 350000)
		assertResume(t, viewerA, episodeNext, 500000)
		assertResume(t, viewerB, episodeHigh, 0) // No fallback to another profile or the account.
	})
	t.Run("one card per movie or series using latest progress", func(t *testing.T) {
		got := items(t, viewerA)
		if len(got) != 2 {
			t.Fatalf("got %d cards, want 2: %#v", len(got), got)
		}
		versions := map[string]bool{}
		for _, item := range got {
			versions[item.(map[string]any)["mediaVersionId"].(string)] = true
		}
		if !versions[movieHigh] || !versions[episodeNext] {
			t.Fatalf("unexpected versions: %#v", versions)
		}
		if len(items(t, "")) != 1 || len(items(t, viewerB)) != 1 {
			t.Fatal("continue list mixed account and viewer timelines")
		}
	})
	t.Run("completion does not resurrect stale versions or episodes", func(t *testing.T) {
		progress(viewerA, movieHigh, 990000, true, "0 minutes")
		progress(viewerA, episodeNext, 990000, true, "0 minutes")
		if got := items(t, viewerA); len(got) != 0 {
			t.Fatalf("completed titles reappeared: %#v", got)
		}
		if len(items(t, viewerB)) != 1 {
			t.Fatal("completion affected another viewer")
		}
	})
	t.Run("saved progress is immediately resumable across quality variants", func(t *testing.T) {
		body := []byte(`{"mediaVersionId":"` + episodeLow + `","positionMs":420000,"durationMs":1000000}`)
		r := httptest.NewRequest(http.MethodPost, "/v1/watch/progress", bytes.NewReader(body))
		r.Header.Set("X-Filmiqoo-Viewer-Profile", viewerB)
		r = r.WithContext(context.WithValue(ctx, userKey, user))
		w := httptest.NewRecorder()
		s.saveProgress(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("save status=%d body=%s", w.Code, w.Body.String())
		}
		assertResume(t, viewerB, episodeHigh, 420000)
		if len(items(t, viewerB)) != 2 {
			t.Fatal("saved progress did not appear in continue watching")
		}
		if len(items(t, viewerA)) != 0 {
			t.Fatal("save affected another viewer's completed timeline")
		}
		if _, err := db.Exec(ctx, "DELETE FROM viewer_watch_progress WHERE viewer_profile_id=$1 AND media_version_id=$2", viewerB, episodeLow); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("unavailable and hidden media are excluded", func(t *testing.T) {
		if _, err := db.Exec(ctx, "UPDATE media_versions SET stream_ready=false WHERE id=$1", movieLow); err != nil {
			t.Fatal(err)
		}
		if len(items(t, viewerB)) != 0 {
			t.Fatal("unavailable stream appeared")
		}
		if _, err := db.Exec(ctx, "UPDATE media_versions SET stream_ready=true WHERE id=$1", movieLow); err != nil {
			t.Fatal(err)
		}
		if _, err := db.Exec(ctx, "UPDATE media_titles SET visibility='hidden' WHERE id=$1", movie); err != nil {
			t.Fatal(err)
		}
		if len(items(t, viewerB)) != 0 {
			t.Fatal("hidden title appeared")
		}
	})
}
