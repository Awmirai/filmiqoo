package server

import (
    "encoding/json"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) watchlist(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    viewerID:=s.viewerProfileID(r,userID)
    maturity:=s.viewerMaturityLevel(r,userID)
    rows,err:=s.db.Query(r.Context(),`
        WITH saved AS (
            SELECT media_title_id,created_at
              FROM viewer_watchlist
             WHERE viewer_profile_id::text=$2 AND $2<>''
            UNION ALL
            SELECT media_title_id,created_at
              FROM watchlist
             WHERE user_id=$1 AND $2=''
        )
        SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
               mt.poster_url,mt.backdrop_url,mt.year,mt.rating,w.created_at
          FROM saved w
          JOIN media_titles mt ON mt.id=w.media_title_id
         WHERE (
           $3='all'
           OR ($3='teen' AND mt.audience_level IN ('kids','teen'))
           OR ($3='kids' AND mt.audience_level='kids')
         )
         ORDER BY w.created_at DESC
         LIMIT 300
    `,userID,viewerID,maturity)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,kind,title,originalTitle,overview,poster,backdrop string
        var tmdbID *int64
        var year int
        var rating *float64
        var created time.Time
        if err:=rows.Scan(
            &id,&tmdbID,&kind,&title,&originalTitle,&overview,
            &poster,&backdrop,&year,&rating,&created,
        ); err!=nil { continue }
        items=append(items,map[string]any{
            "id":id,"tmdbId":tmdbID,"kind":kind,"title":title,"originalTitle":originalTitle,
            "overview":overview,"posterUrl":poster,"backdropUrl":backdrop,
            "year":year,"rating":rating,"savedAt":created,
        })
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) toggleWatchlist(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    viewerID:=s.viewerProfileID(r,userID)
    mediaID:=chi.URLParam(r,"id")

    if viewerID!="" {
        var audience string
        if err:=s.db.QueryRow(r.Context(),
            "SELECT audience_level FROM media_titles WHERE id=$1",
            mediaID).Scan(&audience); err!=nil {
            writeJSON(w,http.StatusNotFound,map[string]string{"error":"media title not found"}); return
        }
        if !viewerAllowsAudience(s.viewerMaturityLevel(r,userID),audience) {
            writeJSON(w,http.StatusForbidden,map[string]string{"error":"این محتوا برای پروفایل فعال مجاز نیست."}); return
        }
    }

    tx,err:=s.db.Begin(r.Context())
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer tx.Rollback(r.Context())

    var exists bool
    if viewerID!="" {
        if err:=tx.QueryRow(r.Context(),`
            SELECT EXISTS(
                SELECT 1 FROM viewer_watchlist
                 WHERE viewer_profile_id=$1 AND media_title_id=$2
            )
        `,viewerID,mediaID).Scan(&exists); err!=nil {
            writeError(w,http.StatusInternalServerError,err); return
        }

        if exists {
            _,err=tx.Exec(r.Context(),
                "DELETE FROM viewer_watchlist WHERE viewer_profile_id=$1 AND media_title_id=$2",
                viewerID,mediaID)
        } else {
            _,err=tx.Exec(r.Context(),`
                INSERT INTO viewer_watchlist (viewer_profile_id,media_title_id)
                SELECT $1,id FROM media_titles WHERE id=$2
                ON CONFLICT DO NOTHING
            `,viewerID,mediaID)
        }
    } else {
        if err:=tx.QueryRow(r.Context(),`
            SELECT EXISTS(
                SELECT 1 FROM watchlist WHERE user_id=$1 AND media_title_id=$2
            )
        `,userID,mediaID).Scan(&exists); err!=nil {
            writeError(w,http.StatusInternalServerError,err); return
        }

        if exists {
            _,err=tx.Exec(r.Context(),
                "DELETE FROM watchlist WHERE user_id=$1 AND media_title_id=$2",
                userID,mediaID)
        } else {
            _,err=tx.Exec(r.Context(),`
                INSERT INTO watchlist (user_id,media_title_id)
                SELECT $1,id FROM media_titles WHERE id=$2
                ON CONFLICT DO NOTHING
            `,userID,mediaID)
        }
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"inWatchlist":!exists})
}

