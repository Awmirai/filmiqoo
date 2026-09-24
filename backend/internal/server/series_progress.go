package server

import (
    "encoding/json"
    "net/http"

    "github.com/go-chi/chi/v5"
)

func (s *Server) seriesProgress(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    mediaID:=chi.URLParam(r,"id")

    rows,err:=s.db.Query(r.Context(),`
        SELECT sn.id::text,sn.season_number,
               e.id::text,e.episode_number,
               COALESCE(p.position_ms,0),
               COALESCE(p.duration_ms,0),
               COALESCE(p.completed,false)
          FROM seasons sn
          JOIN episodes e ON e.season_id=sn.id
          LEFT JOIN LATERAL (
            SELECT wp.position_ms,wp.duration_ms,wp.completed
              FROM watch_progress wp
              JOIN media_versions watched ON watched.id=wp.media_version_id
             WHERE wp.user_id=$1
               AND watched.episode_id=e.id
             ORDER BY wp.completed DESC,wp.updated_at DESC
             LIMIT 1
          ) p ON true
         WHERE sn.media_title_id=$2
         ORDER BY sn.season_number,e.episode_number
    `,userID,mediaID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    var watchedCount,totalCount int64

    for rows.Next() {
        var seasonID,episodeID string
        var seasonNumber,episodeNumber int
        var position,duration int64
        var completed bool
        if err:=rows.Scan(
            &seasonID,&seasonNumber,&episodeID,&episodeNumber,
            &position,&duration,&completed,
        ); err!=nil { continue }

        totalCount++
        if completed { watchedCount++ }

        progress:=0.0
        if duration>0 {
            progress=float64(position)/float64(duration)
            if progress<0 { progress=0 }
            if progress>1 { progress=1 }
        } else if completed {
            progress=1
        }

        items=append(items,map[string]any{
            "seasonId":seasonID,
            "seasonNumber":seasonNumber,
            "episodeId":episodeID,
            "episodeNumber":episodeNumber,
            "positionMs":position,
            "durationMs":duration,
            "completed":completed,
            "progress":progress,
        })
    }

    overall:=0.0
    if totalCount>0 {
        overall=float64(watchedCount)/float64(totalCount)
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "mediaTitleId":mediaID,
        "watchedCount":watchedCount,
        "totalCount":totalCount,
        "progress":overall,
        "items":items,
    })
}

func (s *Server) setEpisodeWatchedStatus(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    episodeID:=chi.URLParam(r,"id")

    var body struct {
        Watched bool `json:"watched"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    var exists bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(SELECT 1 FROM episodes WHERE id=$1)
    `,episodeID).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    if !exists {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"episode not found"}); return
    }

    if !body.Watched {
        _,err:=s.db.Exec(r.Context(),`
            DELETE FROM watch_progress wp
             USING media_versions mv
             WHERE wp.media_version_id=mv.id
               AND wp.user_id=$1
               AND mv.episode_id=$2
        `,userID,episodeID)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        writeJSON(w,http.StatusOK,map[string]any{"watched":false})
        return
    }

    var versionID string
    var duration int64
    err:=s.db.QueryRow(r.Context(),`
        SELECT id::text,COALESCE(duration_ms,0)
          FROM media_versions
         WHERE episode_id=$1 AND stream_ready=true
         ORDER BY preferred DESC,height DESC,file_size_bytes DESC
         LIMIT 1
    `,episodeID).Scan(&versionID,&duration)
    if err!=nil {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"episode has no stream-ready version"}); return
    }

    position:=duration
    if position<=0 { position=1 }

    _,err=s.db.Exec(r.Context(),`
        INSERT INTO watch_progress (
            user_id,media_version_id,position_ms,duration_ms,completed,updated_at
        ) VALUES ($1,$2,$3,$4,true,now())
        ON CONFLICT (user_id,media_version_id)
        DO UPDATE SET
          position_ms=EXCLUDED.position_ms,
          duration_ms=EXCLUDED.duration_ms,
          completed=true,
          updated_at=now()
    `,userID,versionID,position,duration)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"watched":true})
}

func (s *Server) setSeasonWatchedStatus(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    seasonID:=chi.URLParam(r,"id")

    var body struct {
        Watched bool `json:"watched"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    var exists bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(SELECT 1 FROM seasons WHERE id=$1)
    `,seasonID).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    if !exists {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"season not found"}); return
    }

    if !body.Watched {
        _,err:=s.db.Exec(r.Context(),`
            DELETE FROM watch_progress wp
             USING media_versions mv,episodes e
             WHERE wp.media_version_id=mv.id
               AND mv.episode_id=e.id
               AND wp.user_id=$1
               AND e.season_id=$2
        `,userID,seasonID)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        writeJSON(w,http.StatusOK,map[string]any{"watched":false})
        return
    }

    _,err:=s.db.Exec(r.Context(),`
        INSERT INTO watch_progress (
            user_id,media_version_id,position_ms,duration_ms,completed,updated_at
        )
        SELECT $1,mv.id,
               CASE WHEN COALESCE(mv.duration_ms,0)>0 THEN mv.duration_ms ELSE 1 END,
               COALESCE(mv.duration_ms,0),
               true,now()
          FROM episodes e
          JOIN LATERAL (
            SELECT id,duration_ms
              FROM media_versions
             WHERE episode_id=e.id AND stream_ready=true
             ORDER BY preferred DESC,height DESC,file_size_bytes DESC
             LIMIT 1
          ) mv ON true
         WHERE e.season_id=$2
        ON CONFLICT (user_id,media_version_id)
        DO UPDATE SET
          position_ms=EXCLUDED.position_ms,
          duration_ms=EXCLUDED.duration_ms,
          completed=true,
          updated_at=now()
    `,userID,seasonID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"watched":true})
}
