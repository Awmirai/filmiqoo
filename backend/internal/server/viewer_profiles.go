package server

import (
    "context"
    "encoding/json"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
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

func (s *Server) viewerProfiles(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    if err:=s.ensureDefaultViewerProfile(r.Context(),userID); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    rows,err:=s.db.Query(r.Context(),`
        SELECT id::text,name,avatar_url,kids_mode,maturity_level,
               preferred_audio_language,preferred_subtitle_language,
               autoplay_next,created_at
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
        var kids,autoplay bool
        var created time.Time
        if err:=rows.Scan(
            &id,&name,&avatar,&kids,&maturity,
            &audioLang,&subtitleLang,&autoplay,&created,
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
