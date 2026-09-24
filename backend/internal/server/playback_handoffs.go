package server

import (
    "encoding/json"
    "fmt"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) heartbeatPlaybackDevice(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())

    var body struct {
        DeviceID string `json:"deviceId"`
        DeviceName string `json:"deviceName"`
        Platform string `json:"platform"`
        MediaVersionID *string `json:"mediaVersionId"`
        PositionMs int64 `json:"positionMs"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.DeviceID=strings.TrimSpace(body.DeviceID)
    body.DeviceName=strings.TrimSpace(body.DeviceName)
    body.Platform=strings.ToLower(strings.TrimSpace(body.Platform))
    if body.DeviceID=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"deviceId is required"}); return
    }
    if body.DeviceName=="" { body.DeviceName="Android device" }
    if len([]rune(body.DeviceName))>120 {
        body.DeviceName=string([]rune(body.DeviceName)[:120])
    }
    if body.Platform=="" { body.Platform="android" }
    if body.PositionMs<0 { body.PositionMs=0 }

    var media any=nil
    if body.MediaVersionID!=nil {
        v:=strings.TrimSpace(*body.MediaVersionID)
        if v!="" {
            var exists bool
            _=s.db.QueryRow(r.Context(),
                "SELECT EXISTS(SELECT 1 FROM media_versions WHERE id=$1)",v).Scan(&exists)
            if exists { media=v }
        }
    }

    _,err:=s.db.Exec(r.Context(),`
        INSERT INTO playback_devices (
            user_id,device_id,device_name,platform,
            current_media_version_id,position_ms,last_seen_at
        ) VALUES ($1,$2,$3,$4,$5,$6,now())
        ON CONFLICT (user_id,device_id)
        DO UPDATE SET
          device_name=EXCLUDED.device_name,
          platform=EXCLUDED.platform,
          current_media_version_id=COALESCE(
              EXCLUDED.current_media_version_id,
              playback_devices.current_media_version_id
          ),
          position_ms=CASE
              WHEN EXCLUDED.current_media_version_id IS NULL
                  THEN playback_devices.position_ms
              ELSE EXCLUDED.position_ms
          END,
          last_seen_at=now()
    `,
        userID,body.DeviceID,body.DeviceName,body.Platform,
        media,body.PositionMs,
    )
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"registered":true})
}

func (s *Server) playbackDevices(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    currentDeviceID:=strings.TrimSpace(r.URL.Query().Get("currentDeviceId"))

    rows,err:=s.db.Query(r.Context(),`
        SELECT pd.device_id,pd.device_name,pd.platform,pd.last_seen_at,
               COALESCE(pd.current_media_version_id::text,''),
               pd.position_ms
          FROM playback_devices pd
         WHERE pd.user_id=$1
           AND pd.last_seen_at>now()-interval '30 days'
         ORDER BY pd.last_seen_at DESC
         LIMIT 50
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var deviceID,name,platform,versionID string
        var seen time.Time
        var position int64
        if err:=rows.Scan(
            &deviceID,&name,&platform,&seen,&versionID,&position,
        ); err!=nil { continue }

        items=append(items,map[string]any{
            "deviceId":deviceID,
            "deviceName":name,
            "platform":platform,
            "lastSeenAt":seen,
            "mediaVersionId":versionID,
            "positionMs":position,
            "current":deviceID==currentDeviceID,
            "online":seen.After(time.Now().Add(-2*time.Minute)),
        })
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) createPlaybackHandoff(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())

    var body struct {
        SourceDeviceID string `json:"sourceDeviceId"`
        TargetDeviceID string `json:"targetDeviceId"`
        MediaVersionID string `json:"mediaVersionId"`
        PositionMs int64 `json:"positionMs"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.SourceDeviceID=strings.TrimSpace(body.SourceDeviceID)
    body.TargetDeviceID=strings.TrimSpace(body.TargetDeviceID)
    body.MediaVersionID=strings.TrimSpace(body.MediaVersionID)
    if body.SourceDeviceID=="" || body.TargetDeviceID=="" || body.MediaVersionID=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"sourceDeviceId, targetDeviceId and mediaVersionId are required"}); return
    }
    if body.SourceDeviceID==body.TargetDeviceID {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"target device must be different"}); return
    }
    if body.PositionMs<0 { body.PositionMs=0 }

    var targetExists,mediaExists bool
    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM playback_devices
             WHERE user_id=$1 AND device_id=$2
               AND last_seen_at>now()-interval '30 days'
        )
    `,userID,body.TargetDeviceID).Scan(&targetExists)
    if !targetExists {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"target device not found"}); return
    }

    _=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM media_versions WHERE id=$1 AND stream_ready=true
        )
    `,body.MediaVersionID).Scan(&mediaExists)
    if !mediaExists {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"playback version not found"}); return
    }

    _,_=s.db.Exec(r.Context(),`
        UPDATE playback_handoffs
           SET state='cancelled'
         WHERE user_id=$1
           AND target_device_id=$2
           AND state='pending'
    `,userID,body.TargetDeviceID)

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO playback_handoffs (
            user_id,source_device_id,target_device_id,
            media_version_id,position_ms,state,expires_at
        ) VALUES ($1,$2,$3,$4,$5,'pending',now()+interval '5 minutes')
        RETURNING id::text
    `,
        userID,body.SourceDeviceID,body.TargetDeviceID,
        body.MediaVersionID,body.PositionMs,
    ).Scan(&id)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusCreated,map[string]any{
        "id":id,
        "state":"pending",
        "expiresInSeconds":300,
    })
}

