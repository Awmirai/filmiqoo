package server

import (
	"net/http"
	"sort"
)

type dnaAffinity struct {
	Label string
	Weight float64
}

func (s *Server) filmDNA(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	if err:=s.ensureDefaultViewerProfile(r.Context(),userID); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	viewerID:=s.viewerProfileID(r,userID)
	if viewerID=="" {
		_ = s.db.QueryRow(r.Context(),`
			SELECT id::text
			  FROM viewer_profiles
			 WHERE user_id=$1
			 ORDER BY created_at ASC,id ASC
			 LIMIT 1
		`,userID).Scan(&viewerID)
	}
	if viewerID=="" {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"viewer profile not found"})
		return
	}

	var profileName string
	var kidsMode bool
	_ = s.db.QueryRow(r.Context(),`
		SELECT name,kids_mode
		  FROM viewer_profiles
		 WHERE id=$1 AND user_id=$2
	`,viewerID,userID).Scan(&profileName,&kidsMode)

	var watchedVersions,distinctTitles,completedVersions,watchMinutes,favorites,watchlist int64
	err:=s.db.QueryRow(r.Context(),`
		WITH watched AS (
			SELECT vwp.media_version_id,
			       vwp.position_ms,
			       vwp.duration_ms,
			       vwp.completed,
			       COALESCE(mv.media_title_id,sn.media_title_id) AS media_title_id
			  FROM viewer_watch_progress vwp
			  JOIN media_versions mv ON mv.id=vwp.media_version_id
			  LEFT JOIN episodes ep ON ep.id=mv.episode_id
			  LEFT JOIN seasons sn ON sn.id=ep.season_id
			 WHERE vwp.viewer_profile_id=$1
		)
		SELECT
		  COUNT(*),
		  COUNT(DISTINCT media_title_id) FILTER (WHERE media_title_id IS NOT NULL),
		  COUNT(*) FILTER (WHERE completed),
		  COALESCE(SUM(
		    CASE
		      WHEN duration_ms>0 THEN LEAST(position_ms,duration_ms)
		      ELSE position_ms
		    END
		  ),0)/60000,
		  (SELECT COUNT(*) FROM viewer_favorites WHERE viewer_profile_id=$1),
		  (SELECT COUNT(*) FROM viewer_watchlist WHERE viewer_profile_id=$1)
		FROM watched
	`,viewerID).Scan(
		&watchedVersions,&distinctTitles,&completedVersions,&watchMinutes,&favorites,&watchlist,
	)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	kinds,err:=s.dnaKinds(r,viewerID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	languages,err:=s.dnaLanguages(r,viewerID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	genres,err:=s.dnaGenres(r,viewerID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	decades,err:=s.dnaDecades(r,viewerID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	completionRate:=0.0
	if watchedVersions>0 {
		completionRate=float64(completedVersions)*100.0/float64(watchedVersions)
	}

	confidence:=int(distinctTitles*5 + favorites*2 + watchlist)
	if confidence>100 { confidence=100 }
	if confidence<0 { confidence=0 }

	badges:=make([]map[string]any,0)
	addBadge:=func(id,title,description string,level int) {
		badges=append(badges,map[string]any{
			"id":id,"title":title,"description":description,"level":level,
		})
	}

	if watchMinutes>=6000 {
		level:=1
		if watchMinutes>=18000 { level=2 }
		if watchMinutes>=60000 { level=3 }
		addBadge("watch_time","تماشاگر حرفه‌ای","بر اساس مجموع زمان تماشای واقعی",level)
	}
	if completedVersions>=25 {
		level:=1
		if completedVersions>=100 { level=2 }
		if completedVersions>=300 { level=3 }
		addBadge("completionist","تمام‌کننده","برای تمام‌کردن فیلم‌ها و قسمت‌ها",level)
	}
	if favorites+watchlist>=25 {
		level:=1
		if favorites+watchlist>=75 { level=2 }
		if favorites+watchlist>=200 { level=3 }
		addBadge("collector","کلکسیونر","برای Library و Watchlist پرمحتوا",level)
	}
	if len(languages)>=4 {
		level:=1
		if len(languages)>=6 { level=2 }
		if len(languages)>=9 { level=3 }
		addBadge("world_cinema","جهان‌گرد سینما","برای تماشای زبان‌ها و سینماهای متنوع",level)
	}
	if distinctTitles>=40 {
		level:=1
		if distinctTitles>=120 { level=2 }
		if distinctTitles>=300 { level=3 }
		addBadge("cinephile","فیلم‌باز","بر اساس تعداد عنوان‌های منحصربه‌فرد دیده‌شده",level)
	}

	archetype:="در حال شکل‌گیری"
	if len(kinds)>0 {
		switch kinds[0].Label {
		case "movie":
			archetype="فیلم‌باز"
		case "series":
			archetype="سریال‌باز"
		case "anime":
			archetype="انیمه‌باز"
		}
	}
	if completionRate>=80 && watchedVersions>=10 {
		archetype+=" • Completionist"
	}
	if len(languages)>=4 {
		archetype+=" • World Cinema"
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"viewerProfileId":viewerID,
		"profileName":profileName,
		"kidsMode":kidsMode,
		"archetype":archetype,
		"confidence":confidence,
		"stats":map[string]any{
			"watchMinutes":watchMinutes,
			"watchedVersions":watchedVersions,
			"distinctTitles":distinctTitles,
			"completedVersions":completedVersions,
			"completionRate":completionRate,
			"favorites":favorites,
			"watchlist":watchlist,
		},
		"kinds":dnaAffinityJSON(kinds),
		"languages":dnaAffinityJSON(languages),
		"genres":dnaAffinityJSON(genres),
		"decades":dnaAffinityJSON(decades),
		"badges":badges,
	})
}

func (s *Server) dnaKinds(r *http.Request,viewerID string) ([]dnaAffinity,error) {
	rows,err:=s.db.Query(r.Context(),dnaSignalCTE+`
		SELECT mt.kind,SUM(sig.weight)::double precision
		  FROM signals sig
		  JOIN media_titles mt ON mt.id=sig.media_title_id
		 GROUP BY mt.kind
		 ORDER BY SUM(sig.weight) DESC,mt.kind ASC
		 LIMIT 5
	`,viewerID)
	if err!=nil { return nil,err }
	defer rows.Close()

	items:=make([]dnaAffinity,0)
	for rows.Next() {
		var x dnaAffinity
		if rows.Scan(&x.Label,&x.Weight)==nil && x.Label!="" {
			items=append(items,x)
		}
	}
	return items,rows.Err()
}

func (s *Server) dnaLanguages(r *http.Request,viewerID string) ([]dnaAffinity,error) {
	rows,err:=s.db.Query(r.Context(),dnaSignalCTE+`
		SELECT mt.original_language,SUM(sig.weight)::double precision
		  FROM signals sig
		  JOIN media_titles mt ON mt.id=sig.media_title_id
		 WHERE mt.original_language<>''
		 GROUP BY mt.original_language
		 ORDER BY SUM(sig.weight) DESC,mt.original_language ASC
		 LIMIT 8
	`,viewerID)
	if err!=nil { return nil,err }
	defer rows.Close()

	items:=make([]dnaAffinity,0)
	for rows.Next() {
		var x dnaAffinity
		if rows.Scan(&x.Label,&x.Weight)==nil && x.Label!="" {
			items=append(items,x)
		}
	}
	return items,rows.Err()
}

func (s *Server) dnaGenres(r *http.Request,viewerID string) ([]dnaAffinity,error) {
	rows,err:=s.db.Query(r.Context(),dnaSignalCTE+`
		SELECT genre,SUM(weight)::double precision
		  FROM (
		    SELECT
		      CASE
		        WHEN jsonb_typeof(g.value)='object'
		          THEN COALESCE(NULLIF(g.value->>'name',''),NULLIF(g.value->>'label',''),'')
		        WHEN jsonb_typeof(g.value)='string'
		          THEN trim(both '"' from g.value::text)
		        ELSE ''
		      END AS genre,
		      sig.weight
		      FROM signals sig
		      JOIN media_titles mt ON mt.id=sig.media_title_id
		      CROSS JOIN LATERAL jsonb_array_elements(mt.genres) AS g(value)
		  ) expanded
		 WHERE genre<>''
		 GROUP BY genre
		 ORDER BY SUM(weight) DESC,genre ASC
		 LIMIT 8
	`,viewerID)
	if err!=nil { return nil,err }
	defer rows.Close()

	items:=make([]dnaAffinity,0)
	for rows.Next() {
		var x dnaAffinity
		if rows.Scan(&x.Label,&x.Weight)==nil && x.Label!="" {
			items=append(items,x)
		}
	}
	return items,rows.Err()
}

func (s *Server) dnaDecades(r *http.Request,viewerID string) ([]dnaAffinity,error) {
	rows,err:=s.db.Query(r.Context(),dnaSignalCTE+`
		SELECT ((mt.year/10)*10)::text,SUM(sig.weight)::double precision
		  FROM signals sig
		  JOIN media_titles mt ON mt.id=sig.media_title_id
		 WHERE mt.year>=1900 AND mt.year<=2100
		 GROUP BY ((mt.year/10)*10)
		 ORDER BY SUM(sig.weight) DESC,((mt.year/10)*10) DESC
		 LIMIT 6
	`,viewerID)
	if err!=nil { return nil,err }
	defer rows.Close()

	items:=make([]dnaAffinity,0)
	for rows.Next() {
		var x dnaAffinity
		if rows.Scan(&x.Label,&x.Weight)==nil && x.Label!="" {
			x.Label=x.Label+"s"
			items=append(items,x)
		}
	}
	return items,rows.Err()
}

const dnaSignalCTE = `
	WITH signals AS (
		SELECT
		  COALESCE(mv.media_title_id,sn.media_title_id) AS media_title_id,
		  GREATEST(
		    1,
		    CASE
		      WHEN vwp.duration_ms>0
		        THEN LEAST(vwp.position_ms,vwp.duration_ms)/60000
		      ELSE vwp.position_ms/60000
		    END
		  )::double precision AS weight
		  FROM viewer_watch_progress vwp
		  JOIN media_versions mv ON mv.id=vwp.media_version_id
		  LEFT JOIN episodes ep ON ep.id=mv.episode_id
		  LEFT JOIN seasons sn ON sn.id=ep.season_id
		 WHERE vwp.viewer_profile_id=$1
		   AND COALESCE(mv.media_title_id,sn.media_title_id) IS NOT NULL
		UNION ALL
		SELECT media_title_id,60::double precision
		  FROM viewer_favorites
		 WHERE viewer_profile_id=$1
		UNION ALL
		SELECT media_title_id,30::double precision
		  FROM viewer_watchlist
		 WHERE viewer_profile_id=$1
	)
`

func dnaAffinityJSON(items []dnaAffinity) []map[string]any {
	if len(items)==0 { return []map[string]any{} }
	sort.SliceStable(items,func(i,j int) bool { return items[i].Weight>items[j].Weight })
	max:=items[0].Weight
	if max<=0 { max=1 }
	out:=make([]map[string]any,0,len(items))
	for _,item:=range items {
		out=append(out,map[string]any{
			"label":item.Label,
			"weight":item.Weight,
			"strength":item.Weight/max,
		})
	}
	return out
}
