package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var tmdbProxyQueryKeys = map[string]struct{}{
	"air_date.gte": {}, "air_date.lte": {}, "vote_count.lte": {}, "vote_average.gte": {}, "vote_average.lte": {},
	"include_video": {}, "without_genres": {}, "with_runtime.gte": {}, "with_runtime.lte": {}, "with_status": {}, "with_type": {}, "timezone": {},
	"primary_release_year": {}, "first_air_date_year": {}, "year": {}, "region": {},
	"primary_release_date.gte": {},
	"primary_release_date.lte": {},
	"first_air_date.gte":       {},
	"first_air_date.lte":       {},
	"vote_count.gte":           {},
	"language":                 {},
	"page":                     {},
	"sort_by":                  {},
	"with_origin_country":      {},
	"include_adult":            {},
	"with_original_language":   {},
	"with_genres":              {},
	"query":                    {},
	"append_to_response":       {},
}

func (s *Server) tmdbProxy(w http.ResponseWriter, r *http.Request) {
	if s.tmdb == nil || !s.tmdb.Enabled() {
		writeError(w, http.StatusServiceUnavailable, errors.New("TMDB metadata service is not configured"))
		return
	}

	path := strings.TrimSpace(r.URL.Query().Get("path"))
	if !allowedTMDBProxyPath(path) {
		writeError(w, http.StatusBadRequest, errors.New("unsupported TMDB metadata path"))
		return
	}

	params, err := tmdbProxyParameters(path, r.URL.Query())
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}

	raw, err := s.tmdb.RawJSON(r.Context(), path, params)
	if err != nil {
		writeError(w, http.StatusBadGateway, errors.New("TMDB metadata service is temporarily unavailable"))
		return
	}

	payload, err := tmdbDiscoveryEnvelope(raw, params)
	if err != nil {
		writeError(w, http.StatusBadGateway, errors.New("TMDB metadata response is invalid"))
		return
	}
	writeJSON(w, http.StatusOK, payload)
}

func allowedTMDBProxyPath(path string) bool {
	switch path {
	case "trending/movie/day", "trending/movie/week", "trending/tv/day", "trending/tv/week",
		"movie/top_rated", "tv/top_rated", "tv/on_the_air", "tv/airing_today", "search/movie", "search/tv",
		"genre/movie/list", "genre/tv/list", "configuration/countries", "configuration/languages":
		return true
	case "trending/all/day",
		"trending/all/week",
		"movie/popular",
		"tv/popular",
		"discover/movie",
		"discover/tv",
		"search/multi":
		return true
	}

	parts := strings.Split(path, "/")
	if len(parts) == 4 && parts[0] == "tv" && parts[2] == "season" {
		id, e1 := strconv.ParseInt(parts[1], 10, 64)
		season, e2 := strconv.Atoi(parts[3])
		return e1 == nil && id > 0 && e2 == nil && season >= 0 && season <= 1000
	}
	if len(parts) == 3 {
		if parts[2] != "external_ids" {
			return false
		}
		switch parts[0] {
		case "movie", "tv":
		default:
			return false
		}
		id, err := strconv.ParseInt(parts[1], 10, 64)
		return err == nil && id > 0
	}

	if len(parts) != 2 {
		return false
	}
	switch parts[0] {
	case "movie", "tv", "person", "collection":
	default:
		return false
	}
	id, err := strconv.ParseInt(parts[1], 10, 64)
	return err == nil && id > 0
}

