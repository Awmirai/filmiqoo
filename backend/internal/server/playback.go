package server

import (
	"context"
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
	w.Header().Set("Cache-Control","no-store")
	w.Header().Set("Pragma","no-cache")
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
	w.Header().Set("Cache-Control","private, no-store")
	w.Header().Set("Referrer-Policy","no-referrer")
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

	var resp *http.Response
	var lastErr error
	for attempt:=0; attempt<2; attempt++ {
		req,reqErr:=http.NewRequestWithContext(r.Context(),http.MethodGet,upstream,nil)
		if reqErr!=nil {
			writeError(w,http.StatusInternalServerError,reqErr)
			return
		}
		if v:=r.Header.Get("Range"); v!="" { req.Header.Set("Range",v) }
		if v:=r.Header.Get("User-Agent"); v!="" { req.Header.Set("User-Agent",v) }

		resp,lastErr=s.upstreamClient.Do(req)
		if lastErr==nil &&
			resp.StatusCode!=http.StatusBadGateway &&
			resp.StatusCode!=http.StatusServiceUnavailable &&
			resp.StatusCode!=http.StatusGatewayTimeout {
			break
		}
		if resp!=nil {
			resp.Body.Close()
			resp=nil
		}
		if attempt==0 {
			select {
			case <-r.Context().Done():
				writeError(w,http.StatusBadGateway,r.Context().Err())
				return
			case <-time.After(150*time.Millisecond):
			}
		}
	}
	if lastErr!=nil || resp==nil {
		s.recordPlaybackOriginResult(r.Context(),false)
		writeError(w,http.StatusBadGateway,lastErr)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode>=500 {
		s.recordPlaybackOriginResult(r.Context(),false)
	} else {
		s.recordPlaybackOriginResult(r.Context(),true)
	}

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

	var resumePosition,resumeDuration int64
	var resumeCompleted bool
	if episodeID!=nil {
		_ = s.db.QueryRow(r.Context(),`
			SELECT wp.position_ms,wp.duration_ms,wp.completed
			  FROM watch_progress wp
			  JOIN media_versions watched ON watched.id=wp.media_version_id
			 WHERE wp.user_id=$1 AND watched.episode_id=$2
			 ORDER BY wp.updated_at DESC
			 LIMIT 1
		`,userID,*episodeID).Scan(&resumePosition,&resumeDuration,&resumeCompleted)
	} else {
		_ = s.db.QueryRow(r.Context(),`
			SELECT wp.position_ms,wp.duration_ms,wp.completed
			  FROM watch_progress wp
			  JOIN media_versions watched ON watched.id=wp.media_version_id
			 WHERE wp.user_id=$1 AND watched.media_title_id=$2
			 ORDER BY wp.updated_at DESC
			 LIMIT 1
		`,userID,mediaID).Scan(&resumePosition,&resumeDuration,&resumeCompleted)
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
	var previousVersionID,previousTitle,previousSubtitle *string
	upNext:=make([]map[string]any,0)

	if episodeID!=nil && seasonNumber!=nil && episodeNumber!=nil {
		var nextName,nextQuality string
		var nextSeason,nextEpisode int
		err=s.db.QueryRow(r.Context(),`
			SELECT mv2.id::text,e2.name,mv2.quality_label,
			       sn2.season_number,e2.episode_number
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

		var prevName,prevQuality string
		var prevSeason,prevEpisode int
		err=s.db.QueryRow(r.Context(),`
			SELECT mv2.id::text,e2.name,mv2.quality_label,
			       sn2.season_number,e2.episode_number
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
			     sn2.season_number<$2 OR
			     (sn2.season_number=$2 AND e2.episode_number<$3)
			   )
			 ORDER BY sn2.season_number DESC,e2.episode_number DESC
			 LIMIT 1
		`,mediaID,*seasonNumber,*episodeNumber).Scan(
			&previousVersionID,&prevName,&prevQuality,
			&prevSeason,&prevEpisode,
		)
		if err==nil && previousVersionID!=nil {
			value:=prevName
			if strings.TrimSpace(value)=="" {
				value=fmt.Sprintf("%s • قسمت %d",title,prevEpisode)
			}
			previousTitle=&value
			sub:=fmt.Sprintf("S%02dE%02d",prevSeason,prevEpisode)
			if prevQuality!="" { sub+=" • "+prevQuality }
			previousSubtitle=&sub
		}

		queueRows,qErr:=s.db.Query(r.Context(),`
			SELECT mv2.id::text,e2.name,mv2.quality_label,
			       sn2.season_number,e2.episode_number,COALESCE(e2.still_url,'')
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
			 LIMIT 8
		`,mediaID,*seasonNumber,*episodeNumber)
		if qErr==nil {
			for queueRows.Next() {
				var id,name,label,still string
				var sNum,eNum int
				if queueRows.Scan(&id,&name,&label,&sNum,&eNum,&still)==nil {
					if strings.TrimSpace(name)=="" {
						name=fmt.Sprintf("%s • قسمت %d",title,eNum)
					}
					sub:=fmt.Sprintf("S%02dE%02d",sNum,eNum)
					if label!="" { sub+=" • "+label }
					upNext=append(upNext,map[string]any{
						"mediaVersionId":id,
						"title":name,
						"subtitle":sub,
						"posterUrl":still,
					})
				}
			}
			queueRows.Close()
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
		"resumePositionMs":resumePosition,
		"resumeDurationMs":resumeDuration,
		"resumeCompleted":resumeCompleted,
		"nextMediaVersionId":nextVersionID,
		"nextTitle":nextTitle,
		"nextSubtitle":nextSubtitle,
		"previousMediaVersionId":previousVersionID,
		"previousTitle":previousTitle,
		"previousSubtitle":previousSubtitle,
		"upNext":upNext,
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


func (s *Server) recordPlaybackOriginResult(ctx context.Context,success bool) {
	if s.redis==nil { return }
	status:="success"
	if !success { status="error" }
	key:="metrics:playback-origin:"+status+":"+time.Now().UTC().Format("2006010215")
	pipe:=s.redis.Pipeline()
	pipe.Incr(ctx,key)
	pipe.Expire(ctx,key,48*time.Hour)
	_,_=pipe.Exec(ctx)
}
