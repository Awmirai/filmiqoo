package server

import (
	"fmt"
	"net/http"
	"strconv"
	"time"
)

// Preserve the first-page item shape and original limits for existing clients.
func (s *Server) savedLibraryList(w http.ResponseWriter, r *http.Request, favorite bool) {
	page := 1
	if values, exists := r.URL.Query()["page"]; exists {
		if len(values) != 1 {
			writeError(w, http.StatusBadRequest, fmt.Errorf("invalid library page"))
			return
		}
		parsed, err := strconv.Atoi(values[0])
		if err != nil || parsed < 1 || parsed > 500 {
			writeError(w, http.StatusBadRequest, fmt.Errorf("invalid library page"))
			return
		}
		page = parsed
	}
	table, viewerTable, pageSize := "watchlist", "viewer_watchlist", 300
	if favorite {
		table, viewerTable, pageSize = "favorites", "viewer_favorites", 200
	}
	userID := userIDFromContext(r.Context())
	viewerID := s.viewerProfileID(r, userID)
	maturity := s.viewerMaturityLevel(r, userID)
	// Table names are constants, never request input.
	query := fmt.Sprintf(`WITH saved AS (
 SELECT media_title_id,created_at FROM %s WHERE viewer_profile_id=NULLIF($2,'')::uuid AND $2<>''
 UNION ALL SELECT media_title_id,created_at FROM %s WHERE user_id=$1 AND $2=''
 ) SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
 mt.poster_url,mt.backdrop_url,mt.year,mt.rating,saved.created_at
 FROM saved JOIN media_titles mt ON mt.id=saved.media_title_id
 WHERE ($3='all' OR ($3='teen' AND mt.audience_level IN ('kids','teen')) OR ($3='kids' AND mt.audience_level='kids'))
 ORDER BY saved.created_at DESC,mt.id LIMIT $4 OFFSET $5`, viewerTable, table)
	rows, err := s.db.Query(r.Context(), query, userID, viewerID, maturity, pageSize+1, (page-1)*pageSize)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	items := make([]map[string]any, 0, pageSize+1)
	for rows.Next() {
		var id, kind, title, originalTitle, overview, poster, backdrop string
		var tmdbID *int64
		var year int
		var rating *float64
		var created time.Time
		if err = rows.Scan(&id, &tmdbID, &kind, &title, &originalTitle, &overview, &poster, &backdrop, &year, &rating, &created); err != nil {
			rows.Close()
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		items = append(items, map[string]any{"id": id, "tmdbId": tmdbID, "kind": kind, "title": title, "originalTitle": originalTitle, "overview": overview, "posterUrl": poster, "backdropUrl": backdrop, "year": year, "rating": rating, "savedAt": created})
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	more := len(items) > pageSize
	if more {
		items = items[:pageSize]
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "libraryVersion": 1, "page": page, "hasMore": more})
}
