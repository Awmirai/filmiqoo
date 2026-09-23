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
	if err := s.db.QueryRow(r.Context(),
		"SELECT stream_ready FROM media_versions WHERE id=$1",
		body.MediaVersionID,
	).Scan(&ready); err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error":"media version not found"})
		return
	}
	if !ready {
		writeJSON(w, http.StatusConflict, map[string]string{"error":"media version is not ready for streaming"})
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

func (s *Server) signPlayback(versionID string, exp int64, download bool) string {
	mode := "stream"
	if download { mode = "download" }
	payload := versionID + "|" + strconv.FormatInt(exp,10) + "|" + mode
	mac := hmac.New(sha256.New, []byte(s.cfg.PlaybackSigningSecret))
	_, _ = mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil))
}
