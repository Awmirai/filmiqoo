package server

import (
    "encoding/json"
    "net/http"
    "time"

    "github.com/go-chi/chi/v5"
)

func (s *Server) securitySessions(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    var body struct {
        RefreshToken string `json:"refreshToken"`
    }
    _=json.NewDecoder(r.Body).Decode(&body)
    currentHash:=hashRefreshToken(body.RefreshToken)

    rows,err:=s.db.Query(r.Context(),`
        SELECT id::text,refresh_token_hash,device_name,user_agent,
               COALESCE(ip_address::text,''),created_at,last_used_at,expires_at
          FROM auth_sessions
         WHERE user_id=$1
           AND revoked_at IS NULL
           AND expires_at>now()
         ORDER BY last_used_at DESC,created_at DESC
         LIMIT 100
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,hash,device,userAgent,ip string
        var created,lastUsed,expires time.Time
        if err:=rows.Scan(
            &id,&hash,&device,&userAgent,&ip,&created,&lastUsed,&expires,
        ); err!=nil { continue }
        items=append(items,map[string]any{
            "id":id,
            "deviceName":device,
            "userAgent":userAgent,
            "ipAddress":ip,
            "createdAt":created,
            "lastUsedAt":lastUsed,
            "expiresAt":expires,
            "current":currentHash!="" && hash==currentHash,
        })
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) revokeSession(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    sessionID:=chi.URLParam(r,"id")

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE auth_sessions
           SET revoked_at=COALESCE(revoked_at,now())
         WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL
    `,sessionID,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"revoked":tag.RowsAffected()>0})
}

func (s *Server) revokeOtherSessions(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    var body struct {
        RefreshToken string `json:"refreshToken"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    hash:=hashRefreshToken(body.RefreshToken)
    if hash=="" {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"refreshToken is required"}); return
    }

    var currentID string
    if err:=s.db.QueryRow(r.Context(),`
        SELECT id::text
          FROM auth_sessions
         WHERE user_id=$1 AND refresh_token_hash=$2
           AND revoked_at IS NULL AND expires_at>now()
         LIMIT 1
    `,userID,hash).Scan(&currentID); err!=nil {
        writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"current session not found"}); return
    }

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE auth_sessions
           SET revoked_at=now()
         WHERE user_id=$1
           AND id<>$2
           AND revoked_at IS NULL
    `,userID,currentID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"revoked":tag.RowsAffected()})
}
