package server

import (
	"net/http"
	"time"
)

func (s *Server) followingActivityFeed(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	rows,err:=s.db.Query(r.Context(),`
		WITH activity AS (
			SELECT
				'watching'::text AS activity_type,
				up.user_id AS actor_user_id,
				up.media_title_id,
				up.media_title_id AS entity_id,
				''::text AS body,
				false AS spoiler,
				up.updated_at AS activity_at
			  FROM user_presence up
			  JOIN user_follows uf
			    ON uf.followed_user_id=up.user_id
			   AND uf.follower_user_id=$1
			 WHERE up.state='watching'
			   AND up.visible_until>now()

			UNION ALL

			SELECT
				'post',p.author_user_id,p.media_title_id,p.id,p.body,p.spoiler,
				COALESCE(p.published_at,p.created_at)
			  FROM posts p
			  JOIN user_follows uf
			    ON uf.followed_user_id=p.author_user_id
			   AND uf.follower_user_id=$1
			 WHERE p.status='published'
			   AND COALESCE(p.published_at,p.created_at)>now()-interval '30 days'

			UNION ALL

			SELECT
				'reel',rl.creator_user_id,rl.media_title_id,rl.id,rl.caption,rl.spoiler,
				COALESCE(rl.published_at,rl.created_at)
			  FROM reels rl
			  JOIN user_follows uf
			    ON uf.followed_user_id=rl.creator_user_id
			   AND uf.follower_user_id=$1
			 WHERE rl.status='published'
			   AND COALESCE(rl.published_at,rl.created_at)>now()-interval '30 days'

			UNION ALL

			SELECT
				'review',mr.user_id,mr.media_title_id,mr.id,mr.body,mr.spoiler,mr.updated_at
			  FROM media_reviews mr
			  JOIN user_follows uf
			    ON uf.followed_user_id=mr.user_id
			   AND uf.follower_user_id=$1
			 WHERE mr.updated_at>now()-interval '30 days'
		)
		SELECT
			a.activity_type,a.entity_id::text,a.body,a.spoiler,a.activity_at,
			p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
			mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
			mt.poster_url,mt.backdrop_url,mt.year,mt.rating
		  FROM activity a
		  JOIN profiles p ON p.user_id=a.actor_user_id
		  LEFT JOIN media_titles mt ON mt.id=a.media_title_id
		 WHERE NOT EXISTS (
		     SELECT 1 FROM blocks b
		      WHERE (b.blocker_user_id=$1 AND b.blocked_user_id=a.actor_user_id)
		         OR (b.blocker_user_id=a.actor_user_id AND b.blocked_user_id=$1)
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM user_mutes m
		      WHERE m.muter_user_id=$1 AND m.muted_user_id=a.actor_user_id
		   )
		 ORDER BY a.activity_at DESC
		 LIMIT 120
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var typ,entityID,body string
		var spoiler bool
		var activityAt time.Time
		var actorID,username,displayName,avatar string
		var verified bool

		var mediaID,kind,title,originalTitle,overview,poster,backdrop *string
		var tmdbID *int64
		var year *int
		var rating *float64

		if err:=rows.Scan(
			&typ,&entityID,&body,&spoiler,&activityAt,
			&actorID,&username,&displayName,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&overview,
			&poster,&backdrop,&year,&rating,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"type":typ,
			"entityId":entityID,
			"body":body,
			"spoiler":spoiler,
			"createdAt":activityAt,
			"actor":map[string]any{
				"id":actorID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,
				"originalTitle":originalTitle,"overview":overview,
				"posterUrl":poster,"backdropUrl":backdrop,"year":year,"rating":rating,
			},
		})
	}

	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
