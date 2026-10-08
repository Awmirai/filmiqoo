package server

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/Awmirai/filmiqoo/backend/internal/catalogindex"
	"github.com/Awmirai/filmiqoo/backend/internal/tmdb"
	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

func TestCatalogDiscoveryValidatesRealFilters(t *testing.T) {
	for _, query := range []string{"type=movie&status=3", "type=series&minRating=NaN", "type=series&page=501", "type=series&country=Korea", "type=series&runtimeMax=0", "type=series&yearFrom=2026&yearTo=2020", "type=series&with_status=3", "type=series&country=KR&country=US", "type=series&section=trending"} {
		v, _ := url.ParseQuery(query)
		if _, err := parseCatalogDiscovery(v); err == nil {
			t.Fatalf("invalid filter accepted: %s", query)
		}
	}
	v, _ := url.ParseQuery("type=series&country=kr&language=KO&genreId=18&status=3&minRating=8.0&runtimeMax=45&persianDubbedOnly=true&persianSubtitleOnly=true")
	f, err := parseCatalogDiscovery(v)
	if err != nil {
		t.Fatal(err)
	}
	where, args := catalogDiscoveryWhere(f, "kids")
	if len(args) != 6 || !strings.Contains(where, "mt.audience_level='kids'") || !strings.Contains(where, "av.has_dub") || !strings.Contains(where, "av.has_sub") {
		t.Fatalf("combined filters lost: %s %#v", where, args)
	}
}
func TestCatalogDiscoveryCanonicalPresentationAndDistinctEpisodes(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated opt-in PostgreSQL test database")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{db: db}
	ids := []string{}
	chatID := -time.Now().UnixNano()
	t.Cleanup(func() {
		c, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		_, _ = db.Exec(c, `DELETE FROM telegram_ingest_items WHERE telegram_chat_id=$1`, chatID)
		_, _ = db.Exec(c, `DELETE FROM media_titles WHERE id::text=ANY($1::text[])`, ids)
		_, _ = db.Exec(c, `DELETE FROM telegram_source_conventions WHERE telegram_chat_id=$1`, chatID)
		db.Close()
	})
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.Exec(ctx, q, args...); err != nil {
			t.Fatal(err)
		}
	}
	var titleID string
	err = db.QueryRow(ctx, `INSERT INTO media_titles(kind,title,year,rating,genre_ids,origin_countries,original_language,runtime_minutes,series_status,series_type,episode_count,vote_count) VALUES('series','Discovery Fixture',2025,8.4,'[18]','["KR","US"]','ko',43,'Ended','Miniseries',8,400) RETURNING id::text`).Scan(&titleID)
	if err != nil {
		t.Fatal(err)
	}
	ids = append(ids, titleID)
	var seasonID string
	err = db.QueryRow(ctx, `INSERT INTO seasons(media_title_id,season_number)VALUES($1,1)RETURNING id::text`, titleID).Scan(&seasonID)
	if err != nil {
		t.Fatal(err)
	}
	episodeIDs := []string{}
	for n := 1; n <= 3; n++ {
		var id string
		err = db.QueryRow(ctx, `INSERT INTO episodes(season_id,episode_number,name)VALUES($1,$2,$3)RETURNING id::text`, seasonID, n, fmt.Sprintf("Episode %d", n)).Scan(&id)
		if err != nil {
			t.Fatal(err)
		}
		episodeIDs = append(episodeIDs, id)
	}
	mixed := "دو نسخه دوبله فارسی و زبان اصلی با زیرنویس فارسی"
	versionIDs := []string{}
	for index, episode := range []string{episodeIDs[0], episodeIDs[0], episodeIDs[1], episodeIDs[2]} {
		filename := "Series.DUB.mkv"
		if index == 2 {
			filename = "Series.SUB.mkv"
		}
		var id string
		message := int64(index + 1)
		err = db.QueryRow(ctx, `INSERT INTO media_versions(episode_id,source_ref,telegram_chat_id,telegram_message_id,file_name,quality_label,stream_ready)VALUES($1,$2,$3,$4,$5,'1080p',$6)RETURNING id::text`, episode, fmt.Sprintf("telegram:%d:%d", chatID, message), chatID, message, filename, index != 3).Scan(&id)
		if err != nil {
			t.Fatal(err)
		}
		versionIDs = append(versionIDs, id)
		exec(`INSERT INTO telegram_ingest_items(telegram_chat_id,telegram_message_id,file_name,caption)VALUES($1,$2,$3,$4)`, chatID, message, filename, mixed)
	}
	detection, processed, err := catalogindex.DetectVersion(ctx, db, versionIDs[0], false)
	if err != nil || !processed || !detection.IsPersianDubbed {
		t.Fatalf("stored-caption dryrun: %#v %v", detection, err)
	}
	report, err := catalogindex.Backfill(ctx, db, nil, catalogindex.BackfillOptions{Apply: false, Limit: 10000})
	if err != nil {
		t.Fatal(err)
	}
	if !report.DryRun {
		t.Fatal("dry run lost")
	}
	var version int
	err = db.QueryRow(ctx, `SELECT detection_version FROM media_versions WHERE id=$1`, versionIDs[0]).Scan(&version)
	if err != nil || version != 0 {
		t.Fatalf("dryrun mutated: %d %v", version, err)
	}
	for _, id := range versionIDs {
		if _, _, err = catalogindex.DetectVersion(ctx, db, id, true); err != nil {
			t.Fatal(err)
		}
	}
	exec(`UPDATE media_versions SET detection_source='manual',is_persian_dubbed=false WHERE id=$1`, versionIDs[3])
	if _, processed, err = catalogindex.DetectVersion(ctx, db, versionIDs[3], true); err != nil || processed {
		t.Fatalf("manual overwritten: %v %v", processed, err)
	}
	exec(`INSERT INTO telegram_ingest_items(telegram_chat_id,telegram_message_id,file_name,caption,resolved_media_version_id)VALUES($1,99,'Repost.DUB.mkv','دوبله فارسی',$2)`, chatID, versionIDs[2])
	detection, _, err = catalogindex.DetectVersion(ctx, db, versionIDs[2], true)
	if err != nil || detection.IsPersianDubbed || !detection.HasPersianSubtitle {
		t.Fatalf("canonical caption contaminated: %#v %v", detection, err)
	}
	var hiddenID string
	err = db.QueryRow(ctx, `INSERT INTO media_titles(kind,title,year,rating,genre_ids,origin_countries,original_language,runtime_minutes,series_status,visibility)VALUES('series','Hidden Fixture',2025,9,'[18]','["KR","US"]','ko',40,'Ended','hidden')RETURNING id::text`).Scan(&hiddenID)
	if err != nil {
		t.Fatal(err)
	}
	ids = append(ids, hiddenID)
	exec(`INSERT INTO media_versions(media_title_id,source_ref,file_name,stream_ready,is_persian_dubbed,has_persian_subtitle)VALUES($1,'fixture:hidden','hidden.mkv',true,true,true)`, hiddenID)
	request := httptest.NewRequest(http.MethodGet, "/v1/catalog/discovery?type=series&country=US&language=ko&genreId=18&minRating=8&runtimeMax=45&status=3&persianDubbedOnly=true&persianSubtitleOnly=true", nil)
	response := httptest.NewRecorder()
	s.catalogDiscovery(response, request)
	if response.Code != 200 {
		t.Fatalf("discovery: %d %s", response.Code, response.Body.String())
	}
	var payload struct {
		Version int              `json:"discoveryVersion"`
		Items   []map[string]any `json:"items"`
		Total   int              `json:"totalResults"`
	}
	if err = json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Version != 1 || len(payload.Items) != 1 || payload.Items[0]["id"] != titleID || payload.Total != 1 {
		t.Fatalf("filters/visibility: %s", response.Body.String())
	}
	item := payload.Items[0]
	if item["hasPersianDub"] != true || item["hasPersianSubtitle"] != true || item["dubbedEpisodeCount"] != float64(1) || item["availableEpisodeCount"] != float64(2) || item["episodeCount"] != float64(8) {
		t.Fatalf("incomplete series mislabeled: %#v", item)
	}
	route := chi.NewRouteContext()
	route.URLParams.Add("id", titleID)
	req := httptest.NewRequest("GET", "/v1/catalog/"+titleID, nil).WithContext(context.WithValue(ctx, chi.RouteCtxKey, route))
	res := httptest.NewRecorder()
	s.catalogDetail(res, req)
	if res.Code != 200 {
		t.Fatalf("detail: %d %s", res.Code, res.Body.String())
	}
	var detail map[string]any
	if err = json.Unmarshal(res.Body.Bytes(), &detail); err != nil {
		t.Fatal(err)
	}
	seasons := detail["seasons"].([]any)
	episodes := seasons[0].(map[string]any)["episodes"].([]any)
	first := episodes[0].(map[string]any)
	second := episodes[1].(map[string]any)
	if len(first["versions"].([]any)) != 2 || first["hasPersianDub"] != true || second["isPersianDubbed"] != false || second["hasPersianSubtitle"] != true {
		t.Fatalf("version choices lost: %s", res.Body.String())
	}
	count := 9
	meta := tmdb.TitleMetadata{GenreIDs: []int{18}, Genres: []string{"Drama"}, OriginCountries: []string{"KR", "US"}, OriginalLanguage: "ko", Year: 2025, Rating: 8.4, VoteCount: 400, SeriesStatus: "Returning Series", EpisodeCount: &count}
	if err = catalogindex.StoreMetadataWithRefresh(ctx, db, titleID, meta, true); err != nil {
		t.Fatal(err)
	}
	var status string
	var actual int
	err = db.QueryRow(ctx, `SELECT series_status,episode_count FROM media_titles WHERE id=$1`, titleID).Scan(&status, &actual)
	if err != nil || status != "Returning Series" || actual != 9 {
		t.Fatalf("stale metadata: %s %d %v", status, actual, err)
	}
}
