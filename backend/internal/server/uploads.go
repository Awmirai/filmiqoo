package server

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

var safeObjectKey=regexp.MustCompile(`^ugc-[a-f0-9-]+-[a-f0-9]{24}\.[a-z0-9]{1,8}$`)

func (s *Server) presignUpload(w http.ResponseWriter,r *http.Request) {
	if s.objects==nil {
		writeJSON(w,http.StatusServiceUnavailable,map[string]string{"error":"object storage is not configured"})
		return
	}
	userID:=userIDFromContext(r.Context())
	var body struct {
		Kind string `json:"kind"`
		MimeType string `json:"mimeType"`
		FileName string `json:"fileName"`
		SizeBytes int64 `json:"sizeBytes"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	body.Kind=strings.ToLower(strings.TrimSpace(body.Kind))
	body.MimeType=strings.ToLower(strings.TrimSpace(body.MimeType))
	if body.Kind=="" { body.Kind="media" }

	max:=int64(500*1024*1024)
	if body.Kind=="story" { max=150*1024*1024 }
	if body.Kind=="image" { max=25*1024*1024 }
	if body.SizeBytes<=0 || body.SizeBytes>max {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"file size is outside allowed range"}); return
	}
	if !(strings.HasPrefix(body.MimeType,"video/") || strings.HasPrefix(body.MimeType,"image/") || strings.HasPrefix(body.MimeType,"audio/")) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"unsupported media type"}); return
	}

	ext:=strings.ToLower(strings.TrimPrefix(filepath.Ext(body.FileName),"."))
	if !regexp.MustCompile(`^[a-z0-9]{1,8}$`).MatchString(ext) {
		ext=extensionForMime(body.MimeType)
	}
	token,err:=randomHex(12)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	key:="ugc-"+userID+"-"+token+"."+ext

	url,err:=s.objects.PresignPut(r.Context(),key,15*time.Minute)
	if err!=nil { writeError(w,http.StatusServiceUnavailable,err); return }

	var uploadID string
	err=s.db.QueryRow(r.Context(),`
		INSERT INTO ugc_uploads (user_id,object_key,kind,mime_type,original_name,size_bytes,status)
		VALUES ($1,$2,$3,$4,$5,$6,'presigned')
		RETURNING id::text
	`,userID,key,body.Kind,body.MimeType,body.FileName,body.SizeBytes).Scan(&uploadID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusCreated,map[string]any{
		"uploadId":uploadID,
		"uploadUrl":url.String(),
		"objectKey":key,
		"mediaUrl":s.mediaURL(key),
		"expiresIn":900,
	})
}

func (s *Server) completeUpload(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	id:=chi.URLParam(r,"id")
	tag,err:=s.db.Exec(r.Context(),`
		UPDATE ugc_uploads SET status='uploaded',uploaded_at=now()
		 WHERE id=$1 AND user_id=$2 AND status='presigned'
	`,id,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if tag.RowsAffected()!=1 {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"upload not found or already completed"}); return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) mediaRedirect(w http.ResponseWriter,r *http.Request) {
	if s.objects==nil {
		writeJSON(w,http.StatusServiceUnavailable,map[string]string{"error":"object storage is not configured"})
		return
	}
	key:=chi.URLParam(r,"key")
	if !safeObjectKey.MatchString(key) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid media key"}); return
	}
	url,err:=s.objects.PresignGet(r.Context(),key,30*time.Minute)
	if err!=nil { writeError(w,http.StatusServiceUnavailable,err); return }
	http.Redirect(w,r,url.String(),http.StatusTemporaryRedirect)
}

func (s *Server) createReel(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body struct {
		UploadID string `json:"uploadId"`
		Caption string `json:"caption"`
		CoverURL string `json:"coverUrl"`
		DurationMS int `json:"durationMs"`
		ChannelID *string `json:"channelId"`
		MediaTitleID *string `json:"mediaTitleId"`
		EpisodeID *string `json:"episodeId"`
		Spoiler bool `json:"spoiler"`
		AllowComments bool `json:"allowComments"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil { writeError(w,http.StatusBadRequest,err); return }
	body.Caption=strings.TrimSpace(body.Caption)
	if len([]rune(body.Caption))>2200 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"caption is too long"}); return
	}

	var key,mime,status string
	err:=s.db.QueryRow(r.Context(),`
		SELECT object_key,mime_type,status
		  FROM ugc_uploads
		 WHERE id=$1 AND user_id=$2
	`,body.UploadID,userID).Scan(&key,&mime,&status)
	if err!=nil || status!="uploaded" || !strings.HasPrefix(mime,"video/") {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"completed video upload is required"}); return
	}

	playback:=s.mediaURL(key)
	var id string
	err=s.db.QueryRow(r.Context(),`
		INSERT INTO reels (
			creator_user_id,channel_id,media_title_id,episode_id,caption,source_url,playback_url,
			cover_url,duration_ms,spoiler,allow_comments,status,published_at
		) VALUES ($1,$2,$3,$4,$5,$6,$6,$7,$8,$9,$10,'published',now())
		RETURNING id::text
	`,userID,body.ChannelID,body.MediaTitleID,body.EpisodeID,body.Caption,playback,
		body.CoverURL,body.DurationMS,body.Spoiler,body.AllowComments).Scan(&id)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,_=s.db.Exec(r.Context(),"UPDATE profiles SET reel_count=reel_count+1,updated_at=now() WHERE user_id=$1",userID)
	if body.ChannelID!=nil {
		_,_=s.db.Exec(r.Context(),"UPDATE channels SET reel_count=reel_count+1,updated_at=now() WHERE id=$1",*body.ChannelID)
	}
	_,_=s.db.Exec(r.Context(),"UPDATE ugc_uploads SET status='attached' WHERE id=$1",body.UploadID)

	writeJSON(w,http.StatusCreated,map[string]any{
		"id":id,"playbackUrl":playback,"status":"published",
	})
}

func (s *Server) mediaURL(key string) string {
	base:=strings.TrimRight(s.cfg.PublicAPIBaseURL,"/")
	return base+"/v1/media/"+key
}

func randomHex(bytesN int) (string,error) {
	buf:=make([]byte,bytesN)
	if _,err:=rand.Read(buf); err!=nil { return "",err }
	return hex.EncodeToString(buf),nil
}

func extensionForMime(mime string) string {
	switch mime {
	case "video/mp4": return "mp4"
	case "video/webm": return "webm"
	case "image/jpeg": return "jpg"
	case "image/png": return "png"
	case "image/webp": return "webp"
	case "audio/mpeg": return "mp3"
	default: return "bin"
	}
}

var _ = fmt.Sprintf
