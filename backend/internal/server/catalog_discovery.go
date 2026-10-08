package server

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

var catalogISOCode = regexp.MustCompile(`^[A-Za-z]{2}$`)

type catalogDiscoveryFilters struct {
	Kind, Section, Sort, Country, Language, Status   string
	Genre, YearFrom, YearTo, RuntimeMax, Page, Limit int
	MinRating                                        float64
	Dubbed, Subtitled                                bool
}

func parseCatalogDiscovery(q url.Values) (catalogDiscoveryFilters, error) {
	known := map[string]bool{"type": true, "section": true, "sort": true, "country": true, "language": true, "status": true, "genreId": true, "yearFrom": true, "yearTo": true, "runtimeMax": true, "page": true, "limit": true, "minRating": true, "persianDubbedOnly": true, "persianSubtitleOnly": true}
	for key, values := range q {
		if !known[key] || len(values) != 1 {
			return catalogDiscoveryFilters{}, fmt.Errorf("invalid discovery parameter")
		}
	}

	f := catalogDiscoveryFilters{Kind: q.Get("type"), Section: q.Get("section"), Sort: q.Get("sort"), Country: strings.ToUpper(q.Get("country")), Language: strings.ToLower(q.Get("language")), Status: q.Get("status"), Page: 1, Limit: 20}
	fail := func() (catalogDiscoveryFilters, error) { return f, fmt.Errorf("invalid discovery filter") }
	if f.Kind != "movie" && f.Kind != "series" {
		return fail()
	}
	if f.Section == "" {
		f.Section = "popular"
	}
	if f.Sort == "" {
		f.Sort = "popular"
	}
	switch f.Section {
	case "popular", "new", "acclaimed", "hidden_gems", "top_rated", "airing", "completed", "miniseries", "dubbed", "subtitled":
	default:
		return fail()
	}
	switch f.Sort {
	case "popular", "newest", "rating", "votes":
	default:
		return fail()
	}
	if f.Country != "" && !catalogISOCode.MatchString(f.Country) {
		return fail()
	}
	if f.Language != "" && !catalogISOCode.MatchString(f.Language) {
		return fail()
	}
	if f.Status != "" && f.Status != "0" && f.Status != "1" && f.Status != "2" && f.Status != "3" && f.Status != "4" && f.Status != "5" {
		return fail()
	}
	if f.Kind == "movie" && (f.Status != "" || f.Section == "airing" || f.Section == "completed" || f.Section == "miniseries") {
		return fail()
	}
	for key, pointer := range map[string]*int{"genreId": &f.Genre, "yearFrom": &f.YearFrom, "yearTo": &f.YearTo, "runtimeMax": &f.RuntimeMax, "page": &f.Page, "limit": &f.Limit} {
		if raw := q.Get(key); raw != "" {
			n, err := strconv.Atoi(raw)
			if err != nil || n <= 0 {
				return fail()
			}
			*pointer = n
		}
	}
	if f.Page > 500 || f.Limit > 40 || f.RuntimeMax > 1440 || (f.YearFrom != 0 && (f.YearFrom < 1000 || f.YearFrom > 9999)) || (f.YearTo != 0 && (f.YearTo < 1000 || f.YearTo > 9999)) || (f.YearFrom != 0 && f.YearTo != 0 && f.YearFrom > f.YearTo) {
		return fail()
	}
	if raw := q.Get("minRating"); raw != "" {
		n, err := strconv.ParseFloat(raw, 64)
		if err != nil || math.IsNaN(n) || math.IsInf(n, 0) || n < 0 || n > 10 {
			return fail()
		}
		f.MinRating = n
	}
	for key, pointer := range map[string]*bool{"persianDubbedOnly": &f.Dubbed, "persianSubtitleOnly": &f.Subtitled} {
		if raw := q.Get(key); raw != "" {
			if raw != "true" && raw != "false" {
				return fail()
			}
			*pointer = raw == "true"
		}
	}
	if f.Section == "dubbed" {
		f.Dubbed = true
	}
	if f.Section == "subtitled" {
		f.Subtitled = true
	}
	return f, nil
}

