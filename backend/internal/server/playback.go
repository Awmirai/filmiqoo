package server

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
)

type playbackTokenRequest struct {
	MediaVersionID string `json:"mediaVersionId"`
	Download bool `json:"download"`
}

func (s *Server) playbackToken(w http.ResponseWriter, r *http.Request) {
	var body playbackTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	if strings.TrimSpace(body.MediaVersionID) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error":"mediaVersionId is required"})
		return
	}

	var ready bool
	var audienceLevel string
	if err := s.db.QueryRow(r.Context(),`
		SELECT mv.stream_ready,mt.audience_level
		  FROM media_versions mv
		  LEFT JOIN episodes e ON e.id=mv.episode_id
		  LEFT JOIN seasons sn ON sn.id=e.season_id
		  JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
		 WHERE mv.id=$1
	`,
		body.MediaVersionID,
	).Scan(&ready,&audienceLevel); err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error":"media version not found"})
		return
	}
	if !ready {
		writeJSON(w, http.StatusConflict, map[string]string{"error":"media version is not ready for streaming"})
		return
	}

	userID:=userIDFromContext(r.Context())
	if !viewerAllowsAudience(s.viewerMaturityLevel(r,userID),audienceLevel) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"این محتوا برای پروفایل فعال مجاز نیست."})
		return
	}

	ttl := time.Duration(s.cfg.PlaybackTokenTTLSeconds) * time.Second
	if ttl <= 0 { ttl = 5 * time.Minute }
	exp := time.Now().Add(ttl).Unix()
	sig := s.signPlayback(body.MediaVersionID, exp, body.Download)

	base := strings.TrimRight(s.cfg.PublicAPIBaseURL, "/")
	if base == "" {
		scheme := "https"
		if r.TLS == nil { scheme = "http" }
		base = scheme + "://" + r.Host
	}

	playURL := fmt.Sprintf("%s/v1/playback/%s?exp=%d&sig=%s",
		base, url.PathEscape(body.MediaVersionID), exp, sig)
	if body.Download { playURL += "&download=1" }

	writeJSON(w, http.StatusOK, map[string]any{
		"url": playURL,
		"expiresAt": exp,
		"download": body.Download,
	})
}

func (s *Server) playback(w http.ResponseWriter, r *http.Request) {
	versionID := chi.URLParam(r, "versionID")
	exp, err := strconv.ParseInt(r.URL.Query().Get("exp"), 10, 64)
	if err != nil || exp <= time.Now().Unix() {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"playback token expired"})
		return
	}
	download := r.URL.Query().Get("download") == "1"
	sig := r.URL.Query().Get("sig")
	if !hmac.Equal([]byte(sig), []byte(s.signPlayback(versionID, exp, download))) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid playback signature"})
		return
	}

	var messageID int64
	var streamHash, fileName string
	var ready bool
	err = s.db.QueryRow(r.Context(),
		"SELECT telegram_message_id,stream_hash,file_name,stream_ready FROM media_versions WHERE id=$1",
		versionID,
	).Scan(&messageID,&streamHash,&fileName,&ready)
	if err != nil || !ready || messageID == 0 || streamHash == "" {
		writeJSON(w, http.StatusNotFound, map[string]string{"error":"stream source unavailable"})
		return
	}

	if strings.TrimSpace(s.cfg.TelegramStreamBaseURL) == "" {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error":"telegram stream origin is not configured"})
		return
	}

	upstream := strings.TrimRight(s.cfg.TelegramStreamBaseURL, "/") +
		"/stream/" + strconv.FormatInt(messageID,10) +
		"?hash=" + url.QueryEscape(streamHash)
	if download { upstream += "&d=true" }

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, upstream, nil)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	if v := r.Header.Get("Range"); v != "" { req.Header.Set("Range", v) }
	if v := r.Header.Get("User-Agent"); v != "" { req.Header.Set("User-Agent", v) }

	resp, err := s.upstreamClient.Do(req)
	if err != nil {
		writeError(w, http.StatusBadGateway, err)
		return
	}
	defer resp.Body.Close()

	for _, key := range []string{
		"Content-Type","Content-Length","Content-Range","Accept-Ranges",
		"Content-Disposition","ETag","Last-Modified",
	} {
		if value := resp.Header.Get(key); value != "" { w.Header().Set(key,value) }
	}
	if download && w.Header().Get("Content-Disposition") == "" {
		w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", fileName))
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}