func tmdbDiscoveryEnvelope(raw json.RawMessage, params url.Values) (map[string]any, error) {
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, err
	}
	payload, ok := value.(map[string]any)
	if !ok {
		if _, array := value.([]any); !array {
			return nil, errors.New("invalid metadata shape")
		}
		payload = map[string]any{"results": value}
	}
	applied := map[string]string{}
	for key := range params {
		applied[key] = params.Get(key)
	}
	payload["_filmiqooDiscovery"] = map[string]any{"version": 1, "provider": "tmdb", "appliedParameters": applied}
	return payload, nil
}
func tmdbProxyParameters(path string, query url.Values) (url.Values, error) {
	out := url.Values{}
	for key, values := range query {
		if key == "path" {
			continue
		}
		if _, allowed := tmdbProxyQueryKeys[key]; !allowed {
			if strings.HasPrefix(key, "with_") || strings.HasPrefix(key, "vote_") || strings.HasPrefix(key, "air_") {
				return nil, fmt.Errorf("unsupported TMDB filter %s", key)
			}
			continue
		}
		if len(values) != 1 {
			return nil, fmt.Errorf("duplicate TMDB parameter %s", key)
		}
		value := strings.TrimSpace(values[0])
		if value == "" {
			continue
		}
		if !tmdbParameterSupported(path, key) {
			return nil, fmt.Errorf("TMDB parameter %s is unsupported for this path", key)
		}
		if len(value) > 600 {
			return nil, errors.New("TMDB parameter is too long")
		}
		switch {
		case key == "page":
			n, err := strconv.Atoi(value)
			if err != nil || n < 1 || n > 500 {
				return nil, errors.New("invalid TMDB page")
			}
		case strings.Contains(key, "date."):
			if _, err := time.Parse("2006-01-02", value); err != nil {
				return nil, errors.New("invalid TMDB date")
			}
		case strings.HasPrefix(key, "vote_average."):
			n, err := strconv.ParseFloat(value, 64)
			if err != nil || n != n || n < 0 || n > 10 {
				return nil, errors.New("invalid TMDB rating")
			}
		case strings.HasPrefix(key, "vote_count."):
			n, err := strconv.Atoi(value)
			if err != nil || n < 0 {
				return nil, errors.New("invalid TMDB vote count")
			}
		case strings.HasPrefix(key, "with_runtime."):
			n, err := strconv.Atoi(value)
			if err != nil || n < 1 || n > 1440 {
				return nil, errors.New("invalid TMDB runtime")
			}
		case key == "with_status":
			if !regexp.MustCompile(`^[0-5]([|,][0-5])*$`).MatchString(value) {
				return nil, errors.New("invalid TV status")
			}
		case key == "with_type":
			if !regexp.MustCompile(`^[0-6]([|,][0-6])*$`).MatchString(value) {
				return nil, errors.New("invalid TV type")
			}
		case key == "with_genres" || key == "without_genres":
			if !regexp.MustCompile(`^[1-9][0-9]*([|,][1-9][0-9]*)*$`).MatchString(value) {
				return nil, errors.New("invalid TMDB genres")
			}
		case key == "with_origin_country" || key == "region":
			if !regexp.MustCompile(`^[A-Za-z]{2}$`).MatchString(value) {
				return nil, errors.New("invalid TMDB country")
			}
		case key == "with_original_language":
			if !regexp.MustCompile(`^[A-Za-z]{2}$`).MatchString(value) {
				return nil, errors.New("invalid TMDB language")
			}
		case key == "include_adult" || key == "include_video":
			if value != "false" {
				return nil, errors.New("adult/video discovery is disabled")
			}
		case key == "timezone":
			if _, err := time.LoadLocation(value); err != nil {
				return nil, errors.New("invalid timezone")
			}
		case strings.HasSuffix(key, "year"):
			n, err := strconv.Atoi(value)
			if err != nil || n < 1000 || n > 9999 {
				return nil, errors.New("invalid TMDB year")
			}
		case key == "sort_by":
			if !regexp.MustCompile(`^(popularity|vote_average|vote_count|primary_release_date|first_air_date|original_title|original_name)\.(asc|desc)$`).MatchString(value) {
				return nil, errors.New("invalid TMDB sort")
			}
		case key == "append_to_response":
			if !regexp.MustCompile(`^[a-z_]+(,[a-z_]+){0,19}$`).MatchString(value) {
				return nil, errors.New("invalid appended metadata")
			}
		}
		out.Set(key, value)
	}
	return out, nil
}
func tmdbParameterSupported(path, key string) bool {
	if key == "language" {
		return true
	}
	if key == "append_to_response" {
		return len(strings.Split(path, "/")) == 2 && !strings.HasPrefix(path, "configuration/") && !strings.HasPrefix(path, "discover/") && !strings.HasPrefix(path, "search/")
	}
	if key == "query" {
		return strings.HasPrefix(path, "search/")
	}
	if key == "page" {
		return strings.HasPrefix(path, "discover/") || strings.HasPrefix(path, "search/") || strings.HasPrefix(path, "trending/") || strings.HasSuffix(path, "/popular") || strings.HasSuffix(path, "/top_rated") || strings.HasSuffix(path, "/on_the_air") || strings.HasSuffix(path, "/airing_today")
	}
	if key == "include_adult" {
		return strings.HasPrefix(path, "search/") || strings.HasPrefix(path, "discover/")
	}
	if key == "year" || key == "first_air_date_year" {
		return strings.HasPrefix(path, "search/") || strings.HasPrefix(path, "discover/")
	}
	if !strings.HasPrefix(path, "discover/") {
		return false
	}
	if key == "with_status" || key == "with_type" || key == "timezone" || strings.HasPrefix(key, "air_date.") || strings.HasPrefix(key, "first_air_date.") {
		return path == "discover/tv"
	}
	if strings.HasPrefix(key, "primary_release_") || key == "include_video" || key == "region" {
		return path == "discover/movie"
	}
	return true
}