const catalogVersionJoins = `
 LEFT JOIN LATERAL (
  SELECT v.id,v.quality_label,v.stream_ready,v.created_at FROM (SELECT direct.* FROM media_versions direct WHERE direct.media_title_id=mt.id
 UNION ALL SELECT episode_version.* FROM seasons owner JOIN episodes owned ON owned.season_id=owner.id
 JOIN media_versions episode_version ON episode_version.episode_id=owned.id WHERE owner.media_title_id=mt.id) v
  LEFT JOIN episodes ep ON ep.id=v.episode_id LEFT JOIN seasons se ON se.id=ep.season_id
  ORDER BY v.stream_ready DESC,se.season_number DESC NULLS LAST,ep.episode_number DESC NULLS LAST,v.preferred DESC,v.height DESC,v.file_size_bytes DESC,v.id LIMIT 1
 ) mv ON true
 LEFT JOIN LATERAL (
  SELECT COALESCE(bool_or(v.is_persian_dubbed) FILTER(WHERE v.stream_ready),false) AS has_dub,
   COALESCE(bool_or(v.has_persian_subtitle) FILTER(WHERE v.stream_ready),false) AS has_sub,
   COUNT(DISTINCT v.episode_id) FILTER(WHERE v.stream_ready AND v.is_persian_dubbed)::int AS dubbed_episodes,
   COUNT(DISTINCT v.episode_id) FILTER(WHERE v.stream_ready)::int AS available_episodes,MAX(v.created_at) AS latest_added_at
  FROM (SELECT direct.* FROM media_versions direct WHERE direct.media_title_id=mt.id
 UNION ALL SELECT episode_version.* FROM seasons owner JOIN episodes owned ON owned.season_id=owner.id
 JOIN media_versions episode_version ON episode_version.episode_id=owned.id WHERE owner.media_title_id=mt.id) v LEFT JOIN episodes ep ON ep.id=v.episode_id LEFT JOIN seasons se ON se.id=ep.season_id
 ) av ON true`
const catalogCardColumns = `mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,mt.year,
 mt.poster_url,mt.backdrop_url,mt.rating,mv.id::text,mv.quality_label,mv.stream_ready,
 mt.genre_ids,mt.original_language,mt.origin_countries,NULLIF(mt.runtime_minutes,0),mt.series_status,
 mt.series_type,mt.season_count,mt.episode_count,mt.vote_count,mt.popularity,
 av.has_dub,av.has_sub,av.dubbed_episodes,av.available_episodes`

