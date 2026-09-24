package server

import (
    "context"
    "crypto/rand"
    "encoding/hex"
    "net/http"
    "time"

    "github.com/go-chi/chi/v5"
)

func newWatchPartyInviteCode() string {
    buf:=make([]byte,6)
    if _,err:=rand.Read(buf); err==nil {
        return hex.EncodeToString(buf)
    }
    return hex.EncodeToString([]byte(time.Now().Format("150405")))
}

func (s *Server) watchPartyInviteInfo(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    var code,visibility,state string
    var scheduled *time.Time
    var allowed bool

    err:=s.db.QueryRow(r.Context(),`
        SELECT wp.invite_code,wp.visibility,wp.state,wp.scheduled_at,
               EXISTS(
                 SELECT 1 FROM watch_party_members wpm
                  WHERE wpm.watch_party_id=wp.id
                    AND wpm.user_id=$2
                    AND wpm.role IN ('host','cohost')
               )
          FROM watch_parties wp
         WHERE wp.id=$1
    `,id,userID).Scan(&code,&visibility,&state,&scheduled,&allowed)
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"watch party not found"})
        return
    }
    if !allowed {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"})
        return
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "inviteCode":code,
        "visibility":visibility,
        "state":state,
        "scheduledAt":scheduled,
    })
}

func (s *Server) regenerateWatchPartyInvite(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    var allowed bool
    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
          SELECT 1 FROM watch_party_members
           WHERE watch_party_id=$1 AND user_id=$2
             AND role IN ('host','cohost')
        )
    `,id,userID).Scan(&allowed)
    if !allowed {
        writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"})
        return
    }

    code:=newWatchPartyInviteCode()
    _,err:=s.db.Exec(r.Context(),`
        UPDATE watch_parties SET invite_code=$2 WHERE id=$1
    `,id,code)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"inviteCode":code})
}

func (s *Server) watchPartyReminderStatus(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    var enabled bool
    err:=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
          SELECT 1 FROM watch_party_reminders
           WHERE watch_party_id=$1 AND user_id=$2
        )
    `,id,userID).Scan(&enabled)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"enabled":enabled})
}

func (s *Server) toggleWatchPartyReminder(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    var scheduled *time.Time
    var state string
    err:=s.db.QueryRow(r.Context(),`
        SELECT scheduled_at,state FROM watch_parties WHERE id=$1
    `,id).Scan(&scheduled,&state)
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"watch party not found"}); return
    }
    if scheduled==nil || state!="scheduled" {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"reminders are only available for scheduled parties"})
        return
    }

    var exists bool
    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
          SELECT 1 FROM watch_party_reminders
           WHERE watch_party_id=$1 AND user_id=$2
        )
    `,id,userID).Scan(&exists)

    if exists {
        _,err=s.db.Exec(r.Context(),`
            DELETE FROM watch_party_reminders
             WHERE watch_party_id=$1 AND user_id=$2
        `,id,userID)
    } else {
        _,err=s.db.Exec(r.Context(),`
            INSERT INTO watch_party_reminders (watch_party_id,user_id)
            VALUES ($1,$2)
            ON CONFLICT DO NOTHING
        `,id,userID)
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"enabled":!exists})
}

func (s *Server) processDueWatchPartyReminders(ctx context.Context,userID string) error {
    tx,err:=s.db.Begin(ctx)
    if err!=nil { return err }
    defer tx.Rollback(ctx)

    rows,err:=tx.Query(ctx,`
        SELECT wpr.watch_party_id::text,wp.title,wp.scheduled_at
          FROM watch_party_reminders wpr
          JOIN watch_parties wp ON wp.id=wpr.watch_party_id
         WHERE wpr.user_id=$1
           AND wpr.notified_at IS NULL
           AND wp.state='scheduled'
           AND wp.scheduled_at IS NOT NULL
           AND wp.scheduled_at<=now()+interval '15 minutes'
           AND wp.scheduled_at>now()-interval '2 hours'
         ORDER BY wp.scheduled_at ASC
         FOR UPDATE OF wpr
    `,userID)
    if err!=nil { return err }

    type due struct {
        id,title string
        at time.Time
    }
    items:=make([]due,0)
    for rows.Next() {
        var x due
        if rows.Scan(&x.id,&x.title,&x.at)==nil {
            items=append(items,x)
        }
    }
    rows.Close()

    for _,item:=range items {
        _,err=tx.Exec(ctx,`
            INSERT INTO notifications (
                user_id,notification_type,entity_type,entity_id,title,body
            ) VALUES (
                $1,'watch_party_reminder','watch_party',$2,$3,$4
            )
        `,
            userID,item.id,
            "Watch Party نزدیکه",
            item.title+" تا چند دقیقه دیگه شروع می‌شه.",
        )
        if err!=nil { return err }

        _,err=tx.Exec(ctx,`
            UPDATE watch_party_reminders
               SET notified_at=now()
             WHERE watch_party_id=$1 AND user_id=$2
        `,item.id,userID)
        if err!=nil { return err }
    }

    return tx.Commit(ctx)
}
