package server

import (
	"bytes"
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
	Title         string
	Year          int
	SeasonNumber  *int
	EpisodeNumber *int
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

func (s *Server) autoSubtitle(w http.ResponseWriter, r *http.Request) {
	if strings.TrimSpace(s.cfg.OpenSubtitlesAPIKey) == "" {
		writeJSON(w,http.StatusServiceUnavailable,map[string]string{
			"error":"جستجوی خودکار زیرنویس هنوز روی سرور تنظیم نشده است.",
		})
		return
	}

	versionID:=strings.TrimSpace(chi.URLParam(r,"versionID"))
	if versionID=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"media version is required"})
		return
	}

	language:=strings.ToLower(strings.TrimSpace(r.URL.Query().Get("lang")))
	if language=="" { language="fa" }
	if len(language)>8 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid subtitle language"})
		return
	}

	media,err:=s.autoSubtitleMedia(r,versionID)
	if err!=nil {
		if errors.Is(err,errAutoSubtitleMediaNotFound) {
			writeJSON(w,http.StatusNotFound,map[string]string{"error":"media version not found"})
			return
		}
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	results,err:=s.searchOpenSubtitles(r,media,language,true)
	if err!=nil {
		writeJSON(w,http.StatusBadGateway,map[string]string{
			"error":"سرویس زیرنویس فعلاً پاسخ نمی‌دهد. دوباره امتحان کن.",
		})
		return
	}
	if len(results.Data)==0 {
		results,err=s.searchOpenSubtitles(r,media,language,false)
		if err!=nil {
			writeJSON(w,http.StatusBadGateway,map[string]string{
				"error":"سرویس زیرنویس فعلاً پاسخ نمی‌دهد. دوباره امتحان کن.",
			})
			return
		}
	}

	candidate,ok:=bestSubtitleCandidate(media,results)
	if !ok {
		writeJSON(w,http.StatusNotFound,map[string]string{
			"error":"برای این ریلیز زیرنویس فارسی مناسبی پیدا نشد.",
		})
		return
	}

	token,err:=s.openSubtitlesToken(r)
	if err!=nil {
		writeJSON(w,http.StatusServiceUnavailable,map[string]string{
			"error":"حساب سرویس زیرنویس روی سرور کامل تنظیم نشده است.",
		})
		return
	}

	link,fileName,err:=s.downloadOpenSubtitle(r,candidate.FileID,token)
	if err!=nil {
		writeJSON(w,http.StatusBadGateway,map[string]string{
			"error":"دانلود زیرنویس از سرویس خارجی ناموفق بود.",
		})
		return
	}
	release:=firstNonBlank(candidate.Release,candidate.FileName,fileName,"Persian subtitle")

	writeJSON(w,http.StatusOK,map[string]any{
		"url":link,
		"mimeType":"application/x-subrip",
		"language":language,
		"release":release,
		"provider":"opensubtitles",
		"exactRelease":candidate.ExactRelease,
		"score":candidate.Score,
	})
}

var errAutoSubtitleMediaNotFound=errors.New("auto subtitle media not found")

func (s *Server) autoSubtitleMedia(r *http.Request,versionID string)(autoSubtitleMedia,error) {
	var m autoSubtitleMedia
	var tmdbID *int64
	var seasonNumber,episodeNumber *int
	err:=s.db.QueryRow(r.Context(),`
		SELECT
			mv.file_name,
			mv.file_size_bytes,
			COALESCE(mt_direct.tmdb_id,mt_episode.tmdb_id),
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
	`,versionID).Scan(
		&m.FileName,
		&m.FileSizeBytes,
		&tmdbID,
		&m.Title,
		&m.Year,
		&seasonNumber,
		&episodeNumber,
	)
	if err!=nil {
		if strings.Contains(strings.ToLower(err.Error()),"no rows") {
			return m,errAutoSubtitleMediaNotFound
		}
		return m,err
	}
	m.TMDBID=tmdbID
	m.SeasonNumber=seasonNumber
	m.EpisodeNumber=episodeNumber
	return m,nil
}

