package server

import (
    "encoding/json"
    "net/http"
    "strings"
)

func (s *Server) updateProfile(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())

    var body struct {
        Username *string `json:"username"`
        DisplayName *string `json:"displayName"`
        Bio *string `json:"bio"`
        AvatarURL *string `json:"avatarUrl"`
        CoverURL *string `json:"coverUrl"`
        PrivateAccount *bool `json:"privateAccount"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    if body.Username!=nil {
        value:=strings.TrimSpace(*body.Username)
        if !usernamePattern.MatchString(value) {
            writeJSON(w,http.StatusBadRequest,map[string]string{
                "error":"username must be 3-24 characters using letters, numbers, _ or .",
            })
            return
        }
        body.Username=&value
    }

    if body.DisplayName!=nil {
        value:=strings.TrimSpace(*body.DisplayName)
        if len([]rune(value))<2 || len([]rune(value))>50 {
            writeJSON(w,http.StatusBadRequest,map[string]string{
                "error":"displayName must be 2-50 characters",
            })
            return
        }
        body.DisplayName=&value
    }

    if body.Bio!=nil {
        value:=strings.TrimSpace(*body.Bio)
        if len([]rune(value))>300 {
            writeJSON(w,http.StatusBadRequest,map[string]string{"error":"bio is too long"})
            return
        }
        body.Bio=&value
    }

    validateOwned:=func(v *string) bool {
        if v==nil { return true }
        value:=strings.TrimSpace(*v)
        if value=="" { return true }
        return strings.Contains(value,"/v1/media/ugc-"+userID+"-")
    }

    if !validateOwned(body.AvatarURL) || !validateOwned(body.CoverURL) {
        writeJSON(w,http.StatusBadRequest,map[string]string{
            "error":"profile images must come from your Filmiqoo uploads",
        })
        return
    }

    _,err:=s.db.Exec(r.Context(),`
        UPDATE profiles SET
          username=COALESCE($2,username),
          display_name=COALESCE($3,display_name),
          bio=COALESCE($4,bio),
          avatar_url=COALESCE($5,avatar_url),
          cover_url=COALESCE($6,cover_url),
          private_account=COALESCE($7,private_account),
          updated_at=now()
        WHERE user_id=$1
    `,
        userID,
        body.Username,
        body.DisplayName,
        body.Bio,
        body.AvatarURL,
        body.CoverURL,
        body.PrivateAccount,
    )
    if err!=nil {
        if body.Username!=nil {
            writeJSON(w,http.StatusConflict,map[string]string{"error":"username is already in use"})
            return
        }
        writeError(w,http.StatusInternalServerError,err)
        return
    }

    s.me(w,r)
}
