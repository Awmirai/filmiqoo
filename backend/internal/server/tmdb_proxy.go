package server

import (
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

var tmdbProxyQueryKeys = map[string]struct{}{
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

	params := url.Values{}
	for key := range tmdbProxyQueryKeys {
		for _, value := range r.URL.Query()[key] {
			value = strings.TrimSpace(value)
			if value != "" {
				params.Add(key, value)
			}
		}
	}

	raw, err := s.tmdb.RawJSON(r.Context(), path, params)
	if err != nil {
		writeError(w, http.StatusBadGateway, errors.New("TMDB metadata service is temporarily unavailable"))
		return
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(raw)
}

func allowedTMDBProxyPath(path string) bool {
	switch path {
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
