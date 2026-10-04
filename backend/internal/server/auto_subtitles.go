package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"unicode"

	"github.com/go-chi/chi/v5"
)

const openSubtitlesBaseURL = "https://api.opensubtitles.com/api/v1"

type autoSubtitleMedia struct {
	FileName      string
	FileSizeBytes int64
	TMDBID        *int64
	Kind          string
	Title         string
	Year          int
	SeasonNumber  *int
	EpisodeNumber *int
}

type automaticSubtitleResult struct {
	URL          string
	MimeType     string
	Language     string
	Release      string
	Provider     string
	ExactRelease bool
	Score        int
}

type openSubtitleFile struct {
	FileID   int64  `json:"file_id"`
	FileName string `json:"file_name"`
}

type openSubtitleAttributes struct {
	Language       string             `json:"language"`
	Release        string             `json:"release"`
	DownloadCount  int64              `json:"download_count"`
	MovieHashMatch bool               `json:"moviehash_match"`
	FromTrusted    bool               `json:"from_trusted"`
	Files          []openSubtitleFile `json:"files"`
}

type openSubtitleSearchResponse struct {
	Data []struct {
		Attributes openSubtitleAttributes `json:"attributes"`
	} `json:"data"`
}

type subtitleCandidate struct {
	FileID       int64
	FileName     string
	Release      string
	Score        int
	ExactRelease bool
}

var (
	errAutoSubtitleMediaNotFound = errors.New("auto subtitle media not found")
	errSubtitleProviderDisabled  = errors.New("subtitle provider disabled")
	errSubtitleNotFound          = errors.New("subtitle not found")
)

func (s *Server) autoSubtitle(w http.ResponseWriter, r *http.Request) {
	versionID := strings.TrimSpace(chi.URLParam(r, "versionID"))
	if versionID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "media version is required"})
		return
	}

	language := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("lang")))
	if language == "" {
		language = "fa"
	}
	if len(language) > 8 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid subtitle language"})
		return
	}

	media, err := s.autoSubtitleMedia(r, versionID)
	if err != nil {
		if errors.Is(err, errAutoSubtitleMediaNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "media version not found"})
			return
		}
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	var fallback *automaticSubtitleResult

	// Persian-first provider. It is release/episode aware and does not require
	// a secret in the Android APK. The upstream service owns its own provider key.
	if isPersianLanguage(language) {
		if result, providerErr := s.persianSubtitleAddon(r.Context(), media, language); providerErr == nil {
			if result.ExactRelease {
				writeAutomaticSubtitle(w, result)
				return
			}
			fallback = &result
		}
	}

	// Official OpenSubtitles provider is preferred whenever it is configured
	// and can produce an exact release match.
	if strings.TrimSpace(s.cfg.OpenSubtitlesAPIKey) != "" {
		if result, providerErr := s.openSubtitlesAutomatic(r, media, language); providerErr == nil {
			if result.ExactRelease || fallback == nil || result.Score > fallback.Score {
				writeAutomaticSubtitle(w, result)
				return
			}
		}
	}

	if fallback != nil {
		writeAutomaticSubtitle(w, *fallback)
		return
	}

	writeJSON(w, http.StatusNotFound, map[string]string{
		"error": "برای این نسخه زیرنویس فارسی هماهنگ پیدا نشد.",
	})
}

func writeAutomaticSubtitle(w http.ResponseWriter, result automaticSubtitleResult) {
	w.Header().Set("Cache-Control", "private, max-age=60")
	writeJSON(w, http.StatusOK, map[string]any{
		"url":          result.URL,
		"mimeType":     result.MimeType,
		"language":     result.Language,
		"release":      result.Release,
		"provider":     result.Provider,
		"exactRelease": result.ExactRelease,
		"score":        result.Score,
	})
}

