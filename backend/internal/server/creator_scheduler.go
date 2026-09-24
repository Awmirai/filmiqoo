package server

import (
    "context"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
    "github.com/jackc/pgx/v5/pgconn"
)

type scheduledPublishRef struct {
    id string
    authorID string
    channelID *string
}

func (s *Server) processScheduledContent(ctx context.Context) error {
    tx,err:=s.db.Begin(ctx)
    if err!=nil { return err }
    defer tx.Rollback(ctx)

    postRows,err:=tx.Query(ctx,`
        UPDATE posts
           SET status='published',updated_at=now()
         WHERE id IN (
           SELECT id
             FROM posts
            WHERE status='scheduled'
              AND published_at IS NOT NULL
              AND published_at<=now()
            ORDER BY published_at ASC
            LIMIT 100
            FOR UPDATE SKIP LOCKED
         )
        RETURNING id::text,author_user_id::text,channel_id::text
    `)
    if err!=nil { return err }

    posts:=make([]scheduledPublishRef,0)
    for postRows.Next() {
        var x scheduledPublishRef
        if postRows.Scan(&x.id,&x.authorID,&x.channelID)==nil {
            posts=append(posts,x)
        }
    }
    postRows.Close()

    reelRows,err:=tx.Query(ctx,`
        UPDATE reels
           SET status='published'
         WHERE id IN (
           SELECT id
             FROM reels
            WHERE status='scheduled'
              AND published_at IS NOT NULL
              AND published_at<=now()
            ORDER BY published_at ASC
            LIMIT 100
            FOR UPDATE SKIP LOCKED
         )
        RETURNING id::text,creator_user_id::text,channel_id::text
    `)
    if err!=nil { return err }

    reels:=make([]scheduledPublishRef,0)
    for reelRows.Next() {
        var x scheduledPublishRef
        if reelRows.Scan(&x.id,&x.authorID,&x.channelID)==nil {
            reels=append(reels,x)
        }
    }
    reelRows.Close()

    touchedUsers:=map[string]bool{}
    touchedChannels:=map[string]bool{}
    for _,x:=range posts {
        touchedUsers[x.authorID]=true
        if x.channelID!=nil { touchedChannels[*x.channelID]=true }
    }
    for _,x:=range reels {
        touchedUsers[x.authorID]=true
        if x.channelID!=nil { touchedChannels[*x.channelID]=true }
    }

    for userID:=range touchedUsers {
        _,err=tx.Exec(ctx,`
            UPDATE profiles p SET
              post_count=(SELECT COUNT(*) FROM posts WHERE author_user_id=p.user_id AND status='published'),
              reel_count=(SELECT COUNT(*) FROM reels WHERE creator_user_id=p.user_id AND status='published'),
              updated_at=now()
             WHERE p.user_id=$1
        `,userID)
        if err!=nil { return err }
    }

    for channelID:=range touchedChannels {
        _,err=tx.Exec(ctx,`
            UPDATE channels c SET
              post_count=(SELECT COUNT(*) FROM posts WHERE channel_id=c.id AND status='published'),
              reel_count=(SELECT COUNT(*) FROM reels WHERE channel_id=c.id AND status='published'),
              updated_at=now()
             WHERE c.id=$1
        `,channelID)
        if err!=nil { return err }
    }

    return tx.Commit(ctx)
}

