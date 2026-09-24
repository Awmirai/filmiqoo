package server

import (
    "context"
    "encoding/json"
    "net/http"
    "strings"

    "github.com/go-chi/chi/v5"
)

var availabilityAlertTypes=map[string]bool{
    "persian_dub":true,
    "persian_subtitle":true,
    "uhd_4k":true,
    "hdr":true,
}

type availabilityState struct {
    PersianDub bool
    PersianSubtitle bool
    UHD4K bool
    HDR bool
}

func (s *Server) mediaAvailabilityState(ctx context.Context,mediaID string) (availabilityState,error) {
    var state availabilityState
    err:=s.db.QueryRow(ctx,`
        WITH versions AS (
            SELECT mv.*
              FROM media_versions mv
              LEFT JOIN episodes e ON e.id=mv.episode_id
              LEFT JOIN seasons sn ON sn.id=e.season_id
             WHERE COALESCE(mv.media_title_id,sn.media_title_id)=$1
               AND (mv.stream_ready=true OR mv.playback_url<>'')
        )
        SELECT
          EXISTS(
            SELECT 1 FROM versions v
             WHERE lower(v.audio_tracks::text) ~
               '("(language|lang|code)"[[:space:]]*:[[:space:]]*"(fa|fas|per)([-_][a-z]+)?"|farsi|persian|فارسی)'
          ),
          EXISTS(
            SELECT 1 FROM versions v
             WHERE lower(v.subtitle_tracks::text) ~
               '("(language|lang|code)"[[:space:]]*:[[:space:]]*"(fa|fas|per)([-_][a-z]+)?"|farsi|persian|فارسی)'
          ),
          EXISTS(
            SELECT 1 FROM versions v
             WHERE v.height>=2160
                OR lower(v.quality_label) LIKE '%2160%'
                OR lower(v.quality_label) LIKE '%4k%'
          ),
          EXISTS(
            SELECT 1 FROM versions v
             WHERE trim(v.hdr_type)<>''
               AND lower(v.hdr_type) NOT IN ('sdr','none','false')
          )
    `,mediaID).Scan(
        &state.PersianDub,&state.PersianSubtitle,&state.UHD4K,&state.HDR,
    )
    return state,err
}

func availabilityForType(state availabilityState,typ string) bool {
    switch typ {
    case "persian_dub":
        return state.PersianDub
    case "persian_subtitle":
        return state.PersianSubtitle
    case "uhd_4k":
        return state.UHD4K
    case "hdr":
        return state.HDR
    default:
        return false
    }
}

func availabilityTitle(typ string) string {
    switch typ {
    case "persian_dub":
        return "دوبله فارسی اضافه شد"
    case "persian_subtitle":
        return "زیرنویس فارسی اضافه شد"
    case "uhd_4k":
        return "نسخه 4K آماده شد"
    case "hdr":
        return "نسخه HDR آماده شد"
    default:
        return "نسخه جدید آماده شد"
    }
}

func availabilityBody(typ,title string) string {
    switch typ {
    case "persian_dub":
        return title+" حالا با ترک صوتی فارسی در Filmiqoo در دسترس است."
    case "persian_subtitle":
        return title+" حالا زیرنویس فارسی دارد."
    case "uhd_4k":
        return "نسخه 4K "+title+" حالا در دسترس است."
    case "hdr":
        return "نسخه HDR "+title+" حالا در دسترس است."
    default:
        return title+" به‌روزرسانی شد."
    }
}

