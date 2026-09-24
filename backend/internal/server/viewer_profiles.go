package server

import (
    "context"
    "encoding/json"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
    "golang.org/x/crypto/bcrypt"
)

type viewerProfilePayload struct {
    Name *string `json:"name"`
    AvatarURL *string `json:"avatarUrl"`
    KidsMode *bool `json:"kidsMode"`
    MaturityLevel *string `json:"maturityLevel"`
    PreferredAudioLanguage *string `json:"preferredAudioLanguage"`
    PreferredSubtitleLanguage *string `json:"preferredSubtitleLanguage"`
    AutoplayNext *bool `json:"autoplayNext"`
}

func (s *Server) ensureDefaultViewerProfile(ctx context.Context,userID string) error {
    var exists bool
    if err:=s.db.QueryRow(ctx,
        "SELECT EXISTS(SELECT 1 FROM viewer_profiles WHERE user_id=$1)",
        userID).Scan(&exists); err!=nil {
        return err
    }
    if exists { return nil }

    _,err:=s.db.Exec(ctx,`
        INSERT INTO viewer_profiles (user_id,name,avatar_url)
        SELECT p.user_id,
               CASE
                 WHEN char_length(trim(p.display_name)) BETWEEN 1 AND 40 THEN trim(p.display_name)
                 ELSE 'Profile'
               END,
               p.avatar_url
          FROM profiles p
         WHERE p.user_id=$1
    `,userID)
    return err
}

func (s *Server) viewerProfileID(r *http.Request,userID string) string {
    raw:=strings.TrimSpace(r.Header.Get("X-Filmiqoo-Viewer-Profile"))
    if raw=="" { return "" }
    var id string
    if s.db.QueryRow(r.Context(),`
        SELECT id::text
          FROM viewer_profiles
         WHERE id=$1 AND user_id=$2
    `,raw,userID).Scan(&id)!=nil {
        return ""
    }
    return id
}

func (s *Server) viewerMaturityLevel(r *http.Request,userID string) string {
    viewerID:=s.viewerProfileID(r,userID)
    if viewerID=="" { return "all" }

    var level string
    if s.db.QueryRow(r.Context(),`
        SELECT maturity_level
          FROM viewer_profiles
         WHERE id=$1 AND user_id=$2
    `,viewerID,userID).Scan(&level)!=nil {
        return "all"
    }
    switch level {
    case "kids","teen","all":
        return level
    default:
        return "all"
    }
}

func viewerAllowsAudience(profileLevel,audienceLevel string) bool {
    switch profileLevel {
    case "kids":
        return audienceLevel=="kids"
    case "teen":
        return audienceLevel=="kids" || audienceLevel=="teen"
    default:
        return true
    }
}