func (s *Server) scheduledCreatorContent(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.processScheduledContent(r.Context()); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    rows,err:=s.db.Query(r.Context(),`
        SELECT id,content_kind,content_type,preview,scheduled_at,channel_id,channel_name,
               media_title_id,media_title,spoiler
          FROM (
            SELECT p.id::text AS id,
                   'post'::text AS content_kind,
                   p.post_type AS content_type,
                   p.body AS preview,
                   p.published_at AS scheduled_at,
                   p.channel_id::text AS channel_id,
                   COALESCE(c.name,'') AS channel_name,
                   p.media_title_id::text AS media_title_id,
                   COALESCE(mt.title,'') AS media_title,
                   p.spoiler
              FROM posts p
              LEFT JOIN channels c ON c.id=p.channel_id
              LEFT JOIN media_titles mt ON mt.id=p.media_title_id
             WHERE p.author_user_id=$1 AND p.status='scheduled'

            UNION ALL

            SELECT r.id::text,
                   'reel'::text,
                   'reel'::text,
                   r.caption,
                   r.published_at,
                   r.channel_id::text,
                   COALESCE(c.name,''),
                   r.media_title_id::text,
                   COALESCE(mt.title,''),
                   r.spoiler
              FROM reels r
              LEFT JOIN channels c ON c.id=r.channel_id
              LEFT JOIN media_titles mt ON mt.id=r.media_title_id
             WHERE r.creator_user_id=$1 AND r.status='scheduled'
          ) q
         ORDER BY scheduled_at ASC
         LIMIT 300
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,kind,contentType,preview,channelName,mediaTitle string
        var scheduledAt time.Time
        var channelID,mediaID *string
        var spoiler bool
        if err:=rows.Scan(
            &id,&kind,&contentType,&preview,&scheduledAt,&channelID,&channelName,
            &mediaID,&mediaTitle,&spoiler,
        ); err!=nil { continue }

        items=append(items,map[string]any{
            "id":id,
            "kind":kind,
            "contentType":contentType,
            "preview":preview,
            "scheduledAt":scheduledAt,
            "channelId":channelID,
            "channelName":channelName,
            "mediaTitleId":mediaID,
            "mediaTitle":mediaTitle,
            "spoiler":spoiler,
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) publishScheduledNow(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    kind:=strings.ToLower(strings.TrimSpace(chi.URLParam(r,"kind")))
    id:=chi.URLParam(r,"id")

    tx,err:=s.db.Begin(r.Context())
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer tx.Rollback(r.Context())

    var authorID string
    var channelID *string

    switch kind {
    case "post":
        err=tx.QueryRow(r.Context(),`
            UPDATE posts
               SET status='published',published_at=now(),updated_at=now()
             WHERE id=$1 AND author_user_id=$2 AND status='scheduled'
            RETURNING author_user_id::text,channel_id::text
        `,id,userID).Scan(&authorID,&channelID)
    case "reel":
        err=tx.QueryRow(r.Context(),`
            UPDATE reels
               SET status='published',published_at=now()
             WHERE id=$1 AND creator_user_id=$2 AND status='scheduled'
            RETURNING creator_user_id::text,channel_id::text
        `,id,userID).Scan(&authorID,&channelID)
    default:
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"kind must be post or reel"}); return
    }
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"scheduled content not found"}); return
    }

    _,err=tx.Exec(r.Context(),`
        UPDATE profiles p SET
          post_count=(SELECT COUNT(*) FROM posts WHERE author_user_id=p.user_id AND status='published'),
          reel_count=(SELECT COUNT(*) FROM reels WHERE creator_user_id=p.user_id AND status='published'),
          updated_at=now()
         WHERE p.user_id=$1
    `,authorID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    if channelID!=nil {
        _,err=tx.Exec(r.Context(),`
            UPDATE channels c SET
              post_count=(SELECT COUNT(*) FROM posts WHERE channel_id=c.id AND status='published'),
              reel_count=(SELECT COUNT(*) FROM reels WHERE channel_id=c.id AND status='published'),
              updated_at=now()
             WHERE c.id=$1
        `,*channelID)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    }

    if err:=tx.Commit(r.Context()); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    writeJSON(w,http.StatusOK,map[string]any{"published":true})
}

func (s *Server) unscheduleCreatorContent(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    kind:=strings.ToLower(strings.TrimSpace(chi.URLParam(r,"kind")))
    id:=chi.URLParam(r,"id")

    var tag pgconn.CommandTag
    var err error

    switch kind {
    case "post":
        tag,err=s.db.Exec(r.Context(),`
            UPDATE posts
               SET status='draft',published_at=NULL,updated_at=now()
             WHERE id=$1 AND author_user_id=$2 AND status='scheduled'
        `,id,userID)
    case "reel":
        tag,err=s.db.Exec(r.Context(),`
            UPDATE reels
               SET status='draft',published_at=NULL
             WHERE id=$1 AND creator_user_id=$2 AND status='scheduled'
        `,id,userID)
    default:
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"kind must be post or reel"}); return
    }

    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    writeJSON(w,http.StatusOK,map[string]any{"unscheduled":tag.RowsAffected()>0})
}
