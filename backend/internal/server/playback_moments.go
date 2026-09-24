package server

import (
    "encoding/json"
    "net/http"
    "strconv"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) playbackMoments(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    versionID:=chi.URLParam(r,"versionID")

    positionMs,_:=strconv.ParseInt(r.URL.Query().Get("positionMs"),10,64)
    if positionMs<0 { positionMs=0 }
    windowMs,_:=strconv.ParseInt(r.URL.Query().Get("windowMs"),10,64)
    if windowMs<=0 || windowMs>120000 { windowMs=15000 }

    rows,err:=s.db.Query(r.Context(),`
        SELECT pm.id::text,pm.position_ms,pm.body,pm.reaction,pm.spoiler,
               pm.like_count,pm.created_at,
               p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
               EXISTS(
                   SELECT 1 FROM playback_moment_likes pml
                    WHERE pml.moment_id=pm.id AND pml.user_id=$2
               )
          FROM playback_moments pm
          JOIN profiles p ON p.user_id=pm.user_id
         WHERE pm.media_version_id=$1
           AND pm.position_ms BETWEEN GREATEST($3-$4,0) AND $3+$4
           AND NOT EXISTS (
               SELECT 1 FROM blocks b
                WHERE (b.blocker_user_id=$2 AND b.blocked_user_id=pm.user_id)
                   OR (b.blocker_user_id=pm.user_id AND b.blocked_user_id=$2)
           )
           AND NOT EXISTS (
               SELECT 1 FROM user_mutes m
                WHERE m.muter_user_id=$2 AND m.muted_user_id=pm.user_id
           )
         ORDER BY ABS(pm.position_ms-$3),pm.created_at DESC
         LIMIT 120
    `,versionID,userID,positionMs,windowMs)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,body,reaction,authorID,username,displayName,avatar string
        var pos,likes int64
        var spoiler,verified,liked bool
        var created time.Time
        if err:=rows.Scan(
            &id,&pos,&body,&reaction,&spoiler,&likes,&created,
            &authorID,&username,&displayName,&avatar,&verified,&liked,
        ); err!=nil { continue }
        items=append(items,map[string]any{
            "id":id,
            "positionMs":pos,
            "body":body,
            "reaction":reaction,
            "spoiler":spoiler,
            "likes":likes,
            "liked":liked,
            "createdAt":created,
            "author":map[string]any{
                "id":authorID,
                "username":username,
                "displayName":displayName,
                "avatarUrl":avatar,
                "verified":verified,
            },
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "positionMs":positionMs,
        "windowMs":windowMs,
        "items":items,
    })
}

func (s *Server) createPlaybackMoment(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    versionID:=chi.URLParam(r,"versionID")

    var body struct {
        PositionMs int64 `json:"positionMs"`
        Body string `json:"body"`
        Reaction string `json:"reaction"`
        Spoiler bool `json:"spoiler"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.Body=strings.TrimSpace(body.Body)
    body.Reaction=strings.TrimSpace(body.Reaction)

    if body.PositionMs<0 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"positionMs must be positive"}); return
    }
    if len([]rune(body.Body))>1200 || len([]rune(body.Reaction))>32 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"moment content is too long"}); return
    }
    if body.Body=="" && body.Reaction=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"moment requires text or reaction"}); return
    }

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO playback_moments (
            media_version_id,user_id,position_ms,body,reaction,spoiler
        )
        SELECT id,$2,$3,$4,$5,$6
          FROM media_versions
         WHERE id=$1
        RETURNING id::text
    `,versionID,userID,body.PositionMs,body.Body,body.Reaction,body.Spoiler).Scan(&id)
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"media version not found"}); return
    }

    writeJSON(w,http.StatusCreated,map[string]any{"id":id})
}

func (s *Server) togglePlaybackMomentLike(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    momentID:=chi.URLParam(r,"id")

    tx,err:=s.db.Begin(r.Context())
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer tx.Rollback(r.Context())

    var exists bool
    if err:=tx.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM playback_moment_likes
             WHERE moment_id=$1 AND user_id=$2
        )
    `,momentID,userID).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    if exists {
        _,err=tx.Exec(r.Context(),`
            DELETE FROM playback_moment_likes
             WHERE moment_id=$1 AND user_id=$2
        `,momentID,userID)
        if err==nil {
            _,err=tx.Exec(r.Context(),`
                UPDATE playback_moments
                   SET like_count=GREATEST(like_count-1,0)
                 WHERE id=$1
            `,momentID)
        }
    } else {
        _,err=tx.Exec(r.Context(),`
            INSERT INTO playback_moment_likes (moment_id,user_id)
            SELECT id,$2 FROM playback_moments WHERE id=$1
            ON CONFLICT DO NOTHING
        `,momentID,userID)
        if err==nil {
            _,err=tx.Exec(r.Context(),`
                UPDATE playback_moments
                   SET like_count=like_count+1
                 WHERE id=$1
            `,momentID)
        }
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    if err:=tx.Commit(r.Context()); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    writeJSON(w,http.StatusOK,map[string]any{"liked":!exists})
}