func (s *Server) autoSubtitleMedia(r *http.Request, versionID string) (autoSubtitleMedia, error) {
	var m autoSubtitleMedia
	var tmdbID *int64
	var seasonNumber, episodeNumber *int
	err := s.db.QueryRow(r.Context(), `
		SELECT
			mv.file_name,
			mv.file_size_bytes,
			COALESCE(mt_direct.tmdb_id,mt_episode.tmdb_id),
			COALESCE(mt_direct.kind,mt_episode.kind,'movie'),
			COALESCE(mt_direct.title,mt_episode.title,''),
			COALESCE(mt_direct.year,mt_episode.year,0),
			s.season_number,
			e.episode_number
		FROM media_versions mv
		LEFT JOIN media_titles mt_direct
			ON mt_direct.id=mv.media_title_id
		LEFT JOIN episodes e
			ON e.id=mv.episode_id
		LEFT JOIN seasons s
			ON s.id=e.season_id
		LEFT JOIN media_titles mt_episode
			ON mt_episode.id=s.media_title_id
		WHERE mv.id=$1
	`, versionID).Scan(
		&m.FileName,
		&m.FileSizeBytes,
		&tmdbID,
		&m.Kind,
		&m.Title,
		&m.Year,
		&seasonNumber,
		&episodeNumber,
	)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "no rows") {
			return m, errAutoSubtitleMediaNotFound
		}
		return m, err
	}
	m.TMDBID = tmdbID
	m.SeasonNumber = seasonNumber
	m.EpisodeNumber = episodeNumber
	return m, nil
}

func isPersianLanguage(language string) bool {
	switch strings.ToLower(strings.TrimSpace(language)) {
	case "fa", "fas", "per", "persian", "farsi":
		return true
	default:
		return false
	}
}

