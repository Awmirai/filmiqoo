package server

import (
	"context"
	"net/http"
)

func (s *Server) personalizedHome(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	preferredKind:=""
	preferredLanguage:=""
	_ = s.db.QueryRow(r.Context(),`
		WITH signals AS (
			SELECT mt.kind,mt.original_language,3::bigint AS weight
			  FROM watch_progress wp
			  JOIN media_versions mv ON mv.id=wp.media_version_id
			  LEFT JOIN episodes e ON e.id=mv.episode_id
			  LEFT JOIN seasons sn ON sn.id=e.season_id
			  JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
			 WHERE wp.user_id=$1
			UNION ALL
			SELECT mt.kind,mt.original_language,5::bigint
			  FROM favorites f
			  JOIN media_titles mt ON mt.id=f.media_title_id
			 WHERE f.user_id=$1
			UNION ALL
			SELECT mt.kind,mt.original_language,4::bigint
			  FROM watchlist wl
			  JOIN media_titles mt ON mt.id=wl.media_title_id
			 WHERE wl.user_id=$1
		)
		SELECT kind,original_language
		  FROM signals
		 WHERE original_language<>''
		 GROUP BY kind,original_language
		 ORDER BY SUM(weight) DESC
		 LIMIT 1
	`,userID).Scan(&preferredKind,&preferredLanguage)

	forYou,err:=s.homeMediaRows(r.Context(),`
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
		       mv.id::text,mv.quality_label,mv.stream_ready
		  FROM media_titles mt
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label,stream_ready
		      FROM media_versions
		     WHERE media_title_id=mt.id
		     ORDER BY preferred DESC,stream_ready DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) mv ON true
		 WHERE mt.visibility='public'
		   AND NOT EXISTS (
		     SELECT 1 FROM favorites f WHERE f.user_id=$1 AND f.media_title_id=mt.id
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM watchlist wl WHERE wl.user_id=$1 AND wl.media_title_id=mt.id
		   )
		 ORDER BY
		   (CASE WHEN $2<>'' AND mt.kind=$2 THEN 5 ELSE 0 END) +
		   (CASE WHEN $3<>'' AND mt.original_language=$3 THEN 7 ELSE 0 END) +
		   COALESCE(mt.rating,0) DESC,
		   mt.updated_at DESC
		 LIMIT 24
	`,userID,preferredKind,preferredLanguage)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	watchlistItems,err:=s.homeMediaRows(r.Context(),`
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
		       mv.id::text,mv.quality_label,mv.stream_ready
		  FROM watchlist wl
		  JOIN media_titles mt ON mt.id=wl.media_title_id
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label,stream_ready
		      FROM media_versions
		     WHERE media_title_id=mt.id
		     ORDER BY preferred DESC,stream_ready DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) mv ON true
		 WHERE wl.user_id=$1 AND mt.visibility='public'
		 ORDER BY wl.created_at DESC
		 LIMIT 20
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	communityHot,err:=s.homeMediaRows(r.Context(),`
		WITH engagement AS (
			SELECT media_title_id,
			       SUM(score) AS score
			  FROM (
				SELECT media_title_id,
				       (1 + like_count*3 + comment_count*4 + save_count*4 + share_count*5)::bigint AS score
				  FROM posts
				 WHERE media_title_id IS NOT NULL AND status='published'
				UNION ALL
				SELECT media_title_id,
				       (1 + like_count*3 + comment_count*4 + save_count*4 + share_count*5 + view_count/20)::bigint
				  FROM reels
				 WHERE media_title_id IS NOT NULL AND status='published'
			  ) x
			 GROUP BY media_title_id
		)
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
		       mv.id::text,mv.quality_label,mv.stream_ready
		  FROM engagement e
		  JOIN media_titles mt ON mt.id=e.media_title_id
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label,stream_ready
		      FROM media_versions
		     WHERE media_title_id=mt.id
		     ORDER BY preferred DESC,stream_ready DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) mv ON true
		 WHERE mt.visibility='public'
		 ORDER BY e.score DESC,COALESCE(mt.rating,0) DESC
		 LIMIT 20
	`)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	newForYou,err:=s.homeMediaRows(r.Context(),`
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
		       mv.id::text,mv.quality_label,mv.stream_ready
		  FROM media_titles mt
		  LEFT JOIN LATERAL (
		    SELECT id,quality_label,stream_ready
		      FROM media_versions
		     WHERE media_title_id=mt.id
		     ORDER BY preferred DESC,stream_ready DESC,height DESC,file_size_bytes DESC
		     LIMIT 1
		  ) mv ON true
		 WHERE mt.visibility='public'
		 ORDER BY
		   CASE WHEN $2<>'' AND mt.original_language=$2 THEN 0 ELSE 1 END,
		   mt.created_at DESC
		 LIMIT 20
	`,userID,preferredLanguage)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{
		"signals":map[string]any{
			"preferredKind":preferredKind,
			"preferredLanguage":preferredLanguage,
		},
		"forYou":forYou,
		"watchlist":watchlistItems,
		"communityHot":communityHot,
		"newForYou":newForYou,
	})
}

func (s *Server) homeMediaRows(ctx context.Context,query string,args ...any) ([]map[string]any,error) {
	rows,err:=s.db.Query(ctx,query,args...)
	if err!=nil { return nil,err }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,kind,title,originalTitle,overview,poster,backdrop string
		var tmdbID *int64
		var year int
		var rating *float64
		var versionID,quality *string
		var ready *bool
		if err:=rows.Scan(
			&id,&tmdbID,&kind,&title,&originalTitle,&overview,
			&poster,&backdrop,&year,&rating,&versionID,&quality,&ready,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"tmdbId":tmdbID,"kind":kind,"title":title,
			"originalTitle":originalTitle,"overview":overview,
			"posterUrl":poster,"backdropUrl":backdrop,
			"year":year,"rating":rating,
			"mediaVersionId":versionID,"quality":quality,"streamReady":ready,
		})
	}
	return items,rows.Err()
}
