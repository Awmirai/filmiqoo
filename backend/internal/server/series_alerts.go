package server

import (
    "context"
    "encoding/json"
    "net/http"
    "strconv"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) seriesSubscriptionStatus(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    mediaID:=chi.URLParam(r,"id")

    var newEpisode,streamReady bool
    err:=s.db.QueryRow(r.Context(),`
        SELECT notify_new_episode,notify_stream_ready
          FROM series_subscriptions
         WHERE user_id=$1 AND media_title_id=$2
    `,userID,mediaID).Scan(&newEpisode,&streamReady)

    if err!=nil {
        writeJSON(w,http.StatusOK,map[string]any{
            "following":false,
            "notifyNewEpisode":true,
            "notifyStreamReady":true,
        })
        return
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "following":true,
        "notifyNewEpisode":newEpisode,
        "notifyStreamReady":streamReady,
    })
}

func (s *Server) updateSeriesSubscription(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    mediaID:=chi.URLParam(r,"id")

    var body struct {
        Following *bool `json:"following"`
        NotifyNewEpisode *bool `json:"notifyNewEpisode"`
        NotifyStreamReady *bool `json:"notifyStreamReady"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    var kind string
    if err:=s.db.QueryRow(r.Context(),`
        SELECT kind FROM media_titles WHERE id=$1
    `,mediaID).Scan(&kind); err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"series not found"}); return
    }
    if kind!="series" && kind!="anime" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"series alerts are only available for series or anime"}); return
    }

    following:=true
    if body.Following!=nil { following=*body.Following }

    if !following {
        _,err:=s.db.Exec(r.Context(),`
            DELETE FROM series_subscriptions
             WHERE user_id=$1 AND media_title_id=$2
        `,userID,mediaID)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        writeJSON(w,http.StatusOK,map[string]any{
            "following":false,
            "notifyNewEpisode":true,
            "notifyStreamReady":true,
        })
        return
    }

    notifyEpisode:=true
    notifyStream:=true
    if body.NotifyNewEpisode!=nil { notifyEpisode=*body.NotifyNewEpisode }
    if body.NotifyStreamReady!=nil { notifyStream=*body.NotifyStreamReady }

    _,err:=s.db.Exec(r.Context(),`
        INSERT INTO series_subscriptions (
            user_id,media_title_id,notify_new_episode,notify_stream_ready
        ) VALUES ($1,$2,$3,$4)
        ON CONFLICT (user_id,media_title_id)
        DO UPDATE SET
          notify_new_episode=EXCLUDED.notify_new_episode,
          notify_stream_ready=EXCLUDED.notify_stream_ready,
          updated_at=now()
    `,userID,mediaID,notifyEpisode,notifyStream)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{
        "following":true,
        "notifyNewEpisode":notifyEpisode,
        "notifyStreamReady":notifyStream,
    })
}

func (s *Server) seriesCalendar(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.processDueSeriesAlerts(r.Context(),userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    days:=45
    if raw:=strings.TrimSpace(r.URL.Query().Get("days")); raw!="" {
        if parsed,err:=strconv.Atoi(raw); err==nil {
            days=parsed
        }
    }
    if days<7 { days=7 }
    if days>180 { days=180 }

    rows,err:=s.db.Query(r.Context(),`
        SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,
               mt.poster_url,mt.backdrop_url,mt.rating,
               sn.season_number,e.id::text,e.episode_number,e.name,e.overview,
               e.still_url,e.runtime_minutes,e.air_date,
               EXISTS(
                 SELECT 1 FROM media_versions mv
                  WHERE mv.episode_id=e.id AND mv.playback_url<>''
               ) AS stream_ready
          FROM series_subscriptions ss
          JOIN media_titles mt ON mt.id=ss.media_title_id
          JOIN seasons sn ON sn.media_title_id=mt.id
          JOIN episodes e ON e.season_id=sn.id
         WHERE ss.user_id=$1
           AND e.air_date IS NOT NULL
           AND e.air_date BETWEEN CURRENT_DATE - INTERVAL '7 days'
                              AND CURRENT_DATE + ($2::int * INTERVAL '1 day')
         ORDER BY e.air_date ASC,mt.title ASC,sn.season_number ASC,e.episode_number ASC
         LIMIT 500
    `,userID,days)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var mediaID,kind,title,originalTitle,poster,backdrop string
        var tmdbID *int64
        var rating *float64
        var seasonNumber int
        var episodeID string
        var episodeNumber int
        var episodeName,overview,still string
        var runtime int
        var airDate time.Time
        var streamReady bool

        if err:=rows.Scan(
            &mediaID,&tmdbID,&kind,&title,&originalTitle,
            &poster,&backdrop,&rating,
            &seasonNumber,&episodeID,&episodeNumber,&episodeName,&overview,
            &still,&runtime,&airDate,&streamReady,
        ); err!=nil { continue }

        items=append(items,map[string]any{
            "media":map[string]any{
                "id":mediaID,"tmdbId":tmdbID,"kind":kind,
                "title":title,"originalTitle":originalTitle,
                "posterUrl":poster,"backdropUrl":backdrop,"rating":rating,
            },
            "episode":map[string]any{
                "id":episodeID,"seasonNumber":seasonNumber,
                "episodeNumber":episodeNumber,"name":episodeName,
                "overview":overview,"stillUrl":still,
                "runtimeMinutes":runtime,
                "airDate":airDate.Format("2006-01-02"),
                "streamReady":streamReady,
            },
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) processDueSeriesAlerts(ctx context.Context,userID string) error {
    tx,err:=s.db.Begin(ctx)
    if err!=nil { return err }
    defer tx.Rollback(ctx)

    episodeRows,err:=tx.Query(ctx,`
        SELECT ss.media_title_id,e.id,mt.title,sn.season_number,e.episode_number,e.name
          FROM series_subscriptions ss
          JOIN media_titles mt ON mt.id=ss.media_title_id
          JOIN seasons sn ON sn.media_title_id=mt.id
          JOIN episodes e ON e.season_id=sn.id
         WHERE ss.user_id=$1
           AND ss.notify_new_episode=true
           AND e.air_date IS NOT NULL
           AND e.air_date<=CURRENT_DATE
           AND e.air_date>=ss.created_at::date
           AND NOT EXISTS (
             SELECT 1 FROM episode_alert_log l
              WHERE l.user_id=$1 AND l.episode_id=e.id AND l.alert_type='new_episode'
           )
         ORDER BY e.air_date ASC
         LIMIT 50
         FOR UPDATE OF ss
    `,userID)
    if err!=nil { return err }

    type episodeAlert struct {
        mediaID string
        episodeID string
        title string
        season int
        episode int
        episodeName string
    }
    dueEpisodes:=make([]episodeAlert,0)
    for episodeRows.Next() {
        var x episodeAlert
        if episodeRows.Scan(
            &x.mediaID,&x.episodeID,&x.title,&x.season,&x.episode,&x.episodeName,
        )==nil {
            dueEpisodes=append(dueEpisodes,x)
        }
    }
    episodeRows.Close()

    for _,item:=range dueEpisodes {
        episodeLabel:="S"+strconv.Itoa(item.season)+"E"+strconv.Itoa(item.episode)
        if item.episodeName!="" {
            episodeLabel+=" • "+item.episodeName
        }
        _,err=tx.Exec(ctx,`
            INSERT INTO notifications (
                user_id,notification_type,entity_type,entity_id,title,body
            ) VALUES ($1,'new_episode','series',$2,$3,$4)
        `,userID,item.mediaID,"قسمت جدید: "+item.title,episodeLabel+" منتشر شده.")
        if err!=nil { return err }

        _,err=tx.Exec(ctx,`
            INSERT INTO episode_alert_log (user_id,episode_id,alert_type)
            VALUES ($1,$2,'new_episode')
            ON CONFLICT DO NOTHING
        `,userID,item.episodeID)
        if err!=nil { return err }
    }

    readyRows,err:=tx.Query(ctx,`
        SELECT DISTINCT ON (e.id)
               ss.media_title_id,e.id,mt.title,sn.season_number,e.episode_number,e.name
          FROM series_subscriptions ss
          JOIN media_titles mt ON mt.id=ss.media_title_id
          JOIN seasons sn ON sn.media_title_id=mt.id
          JOIN episodes e ON e.season_id=sn.id
          JOIN media_versions mv ON mv.episode_id=e.id
         WHERE ss.user_id=$1
           AND ss.notify_stream_ready=true
           AND mv.playback_url<>''
           AND mv.created_at>=ss.created_at
           AND NOT EXISTS (
             SELECT 1 FROM episode_alert_log l
              WHERE l.user_id=$1 AND l.episode_id=e.id AND l.alert_type='stream_ready'
           )
         ORDER BY e.id,mv.preferred DESC,mv.created_at DESC
         LIMIT 50
    `,userID)
    if err!=nil { return err }

    ready:=make([]episodeAlert,0)
    for readyRows.Next() {
        var x episodeAlert
        if readyRows.Scan(
            &x.mediaID,&x.episodeID,&x.title,&x.season,&x.episode,&x.episodeName,
        )==nil {
            ready=append(ready,x)
        }
    }
    readyRows.Close()

    for _,item:=range ready {
        episodeLabel:="S"+strconv.Itoa(item.season)+"E"+strconv.Itoa(item.episode)
        if item.episodeName!="" { episodeLabel+=" • "+item.episodeName }

        _,err=tx.Exec(ctx,`
            INSERT INTO notifications (
                user_id,notification_type,entity_type,entity_id,title,body
            ) VALUES ($1,'episode_stream_ready','series',$2,$3,$4)
        `,userID,item.mediaID,"آماده تماشا: "+item.title,episodeLabel+" حالا در Filmiqoo قابل پخش است.")
        if err!=nil { return err }

        _,err=tx.Exec(ctx,`
            INSERT INTO episode_alert_log (user_id,episode_id,alert_type)
            VALUES ($1,$2,'stream_ready')
            ON CONFLICT DO NOTHING
        `,userID,item.episodeID)
        if err!=nil { return err }
    }

    return tx.Commit(ctx)
}
