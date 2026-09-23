package server

import (
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

var channelSlugPattern = regexp.MustCompile(`^[a-zA-Z0-9_.-]{3,40}$`)

func (s *Server) socialFeed(w http.ResponseWriter, r *http.Request) {
	limit:=30
	rows,err:=s.db.Query(r.Context(),`
		SELECT p.id::text,p.post_type,p.body,p.spoiler,p.like_count,p.comment_count,
		       p.save_count,p.share_count,p.published_at,
		       pr.user_id::text,pr.username::text,pr.display_name,pr.avatar_url,pr.verified,
		       mt.id::text,mt.title,mt.poster_url
		  FROM posts p
		  JOIN profiles pr ON pr.user_id=p.author_user_id
		  LEFT JOIN media_titles mt ON mt.id=p.media_title_id
		 WHERE p.status='published'
		 ORDER BY p.published_at DESC NULLS LAST,p.created_at DESC
		 LIMIT $1
	`,limit)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,postType,body,authorID,username,displayName,avatar string
		var spoiler,verified bool
		var likes,comments,saves,shares int64
		var publishedAt *time.Time
		var mediaID,title,poster *string
		if err:=rows.Scan(&id,&postType,&body,&spoiler,&likes,&comments,&saves,&shares,&publishedAt,
			&authorID,&username,&displayName,&avatar,&verified,&mediaID,&title,&poster); err!=nil {
			continue
		}
		items=append(items,map[string]any{
			"id":id,"type":postType,"body":body,"spoiler":spoiler,
			"likes":likes,"comments":comments,"saves":saves,"shares":shares,"publishedAt":publishedAt,
			"author":map[string]any{"id":authorID,"username":username,"displayName":displayName,"avatarUrl":avatar,"verified":verified},
			"media":map[string]any{"id":mediaID,"title":title,"posterUrl":poster},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items,"nextCursor":nil})
}

func (s *Server) createPost(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body struct {
		Type string `json:"type"`
		Body string `json:"body"`
		ChannelID *string `json:"channelId"`
		MediaTitleID *string `json:"mediaTitleId"`
		EpisodeID *string `json:"episodeId"`
		Spoiler bool `json:"spoiler"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Body=strings.TrimSpace(body.Body)
	if body.Type=="" { body.Type="post" }
	if body.Body=="" || len([]rune(body.Body))>5000 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"post body must be 1-5000 characters"}); return
	}
	switch body.Type {
	case "post","review","poll","announcement":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid post type"}); return
	}

	var id string
	err:=s.db.QueryRow(r.Context(),`
		INSERT INTO posts (
			author_user_id,channel_id,media_title_id,episode_id,post_type,body,spoiler,status,published_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,'published',now())
		RETURNING id::text
	`,userID,body.ChannelID,body.MediaTitleID,body.EpisodeID,body.Type,body.Body,body.Spoiler).Scan(&id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,_=s.db.Exec(r.Context(),"UPDATE profiles SET post_count=post_count+1,updated_at=now() WHERE user_id=$1",userID)
	if body.ChannelID!=nil {
		_,_=s.db.Exec(r.Context(),"UPDATE channels SET post_count=post_count+1,updated_at=now() WHERE id=$1",*body.ChannelID)
	}
	writeJSON(w,http.StatusCreated,map[string]any{"id":id,"status":"published"})
}

func (s *Server) togglePostLike(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	postID:=chi.URLParam(r,"id")
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var exists bool
	_ = tx.QueryRow(r.Context(),
		"SELECT EXISTS(SELECT 1 FROM post_reactions WHERE post_id=$1 AND user_id=$2)",
		postID,userID).Scan(&exists)
	if exists {
		_,err=tx.Exec(r.Context(),"DELETE FROM post_reactions WHERE post_id=$1 AND user_id=$2",postID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE posts SET like_count=GREATEST(like_count-1,0) WHERE id=$1",postID) }
	} else {
		_,err=tx.Exec(r.Context(),"INSERT INTO post_reactions (post_id,user_id,reaction) VALUES ($1,$2,'like')",postID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE posts SET like_count=like_count+1 WHERE id=$1",postID) }
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusOK,map[string]any{"liked":!exists})
}

func (s *Server) addPostComment(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	postID:=chi.URLParam(r,"id")
	var body struct {
		Body string `json:"body"`
		Spoiler bool `json:"spoiler"`
		ParentCommentID *string `json:"parentCommentId"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	body.Body=strings.TrimSpace(body.Body)
	if body.Body=="" || len([]rune(body.Body))>2000 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"comment must be 1-2000 characters"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var id string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO comments (post_id,parent_comment_id,author_user_id,body,spoiler)
		VALUES ($1,$2,$3,$4,$5) RETURNING id::text
	`,postID,body.ParentCommentID,userID,body.Body,body.Spoiler).Scan(&id)
	if err==nil { _,err=tx.Exec(r.Context(),"UPDATE posts SET comment_count=comment_count+1 WHERE id=$1",postID) }
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusCreated,map[string]any{"id":id})
}

func (s *Server) postComments(w http.ResponseWriter,r *http.Request) {
	postID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT c.id::text,c.parent_comment_id::text,c.body,c.spoiler,c.like_count,c.created_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified
		  FROM comments c JOIN profiles p ON p.user_id=c.author_user_id
		 WHERE c.post_id=$1
		 ORDER BY c.created_at ASC
		 LIMIT 200
	`,postID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()
	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,body,userID,username,displayName,avatar string
		var parent *string
		var spoiler,verified bool
		var likes int64
		var created time.Time
		if err:=rows.Scan(&id,&parent,&body,&spoiler,&likes,&created,&userID,&username,&displayName,&avatar,&verified); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"parentCommentId":parent,"body":body,"spoiler":spoiler,"likes":likes,"createdAt":created,
			"author":map[string]any{"id":userID,"username":username,"displayName":displayName,"avatarUrl":avatar,"verified":verified},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) createChannel(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body struct {
		Slug string `json:"slug"`
		Name string `json:"name"`
		Bio string `json:"bio"`
		Visibility string `json:"visibility"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	body.Slug=strings.ToLower(strings.TrimSpace(body.Slug))
	body.Name=strings.TrimSpace(body.Name)
	body.Bio=strings.TrimSpace(body.Bio)
	if !channelSlugPattern.MatchString(body.Slug) || len([]rune(body.Name))<2 || len([]rune(body.Name))>80 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid channel slug or name"}); return
	}
	if body.Visibility=="" { body.Visibility="public" }
	switch body.Visibility { case "public","private","invite": default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())
	var id string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO channels (owner_user_id,slug,name,bio,visibility)
		VALUES ($1,$2,$3,$4,$5) RETURNING id::text
	`,userID,body.Slug,body.Name,body.Bio,body.Visibility).Scan(&id)
	if err!=nil { writeJSON(w,http.StatusConflict,map[string]string{"error":"channel slug already exists"}); return }
	_,err=tx.Exec(r.Context(),"INSERT INTO channel_members (channel_id,user_id,role) VALUES ($1,$2,'owner')",id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusCreated,map[string]any{"id":id,"slug":body.Slug})
}

func (s *Server) channelDetail(w http.ResponseWriter,r *http.Request) {
	id:=chi.URLParam(r,"id")
	var ownerID,slug,name,bio,avatar,cover,visibility string
	var verified bool
	var followers,posts,reels int64
	err:=s.db.QueryRow(r.Context(),`
		SELECT owner_user_id::text,slug::text,name,bio,avatar_url,cover_url,visibility,verified,
		       follower_count,post_count,reel_count
		  FROM channels WHERE id=$1
	`,id).Scan(&ownerID,&slug,&name,&bio,&avatar,&cover,&visibility,&verified,&followers,&posts,&reels)
	if err!=nil { writeJSON(w,http.StatusNotFound,map[string]string{"error":"channel not found"}); return }
	writeJSON(w,http.StatusOK,map[string]any{
		"id":id,"ownerUserId":ownerID,"slug":slug,"name":name,"bio":bio,"avatarUrl":avatar,
		"coverUrl":cover,"visibility":visibility,"verified":verified,"followers":followers,"posts":posts,"reels":reels,
	})
}

func (s *Server) channelPosts(w http.ResponseWriter,r *http.Request) {
	id:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT p.id::text,p.post_type,p.body,p.spoiler,p.like_count,p.comment_count,p.published_at,
		       pr.user_id::text,pr.username::text,pr.display_name,pr.avatar_url,pr.verified
		  FROM posts p JOIN profiles pr ON pr.user_id=p.author_user_id
		 WHERE p.channel_id=$1 AND p.status='published'
		 ORDER BY p.published_at DESC NULLS LAST,p.created_at DESC
		 LIMIT 100
	`,id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()
	items:=make([]map[string]any,0)
	for rows.Next() {
		var postID,typ,body,userID,username,displayName,avatar string
		var spoiler,verified bool
		var likes,comments int64
		var published *time.Time
		if err:=rows.Scan(&postID,&typ,&body,&spoiler,&likes,&comments,&published,&userID,&username,&displayName,&avatar,&verified); err!=nil { continue }
		items=append(items,map[string]any{
			"id":postID,"type":typ,"body":body,"spoiler":spoiler,"likes":likes,"comments":comments,"publishedAt":published,
			"author":map[string]any{"id":userID,"username":username,"displayName":displayName,"avatarUrl":avatar,"verified":verified},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) toggleChannelFollow(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())
	var exists bool
	_ = tx.QueryRow(r.Context(),"SELECT EXISTS(SELECT 1 FROM channel_followers WHERE channel_id=$1 AND user_id=$2)",channelID,userID).Scan(&exists)
	if exists {
		_,err=tx.Exec(r.Context(),"DELETE FROM channel_followers WHERE channel_id=$1 AND user_id=$2",channelID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE channels SET follower_count=GREATEST(follower_count-1,0) WHERE id=$1",channelID) }
	} else {
		_,err=tx.Exec(r.Context(),"INSERT INTO channel_followers (channel_id,user_id) VALUES ($1,$2)",channelID,userID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE channels SET follower_count=follower_count+1 WHERE id=$1",channelID) }
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusOK,map[string]any{"following":!exists})
}

func (s *Server) stories(w http.ResponseWriter,r *http.Request) {
	rows,err:=s.db.Query(r.Context(),`
		SELECT st.id::text,st.story_type,st.media_url,st.thumbnail_url,st.caption,st.spoiler,
		       st.view_count,st.created_at,st.expires_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,
		       mt.backdrop_url,mt.year,mt.rating
		  FROM stories st
		  JOIN profiles p ON p.user_id=st.author_user_id
		  LEFT JOIN media_titles mt ON mt.id=st.media_title_id
		 WHERE st.expires_at>now() AND st.close_friends_only=false
		 ORDER BY st.created_at DESC
		 LIMIT 100
	`)
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
		if err:=rows.Scan(&id,&typ,&mediaURL,&thumb,&caption,&spoiler,&views,&created,&expires,
			&userID,&username,&displayName,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"type":typ,"mediaUrl":mediaURL,"thumbnailUrl":thumb,"caption":caption,
			"spoiler":spoiler,"views":views,"createdAt":created,"expiresAt":expires,
			"author":map[string]any{"id":userID,"username":username,"displayName":displayName,"avatarUrl":avatar,"verified":verified},
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,
				"originalTitle":originalTitle,"posterUrl":poster,"backdropUrl":backdrop,
				"year":year,"rating":rating,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) createStory(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body struct {
		Type string `json:"type"`
		MediaURL string `json:"mediaUrl"`
		ThumbnailURL string `json:"thumbnailUrl"`
		Caption string `json:"caption"`
		MediaTitleID *string `json:"mediaTitleId"`
		ChannelID *string `json:"channelId"`
		Spoiler bool `json:"spoiler"`
		CloseFriendsOnly bool `json:"closeFriendsOnly"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	if body.Type=="" { body.Type="text" }
	switch body.Type { case "image","video","text": default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid story type"}); return
	}
	if body.Type!="text" && strings.TrimSpace(body.MediaURL)=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"mediaUrl is required"}); return
	}
	var id string
	expires:=time.Now().Add(24*time.Hour)
	err:=s.db.QueryRow(r.Context(),`
		INSERT INTO stories (
			author_user_id,channel_id,media_title_id,story_type,media_url,thumbnail_url,
			caption,spoiler,close_friends_only,expires_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
		RETURNING id::text
	`,userID,body.ChannelID,body.MediaTitleID,body.Type,body.MediaURL,body.ThumbnailURL,
		body.Caption,body.Spoiler,body.CloseFriendsOnly,expires).Scan(&id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusCreated,map[string]any{"id":id,"expiresAt":expires})
}

