package server

import (
    "encoding/json"
    "net/http"
    "strings"

    "github.com/go-chi/chi/v5"
)

type playbackSessionStartPayload struct {
    MediaVersionID string `json:"mediaVersionId"`
    PositionMS int64 `json:"positionMs"`
    NetworkType string `json:"networkType"`
    DeviceName string `json:"deviceName"`
    AppVersion string `json:"appVersion"`
}

type playbackSessionHeartbeatPayload struct {
    CurrentMediaVersionID string `json:"currentMediaVersionId"`
    PositionMS int64 `json:"positionMs"`
    DurationMS int64 `json:"durationMs"`
    WatchedDeltaMS int64 `json:"watchedDeltaMs"`
    BufferCountDelta int `json:"bufferCountDelta"`
    BufferMSDelta int64 `json:"bufferMsDelta"`
    QualitySwitchDelta int `json:"qualitySwitchDelta"`
    NetworkType string `json:"networkType"`
}

type playbackSessionEndPayload struct {
    CurrentMediaVersionID string `json:"currentMediaVersionId"`
    PositionMS int64 `json:"positionMs"`
    DurationMS int64 `json:"durationMs"`
    WatchedDeltaMS int64 `json:"watchedDeltaMs"`
    BufferCountDelta int `json:"bufferCountDelta"`
    BufferMSDelta int64 `json:"bufferMsDelta"`
    QualitySwitchDelta int `json:"qualitySwitchDelta"`
    NetworkType string `json:"networkType"`
    Completed bool `json:"completed"`
    ExitReason string `json:"exitReason"`
}

func clampInt64(v,min,max int64) int64 {
    if v<min { return min }
    if v>max { return max }
    return v
}

func clampInt(v,min,max int) int {
    if v<min { return min }
    if v>max { return max }
    return v
}

func cleanTelemetryText(value string,max int) string {
    value=strings.TrimSpace(value)
    runes:=[]rune(value)
    if len(runes)>max { value=string(runes[:max]) }
    return value
}

func (s *Server) startPlaybackSession(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    var body playbackSessionStartPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    body.MediaVersionID=strings.TrimSpace(body.MediaVersionID)
    if body.MediaVersionID=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"mediaVersionId is required"}); return
    }

    var exists bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(SELECT 1 FROM media_versions WHERE id=$1 AND stream_ready=true)
    `,body.MediaVersionID).Scan(&exists); err!=nil || !exists {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"playback version not found"}); return
    }

    viewerID:=s.viewerProfileID(r,userID)
    var viewer any=nil
    if viewerID!="" { viewer=viewerID }

    // Only one active video playback session per viewer profile/device flow.
    _,_=s.db.Exec(r.Context(),`
        UPDATE playback_sessions
           SET ended_at=now(),
               exit_reason=CASE WHEN exit_reason='' THEN 'replaced' ELSE exit_reason END
         WHERE user_id=$1
           AND ended_at IS NULL
           AND (
             ($2::uuid IS NULL AND viewer_profile_id IS NULL) OR
             viewer_profile_id=$2::uuid
           )
    `,userID,viewer)

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO playback_sessions (
            user_id,viewer_profile_id,
            started_media_version_id,current_media_version_id,
            position_ms,network_type,device_name,app_version
        ) VALUES ($1,$2,$3,$3,$4,$5,$6,$7)
        RETURNING id::text
    `,
        userID,viewer,body.MediaVersionID,
        clampInt64(body.PositionMS,0,86_400_000),
        cleanTelemetryText(body.NetworkType,32),
        cleanTelemetryText(body.DeviceName,120),
        cleanTelemetryText(body.AppVersion,40),
    ).Scan(&id)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusCreated,map[string]any{
        "id":id,
        "started":true,
    })
}

func (s *Server) heartbeatPlaybackSession(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")
    var body playbackSessionHeartbeatPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.CurrentMediaVersionID=strings.TrimSpace(body.CurrentMediaVersionID)
    watched:=clampInt64(body.WatchedDeltaMS,0,30_000)
    bufferCount:=clampInt(body.BufferCountDelta,0,20)
    bufferMS:=clampInt64(body.BufferMSDelta,0,120_000)
    switches:=clampInt(body.QualitySwitchDelta,0,10)

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE playback_sessions SET
          current_media_version_id=COALESCE(
            (SELECT id FROM media_versions WHERE id=$3),
            current_media_version_id
          ),
          position_ms=$4,
          duration_ms=$5,
          watched_ms=watched_ms+$6,
          buffer_count=buffer_count+$7,
          buffer_ms=buffer_ms+$8,
          quality_switch_count=quality_switch_count+$9,
          network_type=$10,
          last_heartbeat_at=now()
        WHERE id=$1 AND user_id=$2 AND ended_at IS NULL
    `,
        id,userID,body.CurrentMediaVersionID,
        clampInt64(body.PositionMS,0,86_400_000),
        clampInt64(body.DurationMS,0,86_400_000),
        watched,bufferCount,bufferMS,switches,
        cleanTelemetryText(body.NetworkType,32),
    )
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    if tag.RowsAffected()==0 {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"playback session not found"}); return
    }

    writeJSON(w,http.StatusOK,map[string]any{"updated":true})
}

func (s *Server) endPlaybackSession(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")
    var body playbackSessionEndPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    reason:=cleanTelemetryText(strings.ToLower(body.ExitReason),40)
    switch reason {
    case "completed","back","error","content_change","app_background","replaced","":
    default:
        reason="other"
    }

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE playback_sessions SET
          current_media_version_id=COALESCE(
            (SELECT id FROM media_versions WHERE id=$3),
            current_media_version_id
          ),
          position_ms=$4,
          duration_ms=$5,
          watched_ms=watched_ms+$6,
          buffer_count=buffer_count+$7,
          buffer_ms=buffer_ms+$8,
          quality_switch_count=quality_switch_count+$9,
          network_type=$10,
          completed=$11,
          exit_reason=$12,
          last_heartbeat_at=now(),
          ended_at=now()
        WHERE id=$1 AND user_id=$2 AND ended_at IS NULL
    `,
        id,userID,strings.TrimSpace(body.CurrentMediaVersionID),
        clampInt64(body.PositionMS,0,86_400_000),
        clampInt64(body.DurationMS,0,86_400_000),
        clampInt64(body.WatchedDeltaMS,0,30_000),
        clampInt(body.BufferCountDelta,0,20),
        clampInt64(body.BufferMSDelta,0,120_000),
        clampInt(body.QualitySwitchDelta,0,10),
        cleanTelemetryText(body.NetworkType,32),
        body.Completed,reason,
    )
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    if tag.RowsAffected()==0 {
        writeJSON(w,http.StatusOK,map[string]any{"ended":false})
        return
    }
    writeJSON(w,http.StatusOK,map[string]any{"ended":true})
}
