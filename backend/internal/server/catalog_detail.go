package server

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (s *Server) catalogDetail(w http.ResponseWriter, r *http.Request) {
	id:=chi.URLParam(r,"id")

	var tmdbID *int64
	var kind,title,originalTitle,overview,posterURL,backdropURL,language string
	var year,runtime int
	var rating *float64

	err:=s.db.QueryRow(r.Context(),`
		SELECT tmdb_id,kind,title,original_title,overview,year,runtime_minutes,
		       poster_url,backdrop_url,rating,original_language
		  FROM media_titles WHERE id=$1
	`,id).Scan(&tmdbID,&kind,&title,&originalTitle,&overview,&year,&runtime,
		&posterURL,&backdropURL,&rating,&language)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"media title not found"})
		return
	}

	versions:=make([]map[string]any,0)
	rows,err:=s.db.Query(r.Context(),`
		SELECT id::text,quality_label,video_codec,hdr_type,file_size_bytes,duration_ms,
		       audio_tracks,subtitle_tracks,stream_ready,preferred
		  FROM media_versions
		 WHERE media_title_id=$1
		 ORDER BY preferred DESC,height DESC,file_size_bytes DESC
	`,id)
	if err==nil {
		defer rows.Close()
		for rows.Next() {
			var versionID,quality,codec,hdr string
			var fileSize,duration int64
			var audio,subs []byte
			var ready,preferred bool
			if err:=rows.Scan(&versionID,&quality,&codec,&hdr,&fileSize,&duration,&audio,&subs,&ready,&preferred); err!=nil {
				continue
			}
			versions=append(versions,map[string]any{
				"id":versionID,"quality":quality,"codec":codec,"hdr":hdr,
				"fileSizeBytes":fileSize,"durationMs":duration,
				"audioTracks":decodeJSONOrEmptyArray(audio),
				"subtitleTracks":decodeJSONOrEmptyArray(subs),
				"streamReady":ready,"preferred":preferred,
			})
		}
	}

	seasons:=make([]map[string]any,0)
	seasonRows,err:=s.db.Query(r.Context(),`
		SELECT id::text,season_number,name,overview,poster_url,air_date
		  FROM seasons WHERE media_title_id=$1 ORDER BY season_number
	`,id)
	if err==nil {
		defer seasonRows.Close()
		for seasonRows.Next() {
			var seasonID,name,seasonOverview,seasonPoster string
			var seasonNumber int
			var airDate any
			if err:=seasonRows.Scan(&seasonID,&seasonNumber,&name,&seasonOverview,&seasonPoster,&airDate); err!=nil {
				continue
			}

			episodes:=make([]map[string]any,0)
			epRows,epErr:=s.db.Query(r.Context(),`
				SELECT e.id::text,e.episode_number,e.name,e.overview,e.still_url,e.runtime_minutes,e.air_date,
				       e.intro_start_ms,e.intro_end_ms,e.recap_start_ms,e.recap_end_ms,e.credits_start_ms,
				       mv.id::text,mv.quality_label,mv.stream_ready,mv.preferred
				  FROM episodes e
				  LEFT JOIN LATERAL (
					SELECT id,quality_label,stream_ready,preferred
					  FROM media_versions
					 WHERE episode_id=e.id
					 ORDER BY preferred DESC,height DESC,file_size_bytes DESC
					 LIMIT 1
				  ) mv ON true
				 WHERE e.season_id=$1
				 ORDER BY e.episode_number
			`,seasonID)
			if epErr==nil {
				for epRows.Next() {
					var epID,epName,epOverview,stillURL string
					var epNumber,epRuntime int
					var epAirDate any
					var introStart,introEnd,recapStart,recapEnd,creditsStart *int64
					var versionID,quality *string
					var ready,preferred *bool
					if err:=epRows.Scan(
						&epID,&epNumber,&epName,&epOverview,&stillURL,&epRuntime,&epAirDate,
						&introStart,&introEnd,&recapStart,&recapEnd,&creditsStart,
						&versionID,&quality,&ready,&preferred,
					); err!=nil {
						continue
					}
					episodes=append(episodes,map[string]any{
						"id":epID,"number":epNumber,"name":epName,"overview":epOverview,
						"stillUrl":stillURL,"runtimeMinutes":epRuntime,"airDate":epAirDate,
						"introStartMs":introStart,"introEndMs":introEnd,
						"recapStartMs":recapStart,"recapEndMs":recapEnd,
						"creditsStartMs":creditsStart,
						"mediaVersionId":versionID,"quality":quality,"streamReady":ready,"preferred":preferred,
					})
				}
				epRows.Close()
			}

			seasons=append(seasons,map[string]any{
				"id":seasonID,"number":seasonNumber,"name":name,"overview":seasonOverview,
				"posterUrl":seasonPoster,"airDate":airDate,"episodes":episodes,
			})
		}
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"id":id,"tmdbId":tmdbID,"kind":kind,"title":title,"originalTitle":originalTitle,
		"overview":overview,"year":year,"runtimeMinutes":runtime,"posterUrl":posterURL,
		"backdropUrl":backdropURL,"rating":rating,"originalLanguage":language,
		"versions":versions,"seasons":seasons,
	})
}

func decodeJSONOrEmptyArray(raw []byte) any {
	if len(raw)==0 { return []any{} }
	var value any
	if err:=json.Unmarshal(raw,&value); err!=nil { return []any{} }
	return value
}
