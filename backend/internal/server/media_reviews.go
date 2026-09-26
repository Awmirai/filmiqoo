package server

import (
    "encoding/json"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) mediaReviews(w http.ResponseWriter,r *http.Request) {
    mediaID:=chi.URLParam(r,"id")

    var average float64
    var count int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COALESCE(AVG(rating),0),COUNT(*)
          FROM media_reviews
         WHERE media_title_id=$1
    `,mediaID).Scan(&average,&count)

    distribution:=make(map[string]int64,10)
    distRows,err:=s.db.Query(r.Context(),`
        SELECT rating,COUNT(*)
          FROM media_reviews
         WHERE media_title_id=$1
         GROUP BY rating
         ORDER BY rating DESC
    `,mediaID)
    if err==nil {
        defer distRows.Close()
        for distRows.Next() {
            var rating int
            var c int64
            if distRows.Scan(&rating,&c)==nil {
                distribution[fmtInt(rating)]=c
            }
        }
    }

    rows,err:=s.db.Query(r.Context(),`
        SELECT mr.id::text,mr.rating,mr.body,mr.spoiler,mr.like_count,mr.updated_at,
               p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified
          FROM media_reviews mr
          JOIN profiles p ON p.user_id=mr.user_id
         WHERE mr.media_title_id=$1
         ORDER BY mr.like_count DESC,mr.updated_at DESC
         LIMIT 100
    `,mediaID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,body,userID,username,displayName,avatar string
        var rating int
        var spoiler,verified bool
        var likes int64
        var updated time.Time
        if err:=rows.Scan(
            &id,&rating,&body,&spoiler,&likes,&updated,
            &userID,&username,&displayName,&avatar,&verified,
        ); err!=nil { continue }
        items=append(items,map[string]any{
            "id":id,"rating":rating,"body":body,"spoiler":spoiler,
            "likes":likes,"updatedAt":updated,
            "author":map[string]any{
                "id":userID,"username":username,"displayName":displayName,
                "avatarUrl":avatar,"verified":verified,
            },
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "average":average,
        "count":count,
        "distribution":distribution,
        "items":items,
    })
}

func (s *Server) upsertMediaReview(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    mediaID:=chi.URLParam(r,"id")
    var body struct {
        Rating int `json:"rating"`
        Body string `json:"body"`
        Spoiler bool `json:"spoiler"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    body.Body=strings.TrimSpace(body.Body)
    if body.Rating<1 || body.Rating>10 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"rating must be 1-10"}); return
    }
    if len([]rune(body.Body))>5000 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"review is too long"}); return
    }

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO media_reviews (media_title_id,user_id,rating,body,spoiler)
        SELECT id,$1,$3,$4,$5 FROM media_titles WHERE id=$2
        ON CONFLICT (media_title_id,user_id)
        DO UPDATE SET
          rating=EXCLUDED.rating,
          body=EXCLUDED.body,
          spoiler=EXCLUDED.spoiler,
          updated_at=now()
        RETURNING id::text
    `,userID,mediaID,body.Rating,body.Body,body.Spoiler).Scan(&id)
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"media not found"}); return
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "id":id,"rating":body.Rating,"body":body.Body,"spoiler":body.Spoiler,
    })
}

func (s *Server) toggleReviewLike(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    reviewID:=chi.URLParam(r,"id")

    tx,err:=s.db.Begin(r.Context())
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer tx.Rollback(r.Context())

    var exists bool
    if err:=tx.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM media_review_likes WHERE review_id=$1 AND user_id=$2
        )
    `,reviewID,userID).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    if exists {
        _,err=tx.Exec(r.Context(),
            "DELETE FROM media_review_likes WHERE review_id=$1 AND user_id=$2",
            reviewID,userID)
        if err==nil {
            _,err=tx.Exec(r.Context(),
                "UPDATE media_reviews SET like_count=GREATEST(like_count-1,0) WHERE id=$1",
                reviewID)
        }
    } else {
        _,err=tx.Exec(r.Context(),`
            INSERT INTO media_review_likes (review_id,user_id)
            VALUES ($1,$2) ON CONFLICT DO NOTHING
        `,reviewID,userID)
        if err==nil {
            _,err=tx.Exec(r.Context(),
                "UPDATE media_reviews SET like_count=like_count+1 WHERE id=$1",
                reviewID)
        }
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    if !exists {
        var authorID string
        if scanErr:=tx.QueryRow(
            r.Context(),
            "SELECT user_id::text FROM media_reviews WHERE id=$1",
            reviewID,
        ).Scan(&authorID); scanErr==nil && authorID!=userID {
            _,_=tx.Exec(r.Context(),`
                INSERT INTO notifications (
                    user_id,actor_user_id,notification_type,entity_type,entity_id,title
                )
                SELECT $1,$2,'review_like','review',$3,'پسند جدید روی Review'
                WHERE NOT EXISTS (
                    SELECT 1 FROM notifications
                     WHERE user_id=$1
                       AND actor_user_id=$2
                       AND notification_type='review_like'
                       AND entity_id=$3
                       AND created_at>now()-interval '12 hours'
                )
            `,authorID,userID,reviewID)
        }
    }

    if err:=tx.Commit(r.Context()); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    writeJSON(w,http.StatusOK,map[string]any{"liked":!exists})
}

func fmtInt(v int) string {
    if v==10 { return "10" }
    return string(rune('0'+v))
}
