package server

import (
	"net/http"
	"strings"
)

func (s *Server) universalSearch(w http.ResponseWriter,r *http.Request) {
	query:=strings.TrimSpace(r.URL.Query().Get("q"))
	pattern:="%"+query+"%"

	media:=make([]map[string]any,0)
	mediaRows,err:=s.db.Query(r.Context(),`
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,mt.year,
		       mt.poster_url,mt.backdrop_url,mt.rating,
		       mv.id::text,mv.quality_label,mv.stream_ready
		  FROM media_titles mt
		  LEFT JOIN LATERAL (
			SELECT id,quality_label,stream_ready
			  FROM media_versions
			 WHERE media_title_id=mt.id
			 ORDER BY preferred DESC,height DESC,file_size_bytes DESC
			 LIMIT 1
		  ) mv ON true
		 WHERE mt.visibility='public'
		   AND ($1='' OR mt.title ILIKE $2 OR mt.original_title ILIKE $2 OR mt.overview ILIKE $2)
		 ORDER BY
		   CASE WHEN $1<>'' AND lower(mt.title)=lower($1) THEN 0 ELSE 1 END,
		   mt.rating DESC NULLS LAST,
		   mt.created_at DESC
		 LIMIT 30
	`,query,pattern)
	if err==nil {
		defer mediaRows.Close()
		for mediaRows.Next() {
			var id,kind,title,originalTitle,overview,poster,backdrop string
			var tmdbID *int64
			var year int
			var rating *float64
			var versionID,quality *string
			var ready *bool
			if err:=mediaRows.Scan(
				&id,&tmdbID,&kind,&title,&originalTitle,&overview,&year,
				&poster,&backdrop,&rating,&versionID,&quality,&ready,
			); err!=nil { continue }

			media=append(media,map[string]any{
				"id":id,"tmdbId":tmdbID,"kind":kind,"title":title,"originalTitle":originalTitle,
				"overview":overview,"year":year,"posterUrl":poster,"backdropUrl":backdrop,
				"rating":rating,"mediaVersionId":versionID,"quality":quality,"streamReady":ready,
			})
		}
	}

	users:=make([]map[string]any,0)
	userRows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.bio,p.avatar_url,p.cover_url,
		       p.verified,p.follower_count,p.following_count,p.post_count,p.reel_count
		  FROM profiles p
		 WHERE p.private_account=false
		   AND ($1='' OR p.username::text ILIKE $2 OR p.display_name ILIKE $2 OR p.bio ILIKE $2)
		 ORDER BY
		   CASE WHEN $1<>'' AND lower(p.username::text)=lower($1) THEN 0 ELSE 1 END,
		   p.follower_count DESC,
		   p.updated_at DESC
		 LIMIT 20
	`,query,pattern)
	if err==nil {
		defer userRows.Close()
		for userRows.Next() {
			var id,username,displayName,bio,avatar,cover string
			var verified bool
			var followers,following,posts,reels int64
			if err:=userRows.Scan(
				&id,&username,&displayName,&bio,&avatar,&cover,&verified,
				&followers,&following,&posts,&reels,
			); err!=nil { continue }
			users=append(users,map[string]any{
				"id":id,"username":username,"displayName":displayName,"bio":bio,
				"avatarUrl":avatar,"coverUrl":cover,"verified":verified,
				"followers":followers,"following":following,"posts":posts,"reels":reels,
			})
		}
	}

	channels:=make([]map[string]any,0)
	channelRows,err:=s.db.Query(r.Context(),`
		SELECT id::text,slug::text,name,bio,avatar_url,cover_url,verified,
		       follower_count,post_count,reel_count
		  FROM channels
		 WHERE visibility='public'
		   AND ($1='' OR slug::text ILIKE $2 OR name ILIKE $2 OR bio ILIKE $2)
		 ORDER BY
		   CASE WHEN $1<>'' AND lower(slug::text)=lower($1) THEN 0 ELSE 1 END,
		   follower_count DESC,
		   created_at DESC
		 LIMIT 20
	`,query,pattern)
	if err==nil {
		defer channelRows.Close()
		for channelRows.Next() {
			var id,slug,name,bio,avatar,cover string
			var verified bool
			var followers,posts,reels int64
			if err:=channelRows.Scan(
				&id,&slug,&name,&bio,&avatar,&cover,&verified,&followers,&posts,&reels,
			); err!=nil { continue }
			channels=append(channels,map[string]any{
				"id":id,"slug":slug,"name":name,"bio":bio,"avatarUrl":avatar,"coverUrl":cover,
				"verified":verified,"followers":followers,"posts":posts,"reels":reels,
			})
		}
	}

	reels:=make([]map[string]any,0)
	reelRows,err:=s.db.Query(r.Context(),`
		SELECT r.id::text,r.caption,r.playback_url,r.cover_url,r.duration_ms,
		       r.like_count,r.comment_count,r.view_count,r.spoiler,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.title,mt.poster_url
		  FROM reels r
		  JOIN profiles p ON p.user_id=r.creator_user_id
		  LEFT JOIN media_titles mt ON mt.id=r.media_title_id
		 WHERE r.status='published'
		   AND ($1='' OR r.caption ILIKE $2 OR p.username::text ILIKE $2 OR p.display_name ILIKE $2 OR mt.title ILIKE $2)
		 ORDER BY r.view_count DESC,r.published_at DESC NULLS LAST
		 LIMIT 20
	`,query,pattern)
	if err==nil {
		defer reelRows.Close()
		for reelRows.Next() {
			var id,caption,playback,cover,userID,username,displayName,avatar string
			var duration int
			var likes,comments,views int64
			var spoiler,verified bool
			var mediaID,title,poster *string
			if err:=reelRows.Scan(
				&id,&caption,&playback,&cover,&duration,&likes,&comments,&views,&spoiler,
				&userID,&username,&displayName,&avatar,&verified,&mediaID,&title,&poster,
			); err!=nil { continue }
			reels=append(reels,map[string]any{
				"id":id,"caption":caption,"playbackUrl":playback,"coverUrl":cover,
				"durationMs":duration,"likes":likes,"comments":comments,"views":views,"spoiler":spoiler,
				"author":map[string]any{
					"id":userID,"username":username,"displayName":displayName,
					"avatarUrl":avatar,"verified":verified,
				},
				"media":map[string]any{"id":mediaID,"title":title,"posterUrl":poster},
			})
		}
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"query":query,
		"media":media,
		"users":users,
		"channels":channels,
		"reels":reels,
	})
}