func (s *Server) collections(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    rows,err:=s.db.Query(r.Context(),`
        SELECT c.id::text,c.name,c.description,c.emoji,c.visibility,c.item_count,c.updated_at,
               COALESCE(preview.poster_url,'')
          FROM collections c
          LEFT JOIN LATERAL (
            SELECT mt.poster_url
              FROM collection_items ci
              JOIN media_titles mt ON mt.id=ci.media_title_id
             WHERE ci.collection_id=c.id
             ORDER BY ci.added_at DESC
             LIMIT 1
          ) preview ON true
         WHERE c.owner_user_id=$1
         ORDER BY c.updated_at DESC,c.created_at DESC
         LIMIT 100
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,name,description,emoji,visibility,poster string
        var count int
        var updated time.Time
        if err:=rows.Scan(
            &id,&name,&description,&emoji,&visibility,&count,&updated,&poster,
        ); err!=nil { continue }
        items=append(items,map[string]any{
            "id":id,"name":name,"description":description,"emoji":emoji,
            "visibility":visibility,"itemCount":count,"posterUrl":poster,"updatedAt":updated,
        })
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) createCollection(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    var body struct {
        Name string `json:"name"`
        Description string `json:"description"`
        Emoji string `json:"emoji"`
        Visibility string `json:"visibility"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    body.Name=strings.TrimSpace(body.Name)
    body.Description=strings.TrimSpace(body.Description)
    body.Emoji=strings.TrimSpace(body.Emoji)
    body.Visibility=strings.TrimSpace(body.Visibility)

    if len([]rune(body.Name))<1 || len([]rune(body.Name))>80 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"collection name must be 1-80 characters"})
        return
    }
    if len([]rune(body.Description))>500 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"description is too long"})
        return
    }
    if body.Emoji=="" { body.Emoji="🎬" }
    if len([]rune(body.Emoji))>8 { body.Emoji=string([]rune(body.Emoji)[:8]) }
    if body.Visibility=="" { body.Visibility="private" }
    switch body.Visibility {
    case "private","public","unlisted":
    default:
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"}); return
    }

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO collections (owner_user_id,name,description,emoji,visibility)
        VALUES ($1,$2,$3,$4,$5)
        RETURNING id::text
    `,userID,body.Name,body.Description,body.Emoji,body.Visibility).Scan(&id)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusCreated,map[string]any{
        "id":id,"name":body.Name,"description":body.Description,
        "emoji":body.Emoji,"visibility":body.Visibility,"itemCount":0,
    })
}

func (s *Server) collectionDetail(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    var name,description,emoji,visibility string
    var count int
    err:=s.db.QueryRow(r.Context(),`
        SELECT name,description,emoji,visibility,item_count
          FROM collections
         WHERE id=$1 AND owner_user_id=$2
    `,id,userID).Scan(&name,&description,&emoji,&visibility,&count)
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"collection not found"})
        return
    }

    rows,err:=s.db.Query(r.Context(),`
        SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,
               mt.poster_url,mt.backdrop_url,mt.year,mt.rating,ci.added_at
          FROM collection_items ci
          JOIN media_titles mt ON mt.id=ci.media_title_id
         WHERE ci.collection_id=$1
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
            "originalTitle":originalTitle,"overview":overview,"posterUrl":poster,
            "backdropUrl":backdrop,"year":year,"rating":rating,"addedAt":added,
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "id":id,"name":name,"description":description,"emoji":emoji,
        "visibility":visibility,"itemCount":count,"items":items,
    })
}

func (s *Server) toggleCollectionItem(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    collectionID:=chi.URLParam(r,"id")
    mediaID:=chi.URLParam(r,"mediaID")

    tx,err:=s.db.Begin(r.Context())
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer tx.Rollback(r.Context())

    var owns bool
    if err:=tx.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM collections WHERE id=$1 AND owner_user_id=$2
        )
    `,collectionID,userID).Scan(&owns); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    if !owns {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"collection not found"})
        return
    }

    var exists bool
    if err:=tx.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM collection_items WHERE collection_id=$1 AND media_title_id=$2
        )
    `,collectionID,mediaID).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    if exists {
        _,err=tx.Exec(r.Context(),`
            DELETE FROM collection_items WHERE collection_id=$1 AND media_title_id=$2
        `,collectionID,mediaID)
    } else {
        _,err=tx.Exec(r.Context(),`
            INSERT INTO collection_items (collection_id,media_title_id,sort_order)
            SELECT $1,id,
                   COALESCE((SELECT MAX(sort_order)+1 FROM collection_items WHERE collection_id=$1),0)
              FROM media_titles WHERE id=$2
            ON CONFLICT DO NOTHING
        `,collectionID,mediaID)
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    _,err=tx.Exec(r.Context(),`
        UPDATE collections
           SET item_count=(SELECT COUNT(*) FROM collection_items WHERE collection_id=$1),
               updated_at=now()
         WHERE id=$1
    `,collectionID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    writeJSON(w,http.StatusOK,map[string]any{"included":!exists})
}

func (s *Server) deleteCollection(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")
    tag,err:=s.db.Exec(r.Context(),
        "DELETE FROM collections WHERE id=$1 AND owner_user_id=$2",
        id,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    writeJSON(w,http.StatusOK,map[string]any{"deleted":tag.RowsAffected()>0})
}