func (s *Server) searchOpenSubtitles(
	r *http.Request,
	media autoSubtitleMedia,
	language string,
	releaseFirst bool,
)(openSubtitleSearchResponse,error) {
	var result openSubtitleSearchResponse
	values:=url.Values{}
	values.Set("languages",language)

	if media.EpisodeNumber!=nil {
		values.Set("type","episode")
		if media.SeasonNumber!=nil {
			values.Set("season_number",strconv.Itoa(*media.SeasonNumber))
		}
		values.Set("episode_number",strconv.Itoa(*media.EpisodeNumber))
	} else {
		values.Set("type","movie")
	}
	if media.TMDBID!=nil && *media.TMDBID>0 {
		values.Set("tmdb_id",strconv.FormatInt(*media.TMDBID,10))
	}

	if releaseFirst {
		query:=strings.TrimSpace(media.FileName)
		if query!="" {
			values.Set("query",query)
		}
	} else {
		query:=strings.TrimSpace(media.Title)
		if media.Year>0 && query!="" {
			query=strconv.Itoa(media.Year)+" - "+query
		}
		if query!="" {
			values.Set("query",query)
		}
	}

	req,err:=http.NewRequestWithContext(
		r.Context(),
		http.MethodGet,
		openSubtitlesBaseURL+"/subtitles?"+values.Encode(),
		nil,
	)
	if err!=nil { return result,err }
	s.applyOpenSubtitlesHeaders(req,"")

	resp,err:=s.upstreamClient.Do(req)
	if err!=nil { return result,err }
	defer resp.Body.Close()
	if resp.StatusCode<200 || resp.StatusCode>=300 {
		io.Copy(io.Discard,io.LimitReader(resp.Body,4096))
		return result,fmt.Errorf("opensubtitles search status %d",resp.StatusCode)
	}
	if err:=json.NewDecoder(io.LimitReader(resp.Body,2*1024*1024)).Decode(&result); err!=nil {
		return result,err
	}
	return result,nil
}

func bestSubtitleCandidate(
	media autoSubtitleMedia,
	response openSubtitleSearchResponse,
)(subtitleCandidate,bool) {
	var best subtitleCandidate
	found:=false
	for _,item:=range response.Data {
		attrs:=item.Attributes
		for _,file:=range attrs.Files {
			if file.FileID<=0 { continue }
			release:=firstNonBlank(attrs.Release,file.FileName)
			score,exact:=subtitleReleaseScore(media.FileName,release,file.FileName)
			if attrs.MovieHashMatch { score+=1000; exact=true }
			if attrs.FromTrusted { score+=35 }
			if attrs.DownloadCount>0 {
				switch {
				case attrs.DownloadCount>=10000: score+=25
				case attrs.DownloadCount>=1000: score+=18
				case attrs.DownloadCount>=100: score+=10
				}
			}
			if !found || score>best.Score {
				best=subtitleCandidate{
					FileID:file.FileID,
					FileName:file.FileName,
					Release:attrs.Release,
					Score:score,
					ExactRelease:exact,
				}
				found=true
			}
		}
	}
	return best,found
}

func subtitleReleaseScore(source string,candidates ...string)(int,bool) {
	sourceCompact:=subtitleReleaseCompact(source)
	sourceTokens:=subtitleReleaseTokens(source)
	best:=0
	exact:=false
	for _,candidate:=range candidates {
		candidateCompact:=subtitleReleaseCompact(candidate)
		if sourceCompact!="" && candidateCompact!="" {
			if sourceCompact==candidateCompact ||
				strings.Contains(candidateCompact,sourceCompact) ||
				strings.Contains(sourceCompact,candidateCompact) {
				if len(candidateCompact)>10 {
					exact=true
					if best<500 { best=500 }
				}
			}
		}
		tokens:=subtitleReleaseTokens(candidate)
		if len(sourceTokens)==0 || len(tokens)==0 { continue }
		overlap:=0
		for token:=range sourceTokens {
			if _,ok:=tokens[token]; ok { overlap++ }
		}
		denom:=len(sourceTokens)
		if len(tokens)<denom { denom=len(tokens) }
		if denom>0 {
			score:=overlap*300/denom
			if score>best { best=score }
			if overlap>=4 && score>=250 { exact=true }
		}
	}
	return best,exact
}

