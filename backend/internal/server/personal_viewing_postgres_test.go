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

// Runs only against an explicitly migrated disposable test DB; every row belongs to a new fixture.
func TestPersonalViewingScopesCompletionAndIdempotentTelemetryPostgres(t *testing.T) {
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
	alice, bob, empty := account(), account(), account()
	viewer := func(user, name, level string) string {
		return insert("INSERT INTO viewer_profiles(user_id,name,maturity_level,kids_mode)VALUES($1,$2,$3,$4)RETURNING id::text", user, name, level, level == "kids")
	}
	adult, kids, foreign := viewer(alice, "Adult", "all"), viewer(alice, "Child", "kids"), viewer(bob, "Other viewer", "all")
	title := func(kind, name, audience string) string {
		id := insert(`INSERT INTO media_titles(kind,title,audience_level,genres,origin_countries)VALUES($1,$2,$3,'[{"id":18,"name":"Drama"}]','["US","KR"]')RETURNING id::text`, kind, name, audience)
		titles = append(titles, id)
		return id
	}
	movie, child, series := title("movie", "Stats adult movie", "adult"), title("movie", "Stats child movie", "kids"), title("series", "Stats ended series", "adult")
	movieTMDBID := int(time.Now().UnixNano()%500_000_000) + 1_000_000_000
	childTMDBID := movieTMDBID + 1
	seriesTMDBID := movieTMDBID + 2
	exec("UPDATE media_titles SET tmdb_id=$2 WHERE id=$1", movie, movieTMDBID)
	exec("UPDATE media_titles SET tmdb_id=$2 WHERE id=$1", child, childTMDBID)
	exec("UPDATE media_titles SET tmdb_id=$2 WHERE id=$1", series, seriesTMDBID)
	exec("UPDATE media_titles SET series_status='Ended',episode_count=2 WHERE id=$1", series)
	movieVersion := func(id, ref string) string {
		return insert("INSERT INTO media_versions(media_title_id,source_ref,stream_ready,duration_ms)VALUES($1,$2,true,1000)RETURNING id::text", id, ref)
	}
	mv1, mv2, childVersion := movieVersion(movie, "stats-primary"), movieVersion(movie, "stats-quality2"), movieVersion(child, "stats-child")
	season := insert("INSERT INTO seasons(media_title_id,season_number)VALUES($1,1)RETURNING id::text", series)
	ep1 := insert("INSERT INTO episodes(season_id,episode_number)VALUES($1,1)RETURNING id::text", season)
	ep2 := insert("INSERT INTO episodes(season_id,episode_number)VALUES($1,2)RETURNING id::text", season)
	episodeVersion := func(id, ref string) string {
		return insert("INSERT INTO media_versions(episode_id,source_ref,stream_ready,duration_ms)VALUES($1,$2,true,1000)RETURNING id::text", id, ref)
	}
	ev1, ev1Second, ev2 := episodeVersion(ep1, "stats-episode1"), episodeVersion(ep1, "stats-episode1-quality2"), episodeVersion(ep2, "stats-episode2")
	s := &Server{db: db}
	call := func(fn http.HandlerFunc, user, view, id, body string) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest(http.MethodPost, "/test", strings.NewReader(body))
		rc := chi.NewRouteContext()
		rc.URLParams.Add("id", id)
		rc.URLParams.Add("versionID", id)
		r = r.WithContext(context.WithValue(context.WithValue(ctx, chi.RouteCtxKey, rc), userKey, user))
		if view != "" {
			r.Header.Set("X-Filmiqoo-Viewer-Profile", view)
		}
		w := httptest.NewRecorder()
		fn(w, r)
		return w
	}
	progress := func(user, view, version string, pos int64) {
		t.Helper()
		w := call(s.saveProgress, user, view, "", fmt.Sprintf(`{"mediaVersionId":%q,"positionMs":%d,"durationMs":1000}`, version, pos))
		if w.Code != 200 {
			t.Fatalf("save progress: %d %s", w.Code, w.Body)
		}
	}
	stats := func(user, view string) userViewingStats {
		t.Helper()
		w := call(s.personalViewingStats, user, view, "", "")
		if w.Code != 200 {
			t.Fatalf("stats: %d %s", w.Code, w.Body)
		}
		var value userViewingStats
		if e := json.Unmarshal(w.Body.Bytes(), &value); e != nil {
			t.Fatal(e)
		}
		if value.Genres == nil || value.Countries == nil {
			t.Fatal("empty taste arrays lost")
		}
		return value
	}
	for _, v := range []string{mv1, mv2, ev1, ev1Second, ev2} {
		progress(alice, "", v, 1000)
	}
	progress(alice, "", mv1, 50)
	progress(alice, "", mv2, 0)
	var completed, ever bool
	err = db.QueryRow(ctx, "SELECT completed,ever_completed FROM watch_progress WHERE user_id=$1 AND media_version_id=$2", alice, mv1).Scan(&completed, &ever)
	if err != nil {
		t.Fatal(err)
	}
	if completed || !ever {
		t.Fatalf("partial rewatch completion: %v %v", completed, ever)
	}
	exec("INSERT INTO watchlist(user_id,media_title_id)VALUES($1,$2)", alice, series)
	exec("INSERT INTO favorites(user_id,media_title_id)VALUES($1,$2)", alice, movie)
	assertMovieIDs := func(value userViewingStats, want ...int) {
		t.Helper()
		if value.CompletedMovieTmdbIDs == nil || fmt.Sprint(value.CompletedMovieTmdbIDs) != fmt.Sprint(want) {
			t.Fatalf("completed movie SQL scope: got %v want %v", value.CompletedMovieTmdbIDs, want)
		}
	}
	initial := stats(alice, "")
	assertMovieIDs(initial, movieTMDBID)
	if initial.MoviesWatched != 1 || initial.SeriesWatched != 1 || initial.EpisodesWatched != 2 || initial.CompletedTitles != 2 || initial.HistoryTitles != 2 || initial.CurrentlyWatching != 1 || initial.TotalWatchMS != 0 || !initial.LegacyHistoryWithoutTime {
		t.Fatalf("legacy dedup/time: %+v", initial)
	}
	if initial.WatchlistCount != 1 || initial.FavoriteCount != 1 || len(initial.Genres) != 0 {
		t.Fatalf("legacy lists/taste: %+v", initial)
	}
	progress(alice, adult, mv1, 100)
	progress(alice, kids, childVersion, 1000)
	progress(alice, kids, mv1, 1000)
	progress(alice, kids, ev1, 1000)
	progress(alice, kids, childVersion, 50)
	err = db.QueryRow(ctx, "SELECT completed,ever_completed FROM viewer_watch_progress WHERE viewer_profile_id=$1 AND media_version_id=$2", kids, childVersion).Scan(&completed, &ever)
	if err != nil {
		t.Fatal(err)
	}
	if completed || !ever {
		t.Fatal("viewer rewatch erased completion")
	}
	progress(bob, "", childVersion, 1000)
	progress(bob, foreign, ev1, 1000)
	exec("INSERT INTO viewer_watchlist(viewer_profile_id,media_title_id)VALUES($1,$2)", adult, movie)
	for _, id := range []string{child, movie, series} {
		exec("INSERT INTO viewer_watchlist(viewer_profile_id,media_title_id)VALUES($1,$2)", kids, id)
		exec("INSERT INTO viewer_favorites(viewer_profile_id,media_title_id)VALUES($1,$2)", kids, id)
	}
	session := func(user, view, version string, watched int64) string {
		return insert(`INSERT INTO playback_sessions(user_id,viewer_profile_id,started_media_version_id,current_media_version_id,watched_ms,last_heartbeat_at)VALUES($1,NULLIF($2,'')::uuid,$3,$3,$4,now()-interval '60 seconds')RETURNING id::text`, user, view, version, watched)
	}
	legacySession := session(alice, "", mv1, 0)
	adultSession := session(alice, adult, mv1, 7777)
	session(alice, kids, childVersion, 3000)
	session(alice, kids, mv1, 900000)
	session(bob, "", childVersion, 5555)
	session(bob, foreign, ev1, 4444)
	telemetry := func(total int64) string {
		return fmt.Sprintf(`{"currentMediaVersionId":%q,"positionMs":900000,"durationMs":1000,"watchedDeltaMs":30000,"watchedTotalMs":%d}`, mv1, total)
	}
	assertLedger := func(expected, high int64) {
		t.Helper()
		var watched, cumulative int64
		err = db.QueryRow(ctx, "SELECT watched_ms,client_watched_ms FROM playback_sessions WHERE id=$1", legacySession).Scan(&watched, &cumulative)
		if err != nil {
			t.Fatal(err)
		}
		if watched != expected || cumulative != high {
			t.Fatalf("ledger: %d/%d expected %d/%d", watched, cumulative, expected, high)
		}
	}
	for _, total := range []int64{10000, 10000, 9000} {
		w := call(s.heartbeatPlaybackSession, alice, "", legacySession, telemetry(total))
		if w.Code != 200 {
			t.Fatalf("heartbeat: %d %s", w.Code, w.Body)
		}
		assertLedger(10000, 10000)
	}
	exec("UPDATE playback_sessions SET last_heartbeat_at=now()-interval '60 seconds' WHERE id=$1", legacySession)
	w := call(s.heartbeatPlaybackSession, alice, "", legacySession, telemetry(15000))
	if w.Code != 200 {
		t.Fatalf("later heartbeat: %d %s", w.Code, w.Body)
	}
	assertLedger(15000, 15000)
	w = call(s.heartbeatPlaybackSession, bob, "", legacySession, telemetry(900000))
	if w.Code != 404 {
		t.Fatalf("foreign account session access: %d %s", w.Code, w.Body)
	}
	assertLedger(15000, 15000)
	exec("UPDATE playback_sessions SET last_heartbeat_at=now()-interval '60 seconds' WHERE id=$1", legacySession)
	for i := 0; i < 2; i++ {
		w = call(s.endPlaybackSession, alice, "", legacySession, telemetry(20000))
		if w.Code != 200 {
			t.Fatalf("end: %d %s", w.Code, w.Body)
		}
		var ended struct {
			Ended bool `json:"ended"`
		}
		if err = json.Unmarshal(w.Body.Bytes(), &ended); err != nil {
			t.Fatal(err)
		}
		if ended.Ended != (i == 0) {
			t.Fatalf("end retry: %s", w.Body)
		}
		assertLedger(20000, 20000)
	}
	w = call(s.heartbeatPlaybackSession, alice, adult, adultSession, fmt.Sprintf(`{"currentMediaVersionId":%q,"positionMs":100,"durationMs":1000,"watchedDeltaMs":1234}`, mv1))
	if w.Code != 200 {
		t.Fatalf("legacy delta: %d %s", w.Code, w.Body)
	}
	legacy := stats(alice, "")
	if legacy.TotalWatchMS != 20000 || legacy.MoviesWatched != 1 || legacy.SeriesWatched != 1 || legacy.EpisodesWatched != 2 || legacy.LegacyHistoryWithoutTime {
		t.Fatalf("account time/seek/scope: %+v", legacy)
	}
	a := stats(alice, adult)
	assertMovieIDs(a)
	if a.TotalWatchMS != 9011 || a.HistoryTitles != 1 || a.CurrentlyWatching != 1 || a.MoviesWatched != 0 || a.SeriesStarted != 0 || a.CompletedTitles != 0 || a.WatchlistCount != 1 || a.FavoriteCount != 0 {
		t.Fatalf("adult scope: %+v", a)
	}
	k := stats(alice, kids)
	assertMovieIDs(k, childTMDBID)
	if k.TotalWatchMS != 3000 || k.HistoryTitles != 1 || k.MoviesWatched != 1 || k.SeriesStarted != 0 || k.EpisodesWatched != 0 || k.CurrentlyWatching != 1 || k.WatchlistCount != 1 || k.FavoriteCount != 1 {
		t.Fatalf("child maturity/scope: %+v", k)
	}
	b := stats(bob, "")
	assertMovieIDs(b, childTMDBID)
	bv := stats(bob, foreign)
	assertMovieIDs(bv)
	if b.TotalWatchMS != 5555 || b.MoviesWatched != 1 || b.HistoryTitles != 1 || b.SeriesStarted != 0 {
		t.Fatalf("other account: %+v", b)
	}
	if bv.TotalWatchMS != 4444 || bv.MoviesWatched != 0 || bv.SeriesStarted != 1 || bv.SeriesWatched != 0 || bv.EpisodesWatched != 1 || bv.HistoryTitles != 1 {
		t.Fatalf("other viewer: %+v", bv)
	}
	forged := stats(alice, foreign)
	if forged.TotalWatchMS != legacy.TotalWatchMS || forged.HistoryTitles != legacy.HistoryTitles || forged.EpisodesWatched != legacy.EpisodesWatched {
		t.Fatalf("forged viewer header: %+v", forged)
	}
	fresh := stats(empty, "")
	assertMovieIDs(fresh)
	if fresh.TotalWatchMS != 0 || fresh.HistoryTitles != 0 || fresh.CompletedTitles != 0 || fresh.WatchlistCount != 0 || fresh.FavoriteCount != 0 || fresh.LegacyHistoryWithoutTime || fresh.TasteSampleSize != 0 {
		t.Fatalf("fabricated new account: %+v", fresh)
	}
	unauth := httptest.NewRecorder()
	s.auth(http.HandlerFunc(s.personalViewingStats)).ServeHTTP(unauth, httptest.NewRequest("GET", "/v1/library/viewing-stats", nil))
	if unauth.Code != 401 {
		t.Fatalf("private stats unauthenticated: %d", unauth.Code)
	}
	exec("UPDATE media_titles SET episode_count=NULL WHERE id=$1", series)
	if got := stats(alice, ""); got.SeriesWatched != 0 || got.EpisodesWatched != 2 || got.SeriesStarted != 1 {
		t.Fatalf("unknown total fabricated completion: %+v", got)
	}
	exec("UPDATE media_titles SET episode_count=2,series_status='Returning Series' WHERE id=$1", series)
	if got := stats(alice, ""); got.SeriesWatched != 0 || got.EpisodesWatched != 2 {
		t.Fatalf("returning series completion: %+v", got)
	}
	exec("UPDATE media_titles SET series_status='Ended',episode_count=2 WHERE id=$1", series)
	legacyBefore, adultBefore, kidsBefore := stats(alice, ""), stats(alice, adult), stats(alice, kids)
	bobBefore, bobViewerBefore := stats(bob, ""), stats(bob, foreign)
	sameStats := func(user, view string, want userViewingStats) {
		t.Helper()
		got := stats(user, view)
		a, _ := json.Marshal(got)
		b, _ := json.Marshal(want)
		if string(a) != string(b) {
			t.Fatalf("erase affected another scope %s/%s: got %s want %s", user, view, a, b)
		}
	}
	erase := func(fn http.HandlerFunc, user, view, version string) {
		t.Helper()
		w := call(fn, user, view, version, "")
		if w.Code != http.StatusNoContent {
			t.Fatalf("erase history: %d %s", w.Code, w.Body)
		}
	}
	count := func(q string, args ...any) int {
		t.Helper()
		var n int
		if e := db.QueryRow(ctx, q, args...).Scan(&n); e != nil {
			t.Fatal(e)
		}
		return n
	}
	erase(s.removeHistoryItem, bob, "", mv1)
	sameStats(alice, "", legacyBefore)
	sameStats(alice, adult, adultBefore)
	sameStats(alice, kids, kidsBefore)
	sameStats(bob, "", bobBefore)
	sameStats(bob, foreign, bobViewerBefore)
	erase(s.removeHistoryItem, alice, adult, mv1)
	afterAdult := stats(alice, adult)
	if afterAdult.HistoryTitles != 0 || afterAdult.TotalWatchMS != 0 || afterAdult.CompletedTitles != 0 || afterAdult.CurrentlyWatching != 0 || afterAdult.WatchlistCount != 1 || afterAdult.FavoriteCount != 0 {
		t.Fatalf("adult erase/list retention: %+v", afterAdult)
	}
	if count("SELECT COUNT(*) FROM viewer_watch_progress WHERE viewer_profile_id=$1", adult) != 0 || count("SELECT COUNT(*) FROM playback_sessions WHERE user_id=$1 AND viewer_profile_id=$2", alice, adult) != 0 {
		t.Fatal("adult erase left private viewing rows")
	}
	sameStats(alice, "", legacyBefore)
	sameStats(alice, kids, kidsBefore)
	sameStats(bob, "", bobBefore)
	sameStats(bob, foreign, bobViewerBefore)
	erase(s.removeHistoryItem, alice, "", mv1)
	afterVersion := stats(alice, "")
	assertMovieIDs(afterVersion, movieTMDBID)
	if afterVersion.TotalWatchMS != 0 || afterVersion.MoviesWatched != 1 || afterVersion.SeriesWatched != 1 || afterVersion.EpisodesWatched != 2 || afterVersion.HistoryTitles != 2 || afterVersion.CurrentlyWatching != 0 || afterVersion.WatchlistCount != 1 || afterVersion.FavoriteCount != 1 || !afterVersion.LegacyHistoryWithoutTime {
		t.Fatalf("version erase affected remaining versions/list: %+v", afterVersion)
	}
	if count("SELECT COUNT(*) FROM watch_progress WHERE user_id=$1 AND media_version_id=$2", alice, mv1) != 0 || count("SELECT COUNT(*) FROM watch_progress WHERE user_id=$1 AND media_version_id=$2 AND ever_completed", alice, mv2) != 1 || count("SELECT COUNT(*) FROM playback_sessions WHERE id=$1", legacySession) != 0 {
		t.Fatal("version erase removed the wrong private rows")
	}
	erase(s.clearHistory, alice, kids, "")
	afterKids := stats(alice, kids)
	assertMovieIDs(afterKids)
	if afterKids.HistoryTitles != 0 || afterKids.TotalWatchMS != 0 || afterKids.CompletedTitles != 0 || afterKids.CurrentlyWatching != 0 || afterKids.TasteSampleSize != 0 || afterKids.LegacyHistoryWithoutTime || afterKids.WatchlistCount != 1 || afterKids.FavoriteCount != 1 {
		t.Fatalf("kids clear/list retention: %+v", afterKids)
	}
	if count("SELECT COUNT(*) FROM viewer_watch_progress WHERE viewer_profile_id=$1", kids) != 0 || count("SELECT COUNT(*) FROM playback_sessions WHERE user_id=$1 AND viewer_profile_id=$2", alice, kids) != 0 || count("SELECT COUNT(*) FROM viewer_watchlist WHERE viewer_profile_id=$1", kids) != 3 || count("SELECT COUNT(*) FROM viewer_favorites WHERE viewer_profile_id=$1", kids) != 3 {
		t.Fatal("kids clear left hidden history or deleted maturity-hidden saved titles")
	}
	sameStats(alice, "", afterVersion)
	sameStats(alice, adult, afterAdult)
	sameStats(bob, "", bobBefore)
	sameStats(bob, foreign, bobViewerBefore)
	erase(s.clearHistory, alice, "", "")
	cleared := stats(alice, "")
	assertMovieIDs(cleared)
	if cleared.TotalWatchMS != 0 || cleared.HistoryTitles != 0 || cleared.CompletedTitles != 0 || cleared.EpisodesWatched != 0 || cleared.TasteSampleSize != 0 || cleared.LegacyHistoryWithoutTime || cleared.WatchlistCount != 1 || cleared.FavoriteCount != 1 {
		t.Fatalf("account clear/list retention: %+v", cleared)
	}
	if count("SELECT COUNT(*) FROM watch_progress WHERE user_id=$1", alice) != 0 || count("SELECT COUNT(*) FROM playback_sessions WHERE user_id=$1 AND viewer_profile_id IS NULL", alice) != 0 || count("SELECT COUNT(*) FROM watchlist WHERE user_id=$1", alice) != 1 || count("SELECT COUNT(*) FROM favorites WHERE user_id=$1", alice) != 1 {
		t.Fatal("account clear viewing rows/list retention mismatch")
	}
	sameStats(alice, adult, afterAdult)
	sameStats(alice, kids, afterKids)
	sameStats(bob, "", bobBefore)
	sameStats(bob, foreign, bobViewerBefore)
	erase(s.clearHistory, alice, foreign, "")
	sameStats(bob, "", bobBefore)
	sameStats(bob, foreign, bobViewerBefore)
	sameStats(alice, adult, afterAdult)
	sameStats(alice, kids, afterKids)
	deniedErase := httptest.NewRecorder()
	s.auth(http.HandlerFunc(s.clearHistory)).ServeHTTP(deniedErase, httptest.NewRequest(http.MethodPost, "/v1/library/history/clear", nil))
	if deniedErase.Code != http.StatusUnauthorized {
		t.Fatalf("history erasure missing auth: %d", deniedErase.Code)
	}
}
