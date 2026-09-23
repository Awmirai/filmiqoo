package server

import (
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) continueWatching(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	rows,err:=s.db.Query(r.Context(),`
		SELECT wp.media_version_id::text,wp.position_ms,wp.duration_ms,wp.completed,wp.updated_at,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating,mv.quality_label,
		       e.id::text,e.episode_number,e.name,s.season_number
		  FROM watch_progress wp
		  JOIN media_versions mv ON mv.id=wp.media_version_id
		  LEFT JOIN episodes e ON e.id=mv.episode_id
		  LEFT JOIN seasons s ON s.id=e.season_id
		  JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,s.media_title_id)
		 WHERE wp.user_id=$1
		   AND wp.completed=false
		   AND wp.position_ms>0
		   AND (wp.duration_ms=0 OR wp.position_ms < wp.duration_ms*0.95)
		 ORDER BY wp.updated_at DESC
		 LIMIT 30
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var versionID,kind,title,originalTitle,poster,backdrop,quality string
		var position,duration int64
		var completed bool
		var updated time.Time
		var mediaID string
		var tmdbID *int64
		var year int
		var rating *float64
		var episodeID,episodeName *string
		var episodeNumber,seasonNumber *int
		if err:=rows.Scan(
			&versionID,&position,&duration,&completed,&updated,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,&quality,
			&episodeID,&episodeNumber,&episodeName,&seasonNumber,
		); err!=nil { continue }

		progress:=0.0
		if duration>0 { progress=float64(position)/float64(duration) }
		items=append(items,map[string]any{
			"mediaVersionId":versionID,
			"positionMs":position,"durationMs":duration,"progress":progress,"updatedAt":updated,
			"quality":quality,
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,"originalTitle":originalTitle,
				"posterUrl":poster,"backdropUrl":backdrop,"year":year,"rating":rating,
			},
			"episode":map[string]any{
				"id":episodeID,"seasonNumber":seasonNumber,"episodeNumber":episodeNumber,"name":episodeName,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) history(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	rows,err:=s.db.Query(r.Context(),`
		SELECT wp.media_version_id::text,wp.position_ms,wp.duration_ms,wp.completed,wp.updated_at,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating,mv.quality_label,
		       e.episode_number,e.name,s.season_number
		  FROM watch_progress wp
		  JOIN media_versions mv ON mv.id=wp.media_version_id
		  LEFT JOIN episodes e ON e.id=mv.episode_id
		  LEFT JOIN seasons s ON s.id=e.season_id
		  JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,s.media_title_id)
		 WHERE wp.user_id=$1
		 ORDER BY wp.updated_at DESC
		 LIMIT 100
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var versionID,kind,title,originalTitle,poster,backdrop,quality string
		var position,duration int64
		var completed bool
		var updated time.Time
		var mediaID string
		var tmdbID *int64
		var year int
		var rating *float64
		var episodeName *string
		var episodeNumber,seasonNumber *int
		if err:=rows.Scan(&versionID,&position,&duration,&completed,&updated,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,&quality,
			&episodeNumber,&episodeName,&seasonNumber); err!=nil { continue }
		items=append(items,map[string]any{
			"mediaVersionId":versionID,"positionMs":position,"durationMs":duration,"completed":completed,
			"updatedAt":updated,"quality":quality,
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,"originalTitle":originalTitle,
				"posterUrl":poster,"backdropUrl":backdrop,"year":year,"rating":rating,
			},
			"episode":map[string]any{"seasonNumber":seasonNumber,"episodeNumber":episodeNumber,"name":episodeName},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) favorites(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	rows,err:=s.db.Query(r.Context(),`
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,mt.poster_url,
		       mt.backdrop_url,mt.year,mt.rating,f.created_at
		  FROM favorites f
		  JOIN media_titles mt ON mt.id=f.media_title_id
		 WHERE f.user_id=$1
		 ORDER BY f.created_at DESC
		 LIMIT 200
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,kind,title,originalTitle,overview,poster,backdrop string
		var tmdbID *int64
		var year int
		var rating *float64
		var created time.Time
		if err:=rows.Scan(&id,&tmdbID,&kind,&title,&originalTitle,&overview,&poster,&backdrop,&year,&rating,&created); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"tmdbId":tmdbID,"kind":kind,"title":title,"originalTitle":originalTitle,
			"overview":overview,"posterUrl":poster,"backdropUrl":backdrop,"year":year,"rating":rating,
			"savedAt":created,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) toggleFavorite(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	mediaID:=chi.URLParam(r,"id")
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var exists bool
	_ = tx.QueryRow(r.Context(),"SELECT EXISTS(SELECT 1 FROM favorites WHERE user_id=$1 AND media_title_id=$2)",userID,mediaID).Scan(&exists)
	if exists {
		_,err=tx.Exec(r.Context(),"DELETE FROM favorites WHERE user_id=$1 AND media_title_id=$2",userID,mediaID)
	} else {
		_,err=tx.Exec(r.Context(),"INSERT INTO favorites (user_id,media_title_id) VALUES ($1,$2)",userID,mediaID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusOK,map[string]any{"favorite":!exists})
}

func (s *Server) libraryStats(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var distinctTitles,completedVersions int64
	var watchedMs int64
	err:=s.db.QueryRow(r.Context(),`
		SELECT
			COUNT(DISTINCT COALESCE(mv.media_title_id,s.media_title_id)),
			COUNT(*) FILTER (WHERE wp.completed=true),
			COALESCE(SUM(LEAST(wp.position_ms,CASE WHEN wp.duration_ms>0 THEN wp.duration_ms ELSE wp.position_ms END)),0)
		  FROM watch_progress wp
		  JOIN media_versions mv ON mv.id=wp.media_version_id
		  LEFT JOIN episodes e ON e.id=mv.episode_id
		  LEFT JOIN seasons s ON s.id=e.season_id
		 WHERE wp.user_id=$1
	`,userID).Scan(&distinctTitles,&completedVersions,&watchedMs)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var favoritesCount int64
	_ = s.db.QueryRow(r.Context(),"SELECT COUNT(*) FROM favorites WHERE user_id=$1",userID).Scan(&favoritesCount)

	writeJSON(w,http.StatusOK,map[string]any{
		"distinctTitles":distinctTitles,
		"completedVersions":completedVersions,
		"watchTimeMinutes":watchedMs/60000,
		"favorites":favoritesCount,
	})
}


func (s *Server) removeHistoryItem(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	versionID:=chi.URLParam(r,"versionID")
	_,err:=s.db.Exec(r.Context(),
		"DELETE FROM watch_progress WHERE user_id=$1 AND media_version_id=$2",
		userID,versionID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) clearHistory(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	_,err:=s.db.Exec(r.Context(),"DELETE FROM watch_progress WHERE user_id=$1",userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	w.WriteHeader(http.StatusNoContent)
}
