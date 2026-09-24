package server

import (
    "encoding/json"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) registerPushDevice(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())

    var body struct {
        DeviceID string `json:"deviceId"`
        Provider string `json:"provider"`
        Platform string `json:"platform"`
        PushToken string `json:"pushToken"`
        Locale string `json:"locale"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.DeviceID=strings.TrimSpace(body.DeviceID)
    body.Provider=strings.ToLower(strings.TrimSpace(body.Provider))
    body.Platform=strings.ToLower(strings.TrimSpace(body.Platform))
    body.PushToken=strings.TrimSpace(body.PushToken)
    body.Locale=strings.ToLower(strings.TrimSpace(body.Locale))

    if body.DeviceID=="" || body.PushToken=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"deviceId and pushToken are required"}); return
    }
    switch body.Provider {
    case "fcm","apns","webpush":
    default:
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid provider"}); return
    }
    if body.Platform=="" { body.Platform="android" }
    if body.Locale=="" { body.Locale="fa" }
    if len(body.PushToken)>4096 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"push token is too long"}); return
    }

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO push_devices (
            user_id,device_id,provider,platform,push_token,locale,enabled,last_seen_at
        ) VALUES ($1,$2,$3,$4,$5,$6,true,now())
        ON CONFLICT (user_id,device_id,provider)
        DO UPDATE SET
          platform=EXCLUDED.platform,
          push_token=EXCLUDED.push_token,
          locale=EXCLUDED.locale,
          enabled=true,
          last_seen_at=now()
        RETURNING id::text
    `,userID,body.DeviceID,body.Provider,body.Platform,body.PushToken,body.Locale).Scan(&id)
    if err!=nil {
        // A token transferred to another account/device should be rebound safely.
        _,_ = s.db.Exec(r.Context(),`
            DELETE FROM push_devices
             WHERE provider=$1 AND push_token=$2
        `,body.Provider,body.PushToken)
        err=s.db.QueryRow(r.Context(),`
            INSERT INTO push_devices (
                user_id,device_id,provider,platform,push_token,locale,enabled,last_seen_at
            ) VALUES ($1,$2,$3,$4,$5,$6,true,now())
            ON CONFLICT (user_id,device_id,provider)
            DO UPDATE SET
              platform=EXCLUDED.platform,
              push_token=EXCLUDED.push_token,
              locale=EXCLUDED.locale,
              enabled=true,
              last_seen_at=now()
            RETURNING id::text
        `,userID,body.DeviceID,body.Provider,body.Platform,body.PushToken,body.Locale).Scan(&id)
    }
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"id":id,"enabled":true})
}

func (s *Server) unregisterPushDevice(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    deviceID:=strings.TrimSpace(chi.URLParam(r,"deviceID"))
    if deviceID=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"deviceId is required"}); return
    }

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE push_devices
           SET enabled=false,last_seen_at=now()
         WHERE user_id=$1 AND device_id=$2 AND enabled=true
    `,userID,deviceID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"disabled":tag.RowsAffected()})
}

func (s *Server) pushDevices(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    rows,err:=s.db.Query(r.Context(),`
        SELECT id::text,device_id,provider,platform,locale,enabled,created_at,last_seen_at
          FROM push_devices
         WHERE user_id=$1
         ORDER BY last_seen_at DESC
         LIMIT 100
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,deviceID,provider,platform,locale string
        var enabled bool
        var created,lastSeen time.Time
        if err:=rows.Scan(
            &id,&deviceID,&provider,&platform,&locale,&enabled,&created,&lastSeen,
        ); err!=nil { continue }
        items=append(items,map[string]any{
            "id":id,"deviceId":deviceID,"provider":provider,"platform":platform,
            "locale":locale,"enabled":enabled,"createdAt":created,"lastSeenAt":lastSeen,
        })
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
