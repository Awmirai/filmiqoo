package server

import (
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) publicCollections(w http.ResponseWriter,r *http.Request) {
	rows,err:=s.db.Query(r.Context(),`
		SELECT c.id::text,c.name,c.description,c.emoji,c.visibility,c.item_count,c.follower_count,c.updated_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       COALESCE(preview.poster_url,'')
		  FROM collections c
		  JOIN profiles p ON p.user_id=c.owner_user_id
		  LEFT JOIN LATERAL (
		    SELECT mt.poster_url
		      FROM collection_items ci
		      JOIN media_titles mt ON mt.id=ci.media_title_id
		     WHERE ci.collection_id=c.id
		     ORDER BY ci.added_at DESC
		     LIMIT 1
		  ) preview ON true
		 WHERE c.visibility='public'
		 ORDER BY c.follower_count DESC,c.updated_at DESC
		 LIMIT 100
	`)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,name,description,emoji,visibility,ownerID,username,displayName,avatar,poster string
		var itemCount int
		var followers int64
		var verified bool
		var updated time.Time
		if err:=rows.Scan(
			&id,&name,&description,&emoji,&visibility,&itemCount,&followers,&updated,
			&ownerID,&username,&displayName,&avatar,&verified,&poster,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"name":name,"description":description,"emoji":emoji,
			"visibility":visibility,"itemCount":itemCount,"followers":followers,
			"posterUrl":poster,"updatedAt":updated,
			"owner":map[string]any{
				"id":ownerID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) publicUserCollections(w http.ResponseWriter,r *http.Request) {
	targetID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT c.id::text,c.name,c.description,c.emoji,c.visibility,c.item_count,c.follower_count,c.updated_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       COALESCE(preview.poster_url,'')
		  FROM collections c
		  JOIN profiles p ON p.user_id=c.owner_user_id
		  LEFT JOIN LATERAL (
		    SELECT mt.poster_url
		      FROM collection_items ci
		      JOIN media_titles mt ON mt.id=ci.media_title_id
		     WHERE ci.collection_id=c.id
		     ORDER BY ci.added_at DESC
		     LIMIT 1
		  ) preview ON true
		 WHERE c.owner_user_id=$1 AND c.visibility='public'
		 ORDER BY c.follower_count DESC,c.updated_at DESC
		 LIMIT 100
	`,targetID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,name,description,emoji,visibility,ownerID,username,displayName,avatar,poster string
		var itemCount int
		var followers int64
		var verified bool
		var updated time.Time
		if err:=rows.Scan(
			&id,&name,&description,&emoji,&visibility,&itemCount,&followers,&updated,
			&ownerID,&username,&displayName,&avatar,&verified,&poster,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"name":name,"description":description,"emoji":emoji,
			"visibility":visibility,"itemCount":itemCount,"followers":followers,
			"posterUrl":poster,"updatedAt":updated,
			"owner":map[string]any{
				"id":ownerID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) publicCollectionDetail(w http.ResponseWriter,r *http.Request) {
	id:=chi.URLParam(r,"id")
	var name,description,emoji,visibility,ownerID,username,displayName,avatar string
	var itemCount int
	var followers int64
	var verified bool
	err:=s.db.QueryRow(r.Context(),`
		SELECT c.name,c.description,c.emoji,c.visibility,c.item_count,c.follower_count,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified
		  FROM collections c
		  JOIN profiles p ON p.user_id=c.owner_user_id
		 WHERE c.id=$1 AND c.visibility IN ('public','unlisted')
	`,id).Scan(
		&name,&description,&emoji,&visibility,&itemCount,&followers,
		&ownerID,&username,&displayName,&avatar,&verified,
	)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"collection not found"})
		return
	}

	rows,err:=s.db.Query(r.Context(),`
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
		       mt.poster_url,mt.backdrop_url,mt.year,mt.rating,ci.added_at
		  FROM collection_items ci
		  JOIN media_titles mt ON mt.id=ci.media_title_id
		 WHERE ci.collection_id=$1 AND mt.visibility='public'
		 ORDER BY ci.sort_order ASC,ci.added_at DESC
		 LIMIT 500
	`,id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var mediaID,kind,title,originalTitle,overview,poster,backdrop string
		var tmdbID *int64
		var year int
		var rating *float64
		var added time.Time
		if err:=rows.Scan(
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&overview,
			&poster,&backdrop,&year,&rating,&added,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,
			"originalTitle":originalTitle,"overview":overview,
			"posterUrl":poster,"backdropUrl":backdrop,"year":year,
			"rating":rating,"addedAt":added,
		})
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"id":id,"name":name,"description":description,"emoji":emoji,
		"visibility":visibility,"itemCount":itemCount,"followers":followers,
		"owner":map[string]any{
			"id":ownerID,"username":username,"displayName":displayName,
			"avatarUrl":avatar,"verified":verified,
		},
		"items":items,
	})
}

func (s *Server) followedCollections(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	rows,err:=s.db.Query(r.Context(),`
		SELECT c.id::text,c.name,c.description,c.emoji,c.visibility,c.item_count,c.follower_count,c.updated_at,
		       p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       COALESCE(preview.poster_url,'')
		  FROM collection_followers cf
		  JOIN collections c ON c.id=cf.collection_id
		  JOIN profiles p ON p.user_id=c.owner_user_id
		  LEFT JOIN LATERAL (
		    SELECT mt.poster_url
		      FROM collection_items ci
		      JOIN media_titles mt ON mt.id=ci.media_title_id
		     WHERE ci.collection_id=c.id
		     ORDER BY ci.added_at DESC
		     LIMIT 1
		  ) preview ON true
		 WHERE cf.user_id=$1 AND c.visibility IN ('public','unlisted')
		 ORDER BY c.updated_at DESC,cf.created_at DESC
		 LIMIT 100
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,name,description,emoji,visibility,ownerID,username,displayName,avatar,poster string
		var itemCount int
		var followers int64
		var verified bool
		var updated time.Time
		if err:=rows.Scan(
			&id,&name,&description,&emoji,&visibility,&itemCount,&followers,&updated,
			&ownerID,&username,&displayName,&avatar,&verified,&poster,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"name":name,"description":description,"emoji":emoji,
			"visibility":visibility,"itemCount":itemCount,"followers":followers,
			"posterUrl":poster,"updatedAt":updated,"following":true,
			"owner":map[string]any{
				"id":ownerID,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) toggleCollectionFollow(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	collectionID:=chi.URLParam(r,"id")

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var ownerID,visibility string
	if err:=tx.QueryRow(r.Context(),`
		SELECT owner_user_id::text,visibility
		  FROM collections
		 WHERE id=$1
	`,collectionID).Scan(&ownerID,&visibility); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"collection not found"})
		return
	}
	if ownerID==userID {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"cannot follow your own collection"})
		return
	}
	if visibility=="private" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"private collection cannot be followed"})
		return
	}

	var exists bool
	if err:=tx.QueryRow(r.Context(),`
		SELECT EXISTS(
		  SELECT 1 FROM collection_followers
		   WHERE collection_id=$1 AND user_id=$2
		)
	`,collectionID,userID).Scan(&exists); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	if exists {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM collection_followers
			 WHERE collection_id=$1 AND user_id=$2
		`,collectionID,userID)
	} else {
		_,err=tx.Exec(r.Context(),`
			INSERT INTO collection_followers (collection_id,user_id)
			VALUES ($1,$2) ON CONFLICT DO NOTHING
		`,collectionID,userID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		UPDATE collections
		   SET follower_count=(
		     SELECT COUNT(*) FROM collection_followers WHERE collection_id=$1
		   ),
		       updated_at=updated_at
		 WHERE id=$1
	`,collectionID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusOK,map[string]any{"following":!exists})
}
