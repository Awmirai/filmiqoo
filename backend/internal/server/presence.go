package server

import (
	"context"
	"net/http"
	"time"
)

func (s *Server) shouldPublishPresence(ctx context.Context,userID,viewerID string) bool {
	if viewerID=="" { return true }
	var kids bool
	if err:=s.db.QueryRow(ctx,`
		SELECT kids_mode FROM viewer_profiles
		 WHERE id=$1 AND user_id=$2
	`,viewerID,userID).Scan(&kids); err!=nil {
		return true
	}
	return !kids
}

func (s *Server) setWatchingPresence(
	ctx context.Context,
	userID string,
	viewerID string,
	mediaVersionID string,
	positionMS int64,
) error {
	if !s.shouldPublishPresence(ctx,userID,viewerID) {
		return nil
	}

	_,err:=s.db.Exec(ctx,`
		INSERT INTO user_presence (
			user_id,media_version_id,media_title_id,episode_id,
			state,position_ms,visible_until,last_seen_at,updated_at
		)
		SELECT
			$1,mv.id,COALESCE(mv.media_title_id,sn.media_title_id),mv.episode_id,
			'watching',$3,now()+interval '90 seconds',now(),now()
		  FROM media_versions mv
		  LEFT JOIN episodes e ON e.id=mv.episode_id
		  LEFT JOIN seasons sn ON sn.id=e.season_id
		 WHERE mv.id=$2
		ON CONFLICT (user_id)
		DO UPDATE SET
			media_version_id=EXCLUDED.media_version_id,
			media_title_id=EXCLUDED.media_title_id,
			episode_id=EXCLUDED.episode_id,
			state='watching',
			position_ms=EXCLUDED.position_ms,
			visible_until=EXCLUDED.visible_until,
			last_seen_at=now(),
			updated_at=now()
	`,
		userID,mediaVersionID,clampInt64(positionMS,0,86_400_000),
	)
	return err
}

func (s *Server) clearWatchingPresence(ctx context.Context,userID string) error {
	_,err:=s.db.Exec(ctx,`
		UPDATE user_presence
		   SET state='offline',
		       visible_until=now(),
		       last_seen_at=now(),
		       updated_at=now()
		 WHERE user_id=$1
	`,userID)
	return err
}

func (s *Server) followingWatchActivity(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	rows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       up.position_ms,up.updated_at,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
		       e.id::text,e.episode_number,e.title,
		       sn.season_number
		  FROM user_follows uf
		  JOIN profiles p ON p.user_id=uf.followed_user_id
		  JOIN user_presence up ON up.user_id=uf.followed_user_id
		  JOIN media_titles mt ON mt.id=up.media_title_id
		  LEFT JOIN episodes e ON e.id=up.episode_id
		  LEFT JOIN seasons sn ON sn.id=e.season_id
		 WHERE uf.follower_user_id=$1
		   AND up.state='watching'
		   AND up.visible_until>now()
		   AND NOT EXISTS (
		     SELECT 1 FROM blocks b
		      WHERE (b.blocker_user_id=$1 AND b.blocked_user_id=uf.followed_user_id)
		         OR (b.blocker_user_id=uf.followed_user_id AND b.blocked_user_id=$1)
		   )
		   AND NOT EXISTS (
		     SELECT 1 FROM user_mutes m
		      WHERE m.muter_user_id=$1 AND m.muted_user_id=uf.followed_user_id
		   )
		 ORDER BY up.updated_at DESC
		 LIMIT 40
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var userID2,username,displayName,avatar string
		var verified bool
		var position int64
		var updated time.Time

		var mediaID,kind,title,originalTitle,overview,poster,backdrop string
		var tmdbID *int64
		var year int
		var rating *float64

		var episodeID,episodeTitle *string
		var episodeNumber,seasonNumber *int

		if err:=rows.Scan(
			&userID2,&username,&displayName,&avatar,&verified,
			&position,&updated,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&overview,
			&poster,&backdrop,&year,&rating,
			&episodeID,&episodeNumber,&episodeTitle,&seasonNumber,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"user":map[string]any{
				"id":userID2,
				"username":username,
				"displayName":displayName,
				"avatarUrl":avatar,
				"verified":verified,
			},
			"media":map[string]any{
				"id":mediaID,
				"tmdbId":tmdbID,
				"kind":kind,
				"title":title,
				"originalTitle":originalTitle,
				"overview":overview,
				"posterUrl":poster,
				"backdropUrl":backdrop,
				"year":year,
				"rating":rating,
			},
			"episode":map[string]any{
				"id":episodeID,
				"seasonNumber":seasonNumber,
				"episodeNumber":episodeNumber,
				"title":episodeTitle,
			},
			"positionMs":position,
			"updatedAt":updated,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
