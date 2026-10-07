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

func TestSeriesManualMarksPreserveScopedActualHistoryPostgres(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated disposable PostgreSQL")
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
		for _, id := range titles {
			if _, e := db.Exec(c, "DELETE FROM media_titles WHERE id=$1", id); e != nil {
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
	adult, kids, sibling, foreign := viewer(alice, "Manual adult", "all"), viewer(alice, "Manual child", "kids"), viewer(alice, "Manual sibling", "all"), viewer(bob, "Other manual viewer", "all")
	title := func(name, level string, total int) string {
		id := insert("INSERT INTO media_titles(kind,title,audience_level,series_status,episode_count)VALUES('series',$1,$2,'Ended',$3)RETURNING id::text", name, level, total)
		titles = append(titles, id)
		return id
	}
	show, restricted := title("Manual child series", "kids", 2), title("Manual restricted series", "adult", 1)
	season := insert("INSERT INTO seasons(media_title_id,season_number)VALUES($1,1)RETURNING id::text", show)
	restrictedSeason := insert("INSERT INTO seasons(media_title_id,season_number)VALUES($1,1)RETURNING id::text", restricted)
	episode := func(season string, n int) string {
		return insert("INSERT INTO episodes(season_id,episode_number)VALUES($1,$2)RETURNING id::text", season, n)
	}
	ep1, ep2, restrictedEpisode := episode(season, 1), episode(season, 2), episode(restrictedSeason, 1)
	version := func(ep, ref string) string {
		return insert("INSERT INTO media_versions(episode_id,source_ref,stream_ready,duration_ms)VALUES($1,$2,true,1000)RETURNING id::text", ep, ref)
	}
	v1, v2 := version(ep1, "manual-scope-one"), version(ep2, "manual-scope-two")
	version(restrictedEpisode, "manual-scope-restricted")
	exec("INSERT INTO watch_progress(user_id,media_version_id,position_ms,duration_ms,completed,ever_completed)VALUES($1,$2,1000,1000,true,true)", alice, v1)
	exec("INSERT INTO viewer_watch_progress(viewer_profile_id,media_version_id,position_ms,duration_ms,completed,ever_completed)VALUES($1,$2,1000,1000,true,true)", adult, v2)
	exec("INSERT INTO viewer_watch_progress(viewer_profile_id,media_version_id,position_ms,duration_ms,completed,ever_completed)VALUES($1,$2,1000,1000,true,true)", foreign, v1)
	for _, x := range []struct {
		user, view, version string
		ms                  int
	}{{alice, "", v1, 7000}, {alice, adult, v2, 5000}, {bob, foreign, v1, 9000}} {
		exec("INSERT INTO playback_sessions(user_id,viewer_profile_id,started_media_version_id,current_media_version_id,watched_ms)VALUES($1,NULLIF($2,'')::uuid,$3,$3,$4)", x.user, x.view, x.version, x.ms)
	}
	s := &Server{db: db}
	call := func(fn http.HandlerFunc, user, view, id, body string) *httptest.ResponseRecorder {
		t.Helper()
		method := http.MethodPost
		if body == "" {
			method = http.MethodGet
		}
		r := httptest.NewRequest(method, "/fixture", strings.NewReader(body))
		rc := chi.NewRouteContext()
		rc.URLParams.Add("id", id)
		r = r.WithContext(context.WithValue(context.WithValue(ctx, chi.RouteCtxKey, rc), userKey, user))
		if view != "" {
			r.Header.Set("X-Filmiqoo-Viewer-Profile", view)
		}
		w := httptest.NewRecorder()
		fn(w, r)
		return w
	}
	mark := func(fn http.HandlerFunc, user, view, id string, watched bool) {
		t.Helper()
		w := call(fn, user, view, id, fmt.Sprintf(`{"watched":%t,"manualMarksVersion":1}`, watched))
		if w.Code != 200 {
			t.Fatalf("manual mark: %d %s", w.Code, w.Body)
		}
	}
	type result struct {
		Version int `json:"manualMarksVersion"`
		Watched int `json:"watchedCount"`
		Manual  int `json:"manualSeenCount"`
		Items   []struct {
			ID        string `json:"episodeId"`
			Completed bool   `json:"completed"`
			Manual    bool   `json:"manualSeen"`
		} `json:"items"`
	}
	progress := func(user, view string, done1, done2, manual1, manual2 bool) {
		t.Helper()
		w := call(s.seriesProgress, user, view, show, "")
		if w.Code != 200 {
			t.Fatalf("series progress: %d %s", w.Code, w.Body)
		}
		var value result
		if e := json.Unmarshal(w.Body.Bytes(), &value); e != nil {
			t.Fatal(e)
		}
		if value.Version != 1 || len(value.Items) != 2 {
			t.Fatalf("manual provenance/version missing: %s", w.Body)
		}
		want := map[string][2]bool{ep1: {done1, manual1}, ep2: {done2, manual2}}
		seen := map[string]bool{}
		for _, item := range value.Items {
			v, ok := want[item.ID]
			if !ok || seen[item.ID] || item.Completed != v[0] || item.Manual != v[1] {
				t.Fatalf("wrong scoped provenance: %s", w.Body)
			}
			seen[item.ID] = true
		}
		d, m := 0, 0
		for _, v := range want {
			if v[0] {
				d++
			}
			if v[1] {
				m++
			}
		}
		if value.Watched != d || value.Manual != m {
			t.Fatalf("manual marks contaminated actual counts: %s", w.Body)
		}
	}
	actual := func() string {
		t.Helper()
		var value string
		e := db.QueryRow(ctx, `SELECT jsonb_build_object('account',(SELECT COALESCE(jsonb_agg(to_jsonb(w) ORDER BY w.user_id,w.media_version_id),'[]'::jsonb) FROM watch_progress w WHERE w.user_id::text=ANY($1::text[])),'viewer',(SELECT COALESCE(jsonb_agg(to_jsonb(w) ORDER BY w.viewer_profile_id,w.media_version_id),'[]'::jsonb) FROM viewer_watch_progress w WHERE w.viewer_profile_id IN(SELECT id FROM viewer_profiles WHERE user_id::text=ANY($1::text[]))),'sessions',(SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY p.id),'[]'::jsonb) FROM playback_sessions p WHERE p.user_id::text=ANY($1::text[])))::text`, users).Scan(&value)
		if e != nil {
			t.Fatal(e)
		}
		return value
	}
	stats := func(user, view string) userViewingStats {
		t.Helper()
		w := call(s.personalViewingStats, user, view, "", "")
		if w.Code != 200 {
			t.Fatalf("stats %d %s", w.Code, w.Body)
		}
		var value userViewingStats
		if e := json.Unmarshal(w.Body.Bytes(), &value); e != nil {
			t.Fatal(e)
		}
		return value
	}
	scopes := [][2]string{{alice, ""}, {alice, adult}, {alice, kids}, {alice, sibling}, {bob, foreign}, {bob, ""}}
	before := make([]userViewingStats, len(scopes))
	for i, x := range scopes {
		before[i] = stats(x[0], x[1])
	}
	actualBefore := actual()
	unchanged := func() {
		t.Helper()
		if actual() != actualBefore {
			t.Fatal("manual status modified actual progress/completion/session ledger")
		}
		for i, x := range scopes {
			got, _ := json.Marshal(stats(x[0], x[1]))
			want, _ := json.Marshal(before[i])
			if string(got) != string(want) {
				t.Fatalf("manual status changed stats for %s/%s: got %s want %s", x[0], x[1], got, want)
			}
		}
	}
	progress(alice, "", true, false, false, false)
	progress(alice, adult, false, true, false, false)
	progress(alice, kids, false, false, false, false)
	progress(alice, sibling, false, false, false, false)
	progress(bob, foreign, true, false, false, false)
	missing := call(s.setEpisodeWatchedStatus, alice, kids, ep1, `{"watched":true}`)
	if missing.Code != 400 && missing.Code != 409 {
		t.Fatalf("missing provenance capability accepted: %d %s", missing.Code, missing.Body)
	}
	unchanged()
	mark(s.setEpisodeWatchedStatus, alice, kids, ep1, true)
	progress(alice, kids, false, false, true, false)
	progress(alice, "", true, false, false, false)
	progress(alice, adult, false, true, false, false)
	progress(alice, sibling, false, false, false, false)
	unchanged()
	mark(s.setSeasonWatchedStatus, alice, kids, season, true)
	progress(alice, kids, false, false, true, true)
	unchanged()
	mark(s.setEpisodeWatchedStatus, alice, kids, ep1, false)
	progress(alice, kids, false, false, false, true)
	unchanged()
	mark(s.setSeasonWatchedStatus, alice, kids, season, false)
	progress(alice, kids, false, false, false, false)
	unchanged()
	mark(s.setSeasonWatchedStatus, alice, "", season, true)
	progress(alice, "", true, false, true, true)
	unchanged()
	mark(s.setEpisodeWatchedStatus, alice, "", ep1, false)
	progress(alice, "", true, false, false, true)
	unchanged()
	mark(s.setSeasonWatchedStatus, alice, "", season, false)
	progress(alice, "", true, false, false, false)
	unchanged()
	mark(s.setEpisodeWatchedStatus, alice, adult, ep2, true)
	progress(alice, adult, false, true, false, true)
	unchanged()
	mark(s.setEpisodeWatchedStatus, alice, adult, ep2, false)
	progress(alice, adult, false, true, false, false)
	unchanged()
	mark(s.setEpisodeWatchedStatus, bob, foreign, ep2, true)
	progress(bob, foreign, true, false, false, true)
	progress(alice, kids, false, false, false, false)
	unchanged()
	mark(s.setEpisodeWatchedStatus, bob, foreign, ep2, false)
	progress(bob, foreign, true, false, false, false)
	unchanged()
	deny := func(w *httptest.ResponseRecorder) {
		t.Helper()
		if w.Code != 403 && w.Code != 404 {
			t.Fatalf("scope/maturity request not safely rejected: %d %s", w.Code, w.Body)
		}
	}
	deny(call(s.seriesProgress, alice, kids, restricted, ""))
	deny(call(s.setEpisodeWatchedStatus, alice, kids, restrictedEpisode, `{"watched":true,"manualMarksVersion":1}`))
	deny(call(s.setSeasonWatchedStatus, alice, kids, restrictedSeason, `{"watched":true,"manualMarksVersion":1}`))
	deny(call(s.seriesProgress, alice, foreign, show, ""))
	deny(call(s.setEpisodeWatchedStatus, alice, foreign, ep1, `{"watched":false,"manualMarksVersion":1}`))
	deny(call(s.setSeasonWatchedStatus, alice, foreign, season, `{"watched":false,"manualMarksVersion":1}`))
	deny(call(s.seriesProgress, alice, "00000000-0000-4000-8000-000000000000", show, ""))
	unchanged()
	for _, fn := range []http.HandlerFunc{s.seriesProgress, s.setEpisodeWatchedStatus, s.setSeasonWatchedStatus} {
		w := httptest.NewRecorder()
		s.auth(fn).ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/fixture", strings.NewReader(`{"watched":true,"manualMarksVersion":1}`)))
		if w.Code != 401 {
			t.Fatalf("manual/series scope unauthenticated: %d", w.Code)
		}
	}
	for _, version := range []string{v1, v2} {
		w := call(s.saveProgress, alice, kids, "", fmt.Sprintf(`{"mediaVersionId":%q,"positionMs":1000,"durationMs":1000}`, version))
		if w.Code != 200 {
			t.Fatalf("actual completion %d %s", w.Code, w.Body)
		}
	}
	progress(alice, kids, true, true, false, false)
	genuine := stats(alice, kids)
	if genuine.SeriesWatched != 1 || genuine.EpisodesWatched != 2 || genuine.TotalWatchMS != 0 {
		t.Fatalf("genuine completion/manual time %+v", genuine)
	}
	before[2] = genuine
	actualBefore = actual()
	mark(s.setEpisodeWatchedStatus, alice, kids, ep1, false)
	progress(alice, kids, true, true, false, false)
	unchanged()
	exec("UPDATE media_titles SET series_status='Returning Series' WHERE id=$1", show)
	if got := stats(alice, kids); got.SeriesWatched != 0 || got.EpisodesWatched != 2 {
		t.Fatalf("active show counted complete %+v", got)
	}
	exec("UPDATE media_titles SET series_status='Ended',episode_count=NULL WHERE id=$1", show)
	if got := stats(alice, kids); got.SeriesWatched != 0 || got.EpisodesWatched != 2 {
		t.Fatalf("unknown total counted complete %+v", got)
	}
}