func (s *Server) persianSubtitleAddon(
	ctx context.Context,
	media autoSubtitleMedia,
	language string,
) (automaticSubtitleResult, error) {
	var empty automaticSubtitleResult
	base := strings.TrimRight(strings.TrimSpace(s.cfg.PersianSubtitleAddonURL), "/")
	if base == "" {
		return empty, errSubtitleProviderDisabled
	}

	imdbID, err := s.subtitleIMDbID(ctx, media)
	if err != nil || imdbID == "" {
		return empty, errSubtitleNotFound
	}

	var endpoint string
	if media.EpisodeNumber != nil {
		if media.SeasonNumber == nil {
			return empty, errSubtitleNotFound
		}
		endpoint = fmt.Sprintf(
			"%s/series/%s:%d:%d.json",
			base,
			url.PathEscape(imdbID),
			*media.SeasonNumber,
			*media.EpisodeNumber,
		)
	} else {
		endpoint = fmt.Sprintf("%s/movie/%s.json", base, url.PathEscape(imdbID))
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return empty, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "Filmiqoo/1.0 subtitle-client")

	resp, err := s.upstreamClient.Do(req)
	if err != nil {
		return empty, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return empty, fmt.Errorf("persian subtitle addon status %d", resp.StatusCode)
	}

	var payload struct {
		Subtitles []struct {
			ID    string `json:"id"`
			URL   string `json:"url"`
			Lang  string `json:"lang"`
			Title string `json:"title"`
		} `json:"subtitles"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 2*1024*1024)).Decode(&payload); err != nil {
		return empty, err
	}

	best := automaticSubtitleResult{}
	found := false
	for _, item := range payload.Subtitles {
		if strings.TrimSpace(item.URL) == "" {
			continue
		}
		if item.Lang != "" && !isPersianLanguage(item.Lang) {
			continue
		}

		downloadURL, ok := secureSubtitleURL(item.URL)
		if !ok {
			continue
		}
		release := strings.TrimSpace(item.Title)
		score, exact := subtitleReleaseScore(media.FileName, release)
		if score == 0 {
			score = 20
		}
		if media.EpisodeNumber != nil {
			episodeTags := []string{
				fmt.Sprintf("s%02de%02d", valueOrZero(media.SeasonNumber), *media.EpisodeNumber),
				fmt.Sprintf("s%de%d", valueOrZero(media.SeasonNumber), *media.EpisodeNumber),
				fmt.Sprintf("%dx%02d", valueOrZero(media.SeasonNumber), *media.EpisodeNumber),
			}
			normalized := subtitleReleaseCompact(release)
			for _, tag := range episodeTags {
				if strings.Contains(normalized, subtitleReleaseCompact(tag)) {
					score += 180
					break
				}
			}
		}

		if !found || score > best.Score {
			best = automaticSubtitleResult{
				URL:          downloadURL,
				MimeType:     "application/x-subrip",
				Language:     language,
				Release:      firstNonBlank(release, media.FileName, "Persian subtitle"),
				Provider:     "Persian Subtitles • SubSource",
				ExactRelease: exact,
				Score:        score,
			}
			found = true
		}
	}

	if !found {
		return empty, errSubtitleNotFound
	}
	return best, nil
}

func valueOrZero(value *int) int {
	if value == nil {
		return 0
	}
	return *value
}

func secureSubtitleURL(raw string) (string, bool) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed.Host == "" {
		return "", false
	}
	if parsed.Scheme == "http" && strings.EqualFold(parsed.Hostname(), "stremio.alirostami.com") {
		parsed.Scheme = "https"
	}
	if parsed.Scheme != "https" {
		return "", false
	}
	return parsed.String(), true
}

func (s *Server) subtitleIMDbID(ctx context.Context, media autoSubtitleMedia) (string, error) {
	if media.TMDBID == nil || *media.TMDBID <= 0 || s.tmdb == nil || !s.tmdb.Enabled() {
		return "", errSubtitleNotFound
	}

	path := fmt.Sprintf("movie/%d/external_ids", *media.TMDBID)
	if strings.ToLower(media.Kind) != "movie" {
		path = fmt.Sprintf("tv/%d/external_ids", *media.TMDBID)
	}
	raw, err := s.tmdb.RawJSON(ctx, path, url.Values{})
	if err != nil {
		return "", err
	}
	var payload struct {
		IMDbID string `json:"imdb_id"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return "", err
	}
	imdbID := strings.TrimSpace(payload.IMDbID)
	if !strings.HasPrefix(imdbID, "tt") {
		return "", errSubtitleNotFound
	}
	return imdbID, nil
}

func (s *Server) openSubtitlesAutomatic(
	r *http.Request,
	media autoSubtitleMedia,
	language string,
) (automaticSubtitleResult, error) {
	var empty automaticSubtitleResult
	results, err := s.searchOpenSubtitles(r, media, language, true)
	if err != nil {
		return empty, err
	}
	if len(results.Data) == 0 {
		results, err = s.searchOpenSubtitles(r, media, language, false)
		if err != nil {
			return empty, err
		}
	}

	candidate, ok := bestSubtitleCandidate(media, results)
	if !ok {
		return empty, errSubtitleNotFound
	}

	token, err := s.openSubtitlesToken(r)
	if err != nil {
		return empty, err
	}
	link, fileName, err := s.downloadOpenSubtitle(r, candidate.FileID, token)
	if err != nil {
		return empty, err
	}

	return automaticSubtitleResult{
		URL:          link,
		MimeType:     "application/x-subrip",
		Language:     language,
		Release:      firstNonBlank(candidate.Release, candidate.FileName, fileName, "Persian subtitle"),
		Provider:     "OpenSubtitles",
		ExactRelease: candidate.ExactRelease,
		Score:        candidate.Score,
	}, nil
}