func (s *Server) pendingPlaybackHandoff(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    deviceID:=strings.TrimSpace(r.URL.Query().Get("deviceId"))
    if deviceID=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"deviceId is required"}); return
    }

    _,_=s.db.Exec(r.Context(),`
        UPDATE playback_handoffs
           SET state='expired'
         WHERE user_id=$1
           AND state='pending'
           AND expires_at<=now()
    `,userID)

    row:=s.db.QueryRow(r.Context(),`
        SELECT ph.id::text,ph.media_version_id::text,ph.position_ms,
               ph.source_device_id,ph.created_at,ph.expires_at,
               mt.title,mt.poster_url,mv.quality_label,
               e.name,sn.season_number,e.episode_number,
               COALESCE(src.device_name,'')
          FROM playback_handoffs ph
          JOIN media_versions mv ON mv.id=ph.media_version_id
          LEFT JOIN playback_devices src
            ON src.user_id=ph.user_id AND src.device_id=ph.source_device_id
          LEFT JOIN episodes e ON e.id=mv.episode_id
          LEFT JOIN seasons sn ON sn.id=e.season_id
          JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
         WHERE ph.user_id=$1
           AND ph.target_device_id=$2
           AND ph.state='pending'
           AND ph.expires_at>now()
         ORDER BY ph.created_at DESC
         LIMIT 1
    `,userID,deviceID)

    var id,versionID,sourceDeviceID,title,poster,quality,sourceDeviceName string
    var episodeName *string
    var seasonNumber,episodeNumber *int
    var position int64
    var created,expires time.Time

    err:=row.Scan(
        &id,&versionID,&position,&sourceDeviceID,&created,&expires,
        &title,&poster,&quality,&episodeName,&seasonNumber,&episodeNumber,
        &sourceDeviceName,
    )
    if err!=nil {
        writeJSON(w,http.StatusOK,map[string]any{"pending":false})
        return
    }

    displayTitle:=title
    subtitle:=quality
    if episodeNumber!=nil && seasonNumber!=nil {
        if episodeName!=nil && strings.TrimSpace(*episodeName)!="" {
            displayTitle=*episodeName
        }
        subtitle=fmt.Sprintf("S%02dE%02d",*seasonNumber,*episodeNumber)
        if quality!="" { subtitle+=" • "+quality }
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "pending":true,
        "handoff":map[string]any{
            "id":id,
            "mediaVersionId":versionID,
            "positionMs":position,
            "sourceDeviceId":sourceDeviceID,
            "sourceDeviceName":sourceDeviceName,
            "title":displayTitle,
            "subtitle":subtitle,
            "posterUrl":poster,
            "createdAt":created,
            "expiresAt":expires,
        },
    })
}

func (s *Server) acceptPlaybackHandoff(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    var body struct {
        DeviceID string `json:"deviceId"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    body.DeviceID=strings.TrimSpace(body.DeviceID)

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE playback_handoffs
           SET state='accepted',accepted_at=now()
         WHERE id=$1 AND user_id=$2
           AND target_device_id=$3
           AND state='pending'
           AND expires_at>now()
    `,id,userID,body.DeviceID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    if tag.RowsAffected()==0 {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"handoff is no longer available"}); return
    }

    writeJSON(w,http.StatusOK,map[string]any{"accepted":true})
}

func (s *Server) cancelPlaybackHandoff(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE playback_handoffs
           SET state='cancelled'
         WHERE id=$1 AND user_id=$2 AND state='pending'
    `,id,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"cancelled":tag.RowsAffected()>0})
}
