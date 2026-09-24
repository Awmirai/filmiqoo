package server

import (
    "context"
    "encoding/json"
    "net/http"
    "strings"
    "time"
)

func (s *Server) releaseReminders(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.processDueReleaseReminders(r.Context(),userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    rows,err:=s.db.Query(r.Context(),`
        SELECT tmdb_id,kind,title,release_date,
               COALESCE(media_title_id::text,''),notified_at,created_at
          FROM release_reminders
         WHERE user_id=$1
         ORDER BY release_date ASC,created_at DESC
         LIMIT 300
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var tmdbID int64
        var kind,title,dateText,mediaID string
        var notified *time.Time
        var created time.Time
        var releaseDate time.Time
        if err:=rows.Scan(
            &tmdbID,&kind,&title,&releaseDate,&mediaID,&notified,&created,
        ); err!=nil { continue }
        dateText=releaseDate.Format("2006-01-02")
        items=append(items,map[string]any{
            "tmdbId":tmdbID,
            "kind":kind,
            "title":title,
            "releaseDate":dateText,
            "mediaTitleId":mediaID,
            "notified":notified!=nil,
            "createdAt":created,
        })
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) toggleReleaseReminder(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())

    var body struct {
        TMDBID int64 `json:"tmdbId"`
        Kind string `json:"kind"`
        Title string `json:"title"`
        ReleaseDate string `json:"releaseDate"`
        MediaTitleID *string `json:"mediaTitleId"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.Kind=strings.ToLower(strings.TrimSpace(body.Kind))
    if body.Kind=="series" { body.Kind="tv" }
    body.Title=strings.TrimSpace(body.Title)

    if body.TMDBID<=0 || body.Title=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"tmdbId and title are required"}); return
    }
    if body.Kind!="movie" && body.Kind!="tv" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"kind must be movie or tv"}); return
    }
    releaseDate,err:=time.Parse("2006-01-02",body.ReleaseDate)
    if err!=nil {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"releaseDate must use YYYY-MM-DD"}); return
    }

    var exists bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM release_reminders
             WHERE user_id=$1 AND tmdb_id=$2 AND kind=$3
        )
    `,userID,body.TMDBID,body.Kind).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    if exists {
        _,err=s.db.Exec(r.Context(),`
            DELETE FROM release_reminders
             WHERE user_id=$1 AND tmdb_id=$2 AND kind=$3
        `,userID,body.TMDBID,body.Kind)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        writeJSON(w,http.StatusOK,map[string]any{"reminded":false})
        return
    }

    var mediaID any=nil
    if body.MediaTitleID!=nil && strings.TrimSpace(*body.MediaTitleID)!="" {
        mediaID=strings.TrimSpace(*body.MediaTitleID)
    } else {
        var discovered string
        if s.db.QueryRow(r.Context(),`
            SELECT id::text
              FROM media_titles
             WHERE tmdb_id=$1
             ORDER BY updated_at DESC
             LIMIT 1
        `,body.TMDBID).Scan(&discovered)==nil {
            mediaID=discovered
        }
    }

    _,err=s.db.Exec(r.Context(),`
        INSERT INTO release_reminders (
            user_id,tmdb_id,kind,title,release_date,media_title_id
        ) VALUES ($1,$2,$3,$4,$5,$6)
        ON CONFLICT (user_id,tmdb_id,kind)
        DO UPDATE SET
          title=EXCLUDED.title,
          release_date=EXCLUDED.release_date,
          media_title_id=COALESCE(EXCLUDED.media_title_id,release_reminders.media_title_id),
          notified_at=NULL
    `,userID,body.TMDBID,body.Kind,body.Title,releaseDate,mediaID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"reminded":true})
}

func (s *Server) processDueReleaseReminders(ctx context.Context,userID string) error {
    tx,err:=s.db.Begin(ctx)
    if err!=nil { return err }
    defer tx.Rollback(ctx)

    rows,err:=tx.Query(ctx,`
        SELECT tmdb_id,kind,title,release_date,media_title_id
          FROM release_reminders
         WHERE user_id=$1
           AND notified_at IS NULL
           AND release_date<=CURRENT_DATE
         ORDER BY release_date ASC
         FOR UPDATE
    `,userID)
    if err!=nil { return err }

    type dueReminder struct {
        tmdbID int64
        kind,title string
        date time.Time
        mediaID *string
    }
    due:=make([]dueReminder,0)
    for rows.Next() {
        var x dueReminder
        if err:=rows.Scan(&x.tmdbID,&x.kind,&x.title,&x.date,&x.mediaID); err==nil {
            due=append(due,x)
        }
    }
    rows.Close()

    for _,item:=range due {
        var entityID any=nil
        if item.mediaID!=nil && *item.mediaID!="" { entityID=*item.mediaID }

        _,err=tx.Exec(ctx,`
            INSERT INTO notifications (
                user_id,notification_type,entity_type,entity_id,title,body
            ) VALUES ($1,'release_ready','release',$2,$3,$4)
        `,
            userID,
            entityID,
            "منتشر شد: "+item.title,
            "عنوانی که برای انتشارش Reminder گذاشته بودی حالا به تاریخ انتشار رسیده.",
        )
        if err!=nil { return err }

        _,err=tx.Exec(ctx,`
            UPDATE release_reminders
               SET notified_at=now()
             WHERE user_id=$1 AND tmdb_id=$2 AND kind=$3
        `,userID,item.tmdbID,item.kind)
        if err!=nil { return err }
    }

    return tx.Commit(ctx)
}
