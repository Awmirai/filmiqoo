package server

import "net/http"

func (s *Server) savedReels(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	rows,err:=s.db.Query(r.Context(),`
		SELECT rl.id::text,rl.caption,rl.playback_url,rl.cover_url,rl.duration_ms,
		       rl.like_count,rl.comment_count,rl.save_count,rl.share_count,rl.view_count,rl.spoiler,
		       p.user_id::text,p.display_name,p.username::text,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating
		  FROM reel_saves rs
		  JOIN reels rl ON rl.id=rs.reel_id
		  JOIN profiles p ON p.user_id=rl.creator_user_id
		  LEFT JOIN media_titles mt ON mt.id=rl.media_title_id
		 WHERE rs.user_id=$1 AND rl.status='published'
		 ORDER BY rs.created_at DESC
		 LIMIT 200
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,caption,playbackURL,coverURL,authorID,displayName,username,avatar string
		var duration int
		var likes,comments,saves,shares,views int64
		var spoiler,verified bool
		var mediaID,kind,title,originalTitle,poster,backdrop *string
		var tmdbID *int64
		var year *int
		var rating *float64

		if err:=rows.Scan(
			&id,&caption,&playbackURL,&coverURL,&duration,
			&likes,&comments,&saves,&shares,&views,&spoiler,
			&authorID,&displayName,&username,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"id":id,"caption":caption,"playbackUrl":playbackURL,"coverUrl":coverURL,
			"durationMs":duration,"likes":likes,"comments":comments,"saves":saves,
			"shares":shares,"views":views,"spoiler":spoiler,"savedByMe":true,
			"author":map[string]any{
				"id":authorID,"displayName":displayName,"username":username,
				"avatarUrl":avatar,"verified":verified,
			},
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,
				"originalTitle":originalTitle,"posterUrl":poster,"backdropUrl":backdrop,
				"year":year,"rating":rating,
			},
		})
	}

	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
