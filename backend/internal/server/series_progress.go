package server

import (
	"encoding/json"
	"github.com/go-chi/chi/v5"
	"net/http"
	"strings"
)

// An explicit invalid viewer must never fall back to the parent's account scope.
func (s *Server) seriesViewingScope(w http.ResponseWriter, r *http.Request) (string, string, string, bool) {
	user := userIDFromContext(r.Context())
	if user == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "authentication required"})
		return "", "", "", false
	}
	viewer := strings.TrimSpace(r.Header.Get("X-Filmiqoo-Viewer-Profile"))
	maturity := "all"
	if viewer != "" {
		var owned string
		if err := s.db.QueryRow(r.Context(), "SELECT id::text,maturity_level FROM viewer_profiles WHERE id=$1 AND user_id=$2", viewer, user).Scan(&owned, &maturity); err != nil {
			writeJSON(w, http.StatusForbidden, map[string]string{"error": "viewer unavailable"})
			return "", "", "", false
		}
		viewer = owned
		if maturity != "kids" && maturity != "teen" && maturity != "all" {
			writeJSON(w, http.StatusForbidden, map[string]string{"error": "viewer maturity unavailable"})
			return "", "", "", false
		}
	}
	return user, viewer, maturity, true
}

func (s *Server) seriesTargetAllowed(w http.ResponseWriter, r *http.Request, kind, id, maturity string) bool {
	query := "SELECT audience_level FROM media_titles WHERE id=$1 AND kind IN ('series','anime')"
	if kind == "episode" {
		query = "SELECT mt.audience_level FROM episodes e JOIN seasons sn ON sn.id=e.season_id JOIN media_titles mt ON mt.id=sn.media_title_id WHERE e.id=$1"
	}
	if kind == "season" {
		query = "SELECT mt.audience_level FROM seasons sn JOIN media_titles mt ON mt.id=sn.media_title_id WHERE sn.id=$1"
	}
	var audience string
	if err := s.db.QueryRow(r.Context(), query, id).Scan(&audience); err != nil || !viewerAllowsAudience(maturity, audience) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "series target unavailable"})
		return false
	}
	return true
}

func (s *Server) seriesProgress(w http.ResponseWriter, r *http.Request) {
	user, viewer, maturity, ok := s.seriesViewingScope(w, r)
	if !ok {
		return
	}
	id := chi.URLParam(r, "id")
	if !s.seriesTargetAllowed(w, r, "title", id, maturity) {
		return
	}
	rows, err := s.db.Query(r.Context(), `
 WITH progress AS (
  SELECT media_version_id,position_ms,duration_ms,completed,updated_at FROM viewer_watch_progress WHERE viewer_profile_id=NULLIF($2,'')::uuid AND $2<>''
  UNION ALL SELECT media_version_id,position_ms,duration_ms,completed,updated_at FROM watch_progress WHERE user_id=$1 AND $2=''
 ), marks AS (
  SELECT episode_id FROM viewer_episode_seen_marks WHERE viewer_profile_id=NULLIF($2,'')::uuid AND $2<>''
  UNION ALL SELECT episode_id FROM episode_seen_marks WHERE user_id=$1 AND $2=''
 )
 SELECT sn.id::text,sn.season_number,e.id::text,e.episode_number,
 COALESCE(p.position_ms,0),COALESCE(p.duration_ms,0),COALESCE(p.completed,false),EXISTS(SELECT 1 FROM marks m WHERE m.episode_id=e.id)
 FROM seasons sn JOIN episodes e ON e.season_id=sn.id
 LEFT JOIN LATERAL (
  SELECT wp.position_ms,wp.duration_ms,wp.completed FROM progress wp JOIN media_versions mv ON mv.id=wp.media_version_id
  WHERE mv.episode_id=e.id ORDER BY wp.completed DESC,wp.updated_at DESC LIMIT 1
 ) p ON true WHERE sn.media_title_id=$3 ORDER BY sn.season_number,e.episode_number`, user, viewer, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	defer rows.Close()
	items := []map[string]any{}
	var watched, manual int64
	for rows.Next() {
		var season, episode string
		var sn, en int
		var position, duration int64
		var completed, seen bool
		if err = rows.Scan(&season, &sn, &episode, &en, &position, &duration, &completed, &seen); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		fraction := 0.0
		if duration > 0 {
			fraction = float64(position) / float64(duration)
			if fraction < 0 {
				fraction = 0
			}
			if fraction > 1 {
				fraction = 1
			}
		} else if completed {
			fraction = 1
		}
		if completed {
			watched++
		}
		if seen {
			manual++
		}
		items = append(items, map[string]any{"seasonId": season, "seasonNumber": sn, "episodeId": episode, "episodeNumber": en, "positionMs": position, "durationMs": duration, "completed": completed, "progress": fraction, "manualSeen": seen})
	}
	if err = rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	fraction := 0.0
	if len(items) > 0 {
		fraction = float64(watched) / float64(len(items))
	}
	writeJSON(w, http.StatusOK, map[string]any{"mediaTitleId": id, "watchedCount": watched, "totalCount": len(items), "progress": fraction, "items": items, "manualSeenCount": manual, "manualMarksVersion": 1})
}

func (s *Server) setEpisodeWatchedStatus(w http.ResponseWriter, r *http.Request) {
	s.setSeriesManualSeen(w, r, false)
}
func (s *Server) setSeasonWatchedStatus(w http.ResponseWriter, r *http.Request) {
	s.setSeriesManualSeen(w, r, true)
}

// Manual marks never manufacture, overwrite, or erase actual playback/resume/time.
func (s *Server) setSeriesManualSeen(w http.ResponseWriter, r *http.Request, season bool) {
	user, viewer, maturity, ok := s.seriesViewingScope(w, r)
	if !ok {
		return
	}
	var body struct {
		Watched            bool `json:"watched"`
		ManualMarksVersion int  `json:"manualMarksVersion"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	if body.ManualMarksVersion != 1 {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "manual marks require an updated client"})
		return
	}
	id := chi.URLParam(r, "id")
	kind := "episode"
	if season {
		kind = "season"
	}
	if !s.seriesTargetAllowed(w, r, kind, id, maturity) {
		return
	}
	table, owner, ownerID := "episode_seen_marks", "user_id", user
	if viewer != "" {
		table, owner, ownerID = "viewer_episode_seen_marks", "viewer_profile_id", viewer
	}
	// Names above are constants. A season change is one atomic SQL statement.
	query := "DELETE FROM " + table + " WHERE " + owner + "=$1 AND episode_id=$2"
	if season {
		query = "DELETE FROM " + table + " m USING episodes e WHERE m.episode_id=e.id AND m." + owner + "=$1 AND e.season_id=$2"
	}
	if body.Watched {
		selection := "SELECT $1::uuid,id,now() FROM episodes WHERE id=$2"
		if season {
			selection = "SELECT $1::uuid,id,now() FROM episodes WHERE season_id=$2"
		}
		query = "INSERT INTO " + table + "(" + owner + ",episode_id,updated_at) " + selection + " ON CONFLICT(" + owner + ",episode_id) DO UPDATE SET updated_at=EXCLUDED.updated_at"
	}
	if _, err := s.db.Exec(r.Context(), query, ownerID, id); err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"watched": body.Watched, "manualMarksVersion": 1})
}
