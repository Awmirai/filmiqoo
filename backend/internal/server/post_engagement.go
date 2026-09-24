package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) togglePostSave(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	postID:=chi.URLParam(r,"id")

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var exists bool
	if err:=tx.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM post_saves WHERE post_id=$1 AND user_id=$2
		)
	`,postID,userID).Scan(&exists); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	if exists {
		_,err=tx.Exec(r.Context(),
			"DELETE FROM post_saves WHERE post_id=$1 AND user_id=$2",
			postID,userID)
		if err==nil {
			_,err=tx.Exec(r.Context(),
				"UPDATE posts SET save_count=GREATEST(save_count-1,0) WHERE id=$1",
				postID)
		}
	} else {
		tag,insertErr:=tx.Exec(r.Context(),`
			INSERT INTO post_saves (post_id,user_id)
			SELECT id,$2 FROM posts WHERE id=$1 AND status='published'
			ON CONFLICT DO NOTHING
		`,postID,userID)
		err=insertErr
		if err==nil && tag.RowsAffected()==0 {
			writeJSON(w,http.StatusNotFound,map[string]string{"error":"post not found"}); return
		}
		if err==nil {
			_,err=tx.Exec(r.Context(),
				"UPDATE posts SET save_count=save_count+1 WHERE id=$1",
				postID)
		}
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var count int64
	if err:=tx.QueryRow(r.Context(),
		"SELECT save_count FROM posts WHERE id=$1",
		postID).Scan(&count); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"post not found"}); return
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"saved":!exists,
		"saves":count,
	})
}

func (s *Server) sharePost(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	postID:=chi.URLParam(r,"id")

	var body struct {
		Destination string `json:"destination"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	body.Destination=strings.ToLower(strings.TrimSpace(body.Destination))
	if body.Destination=="" { body.Destination="system" }

	switch body.Destination {
	case "system","dm","story","copy_link":
	default:
		body.Destination="system"
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	tag,err:=tx.Exec(r.Context(),`
		INSERT INTO post_share_events (post_id,user_id,destination)
		SELECT id,$2,$3 FROM posts WHERE id=$1 AND status='published'
	`,postID,userID,body.Destination)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if tag.RowsAffected()==0 {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"post not found"}); return
	}

	_,err=tx.Exec(r.Context(),
		"UPDATE posts SET share_count=share_count+1 WHERE id=$1",
		postID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	var count int64
	if err:=tx.QueryRow(r.Context(),
		"SELECT share_count FROM posts WHERE id=$1",
		postID).Scan(&count); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"shared":true,
		"shares":count,
	})
}

func (s *Server) savedPosts(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	rows,err:=s.db.Query(r.Context(),`
		SELECT p.id::text,p.post_type,p.body,p.spoiler,p.like_count,p.comment_count,
		       p.save_count,p.share_count,p.published_at,
		       pr.user_id::text,pr.username::text,pr.display_name,pr.avatar_url,pr.verified,
		       mt.id::text,mt.title,mt.poster_url
		  FROM post_saves ps
		  JOIN posts p ON p.id=ps.post_id
		  JOIN profiles pr ON pr.user_id=p.author_user_id
		  LEFT JOIN media_titles mt ON mt.id=p.media_title_id
		 WHERE ps.user_id=$1 AND p.status='published'
		 ORDER BY ps.created_at DESC
		 LIMIT 200
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,postType,bodyText,authorID,username,displayName,avatar string
		var spoiler,verified bool
		var likes,comments,saves,shares int64
		var publishedAt *time.Time
		var mediaID,title,poster *string
		if err:=rows.Scan(
			&id,&postType,&bodyText,&spoiler,&likes,&comments,&saves,&shares,&publishedAt,
			&authorID,&username,&displayName,&avatar,&verified,&mediaID,&title,&poster,
		); err!=nil { continue }

		items=append(items,map[string]any{
			"id":id,"type":postType,"body":bodyText,"spoiler":spoiler,
			"likes":likes,"comments":comments,"saves":saves,"shares":shares,
			"publishedAt":publishedAt,"likedByMe":false,"savedByMe":true,
			"author":map[string]any{
				"id":authorID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
			"media":map[string]any{"id":mediaID,"title":title,"posterUrl":poster},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