func (s *Server) markStoryView(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	storyID:=chi.URLParam(r,"id")
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())
	tag,err:=tx.Exec(r.Context(),`
		INSERT INTO story_views (story_id,viewer_user_id) VALUES ($1,$2)
		ON CONFLICT DO NOTHING
	`,storyID,userID)
	if err==nil && tag.RowsAffected()>0 {
		_,err=tx.Exec(r.Context(),"UPDATE stories SET view_count=view_count+1 WHERE id=$1",storyID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) toggleUserFollow(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	targetID:=chi.URLParam(r,"id")
	if userID==targetID {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"cannot follow yourself"}); return
	}
	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())
	var exists bool
	_ = tx.QueryRow(r.Context(),"SELECT EXISTS(SELECT 1 FROM user_follows WHERE follower_user_id=$1 AND followed_user_id=$2)",userID,targetID).Scan(&exists)
	if exists {
		_,err=tx.Exec(r.Context(),"DELETE FROM user_follows WHERE follower_user_id=$1 AND followed_user_id=$2",userID,targetID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE profiles SET following_count=GREATEST(following_count-1,0) WHERE user_id=$1",userID) }
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE profiles SET follower_count=GREATEST(follower_count-1,0) WHERE user_id=$1",targetID) }
	} else {
		_,err=tx.Exec(r.Context(),"INSERT INTO user_follows (follower_user_id,followed_user_id) VALUES ($1,$2)",userID,targetID)
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE profiles SET following_count=following_count+1 WHERE user_id=$1",userID) }
		if err==nil { _,err=tx.Exec(r.Context(),"UPDATE profiles SET follower_count=follower_count+1 WHERE user_id=$1",targetID) }
		if err==nil {
			_,_=tx.Exec(r.Context(),`
				INSERT INTO notifications (user_id,actor_user_id,notification_type,entity_type,entity_id,title)
				VALUES ($1,$2,'follow','user',$2,'دنبال‌کننده جدید')
			`,targetID,userID)
		}
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusOK,map[string]any{"following":!exists})
}