func (s *Server) viewerProfiles(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.ensureDefaultViewerProfile(r.Context(),userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    rows,err:=s.db.Query(r.Context(),`
        SELECT id::text,name,avatar_url,kids_mode,maturity_level,
               preferred_audio_language,preferred_subtitle_language,
               autoplay_next,(pin_hash<>''),created_at
          FROM viewer_profiles
         WHERE user_id=$1
         ORDER BY created_at ASC,id ASC
         LIMIT 5
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,name,avatar,maturity,audioLang,subtitleLang string
        var kids,autoplay,pinProtected bool
        var created time.Time
        if err:=rows.Scan(
            &id,&name,&avatar,&kids,&maturity,
            &audioLang,&subtitleLang,&autoplay,&pinProtected,&created,
        ); err!=nil { continue }
        items=append(items,map[string]any{
            "id":id,
            "name":name,
            "avatarUrl":avatar,
            "kidsMode":kids,
            "maturityLevel":maturity,
            "preferredAudioLanguage":audioLang,
            "preferredSubtitleLanguage":subtitleLang,
            "autoplayNext":autoplay,
            "pinProtected":pinProtected,
            "createdAt":created,
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func normalizeViewerProfilePayload(body *viewerProfilePayload) error {
    if body.Name!=nil {
        value:=strings.TrimSpace(*body.Name)
        if len([]rune(value))<1 || len([]rune(value))>40 {
            return errText("profile name must be 1-40 characters")
        }
        body.Name=&value
    }
    if body.MaturityLevel!=nil {
        value:=strings.ToLower(strings.TrimSpace(*body.MaturityLevel))
        switch value {
        case "kids","teen","all":
        default:
            return errText("invalid maturityLevel")
        }
        body.MaturityLevel=&value
    }
    cleanLang:=func(v *string) *string {
        if v==nil { return nil }
        value:=strings.ToLower(strings.TrimSpace(*v))
        if value=="" { value="und" }
        if len(value)>12 { value=value[:12] }
        return &value
    }
    body.PreferredAudioLanguage=cleanLang(body.PreferredAudioLanguage)
    body.PreferredSubtitleLanguage=cleanLang(body.PreferredSubtitleLanguage)
    if body.AvatarURL!=nil {
        value:=strings.TrimSpace(*body.AvatarURL)
        if len(value)>2048 { return errText("avatarUrl is too long") }
        body.AvatarURL=&value
    }
    return nil
}

type errText string
func (e errText) Error() string { return string(e) }

func (s *Server) createViewerProfile(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.ensureDefaultViewerProfile(r.Context(),userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    var count int
    if err:=s.db.QueryRow(r.Context(),
        "SELECT COUNT(*) FROM viewer_profiles WHERE user_id=$1",
        userID).Scan(&count); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    if count>=5 {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"maximum 5 viewer profiles"})
        return
    }

    var body viewerProfilePayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    if body.Name==nil {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"name is required"})
        return
    }
    if err:=normalizeViewerProfilePayload(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    kids:=false
    if body.KidsMode!=nil { kids=*body.KidsMode }
    maturity:="all"
    if kids { maturity="kids" }
    if body.MaturityLevel!=nil { maturity=*body.MaturityLevel }
    audio:="fa"
    if body.PreferredAudioLanguage!=nil { audio=*body.PreferredAudioLanguage }
    subtitle:="fa"
    if body.PreferredSubtitleLanguage!=nil { subtitle=*body.PreferredSubtitleLanguage }
    autoplay:=true
    if body.AutoplayNext!=nil { autoplay=*body.AutoplayNext }
    avatar:=""
    if body.AvatarURL!=nil { avatar=*body.AvatarURL }

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO viewer_profiles (
            user_id,name,avatar_url,kids_mode,maturity_level,
            preferred_audio_language,preferred_subtitle_language,autoplay_next
        ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
        RETURNING id::text
    `,userID,*body.Name,avatar,kids,maturity,audio,subtitle,autoplay).Scan(&id)
    if err!=nil {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"profile name is already in use"})
        return
    }

    writeJSON(w,http.StatusCreated,map[string]any{"id":id})
}

