package server

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
)

var pulseEmojiAllowed = map[string]bool{
	"🔥": true,
	"😱": true,
	"😂": true,
	"❤️": true,
	"👀": true,
}

type pulseReactionRequest struct {
	Emoji      string `json:"emoji"`
	PositionMS int64  `json:"positionMs"`
}

func (s *Server) mediaPulse(w http.ResponseWriter, r *http.Request) {
	mediaID := strings.TrimSpace(chi.URLParam(r, "id"))
	if mediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "media id is required"})
		return
	}

	var exists bool
	if err := s.db.QueryRow(
		r.Context(),
		"SELECT EXISTS(SELECT 1 FROM media_titles WHERE id=$1)",
		mediaID,
	).Scan(&exists); err != nil || !exists {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "media title not found"})
		return
	}

	var watchingNow int64
	_ = s.db.QueryRow(r.Context(), `
		SELECT COUNT(DISTINCT ps.user_id)
		  FROM playback_sessions ps
		  JOIN media_versions mv ON mv.id=ps.current_media_version_id
		  LEFT JOIN episodes e ON e.id=mv.episode_id
		  LEFT JOIN seasons sn ON sn.id=e.season_id
		 WHERE COALESCE(mv.media_title_id,sn.media_title_id)=$1
		   AND ps.ended_at IS NULL
		   AND ps.last_heartbeat_at>now()-interval '90 seconds'
	`, mediaID).Scan(&watchingNow)

	counts := map[string]int64{
		"🔥": 0,
		"😱": 0,
		"😂": 0,
		"❤️": 0,
		"👀": 0,
	}
	rows, err := s.db.Query(r.Context(), `
		SELECT emoji,COUNT(*)
		  FROM media_pulse_reactions
		 WHERE media_title_id=$1
		   AND created_at>now()-interval '6 hours'
		 GROUP BY emoji
	`, mediaID)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var emoji string
			var count int64
			if rows.Scan(&emoji, &count) == nil {
				if pulseEmojiAllowed[emoji] {
					counts[emoji] = count
				}
			}
		}
	}

	var recent int64
	for _, count := range counts {
		recent += count
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"watchingNow": watchingNow,
		"reactions":   counts,
		"recent":      recent,
		"live":        watchingNow > 0 || recent >= 5,
	})
}

func (s *Server) reactMediaPulse(w http.ResponseWriter, r *http.Request) {
	mediaID := strings.TrimSpace(chi.URLParam(r, "id"))
	if mediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "media id is required"})
		return
	}

	var body pulseReactionRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	body.Emoji = strings.TrimSpace(body.Emoji)
	if !pulseEmojiAllowed[body.Emoji] {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unsupported reaction"})
		return
	}
	if body.PositionMS < 0 {
		body.PositionMS = 0
	}

	userID := strings.TrimSpace(userIDFromContext(r.Context()))
	if userID == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "authentication required"})
		return
	}

	var exists bool
	if err := s.db.QueryRow(
		r.Context(),
		"SELECT EXISTS(SELECT 1 FROM media_titles WHERE id=$1)",
		mediaID,
	).Scan(&exists); err != nil || !exists {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "media title not found"})
		return
	}

	var tooSoon bool
	_ = s.db.QueryRow(r.Context(), `
		SELECT EXISTS(
			SELECT 1
			  FROM media_pulse_reactions
			 WHERE user_id=$1
			   AND media_title_id=$2
			   AND created_at>now()-interval '8 seconds'
		)
	`, userID, mediaID).Scan(&tooSoon)
	if tooSoon {
		writeJSON(w, http.StatusTooManyRequests, map[string]any{
			"error": "reaction cooldown",
			"retryAfterSeconds": 8,
		})
		return
	}

	if _, err := s.db.Exec(r.Context(), `
		INSERT INTO media_pulse_reactions (
			user_id,media_title_id,emoji,position_ms
		) VALUES ($1,$2,$3,$4)
	`, userID, mediaID, body.Emoji, body.PositionMS); err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	s.mediaPulse(w, r)
}


func (s *Server) trendingMediaPulse(w http.ResponseWriter, r *http.Request) {
	rows,err:=s.db.Query(r.Context(),`
		SELECT
			mt.id::text,
			mt.tmdb_id,
			mt.kind,
			mt.title,
			mt.original_title,
			mt.poster_url,
			mt.backdrop_url,
			mt.year,
			mt.rating,
			COALESCE(active.watching_now,0),
			COALESCE(rx.reactions,0)
		FROM media_titles mt
		LEFT JOIN (
			SELECT
				COALESCE(mv.media_title_id,sn.media_title_id) AS media_title_id,
				COUNT(DISTINCT ps.user_id) AS watching_now
			FROM playback_sessions ps
			JOIN media_versions mv ON mv.id=ps.current_media_version_id
			LEFT JOIN episodes e ON e.id=mv.episode_id
			LEFT JOIN seasons sn ON sn.id=e.season_id
			WHERE ps.ended_at IS NULL
			  AND ps.last_heartbeat_at>now()-interval '90 seconds'
			GROUP BY COALESCE(mv.media_title_id,sn.media_title_id)
		) active ON active.media_title_id=mt.id
		LEFT JOIN (
			SELECT media_title_id,COUNT(*) AS reactions
			FROM media_pulse_reactions
			WHERE created_at>now()-interval '6 hours'
			GROUP BY media_title_id
		) rx ON rx.media_title_id=mt.id
		WHERE mt.visibility='public'
		  AND (
			COALESCE(active.watching_now,0)>0
			OR COALESCE(rx.reactions,0)>0
		  )
		ORDER BY
			COALESCE(active.watching_now,0) DESC,
			COALESCE(rx.reactions,0) DESC,
			mt.updated_at DESC
		LIMIT 12
	`)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,kind,title,originalTitle,poster,backdrop string
		var tmdbID *int64
		var year int
		var rating *float64
		var watchingNow,reactions int64
		if err:=rows.Scan(
			&id,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,
			&year,&rating,&watchingNow,&reactions,
		); err!=nil {
			continue
		}
		items=append(items,map[string]any{
			"id":id,
			"tmdbId":tmdbID,
			"kind":kind,
			"title":title,
			"originalTitle":originalTitle,
			"posterUrl":poster,
			"backdropUrl":backdrop,
			"year":year,
			"rating":rating,
			"watchingNow":watchingNow,
			"reactions":reactions,
			"live":watchingNow>0,
		})
	}

	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
