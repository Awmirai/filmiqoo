package server

import (
    "encoding/json"
    "net/http"
    "strings"
    "time"

    authpkg "github.com/Awmirai/filmiqoo/backend/internal/auth"
)

type parentalSecretPayload struct {
    Password string `json:"password"`
    PIN string `json:"pin"`
}

func (s *Server) ensureUserPreferences(r *http.Request,userID string) error {
    _,err:=s.db.Exec(r.Context(),
        "INSERT INTO user_preferences (user_id) VALUES ($1) ON CONFLICT DO NOTHING",
        userID,
    )
    return err
}

func (s *Server) parentalStatus(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.ensureUserPreferences(r,userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    var protected bool
    var lockedUntil *time.Time
    err:=s.db.QueryRow(r.Context(),`
        SELECT parental_pin_hash<>'',parental_locked_until
          FROM user_preferences
         WHERE user_id=$1
    `,userID).Scan(&protected,&lockedUntil)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    var retryAfter int
    if lockedUntil!=nil && lockedUntil.After(time.Now()) {
        retryAfter=int(time.Until(*lockedUntil).Seconds())
        if retryAfter<1 { retryAfter=1 }
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "pinProtected":protected,
        "retryAfterSeconds":retryAfter,
        "fallback":"account_password",
    })
}

func (s *Server) setParentalPIN(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.ensureUserPreferences(r,userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    var body parentalSecretPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    var passwordHash string
    if err:=s.db.QueryRow(r.Context(),
        "SELECT password_hash FROM users WHERE id=$1 AND status='active'",
        userID,
    ).Scan(&passwordHash); err!=nil {
        writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"account unavailable"}); return
    }
    if !authpkg.VerifyPassword(body.Password,passwordHash) {
        writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"رمز حساب اشتباه است."}); return
    }

    pin:=strings.TrimSpace(body.PIN)
    if pin=="" {
        _,err:=s.db.Exec(r.Context(),`
            UPDATE user_preferences
               SET parental_pin_hash='',
                   parental_failed_attempts=0,
                   parental_locked_until=NULL,
                   updated_at=now()
             WHERE user_id=$1
        `,userID)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        writeJSON(w,http.StatusOK,map[string]any{"pinProtected":false})
        return
    }

    if !validViewerPIN(pin) {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"PIN والدین باید دقیقاً ۴ رقم باشد."}); return
    }

    hash,err:=authpkg.HashPassword(pin)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    _,err=s.db.Exec(r.Context(),`
        UPDATE user_preferences
           SET parental_pin_hash=$2,
               parental_failed_attempts=0,
               parental_locked_until=NULL,
               updated_at=now()
         WHERE user_id=$1
    `,userID,hash)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"pinProtected":true})
}

func (s *Server) verifyParentalGate(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.ensureUserPreferences(r,userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    var body parentalSecretPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    var pinHash,passwordHash string
    var failures int
    var lockedUntil *time.Time
    err:=s.db.QueryRow(r.Context(),`
        SELECT up.parental_pin_hash,up.parental_failed_attempts,up.parental_locked_until,u.password_hash
          FROM user_preferences up
          JOIN users u ON u.id=up.user_id
         WHERE up.user_id=$1 AND u.status='active'
    `,userID).Scan(&pinHash,&failures,&lockedUntil,&passwordHash)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    now:=time.Now()
    if lockedUntil!=nil && lockedUntil.After(now) {
        retry:=int(time.Until(*lockedUntil).Seconds())
        if retry<1 { retry=1 }
        writeJSON(w,http.StatusTooManyRequests,map[string]any{
            "error":"Parental Gate temporarily locked",
            "retryAfterSeconds":retry,
        })
        return
    }

    valid:=false
    if pinHash!="" {
        valid=authpkg.VerifyPassword(strings.TrimSpace(body.PIN),pinHash)
    } else {
        valid=authpkg.VerifyPassword(body.Password,passwordHash)
    }

    if !valid {
        failures++
        if failures>=5 {
            until:=now.Add(5*time.Minute)
            _,_=s.db.Exec(r.Context(),`
                UPDATE user_preferences
                   SET parental_failed_attempts=0,parental_locked_until=$2,updated_at=now()
                 WHERE user_id=$1
            `,userID,until)
            writeJSON(w,http.StatusTooManyRequests,map[string]any{
                "error":"Parental Gate temporarily locked",
                "retryAfterSeconds":300,
            })
            return
        }
        _,_=s.db.Exec(r.Context(),`
            UPDATE user_preferences
               SET parental_failed_attempts=$2,updated_at=now()
             WHERE user_id=$1
        `,userID,failures)
        writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"کد یا رمز واردشده اشتباه است."})
        return
    }

    _,_=s.db.Exec(r.Context(),`
        UPDATE user_preferences
           SET parental_failed_attempts=0,parental_locked_until=NULL,updated_at=now()
         WHERE user_id=$1
    `,userID)

    writeJSON(w,http.StatusOK,map[string]any{"verified":true,"pinProtected":pinHash!=""})
}
