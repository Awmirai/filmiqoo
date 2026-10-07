package server

import (
	"context"
	"github.com/go-chi/chi/v5"
	"net/http"
)

func (s *Server) catalogVersions(ctx context.Context, titleID string) ([]map[string]any, map[string][]map[string]any, error) {
	rows, err := s.db.Query(ctx, `SELECT v.id::text,v.episode_id::text,v.quality_label,v.video_codec,v.hdr_type,v.file_size_bytes,v.duration_ms,
 v.audio_tracks,v.subtitle_tracks,v.stream_ready,v.preferred,v.is_dubbed,v.is_persian_dubbed,v.has_persian_subtitle,v.detection_source,v.detection_confidence,v.detection_evidence
 FROM (SELECT direct.* FROM media_versions direct WHERE direct.media_title_id=$1
 UNION ALL SELECT episode_version.* FROM seasons owner JOIN episodes owned ON owned.season_id=owner.id
 JOIN media_versions episode_version ON episode_version.episode_id=owned.id WHERE owner.media_title_id=$1) v LEFT JOIN episodes e ON e.id=v.episode_id LEFT JOIN seasons se ON se.id=e.season_id
 ORDER BY v.stream_ready DESC,v.preferred DESC,v.height DESC,v.file_size_bytes DESC,v.id`, titleID)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	movies := []map[string]any{}
	episodes := map[string][]map[string]any{}
	for rows.Next() {
		var id, quality, codec, hdr, source, confidence string
		var episodeID *string
		var size, duration int64
		var audio, subs, evidence []byte
		var ready, preferred, dubbed, persian, subtitle bool
		if err = rows.Scan(&id, &episodeID, &quality, &codec, &hdr, &size, &duration, &audio, &subs, &ready, &preferred, &dubbed, &persian, &subtitle, &source, &confidence, &evidence); err != nil {
			return nil, nil, err
		}
		item := map[string]any{"id": id, "quality": quality, "codec": codec, "hdr": hdr, "fileSizeBytes": size, "durationMs": duration, "audioTracks": decodeJSONOrEmptyArray(audio), "subtitleTracks": decodeJSONOrEmptyArray(subs), "streamReady": ready, "preferred": preferred, "isDubbed": dubbed, "isPersianDubbed": persian, "hasPersianSubtitle": subtitle, "detectionSource": source, "detectionConfidence": confidence, "detectionEvidence": decodeJSONOrEmptyArray(evidence)}
		if episodeID == nil {
			movies = append(movies, item)
		} else {
			episodes[*episodeID] = append(episodes[*episodeID], item)
		}
	}
	return movies, episodes, rows.Err()
}
func (s *Server) catalogDetail(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	maturity := "all"
	if userID := userIDFromContext(r.Context()); userID != "" {
		maturity = s.viewerMaturityLevel(r, userID)
	}
	where := "mt.id=$1 AND mt.visibility='public'"
	if maturity == "kids" {
		where += " AND mt.audience_level='kids'"
	} else if maturity == "teen" {
		where += " AND mt.audience_level IN ('kids','teen')"
	}
	cards, err := s.catalogCards(r.Context(), where, []any{id}, "mt.id", 1, 0)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog details are unavailable"})
		return
	}
	if len(cards) == 0 {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "media title not found"})
		return
	}
	versions, episodeVersions, err := s.catalogVersions(r.Context(), id)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog versions are unavailable"})
		return
	}
	seasonRows, err := s.db.Query(r.Context(), `SELECT id::text,season_number,name,overview,poster_url,air_date FROM seasons WHERE media_title_id=$1 ORDER BY season_number`, id)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog seasons are unavailable"})
		return
	}
	seasons := []map[string]any{}
	seasonIndex := map[string]int{}
	for seasonRows.Next() {
		var seasonID, name, overview, poster string
		var number int
		var airDate any
		if err = seasonRows.Scan(&seasonID, &number, &name, &overview, &poster, &airDate); err != nil {
			break
		}
		seasonIndex[seasonID] = len(seasons)
		seasons = append(seasons, map[string]any{"id": seasonID, "number": number, "name": name, "overview": overview, "posterUrl": poster, "airDate": airDate, "episodes": []map[string]any{}})
	}
	if err == nil {
		err = seasonRows.Err()
	}
	seasonRows.Close()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog seasons are unavailable"})
		return
	}
	epRows, err := s.db.Query(r.Context(), `SELECT e.id::text,e.season_id::text,e.episode_number,e.name,e.overview,e.still_url,e.runtime_minutes,e.air_date,
 e.intro_start_ms,e.intro_end_ms,e.recap_start_ms,e.recap_end_ms,e.credits_start_ms FROM episodes e JOIN seasons se ON se.id=e.season_id WHERE se.media_title_id=$1 ORDER BY se.season_number,e.episode_number`, id)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog episodes are unavailable"})
		return
	}
	for epRows.Next() {
		var epID, seasonID, name, overview, still string
		var number, runtime int
		var airDate any
		var introStart, introEnd, recapStart, recapEnd, creditsStart *int64
		if err = epRows.Scan(&epID, &seasonID, &number, &name, &overview, &still, &runtime, &airDate, &introStart, &introEnd, &recapStart, &recapEnd, &creditsStart); err != nil {
			break
		}
		choices := episodeVersions[epID]
		if choices == nil {
			choices = []map[string]any{}
		}
		item := map[string]any{"id": epID, "number": number, "name": name, "overview": overview, "stillUrl": still, "runtimeMinutes": runtime, "airDate": airDate, "introStartMs": introStart, "introEndMs": introEnd, "recapStartMs": recapStart, "recapEndMs": recapEnd, "creditsStartMs": creditsStart, "versions": choices, "mediaVersionId": nil, "quality": nil, "streamReady": false, "preferred": false, "isDubbed": false, "isPersianDubbed": false, "hasPersianSubtitle": false, "hasPersianDub": false}
		if len(choices) > 0 {
			selected := choices[0]
			item["mediaVersionId"] = selected["id"]
			for _, key := range []string{"quality", "streamReady", "preferred", "isDubbed", "isPersianDubbed", "hasPersianSubtitle"} {
				item[key] = selected[key]
			}
		}
		for _, choice := range choices {
			if choice["streamReady"] == true && choice["isPersianDubbed"] == true {
				item["hasPersianDub"] = true
			}
		}
		if index, ok := seasonIndex[seasonID]; ok {
			seasons[index]["episodes"] = append(seasons[index]["episodes"].([]map[string]any), item)
		}
	}
	if err == nil {
		err = epRows.Err()
	}
	epRows.Close()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "catalog episodes are unavailable"})
		return
	}
	detail := cards[0]
	detail["versions"] = versions
	detail["seasons"] = seasons
	writeJSON(w, http.StatusOK, detail)
}