func subtitleReleaseCompact(value string) string {
	value=strings.TrimSpace(strings.ToLower(filepath.Base(value)))
	value=strings.TrimSuffix(value,filepath.Ext(value))
	var b strings.Builder
	for _,r:=range value {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func subtitleReleaseTokens(value string) map[string]struct{} {
	base:=strings.TrimSuffix(
		strings.ToLower(filepath.Base(strings.TrimSpace(value))),
		filepath.Ext(value),
	)
	parts:=strings.FieldsFunc(base,func(r rune)bool {
		return !(unicode.IsLetter(r) || unicode.IsDigit(r))
	})
	ignored:=map[string]struct{}{
		"1080p":{},"720p":{},"480p":{},"2160p":{},"4k":{},
		"web":{},"webrip":{},"webdl":{},"bluray":{},"brrip":{},
		"x264":{},"x265":{},"h264":{},"h265":{},"hevc":{},
		"aac":{},"ddp":{},"atmos":{},"hdr":{},"dv":{},
		"mkv":{},"mp4":{},
	}
	out:=map[string]struct{}{}
	for _,part:=range parts {
		if len(part)<2 { continue }
		if _,skip:=ignored[part]; skip { continue }
		out[part]=struct{}{}
	}
	return out
}

func (s *Server) openSubtitlesToken(r *http.Request)(string,error) {
	if token:=strings.TrimSpace(s.cfg.OpenSubtitlesToken); token!="" {
		return token,nil
	}
	username:=strings.TrimSpace(s.cfg.OpenSubtitlesUsername)
	password:=strings.TrimSpace(s.cfg.OpenSubtitlesPassword)
	if username=="" || password=="" {
		return "",errors.New("opensubtitles credentials are not configured")
	}

	body,_:=json.Marshal(map[string]string{
		"username":username,
		"password":password,
	})
	req,err:=http.NewRequestWithContext(
		r.Context(),
		http.MethodPost,
		openSubtitlesBaseURL+"/login",
		bytes.NewReader(body),
	)
	if err!=nil { return "",err }
	req.Header.Set("Content-Type","application/json")
	s.applyOpenSubtitlesHeaders(req,"")

	resp,err:=s.upstreamClient.Do(req)
	if err!=nil { return "",err }
	defer resp.Body.Close()
	if resp.StatusCode<200 || resp.StatusCode>=300 {
		io.Copy(io.Discard,io.LimitReader(resp.Body,4096))
		return "",fmt.Errorf("opensubtitles login status %d",resp.StatusCode)
	}
	var payload struct {
		Token string `json:"token"`
	}
	if err:=json.NewDecoder(io.LimitReader(resp.Body,256*1024)).Decode(&payload); err!=nil {
		return "",err
	}
	if strings.TrimSpace(payload.Token)=="" {
		return "",errors.New("opensubtitles login did not return token")
	}
	return payload.Token,nil
}

func (s *Server) downloadOpenSubtitle(
	r *http.Request,
	fileID int64,
	token string,
)(string,string,error) {
	body,_:=json.Marshal(map[string]any{
		"file_id":fileID,
		"sub_format":"srt",
	})
	req,err:=http.NewRequestWithContext(
		r.Context(),
		http.MethodPost,
		openSubtitlesBaseURL+"/download",
		bytes.NewReader(body),
	)
	if err!=nil { return "","",err }
	req.Header.Set("Content-Type","application/json")
	s.applyOpenSubtitlesHeaders(req,token)

	resp,err:=s.upstreamClient.Do(req)
	if err!=nil { return "","",err }
	defer resp.Body.Close()
	if resp.StatusCode<200 || resp.StatusCode>=300 {
		io.Copy(io.Discard,io.LimitReader(resp.Body,4096))
		return "","",fmt.Errorf("opensubtitles download status %d",resp.StatusCode)
	}

	var payload struct {
		Link string `json:"link"`
		FileName string `json:"file_name"`
	}
	if err:=json.NewDecoder(io.LimitReader(resp.Body,256*1024)).Decode(&payload); err!=nil {
		return "","",err
	}
	link:=strings.TrimSpace(payload.Link)
	parsed,err:=url.Parse(link)
	if err!=nil || parsed.Scheme!="https" || parsed.Host=="" {
		return "","",errors.New("invalid subtitle download link")
	}
	return link,payload.FileName,nil
}

func (s *Server) applyOpenSubtitlesHeaders(req *http.Request,token string) {
	req.Header.Set("Accept","application/json")
	req.Header.Set("Api-Key",s.cfg.OpenSubtitlesAPIKey)
	req.Header.Set("User-Agent",s.cfg.OpenSubtitlesUserAgent)
	if strings.TrimSpace(token)!="" {
		req.Header.Set("Authorization","Bearer "+strings.TrimSpace(token))
	}
}

func firstNonBlank(values ...string) string {
	for _,value:=range values {
		if value=strings.TrimSpace(value); value!="" {
			return value
		}
	}
	return ""
}
