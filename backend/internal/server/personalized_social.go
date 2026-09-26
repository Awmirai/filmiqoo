package server

import (
	"net/http"
	"time"
)

func (s *Server) personalizedFeed(w http.ResponseWriter,r *http.Request) {
	_ = s.processScheduledContent(r.Context())
	userID:=userIDFromContext(r.Context())
	rows,err:=s.db.Query(r.Context(),`
		SELECT p.id::text,p.post_type,p.body,p.spoiler,p.like_count,p.comment_count,
		       p.save_count,p.share_count,p.published_at,
		       pr.user_id::text,pr.username::text,pr.display_name,pr.avatar_url,pr.verified,
		       mt.id::text,mt.title,mt.poster_url,
		       EXISTS(SELECT 1 FROM post_reactions prx WHERE prx.post_id=p.id AND prx.user_id=$1),
		       EXISTS(SELECT 1 FROM post_saves psx WHERE psx.post_id=p.id AND psx.user_id=$1)
		  FROM posts p
		  JOIN profiles pr ON pr.user_id=p.author_user_id
		  LEFT JOIN media_titles mt ON mt.id=p.media_title_id
		 WHERE p.status='published'
		   AND NOT EXISTS (
		     SELECT 1 FROM blocks b
		      WHERE (b.blocker_user_id=$1 AND b.blocked_user_id=p.author_user_id)
		         OR (b.blocker_user_id=p.author_user_id AND b.blocked_user_id=$1)
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM user_mutes m
		      WHERE m.muter_user_id=$1 AND m.muted_user_id=p.author_user_id
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM feed_feedback ff
		      WHERE ff.user_id=$1 AND ff.target_type='post'
		        AND ff.target_id=p.id AND ff.action='not_interested'
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM feed_feedback ff
		      WHERE ff.user_id=$1 AND ff.target_type='media'
		        AND ff.target_id=p.media_title_id AND ff.action='not_interested'
		   )
		 ORDER BY
		   (
		     CASE WHEN EXISTS(
		       SELECT 1 FROM user_follows uf
		        WHERE uf.follower_user_id=$1 AND uf.followed_user_id=p.author_user_id
		     ) THEN 120 ELSE 0 END
		     + CASE WHEN p.channel_id IS NOT NULL AND EXISTS(
		       SELECT 1 FROM channel_followers cf
		        WHERE cf.user_id=$1 AND cf.channel_id=p.channel_id
		     ) THEN 80 ELSE 0 END
		     + CASE WHEN p.media_title_id IS NOT NULL AND EXISTS(
		       SELECT 1 FROM favorites f
		        WHERE f.user_id=$1 AND f.media_title_id=p.media_title_id
		     ) THEN 65 ELSE 0 END
		     + CASE WHEN p.media_title_id IS NOT NULL AND EXISTS(
		       SELECT 1 FROM watchlist wl
		        WHERE wl.user_id=$1 AND wl.media_title_id=p.media_title_id
		     ) THEN 55 ELSE 0 END
		     + CASE WHEN EXISTS(
		       SELECT 1 FROM feed_feedback ff
		        WHERE ff.user_id=$1 AND ff.target_type='post'
		          AND ff.target_id=p.id AND ff.action='show_more'
		     ) THEN 90 ELSE 0 END
		     + CASE WHEN EXISTS(
		       SELECT 1
		         FROM post_reactions mine
		         JOIN posts previous ON previous.id=mine.post_id
		        WHERE mine.user_id=$1
		          AND previous.author_user_id=p.author_user_id
		          AND previous.id<>p.id
		     ) THEN 45 ELSE 0 END
		     + CASE
		         WHEN COALESCE(p.published_at,p.created_at)>now()-interval '18 hours' THEN 95
		         WHEN COALESCE(p.published_at,p.created_at)>now()-interval '3 days' THEN 55
		         WHEN COALESCE(p.published_at,p.created_at)>now()-interval '10 days' THEN 20
		         ELSE 0
		       END
		     + LEAST(
		       p.like_count*2 + p.comment_count*4 + p.save_count*5 + p.share_count*6,
		       520
		     )
		   ) DESC,
		   p.published_at DESC NULLS LAST,p.created_at DESC
		 LIMIT 50
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,postType,body,authorID,username,displayName,avatar string
		var spoiler,verified,likedByMe,savedByMe bool
		var likes,comments,saves,shares int64
		var publishedAt *time.Time
		var mediaID,title,poster *string
		if err:=rows.Scan(
			&id,&postType,&body,&spoiler,&likes,&comments,&saves,&shares,&publishedAt,
			&authorID,&username,&displayName,&avatar,&verified,&mediaID,&title,&poster,
			&likedByMe,&savedByMe,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"type":postType,"body":body,"spoiler":spoiler,
			"likes":likes,"comments":comments,"saves":saves,"shares":shares,
			"publishedAt":publishedAt,"likedByMe":likedByMe,"savedByMe":savedByMe,
			"author":map[string]any{
				"id":authorID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
			"media":map[string]any{"id":mediaID,"title":title,"posterUrl":poster},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items,"nextCursor":nil})
}

func (s *Server) personalizedReels(w http.ResponseWriter,r *http.Request) {
	_ = s.processScheduledContent(r.Context())
	userID:=userIDFromContext(r.Context())
	rows,err:=s.db.Query(r.Context(),`
		WITH playback_quality AS (
		  SELECT reel_id,
		         COUNT(*) AS samples,
		         COUNT(*) FILTER (WHERE completed) AS completions,
		         COUNT(*) FILTER (WHERE rewatched) AS rewatches,
		         AVG(
		           CASE
		             WHEN duration_ms>0 THEN LEAST(watch_ms::numeric/duration_ms::numeric,1.5)
		             ELSE 0
		           END
		         ) AS watch_ratio
		    FROM reel_playback_events
		   WHERE created_at>now()-interval '14 days'
		   GROUP BY reel_id
		)
		SELECT rl.id::text,rl.caption,rl.playback_url,rl.cover_url,rl.duration_ms,
		       rl.like_count,rl.comment_count,rl.save_count,rl.share_count,rl.view_count,rl.spoiler,
		       EXISTS(SELECT 1 FROM reel_likes rlx WHERE rlx.reel_id=rl.id AND rlx.user_id=$1),
		       EXISTS(SELECT 1 FROM reel_saves rsx WHERE rsx.reel_id=rl.id AND rsx.user_id=$1),
		       EXISTS(
		         SELECT 1 FROM user_follows uf
		          WHERE uf.follower_user_id=$1 AND uf.followed_user_id=rl.creator_user_id
		       ),
		       EXISTS(
		         SELECT 1 FROM follow_requests fr
		          WHERE fr.requester_user_id=$1
		            AND fr.target_user_id=rl.creator_user_id
		            AND fr.status='pending'
		       ),
		       p.user_id::text,p.display_name,p.username::text,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating
		  FROM reels rl
		  JOIN profiles p ON p.user_id=rl.creator_user_id
		  LEFT JOIN media_titles mt ON mt.id=rl.media_title_id
		  LEFT JOIN playback_quality pq ON pq.reel_id=rl.id
		 WHERE rl.status='published'
		   AND NOT EXISTS (
		     SELECT 1 FROM blocks b
		      WHERE (b.blocker_user_id=$1 AND b.blocked_user_id=rl.creator_user_id)
		         OR (b.blocker_user_id=rl.creator_user_id AND b.blocked_user_id=$1)
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM user_mutes m
		      WHERE m.muter_user_id=$1 AND m.muted_user_id=rl.creator_user_id
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM feed_feedback ff
		      WHERE ff.user_id=$1 AND ff.target_type='reel'
		        AND ff.target_id=rl.id AND ff.action='not_interested'
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM feed_feedback ff
		      WHERE ff.user_id=$1 AND ff.target_type='media'
		        AND ff.target_id=rl.media_title_id AND ff.action='not_interested'
		   )
		 ORDER BY
		   (
		     CASE WHEN EXISTS(
		       SELECT 1 FROM user_follows uf
		        WHERE uf.follower_user_id=$1 AND uf.followed_user_id=rl.creator_user_id
		     ) THEN 140 ELSE 0 END
		     + CASE WHEN rl.channel_id IS NOT NULL AND EXISTS(
		       SELECT 1 FROM channel_followers cf
		        WHERE cf.user_id=$1 AND cf.channel_id=rl.channel_id
		     ) THEN 90 ELSE 0 END
		     + CASE WHEN rl.media_title_id IS NOT NULL AND EXISTS(
		       SELECT 1 FROM favorites f
		        WHERE f.user_id=$1 AND f.media_title_id=rl.media_title_id
		     ) THEN 70 ELSE 0 END
		     + CASE WHEN rl.media_title_id IS NOT NULL AND EXISTS(
		       SELECT 1 FROM watchlist wl
		        WHERE wl.user_id=$1 AND wl.media_title_id=rl.media_title_id
		     ) THEN 60 ELSE 0 END
		     + CASE WHEN EXISTS(
		       SELECT 1 FROM feed_feedback ff
		        WHERE ff.user_id=$1 AND ff.target_type='reel'
		          AND ff.target_id=rl.id AND ff.action='show_more'
		     ) THEN 100 ELSE 0 END
		     + CASE WHEN EXISTS(
		       SELECT 1
		         FROM reel_likes mine
		         JOIN reels previous ON previous.id=mine.reel_id
		        WHERE mine.user_id=$1
		          AND previous.creator_user_id=rl.creator_user_id
		          AND previous.id<>rl.id
		     ) THEN 50 ELSE 0 END
		     + CASE WHEN EXISTS(
		       SELECT 1
		         FROM reel_playback_events own_watch
		         JOIN reels watched ON watched.id=own_watch.reel_id
		        WHERE own_watch.user_id=$1
		          AND watched.creator_user_id=rl.creator_user_id
		          AND watched.id<>rl.id
		          AND (own_watch.completed OR own_watch.rewatched)
		     ) THEN 60 ELSE 0 END
		     + CASE
		         WHEN COALESCE(rl.published_at,rl.created_at)>now()-interval '12 hours' THEN 110
		         WHEN COALESCE(rl.published_at,rl.created_at)>now()-interval '2 days' THEN 65
		         WHEN COALESCE(rl.published_at,rl.created_at)>now()-interval '7 days' THEN 25
		         ELSE 0
		       END
		     + LEAST(
		       (
		         COALESCE(pq.watch_ratio,0)*180 +
		         LEAST(COALESCE(pq.completions,0),40)*3 +
		         LEAST(COALESCE(pq.rewatches,0),20)*5
		       ),
		       260
		     )
		     + LEAST(
		       rl.like_count*2 + rl.comment_count*4 + rl.save_count*5 +
		       rl.share_count*6 + rl.view_count/25,
		       650
		     )
		   ) DESC,
		   rl.published_at DESC NULLS LAST,rl.created_at DESC
		 LIMIT 60
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,caption,playbackURL,coverURL,authorID,displayName,username,avatar string
		var duration int
		var likes,comments,saves,shares,views int64
		var spoiler,likedByMe,savedByMe,followingAuthor,followPending,verified bool
		var mediaID,kind,title,originalTitle,poster,backdrop *string
		var tmdbID *int64
		var year *int
		var rating *float64
		if err:=rows.Scan(
			&id,&caption,&playbackURL,&coverURL,&duration,
			&likes,&comments,&saves,&shares,&views,&spoiler,&likedByMe,&savedByMe,
			&followingAuthor,&followPending,
			&authorID,&displayName,&username,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"id":id,"caption":caption,"playbackUrl":playbackURL,"coverUrl":coverURL,
			"durationMs":duration,"likes":likes,"comments":comments,"saves":saves,
			"shares":shares,"views":views,"spoiler":spoiler,
			"likedByMe":likedByMe,"savedByMe":savedByMe,
			"followingAuthor":followingAuthor,"followPending":followPending,
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
	writeJSON(w,http.StatusOK,map[string]any{"items":items,"nextCursor":nil})
}

func (s *Server) personalizedStories(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	rows,err:=s.db.Query(r.Context(),`
		SELECT st.id::text,st.story_type,st.media_url,st.thumbnail_url,st.caption,st.spoiler,
		       st.close_friends_only,st.view_count,st.created_at,st.expires_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,
		       mt.backdrop_url,mt.year,mt.rating
		  FROM stories st
		  JOIN profiles p ON p.user_id=st.author_user_id
		  LEFT JOIN media_titles mt ON mt.id=st.media_title_id
		 WHERE st.expires_at>now()
		   AND (
		     st.close_friends_only=false
		     OR st.author_user_id=$1
		     OR EXISTS (
		       SELECT 1 FROM close_friends cf
		        WHERE cf.owner_user_id=st.author_user_id
		          AND cf.friend_user_id=$1
		     )
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM blocks b
		      WHERE (b.blocker_user_id=$1 AND b.blocked_user_id=st.author_user_id)
		         OR (b.blocker_user_id=st.author_user_id AND b.blocked_user_id=$1)
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM user_mutes m
		      WHERE m.muter_user_id=$1 AND m.muted_user_id=st.author_user_id
		   )
		 ORDER BY
		   CASE WHEN EXISTS(
		     SELECT 1 FROM user_follows uf
		      WHERE uf.follower_user_id=$1 AND uf.followed_user_id=st.author_user_id
		   ) THEN 0 ELSE 1 END,
		   st.created_at DESC
		 LIMIT 100
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,typ,mediaURL,thumb,caption,userID2,username,displayName,avatar string
		var spoiler,closeFriendsOnly,verified bool
		var views int64
		var created,expires time.Time
		var mediaID,kind,title,originalTitle,poster,backdrop *string
		var tmdbID *int64
		var year *int
		var rating *float64
		if err:=rows.Scan(
			&id,&typ,&mediaURL,&thumb,&caption,&spoiler,&closeFriendsOnly,&views,&created,&expires,
			&userID2,&username,&displayName,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"id":id,"type":typ,"mediaUrl":mediaURL,"thumbnailUrl":thumb,
			"caption":caption,"spoiler":spoiler,"closeFriendsOnly":closeFriendsOnly,"views":views,
			"createdAt":created,"expiresAt":expires,
			"author":map[string]any{
				"id":userID2,"username":username,"displayName":displayName,
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