func (s *Server) updateViewerProfile(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    profileID:=chi.URLParam(r,"id")

    var body viewerProfilePayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }
    if err:=normalizeViewerProfilePayload(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    if body.KidsMode!=nil && *body.KidsMode && body.MaturityLevel==nil {
        value:="kids"
        body.MaturityLevel=&value
    }

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE viewer_profiles SET
          name=COALESCE($3,name),
          avatar_url=COALESCE($4,avatar_url),
          kids_mode=COALESCE($5,kids_mode),
          maturity_level=COALESCE($6,maturity_level),
          preferred_audio_language=COALESCE($7,preferred_audio_language),
          preferred_subtitle_language=COALESCE($8,preferred_subtitle_language),
          autoplay_next=COALESCE($9,autoplay_next),
          updated_at=now()
         WHERE id=$1 AND user_id=$2
    `,
        profileID,userID,
        body.Name,body.AvatarURL,body.KidsMode,body.MaturityLevel,
        body.PreferredAudioLanguage,body.PreferredSubtitleLanguage,body.AutoplayNext,
    )
    if err!=nil {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"profile update failed"})
        return
    }
    if tag.RowsAffected()==0 {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"viewer profile not found"})
        return
    }

    writeJSON(w,http.StatusOK,map[string]any{"updated":true})
}

func (s *Server) deleteViewerProfile(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    profileID:=chi.URLParam(r,"id")

    var count int
    if err:=s.db.QueryRow(r.Context(),
        "SELECT COUNT(*) FROM viewer_profiles WHERE user_id=$1",
        userID).Scan(&count); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    if count<=1 {
        writeJSON(w,http.StatusConflict,map[string]string{"error":"at least one viewer profile is required"})
        return
    }

    tag,err:=s.db.Exec(r.Context(),`
        DELETE FROM viewer_profiles WHERE id=$1 AND user_id=$2
    `,profileID,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    writeJSON(w,http.StatusOK,map[string]any{"deleted":tag.RowsAffected()>0})
}


type viewerPinPayload struct {
    CurrentPIN string `json:"currentPin"`
    NewPIN string `json:"newPin"`
    PIN string `json:"pin"`
}

func validViewerPIN(value string) bool {
    if len(value)!=4 { return false }
    for _,r:=range value {
        if r<'0' || r>'9' { return false }
    }
    return true
}

func (s *Server) verifyViewerPIN(ctx context.Context,userID,profileID,pin string) (bool,time.Duration,error) {
    var hash string
    var failures int
    var lockedUntil *time.Time
    err:=s.db.QueryRow(ctx,`
        SELECT pin_hash,failed_pin_attempts,pin_locked_until
          FROM viewer_profiles
         WHERE id=$1 AND user_id=$2
    `,profileID,userID).Scan(&hash,&failures,&lockedUntil)
    if err!=nil { return false,0,err }

    if hash=="" {
        return true,0,nil
    }

    now:=time.Now()
    if lockedUntil!=nil && lockedUntil.After(now) {
        return false,time.Until(*lockedUntil),nil
    }

    if !validViewerPIN(pin) || bcrypt.CompareHashAndPassword([]byte(hash),[]byte(pin))!=nil {
        failures++
        if failures>=5 {
            until:=now.Add(5*time.Minute)
            _,err=s.db.Exec(ctx,`
                UPDATE viewer_profiles
                   SET failed_pin_attempts=0,pin_locked_until=$3,updated_at=now()
                 WHERE id=$1 AND user_id=$2
            `,profileID,userID,until)
            if err!=nil { return false,0,err }
            return false,5*time.Minute,nil
        }

        _,err=s.db.Exec(ctx,`
            UPDATE viewer_profiles
               SET failed_pin_attempts=$3,updated_at=now()
             WHERE id=$1 AND user_id=$2
        `,profileID,userID,failures)
        if err!=nil { return false,0,err }
        return false,0,nil
    }

    _,err=s.db.Exec(ctx,`
        UPDATE viewer_profiles
           SET failed_pin_attempts=0,pin_locked_until=NULL,updated_at=now()
         WHERE id=$1 AND user_id=$2
    `,profileID,userID)
    if err!=nil { return false,0,err }
    return true,0,nil
}

func (s *Server) unlockViewerProfile(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    profileID:=chi.URLParam(r,"id")

    var body viewerPinPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    ok,wait,err:=s.verifyViewerPIN(r.Context(),userID,profileID,strings.TrimSpace(body.PIN))
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"viewer profile not found"})
        return
    }
    if !ok {
        if wait>0 {
            seconds:=int(wait.Seconds())
            if seconds<1 { seconds=1 }
            writeJSON(w,http.StatusTooManyRequests,map[string]any{
                "error":"PIN temporarily locked",
                "retryAfterSeconds":seconds,
            })
            return
        }
        writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"PIN اشتباه است."})
        return
    }

    writeJSON(w,http.StatusOK,map[string]any{"unlocked":true})
}

func (s *Server) setViewerProfilePIN(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    profileID:=chi.URLParam(r,"id")

    var body viewerPinPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    var existingHash string
    if err:=s.db.QueryRow(r.Context(),`
        SELECT pin_hash FROM viewer_profiles WHERE id=$1 AND user_id=$2
    `,profileID,userID).Scan(&existingHash); err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"viewer profile not found"})
        return
    }

    if existingHash!="" {
        ok,wait,err:=s.verifyViewerPIN(
            r.Context(),userID,profileID,strings.TrimSpace(body.CurrentPIN),
        )
        if err!=nil {
            writeError(w,http.StatusInternalServerError,err); return
        }
        if !ok {
            if wait>0 {
                writeJSON(w,http.StatusTooManyRequests,map[string]any{
                    "error":"PIN temporarily locked",
                    "retryAfterSeconds":int(wait.Seconds()),
                })
                return
            }
            writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"PIN فعلی اشتباه است."})
            return
        }
    }

    newPIN:=strings.TrimSpace(body.NewPIN)
    if newPIN=="" {
        _,err:=s.db.Exec(r.Context(),`
            UPDATE viewer_profiles
               SET pin_hash='',failed_pin_attempts=0,pin_locked_until=NULL,updated_at=now()
             WHERE id=$1 AND user_id=$2
        `,profileID,userID)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        writeJSON(w,http.StatusOK,map[string]any{"pinProtected":false})
        return
    }

    if !validViewerPIN(newPIN) {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"PIN باید دقیقاً ۴ رقم باشد."})
        return
    }

    hash,err:=bcrypt.GenerateFromPassword([]byte(newPIN),bcrypt.DefaultCost)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    _,err=s.db.Exec(r.Context(),`
        UPDATE viewer_profiles
           SET pin_hash=$3,failed_pin_attempts=0,pin_locked_until=NULL,updated_at=now()
         WHERE id=$1 AND user_id=$2
    `,profileID,userID,string(hash))
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"pinProtected":true})
}