func (s *Server) searchOpenSubtitles(
	r *http.Request,
	media autoSubtitleMedia,
	language string,
	releaseFirst bool,
) (openSubtitleSearchResponse, error) {
	var result openSubtitleSearchResponse
	values := url.Values{}
	values.Set("languages", language)

	if media.EpisodeNumber != nil {
		values.Set("type", "episode")
		if media.SeasonNumber != nil {
			values.Set("season_number", strconv.Itoa(*media.SeasonNumber))
		}
		values.Set("episode_number", strconv.Itoa(*media.EpisodeNumber))
	} else {
		values.Set("type", "movie")
	}
	if media.TMDBID != nil && *media.TMDBID > 0 {
		values.Set("tmdb_id", strconv.FormatInt(*media.TMDBID, 10))
	}

	if releaseFirst {
		if query := strings.TrimSpace(media.FileName); query != "" {
			values.Set("query", query)
		}
	} else {
		query := strings.TrimSpace(media.Title)
		if media.Year > 0 && query != "" {
			query = strconv.Itoa(media.Year) + " - " + query
		}
		if query != "" {
			values.Set("query", query)
		}
	}

	req, err := http.NewRequestWithContext(
		r.Context(),
		http.MethodGet,
		openSubtitlesBaseURL+"/subtitles?"+values.Encode(),
		nil,
	)
	if err != nil {
		return result, err
	}
	s.applyOpenSubtitlesHeaders(req, "")

	resp, err := s.upstreamClient.Do(req)
	if err != nil {
		return result, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return result, fmt.Errorf("opensubtitles search status %d", resp.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 2*1024*1024)).Decode(&result); err != nil {
		return result, err
	}
	return result, nil
}

func bestSubtitleCandidate(
	media autoSubtitleMedia,
	response openSubtitleSearchResponse,
) (subtitleCandidate, bool) {
	var best subtitleCandidate
	found := false
	for _, item := range response.Data {
		attrs := item.Attributes
		for _, file := range attrs.Files {
			if file.FileID <= 0 {
				continue
			}
			release := firstNonBlank(attrs.Release, file.FileName)
			score, exact := subtitleReleaseScore(media.FileName, release, file.FileName)
			if attrs.MovieHashMatch {
				score += 1000
				exact = true
			}
			if attrs.FromTrusted {
				score += 35
			}
			if attrs.DownloadCount > 0 {
				switch {
				case attrs.DownloadCount >= 10000:
					score += 25
				case attrs.DownloadCount >= 1000:
					score += 18
				case attrs.DownloadCount >= 100:
					score += 10
				}
			}
			if !found || score > best.Score {
				best = subtitleCandidate{
					FileID:       file.FileID,
					FileName:     file.FileName,
					Release:      attrs.Release,
					Score:        score,
					ExactRelease: exact,
				}
				found = true
			}
		}
	}
	return best, found
}

func subtitleReleaseScore(source string, candidates ...string) (int, bool) {
	sourceCompact := subtitleReleaseCompact(source)
	sourceTokens := subtitleReleaseTokens(source)
	best := 0
	exact := false
	for _, candidate := range candidates {
		candidateCompact := subtitleReleaseCompact(candidate)
		if sourceCompact != "" && candidateCompact != "" {
			if sourceCompact == candidateCompact ||
				strings.Contains(candidateCompact, sourceCompact) ||
				strings.Contains(sourceCompact, candidateCompact) {
				if len(candidateCompact) > 10 {
					exact = true
					if best < 500 {
						best = 500
					}
				}
			}
		}
		tokens := subtitleReleaseTokens(candidate)
		if len(sourceTokens) == 0 || len(tokens) == 0 {
			continue
		}
		overlap := 0
		for token := range sourceTokens {
			if _, ok := tokens[token]; ok {
				overlap++
			}
		}
		denom := len(sourceTokens)
		if len(tokens) < denom {
			denom = len(tokens)
		}
		if denom > 0 {
			score := overlap * 300 / denom
			if score > best {
				best = score
			}
			if overlap >= 4 && score >= 250 {
				exact = true
			}
		}
	}
	return best, exact
}