func (s *Server) catalogCards(ctx context.Context, where string, args []any, order string, limit, offset int) ([]map[string]any, error) {
	args = append(args, limit, offset)
	sql := "SELECT " + catalogCardColumns + " FROM media_titles mt " + catalogVersionJoins + " WHERE " + where + " ORDER BY " + order + fmt.Sprintf(" LIMIT $%d OFFSET $%d", len(args)-1, len(args))
	rows, err := s.db.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []map[string]any{}
	for rows.Next() {
		var id, kind, title, original, overview, poster, backdrop, language, status, seriesType string
		var tmdbID *int64
		var year int
		var rating *float64
		var versionID, quality *string
		var ready *bool
		var genreIDs, countries []byte
		var runtime, seasons, episodes *int
		var voteCount int64
		var popularity float64
		var dubbed, subtitled bool
		var dubbedEpisodes, availableEpisodes int
		if err = rows.Scan(&id, &tmdbID, &kind, &title, &original, &overview, &year, &poster, &backdrop, &rating, &versionID, &quality, &ready, &genreIDs, &language, &countries, &runtime, &status, &seriesType, &seasons, &episodes, &voteCount, &popularity, &dubbed, &subtitled, &dubbedEpisodes, &availableEpisodes); err != nil {
			return nil, err
		}
		var dubCount, availableCount any
		if kind != "movie" {
			dubCount = dubbedEpisodes
			availableCount = availableEpisodes
		}
		items = append(items, map[string]any{"id": id, "tmdbId": tmdbID, "kind": kind, "title": title, "originalTitle": original, "overview": overview, "year": year, "posterUrl": poster, "backdropUrl": backdrop, "rating": rating, "mediaVersionId": versionID, "quality": quality, "streamReady": ready, "genreIds": decodeJSONOrEmptyArray(genreIDs), "originalLanguage": language, "originCountries": decodeJSONOrEmptyArray(countries), "runtimeMinutes": runtime, "seriesStatus": status, "seriesType": seriesType, "seasonCount": seasons, "episodeCount": episodes, "voteCount": voteCount, "popularity": popularity, "hasPersianDub": dubbed, "hasPersianSubtitle": subtitled, "dubbedEpisodeCount": dubCount, "availableEpisodeCount": availableCount})
	}
	return items, rows.Err()
}
func catalogDiscoveryWhere(f catalogDiscoveryFilters, maturity string) (string, []any) {
	clauses := []string{"mt.visibility='public'"}
	args := []any{}
	bind := func(value any) string { args = append(args, value); return fmt.Sprintf("$%d", len(args)) }
	if f.Kind == "movie" {
		clauses = append(clauses, "mt.kind='movie'")
	} else {
		clauses = append(clauses, "mt.kind IN ('series','anime')")
	}
	if maturity == "kids" {
		clauses = append(clauses, "mt.audience_level='kids'")
	} else if maturity == "teen" {
		clauses = append(clauses, "mt.audience_level IN ('kids','teen')")
	}
	if f.Country != "" {
		raw, _ := json.Marshal([]string{f.Country})
		clauses = append(clauses, "mt.origin_countries @> "+bind(string(raw))+"::jsonb")
	}
	if f.Language != "" {
		clauses = append(clauses, "mt.original_language="+bind(f.Language))
	}
	if f.Genre > 0 {
		clauses = append(clauses, "mt.genre_ids @> "+bind(fmt.Sprintf("[%d]", f.Genre))+"::jsonb")
	}
	if f.YearFrom > 0 {
		clauses = append(clauses, "mt.year>="+bind(f.YearFrom))
	}
	if f.YearTo > 0 {
		clauses = append(clauses, "mt.year<="+bind(f.YearTo))
	}
	if f.MinRating > 0 {
		clauses = append(clauses, "mt.rating>="+bind(f.MinRating))
	}
	if f.RuntimeMax > 0 {
		clauses = append(clauses, "mt.runtime_minutes>0 AND mt.runtime_minutes<="+bind(f.RuntimeMax))
	}
	statusNames := map[string]string{"0": "Returning Series", "1": "Planned", "2": "In Production", "3": "Ended", "4": "Canceled", "5": "Pilot"}
	if f.Status != "" {
		clauses = append(clauses, "mt.series_status="+bind(statusNames[f.Status]))
	}
	if f.Dubbed {
		clauses = append(clauses, "av.has_dub")
	}
	if f.Subtitled {
		clauses = append(clauses, "av.has_sub")
	}
	switch f.Section {
	case "acclaimed":
		clauses = append(clauses, "mt.rating>=7.0 AND mt.vote_count>=200")
	case "hidden_gems":
		clauses = append(clauses, "mt.rating>=7.2 AND mt.vote_count BETWEEN 100 AND 2500")
	case "top_rated":
		clauses = append(clauses, "mt.vote_count>=200")
	case "airing":
		clauses = append(clauses, "mt.series_status='Returning Series' AND EXISTS(SELECT 1 FROM seasons s JOIN episodes e ON e.season_id=s.id WHERE s.media_title_id=mt.id AND e.air_date BETWEEN current_date-7 AND current_date+7)")
	case "completed":
		clauses = append(clauses, "mt.series_status IN ('Ended','Canceled')")
	case "miniseries":
		clauses = append(clauses, "mt.series_type='Miniseries'")
	}
	return strings.Join(clauses, " AND "), args
}
func (s *Server) catalogDiscovery(w http.ResponseWriter, r *http.Request) {
	filters, err := parseCatalogDiscovery(r.URL.Query())
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	maturity := "all"
	if userID := userIDFromContext(r.Context()); userID != "" {
		maturity = s.viewerMaturityLevel(r, userID)
	}
	where, args := catalogDiscoveryWhere(filters, maturity)
	var count int
	err = s.db.QueryRow(r.Context(), "SELECT COUNT(*) FROM media_titles mt "+catalogVersionJoins+" WHERE "+where, args...).Scan(&count)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog discovery is unavailable"})
		return
	}
	order := "mt.popularity DESC,mt.id"
	switch filters.Sort {
	case "newest":
		order = "av.latest_added_at DESC NULLS LAST,mt.created_at DESC,mt.id"
	case "rating":
		order = "mt.rating DESC NULLS LAST,mt.vote_count DESC,mt.id"
	case "votes":
		order = "mt.vote_count DESC,mt.rating DESC NULLS LAST,mt.id"
	}
	if filters.Section == "new" && filters.Sort == "popular" {
		order = "av.latest_added_at DESC NULLS LAST,mt.created_at DESC,mt.id"
	}
	if filters.Section == "top_rated" {
		order = "mt.rating DESC NULLS LAST,mt.vote_count DESC,mt.id"
		if count > 250 {
			count = 250
		}
	}
	offset := (filters.Page - 1) * filters.Limit
	limit := filters.Limit
	if filters.Section == "top_rated" && offset+limit > 250 {
		limit = 250 - offset
	}
	items := []map[string]any{}
	if offset < count && limit > 0 {
		items, err = s.catalogCards(r.Context(), where, args, order, limit, offset)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog discovery is unavailable"})
			return
		}
	}
	totalPages := (count + filters.Limit - 1) / filters.Limit
	writeJSON(w, http.StatusOK, map[string]any{"discoveryVersion": 1, "items": items, "page": filters.Page, "totalPages": totalPages, "totalResults": count, "source": "local_catalog"})
}
