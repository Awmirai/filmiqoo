package server

import (
	"net/http"
	"time"
)

func (s *Server) releaseCenter(w http.ResponseWriter,r *http.Request) {
	if s.tmdb==nil || !s.tmdb.Enabled() {
		writeJSON(w,http.StatusServiceUnavailable,map[string]string{"error":"TMDB is not configured"})
		return
	}
	items,err:=s.tmdb.Releases(r.Context())
	if err!=nil { writeError(w,http.StatusBadGateway,err); return }

	out:=make([]map[string]any,0,len(items))
	now:=time.Now()
	for _,item:=range items {
		var backendID,versionID,quality *string
		var ready *bool
		_ = s.db.QueryRow(r.Context(),`
			SELECT mt.id::text,mv.id::text,mv.quality_label,mv.stream_ready
			  FROM media_titles mt
			  LEFT JOIN LATERAL (
			    SELECT id,quality_label,stream_ready
			      FROM media_versions
			     WHERE media_title_id=mt.id
			     ORDER BY preferred DESC,height DESC,file_size_bytes DESC
			     LIMIT 1
			  ) mv ON true
			 WHERE mt.tmdb_id=$1
			 ORDER BY mt.updated_at DESC
			 LIMIT 1
		`,item.TMDBID).Scan(&backendID,&versionID,&quality,&ready)

		var daysAway *int
		if parsed,err:=time.Parse("2006-01-02",item.Date); err==nil {
			d:=int(parsed.Sub(time.Date(now.Year(),now.Month(),now.Day(),0,0,0,0,now.Location())).Hours()/24)
			daysAway=&d
		}

		out=append(out,map[string]any{
			"tmdbId":item.TMDBID,"kind":item.Kind,"title":item.Title,
			"originalTitle":item.OriginalTitle,"overview":item.Overview,"date":item.Date,
			"posterUrl":item.PosterURL,"backdropUrl":item.BackdropURL,
			"rating":item.Rating,"originalLanguage":item.OriginalLanguage,
			"daysAway":daysAway,
			"backendId":backendID,"mediaVersionId":versionID,"quality":quality,"streamReady":ready,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":out,"generatedAt":time.Now()})
}