func (s *Server) playbackContext(w http.ResponseWriter, r *http.Request) {
	versionID:=chi.URLParam(r,"versionID")

	var (
		mediaID,title,poster,quality,codec,hdr,audienceLevel string
		episodeID,episodeName *string
		seasonNumber,episodeNumber *int
		introEnd,recapEnd,creditsStart *int64
	)

	err:=s.db.QueryRow(r.Context(),`
		SELECT mt.id::text,mt.title,mt.poster_url,
		       mv.quality_label,mv.video_codec,mv.hdr_type,mt.audience_level,
		       e.id::text,e.name,sn.season_number,e.episode_number,
		       e.intro_end_ms,e.recap_end_ms,e.credits_start_ms
		  FROM media_versions mv
		  LEFT JOIN episodes e ON e.id=mv.episode_id
		  LEFT JOIN seasons sn ON sn.id=e.season_id
		  JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
		 WHERE mv.id=$1
	`,versionID).Scan(
		&mediaID,&title,&poster,&quality,&codec,&hdr,&audienceLevel,
		&episodeID,&episodeName,&seasonNumber,&episodeNumber,
		&introEnd,&recapEnd,&creditsStart,
	)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"playback context not found"})
		return
	}

	userID:=userIDFromContext(r.Context())
	if !viewerAllowsAudience(s.viewerMaturityLevel(r,userID),audienceLevel) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"این محتوا برای پروفایل فعال مجاز نیست."})
		return
	}

	variants:=make([]map[string]any,0)
	var rows pgx.Rows
	if episodeID!=nil {
		rows,err=s.db.Query(r.Context(),`
			SELECT id::text,quality_label,video_codec,hdr_type
			  FROM media_versions
			 WHERE episode_id=$1 AND stream_ready=true
			 ORDER BY preferred DESC,height DESC,file_size_bytes DESC
		`,*episodeID)
	} else {
		rows,err=s.db.Query(r.Context(),`
			SELECT id::text,quality_label,video_codec,hdr_type
			  FROM media_versions
			 WHERE media_title_id=$1 AND stream_ready=true
			 ORDER BY preferred DESC,height DESC,file_size_bytes DESC
		`,mediaID)
	}
	if err==nil {
		for rows.Next() {
			var id,label,vCodec,vHdr string
			if rows.Scan(&id,&label,&vCodec,&vHdr)==nil {
				variants=append(variants,map[string]any{
					"mediaVersionId":id,
					"label":label,
					"codec":vCodec,
					"hdr":vHdr,
				})
			}
		}
		rows.Close()
	}

	displayTitle:=title
	subtitle:=quality
	if episodeID!=nil && seasonNumber!=nil && episodeNumber!=nil {
		if episodeName!=nil && strings.TrimSpace(*episodeName)!="" {
			displayTitle=*episodeName
		}
		subtitle=fmt.Sprintf("S%02dE%02d",*seasonNumber,*episodeNumber)
		if quality!="" { subtitle+=" • "+quality }
	}

	var nextVersionID,nextTitle,nextSubtitle *string
	if episodeID!=nil && seasonNumber!=nil && episodeNumber!=nil {
		var nextName,nextQuality string
		var nextSeason,nextEpisode int
		var nIntroEnd,nRecapEnd,nCreditsStart *int64
		err=s.db.QueryRow(r.Context(),`
			SELECT mv2.id::text,e2.name,mv2.quality_label,
			       sn2.season_number,e2.episode_number,
			       e2.intro_end_ms,e2.recap_end_ms,e2.credits_start_ms
			  FROM seasons sn2
			  JOIN episodes e2 ON e2.season_id=sn2.id
			  JOIN LATERAL (
			    SELECT id,quality_label
			      FROM media_versions
			     WHERE episode_id=e2.id AND stream_ready=true
			     ORDER BY preferred DESC,height DESC,file_size_bytes DESC
			     LIMIT 1
			  ) mv2 ON true
			 WHERE sn2.media_title_id=$1
			   AND (
			     sn2.season_number>$2 OR
			     (sn2.season_number=$2 AND e2.episode_number>$3)
			   )
			 ORDER BY sn2.season_number,e2.episode_number
			 LIMIT 1
		`,mediaID,*seasonNumber,*episodeNumber).Scan(
			&nextVersionID,&nextName,&nextQuality,
			&nextSeason,&nextEpisode,
			&nIntroEnd,&nRecapEnd,&nCreditsStart,
		)
		if err==nil && nextVersionID!=nil {
			value:=nextName
			if strings.TrimSpace(value)=="" {
				value=fmt.Sprintf("%s • قسمت %d",title,nextEpisode)
			}
			nextTitle=&value
			sub:=fmt.Sprintf("S%02dE%02d",nextSeason,nextEpisode)
			if nextQuality!="" { sub+=" • "+nextQuality }
			nextSubtitle=&sub
		}
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"mediaVersionId":versionID,
		"title":displayTitle,
		"subtitle":subtitle,
		"posterUrl":poster,
		"variants":variants,
		"introEndMs":introEnd,
		"recapEndMs":recapEnd,
		"creditsStartMs":creditsStart,
		"nextMediaVersionId":nextVersionID,
		"nextTitle":nextTitle,
		"nextSubtitle":nextSubtitle,
	})
}

func (s *Server) signPlayback(versionID string, exp int64, download bool) string {
	mode := "stream"
	if download { mode = "download" }
	payload := versionID + "|" + strconv.FormatInt(exp,10) + "|" + mode
	mac := hmac.New(sha256.New, []byte(s.cfg.PlaybackSigningSecret))
	_, _ = mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil))
}
