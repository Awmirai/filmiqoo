package server

import (
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) publicUserProfile(w http.ResponseWriter,r *http.Request) {
	id:=chi.URLParam(r,"id")
	var username,displayName,bio,avatar,cover string
	var verified,private bool
	var followers,following,posts,reels int64
	err:=s.db.QueryRow(r.Context(),`
		SELECT username::text,display_name,bio,avatar_url,cover_url,verified,private_account,
		       follower_count,following_count,post_count,reel_count
		  FROM profiles
		 WHERE user_id=$1
	`,id).Scan(
		&username,&displayName,&bio,&avatar,&cover,&verified,&private,
		&followers,&following,&posts,&reels,
	)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"user not found"})
		return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"id":id,"username":username,"displayName":displayName,"bio":bio,
		"avatarUrl":avatar,"coverUrl":cover,"verified":verified,"private":private,
		"followers":followers,"following":following,"posts":posts,"reels":reels,
	})
}

func (s *Server) publicUserPosts(w http.ResponseWriter,r *http.Request) {
	id:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT p.id::text,p.post_type,p.body,p.spoiler,p.like_count,p.comment_count,
		       p.save_count,p.share_count,p.published_at,
		       pr.user_id::text,pr.username::text,pr.display_name,pr.avatar_url,pr.verified,
		       mt.id::text,mt.title,mt.poster_url
		  FROM posts p
		  JOIN profiles pr ON pr.user_id=p.author_user_id
		  LEFT JOIN media_titles mt ON mt.id=p.media_title_id
		 WHERE p.author_user_id=$1 AND p.status='published'
		 ORDER BY p.published_at DESC NULLS LAST,p.created_at DESC
		 LIMIT 100
	`,id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var postID,typ,body,userID,username,displayName,avatar string
		var spoiler,verified bool
		var likes,comments,saves,shares int64
		var published *time.Time
		var mediaID,title,poster *string
		if err:=rows.Scan(
			&postID,&typ,&body,&spoiler,&likes,&comments,&saves,&shares,&published,
			&userID,&username,&displayName,&avatar,&verified,&mediaID,&title,&poster,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"id":postID,"type":typ,"body":body,"spoiler":spoiler,
			"likes":likes,"comments":comments,"saves":saves,"shares":shares,
			"publishedAt":published,
			"author":map[string]any{
				"id":userID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
			"media":map[string]any{"id":mediaID,"title":title,"posterUrl":poster},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) publicUserReels(w http.ResponseWriter,r *http.Request) {
	s.reelsByOwner(w,r,chi.URLParam(r,"id"),"")
}

func (s *Server) channelReels(w http.ResponseWriter,r *http.Request) {
	s.reelsByOwner(w,r,"",chi.URLParam(r,"id"))
}

func (s *Server) reelsByOwner(w http.ResponseWriter,r *http.Request,userID string,channelID string) {
	rows,err:=s.db.Query(r.Context(),`
		SELECT r.id::text,r.caption,r.playback_url,r.cover_url,r.duration_ms,
		       r.like_count,r.comment_count,r.save_count,r.share_count,r.view_count,r.spoiler,
		       p.user_id::text,p.display_name,p.username::text,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating
		  FROM reels r
		  JOIN profiles p ON p.user_id=r.creator_user_id
		  LEFT JOIN media_titles mt ON mt.id=r.media_title_id
		 WHERE r.status='published'
		   AND ($1='' OR r.creator_user_id::text=$1)
		   AND ($2='' OR r.channel_id::text=$2)
		 ORDER BY r.published_at DESC NULLS LAST,r.created_at DESC
		 LIMIT 100
	`,userID,channelID)
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
			"shares":shares,"views":views,"spoiler":spoiler,
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

func (s *Server) channelStories(w http.ResponseWriter,r *http.Request) {
	channelID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT st.id::text,st.story_type,st.media_url,st.thumbnail_url,st.caption,st.spoiler,
		       st.view_count,st.created_at,st.expires_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,
		       mt.backdrop_url,mt.year,mt.rating
		  FROM stories st
		  JOIN profiles p ON p.user_id=st.author_user_id
		  LEFT JOIN media_titles mt ON mt.id=st.media_title_id
		 WHERE st.channel_id=$1 AND st.expires_at>now()
		 ORDER BY st.created_at DESC
		 LIMIT 100
	`,channelID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,typ,mediaURL,thumb,caption,userID,username,displayName,avatar string
		var spoiler,verified bool
		var views int64
		var created,expires time.Time
		var mediaID,kind,title,originalTitle,poster,backdrop *string
		var tmdbID *int64
		var year *int
		var rating *float64
		if err:=rows.Scan(
			&id,&typ,&mediaURL,&thumb,&caption,&spoiler,&views,&created,&expires,
			&userID,&username,&displayName,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"id":id,"type":typ,"mediaUrl":mediaURL,"thumbnailUrl":thumb,
			"caption":caption,"spoiler":spoiler,"views":views,
			"createdAt":created,"expiresAt":expires,
			"author":map[string]any{
				"id":userID,"username":username,"displayName":displayName,
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

func (s *Server) channelMembers(w http.ResponseWriter,r *http.Request) {
	channelID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT cm.user_id::text,cm.role,p.username::text,p.display_name,p.avatar_url,p.verified
		  FROM channel_members cm
		  JOIN profiles p ON p.user_id=cm.user_id
		 WHERE cm.channel_id=$1
		 ORDER BY
		   CASE cm.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 WHEN 'moderator' THEN 2 ELSE 3 END,
		   cm.joined_at ASC
		 LIMIT 100
	`,channelID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,role,username,displayName,avatar string
		var verified bool
		if err:=rows.Scan(&id,&role,&username,&displayName,&avatar,&verified); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"role":role,"username":username,"displayName":displayName,
			"avatarUrl":avatar,"verified":verified,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) creatorStudio(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	var username,displayName,avatar string
	var followers,following,posts,reels int64
	if err:=s.db.QueryRow(r.Context(),`
		SELECT username::text,display_name,avatar_url,follower_count,following_count,post_count,reel_count
		  FROM profiles WHERE user_id=$1
	`,userID).Scan(&username,&displayName,&avatar,&followers,&following,&posts,&reels); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	var totalViews,totalLikes,totalComments,totalSaves,totalShares int64
	_=s.db.QueryRow(r.Context(),`
		SELECT COALESCE(SUM(view_count),0),COALESCE(SUM(like_count),0),
		       COALESCE(SUM(comment_count),0),COALESCE(SUM(save_count),0),COALESCE(SUM(share_count),0)
		  FROM reels
		 WHERE creator_user_id=$1 AND status='published'
	`,userID).Scan(&totalViews,&totalLikes,&totalComments,&totalSaves,&totalShares)

	var storyViews int64
	_=s.db.QueryRow(r.Context(),`
		SELECT COALESCE(SUM(view_count),0)
		  FROM stories
		 WHERE author_user_id=$1
	`,userID).Scan(&storyViews)

	var channelCount,channelFollowers int64
	_=s.db.QueryRow(r.Context(),`
		SELECT COUNT(*),COALESCE(SUM(follower_count),0)
		  FROM channels
		 WHERE owner_user_id=$1
	`,userID).Scan(&channelCount,&channelFollowers)

	writeJSON(w,http.StatusOK,map[string]any{
		"profile":map[string]any{
			"id":userID,"username":username,"displayName":displayName,"avatarUrl":avatar,
			"followers":followers,"following":following,"posts":posts,"reels":reels,
		},
		"analytics":map[string]any{
			"reelViews":totalViews,"reelLikes":totalLikes,"reelComments":totalComments,
			"reelSaves":totalSaves,"reelShares":totalShares,"storyViews":storyViews,
			"channels":channelCount,"channelFollowers":channelFollowers,
		},
	})
}


func (s *Server) channelRooms(w http.ResponseWriter,r *http.Request) {
	channelID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT rm.id::text,rm.name,rm.topic,rm.room_type,rm.member_count,
		       mt.id::text,mt.title,mt.poster_url
		  FROM rooms rm
		  LEFT JOIN media_titles mt ON mt.id=rm.media_title_id
		 WHERE rm.channel_id=$1 AND rm.visibility='public'
		 ORDER BY rm.member_count DESC,rm.created_at DESC
		 LIMIT 50
	`,channelID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()
	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,name,topic,typ string
		var members int64
		var mediaID,title,poster *string
		if err:=rows.Scan(&id,&name,&topic,&typ,&members,&mediaID,&title,&poster); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"name":name,"topic":topic,"type":typ,"members":members,
			"media":map[string]any{"id":mediaID,"title":title,"posterUrl":poster},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