func subtitleReleaseCompact(value string) string {
	value = strings.TrimSpace(strings.ToLower(filepath.Base(value)))
	value = strings.TrimSuffix(value, filepath.Ext(value))
	var b strings.Builder
	for _, r := range value {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func subtitleReleaseTokens(value string) map[string]struct{} {
	base := strings.TrimSuffix(
		strings.ToLower(filepath.Base(strings.TrimSpace(value))),
		filepath.Ext(value),
	)
	parts := strings.FieldsFunc(base, func(r rune) bool {
		return !(unicode.IsLetter(r) || unicode.IsDigit(r))
	})
	ignored := map[string]struct{}{
		"1080p": {}, "720p": {}, "480p": {}, "2160p": {}, "4k": {},
		"web": {}, "webrip": {}, "webdl": {}, "bluray": {}, "brrip": {},
		"x264": {}, "x265": {}, "h264": {}, "h265": {}, "hevc": {},
		"aac": {}, "ddp": {}, "atmos": {}, "hdr": {}, "dv": {},
		"mkv": {}, "mp4": {},
	}
	out := map[string]struct{}{}
	for _, part := range parts {
		if len(part) < 2 {
			continue
		}
		if _, skip := ignored[part]; skip {
			continue
		}
		out[part] = struct{}{}
	}
	return out
}

func (s *Server) openSubtitlesToken(r *http.Request) (string, error) {
	if token := strings.TrimSpace(s.cfg.OpenSubtitlesToken); token != "" {
		return token, nil
	}
	username := strings.TrimSpace(s.cfg.OpenSubtitlesUsername)
	password := strings.TrimSpace(s.cfg.OpenSubtitlesPassword)
	if username == "" || password == "" {
		return "", errors.New("opensubtitles credentials are not configured")
	}

	body, _ := json.Marshal(map[string]string{
		"username": username,
		"password": password,
	})
	req, err := http.NewRequestWithContext(
		r.Context(),
		http.MethodPost,
		openSubtitlesBaseURL+"/login",
		bytes.NewReader(body),
	)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	s.applyOpenSubtitlesHeaders(req, "")

	resp, err := s.upstreamClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return "", fmt.Errorf("opensubtitles login status %d", resp.StatusCode)
	}
	var payload struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 256*1024)).Decode(&payload); err != nil {
		return "", err
	}
	if strings.TrimSpace(payload.Token) == "" {
		return "", errors.New("opensubtitles login did not return token")
	}
	return payload.Token, nil
}

func (s *Server) downloadOpenSubtitle(
	r *http.Request,
	fileID int64,
	token string,
) (string, string, error) {
	body, _ := json.Marshal(map[string]any{
		"file_id":    fileID,
		"sub_format": "srt",
	})
	req, err := http.NewRequestWithContext(
		r.Context(),
		http.MethodPost,
		openSubtitlesBaseURL+"/download",
		bytes.NewReader(body),
	)
	if err != nil {
		return "", "", err
	}
	req.Header.Set("Content-Type", "application/json")
	s.applyOpenSubtitlesHeaders(req, token)

	resp, err := s.upstreamClient.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return "", "", fmt.Errorf("opensubtitles download status %d", resp.StatusCode)
	}

	var payload struct {
		Link     string `json:"link"`
		FileName string `json:"file_name"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 256*1024)).Decode(&payload); err != nil {
		return "", "", err
	}
	link, ok := secureSubtitleURL(payload.Link)
	if !ok {
		return "", "", errors.New("invalid subtitle download link")
	}
	return link, payload.FileName, nil
}

func (s *Server) applyOpenSubtitlesHeaders(req *http.Request, token string) {
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Api-Key", s.cfg.OpenSubtitlesAPIKey)
	req.Header.Set("User-Agent", s.cfg.OpenSubtitlesUserAgent)
	if strings.TrimSpace(token) != "" {
		req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(token))
	}
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if value = strings.TrimSpace(value); value != "" {
			return value
		}
	}
	return ""
}