func (s *Server) availabilityAlertsStatus(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    mediaID:=chi.URLParam(r,"id")

    var title string
    if err:=s.db.QueryRow(r.Context(),`
        SELECT title FROM media_titles WHERE id=$1
    `,mediaID).Scan(&title); err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"media not found"})
        return
    }

    if err:=s.processAvailabilityAlertsForTitle(r.Context(),userID,mediaID,title); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    state,err:=s.mediaAvailabilityState(r.Context(),mediaID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    subscribed:=map[string]bool{}
    rows,err:=s.db.Query(r.Context(),`
        SELECT alert_type
          FROM media_availability_alerts
         WHERE user_id=$1 AND media_title_id=$2 AND notified_at IS NULL
    `,userID,mediaID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    for rows.Next() {
        var typ string
        if rows.Scan(&typ)==nil { subscribed[typ]=true }
    }
    rows.Close()

    writeJSON(w,http.StatusOK,map[string]any{
        "mediaTitleId":mediaID,
        "title":title,
        "available":map[string]any{
            "persianDub":state.PersianDub,
            "persianSubtitle":state.PersianSubtitle,
            "uhd4k":state.UHD4K,
            "hdr":state.HDR,
        },
        "subscribed":map[string]any{
            "persianDub":subscribed["persian_dub"],
            "persianSubtitle":subscribed["persian_subtitle"],
            "uhd4k":subscribed["uhd_4k"],
            "hdr":subscribed["hdr"],
        },
    })
}

func (s *Server) updateAvailabilityAlert(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    mediaID:=chi.URLParam(r,"id")

    var body struct {
        AlertType string `json:"alertType"`
        Enabled bool `json:"enabled"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    body.AlertType=strings.ToLower(strings.TrimSpace(body.AlertType))
    if !availabilityAlertTypes[body.AlertType] {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid alertType"})
        return
    }

    var title string
    if err:=s.db.QueryRow(r.Context(),`
        SELECT title FROM media_titles WHERE id=$1
    `,mediaID).Scan(&title); err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"media not found"})
        return
    }

    state,err:=s.mediaAvailabilityState(r.Context(),mediaID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    alreadyAvailable:=availabilityForType(state,body.AlertType)

    if !body.Enabled || alreadyAvailable {
        _,err=s.db.Exec(r.Context(),`
            DELETE FROM media_availability_alerts
             WHERE user_id=$1 AND media_title_id=$2 AND alert_type=$3
        `,userID,mediaID,body.AlertType)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

        writeJSON(w,http.StatusOK,map[string]any{
            "enabled":false,
            "available":alreadyAvailable,
            "alertType":body.AlertType,
        })
        return
    }

    _,err=s.db.Exec(r.Context(),`
        INSERT INTO media_availability_alerts (
            user_id,media_title_id,alert_type,notified_at,updated_at
        ) VALUES ($1,$2,$3,NULL,now())
        ON CONFLICT (user_id,media_title_id,alert_type)
        DO UPDATE SET notified_at=NULL,updated_at=now()
    `,userID,mediaID,body.AlertType)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{
        "enabled":true,
        "available":false,
        "alertType":body.AlertType,
    })
}

func (s *Server) processDueAvailabilityAlerts(ctx context.Context,userID string) error {
    rows,err:=s.db.Query(ctx,`
        SELECT DISTINCT media_title_id::text
          FROM media_availability_alerts
         WHERE user_id=$1 AND notified_at IS NULL
         LIMIT 200
    `,userID)
    if err!=nil { return err }

    mediaIDs:=make([]string,0)
    for rows.Next() {
        var id string
        if rows.Scan(&id)==nil { mediaIDs=append(mediaIDs,id) }
    }
    rows.Close()

    for _,mediaID:=range mediaIDs {
        var title string
        if err:=s.db.QueryRow(ctx,`
            SELECT title FROM media_titles WHERE id=$1
        `,mediaID).Scan(&title); err!=nil {
            continue
        }
        if err:=s.processAvailabilityAlertsForTitle(ctx,userID,mediaID,title); err!=nil {
            return err
        }
    }
    return nil
}

func (s *Server) processAvailabilityAlertsForTitle(
    ctx context.Context,
    userID string,
    mediaID string,
    title string,
) error {
    state,err:=s.mediaAvailabilityState(ctx,mediaID)
    if err!=nil { return err }

    rows,err:=s.db.Query(ctx,`
        SELECT alert_type
          FROM media_availability_alerts
         WHERE user_id=$1 AND media_title_id=$2 AND notified_at IS NULL
    `,userID,mediaID)
    if err!=nil { return err }

    types:=make([]string,0)
    for rows.Next() {
        var typ string
        if rows.Scan(&typ)==nil { types=append(types,typ) }
    }
    rows.Close()

    for _,typ:=range types {
        if !availabilityForType(state,typ) { continue }

        tx,err:=s.db.Begin(ctx)
        if err!=nil { return err }

        tag,err:=tx.Exec(ctx,`
            UPDATE media_availability_alerts
               SET notified_at=now(),updated_at=now()
             WHERE user_id=$1 AND media_title_id=$2
               AND alert_type=$3 AND notified_at IS NULL
        `,userID,mediaID,typ)
        if err!=nil {
            tx.Rollback(ctx)
            return err
        }

        if tag.RowsAffected()>0 {
            _,err=tx.Exec(ctx,`
                INSERT INTO notifications (
                    user_id,notification_type,entity_type,entity_id,title,body
                ) VALUES ($1,'availability_ready','availability',$2,$3,$4)
            `,userID,mediaID,availabilityTitle(typ),availabilityBody(typ,title))
            if err!=nil {
                tx.Rollback(ctx)
                return err
            }
        }

        if err:=tx.Commit(ctx); err!=nil { return err }
    }

    return nil
}
