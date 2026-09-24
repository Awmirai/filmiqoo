package server

import (
    "encoding/json"
    "net/http"
    "strconv"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) reelPlaybackEvent(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    reelID:=chi.URLParam(r,"id")

    var body struct {
        WatchMS int64 `json:"watchMs"`
        DurationMS int64 `json:"durationMs"`
        Completed bool `json:"completed"`
        Rewatched bool `json:"rewatched"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    if body.WatchMS<500 {
        w.WriteHeader(http.StatusNoContent)
        return
    }
    if body.WatchMS>6*60*60*1000 { body.WatchMS=6*60*60*1000 }
    if body.DurationMS<0 { body.DurationMS=0 }
    if body.DurationMS>2*60*60*1000 { body.DurationMS=2*60*60*1000 }

    var published bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT status='published' FROM reels WHERE id=$1
    `,reelID).Scan(&published); err!=nil || !published {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"reel not found"}); return
    }

    _,err:=s.db.Exec(r.Context(),`
        INSERT INTO reel_playback_events (
            reel_id,user_id,watch_ms,duration_ms,completed,rewatched
        ) VALUES ($1,$2,$3,$4,$5,$6)
    `,reelID,userID,body.WatchMS,body.DurationMS,body.Completed,body.Rewatched)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusCreated,map[string]any{"recorded":true})
}

func (s *Server) creatorAnalyticsV3(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    days:=30
    if raw:=strings.TrimSpace(r.URL.Query().Get("days")); raw!="" {
        if parsed,err:=strconv.Atoi(raw); err==nil { days=parsed }
    }
    if days<7 { days=7 }
    if days>90 { days=90 }

    var sessions,uniqueViewers,totalWatchMS,completedSessions,rewatchedSessions int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*),
               COUNT(DISTINCT ev.user_id),
               COALESCE(SUM(ev.watch_ms),0),
               COUNT(*) FILTER (WHERE ev.completed),
               COUNT(*) FILTER (WHERE ev.rewatched)
          FROM reel_playback_events ev
          JOIN reels r ON r.id=ev.reel_id
         WHERE r.creator_user_id=$1
           AND ev.created_at>=now()-($2::int * INTERVAL '1 day')
    `,userID,days).Scan(
        &sessions,&uniqueViewers,&totalWatchMS,&completedSessions,&rewatchedSessions,
    )

    var followersGained int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*)
          FROM user_follows
         WHERE followed_user_id=$1
           AND created_at>=now()-($2::int * INTERVAL '1 day')
    `,userID,days).Scan(&followersGained)

    completionRate:=0.0
    rewatchRate:=0.0
    avgWatchMS:=int64(0)
    followerConversion:=0.0
    if sessions>0 {
        completionRate=float64(completedSessions)/float64(sessions)
        rewatchRate=float64(rewatchedSessions)/float64(sessions)
        avgWatchMS=totalWatchMS/sessions
    }
    if uniqueViewers>0 {
        followerConversion=float64(followersGained)/float64(uniqueViewers)
    }

    dailyRows,err:=s.db.Query(r.Context(),`
        WITH dates AS (
            SELECT generate_series(
                CURRENT_DATE-(($2::int-1) * INTERVAL '1 day'),
                CURRENT_DATE,
                INTERVAL '1 day'
            )::date AS day
        ),
        agg AS (
            SELECT ev.created_at::date AS day,
                   COUNT(*) AS sessions,
                   COUNT(DISTINCT ev.user_id) AS unique_viewers,
                   COALESCE(SUM(ev.watch_ms),0) AS watch_ms,
                   COUNT(*) FILTER (WHERE ev.completed) AS completed,
                   COUNT(*) FILTER (WHERE ev.rewatched) AS rewatched
              FROM reel_playback_events ev
              JOIN reels r ON r.id=ev.reel_id
             WHERE r.creator_user_id=$1
               AND ev.created_at>=CURRENT_DATE-(($2::int-1) * INTERVAL '1 day')
             GROUP BY ev.created_at::date
        )
        SELECT d.day,
               COALESCE(a.sessions,0),
               COALESCE(a.unique_viewers,0),
               COALESCE(a.watch_ms,0),
               COALESCE(a.completed,0),
               COALESCE(a.rewatched,0)
          FROM dates d
          LEFT JOIN agg a ON a.day=d.day
         ORDER BY d.day ASC
    `,userID,days)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer dailyRows.Close()

    daily:=make([]map[string]any,0,days)
    for dailyRows.Next() {
        var day time.Time
        var daySessions,dayUnique,watchMS,completed,rewatched int64
        if err:=dailyRows.Scan(
            &day,&daySessions,&dayUnique,&watchMS,&completed,&rewatched,
        ); err!=nil { continue }

        completion:=0.0
        rewatch:=0.0
        if daySessions>0 {
            completion=float64(completed)/float64(daySessions)
            rewatch=float64(rewatched)/float64(daySessions)
        }
        daily=append(daily,map[string]any{
            "date":day.Format("2006-01-02"),
            "sessions":daySessions,
            "uniqueViewers":dayUnique,
            "watchMs":watchMS,
            "completionRate":completion,
            "rewatchRate":rewatch,
        })
    }

    topRows,err:=s.db.Query(r.Context(),`
        SELECT r.id::text,r.caption,r.cover_url,
               COUNT(ev.id) AS sessions,
               COUNT(DISTINCT ev.user_id) AS unique_viewers,
               COALESCE(SUM(ev.watch_ms),0) AS watch_ms,
               COUNT(ev.id) FILTER (WHERE ev.completed) AS completed
          FROM reels r
          LEFT JOIN reel_playback_events ev
            ON ev.reel_id=r.id
           AND ev.created_at>=now()-($2::int * INTERVAL '1 day')
         WHERE r.creator_user_id=$1
           AND r.status='published'
         GROUP BY r.id,r.caption,r.cover_url,r.published_at
         ORDER BY sessions DESC,watch_ms DESC,r.published_at DESC NULLS LAST
         LIMIT 10
    `,userID,days)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer topRows.Close()

    topReels:=make([]map[string]any,0)
    for topRows.Next() {
        var id,caption,cover string
        var reelSessions,reelUnique,watchMS,completed int64
        if err:=topRows.Scan(
            &id,&caption,&cover,&reelSessions,&reelUnique,&watchMS,&completed,
        ); err!=nil { continue }
        rate:=0.0
        if reelSessions>0 { rate=float64(completed)/float64(reelSessions) }
        topReels=append(topReels,map[string]any{
            "id":id,
            "caption":caption,
            "coverUrl":cover,
            "sessions":reelSessions,
            "uniqueViewers":reelUnique,
            "watchMs":watchMS,
            "completionRate":rate,
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "days":days,
        "summary":map[string]any{
            "sessions":sessions,
            "uniqueViewers":uniqueViewers,
            "watchMs":totalWatchMS,
            "averageWatchMs":avgWatchMS,
            "completionRate":completionRate,
            "rewatchRate":rewatchRate,
            "followersGained":followersGained,
            "followerConversion":followerConversion,
        },
        "daily":daily,
        "topReels":topReels,
    })
}
