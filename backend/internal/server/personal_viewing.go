package server

import (
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
)

type cinemaTasteShare struct {
	Key      string  `json:"key"`
	Label    string  `json:"label"`
	Fraction float64 `json:"fraction"`
}
type userViewingStats struct {
	SchemaVersion            int                `json:"schemaVersion"`
	TotalWatchMS             int64              `json:"totalWatchMs"`
	MoviesWatched            int                `json:"moviesWatched"`
	SeriesWatched            int                `json:"seriesWatched"`
	SeriesStarted            int                `json:"seriesStarted"`
	EpisodesWatched          int                `json:"episodesWatched"`
	CompletedTitles          int                `json:"completedTitles"`
	CurrentlyWatching        int                `json:"currentlyWatching"`
	HistoryTitles            int                `json:"historyTitles"`
	WatchlistCount           int                `json:"watchlistCount"`
	FavoriteCount            int                `json:"favoriteCount"`
	TasteSampleSize          int                `json:"tasteSampleSize"`
	Genres                   []cinemaTasteShare `json:"genres"`
	Countries                []cinemaTasteShare `json:"countries"`
	LegacyHistoryWithoutTime bool               `json:"legacyHistoryWithoutTime"`
	CompletedMovieTmdbIDs    []int              `json:"completedMovieTmdbIds"`
}
type viewingFact struct {
	TitleID, Kind, EpisodeID, Status string
	Season, ExpectedEpisodes         int
	TmdbID                           int
	Completed, EverCompleted         bool
	Position, Duration               int64
	Genres, Countries                []byte
}
type viewedTitle struct {
	kind, status       string
	expected           int
	completed, partial bool
	episodes, regular  map[string]bool
	genres, countries  []byte
}

// Pure aggregation handles qualities/rewatches as one title and one episode.
// Time is supplied ONLY from playing-time telemetry, never from a seek position or runtime.
func calculateViewingStats(facts []viewingFact, watchedMS int64) userViewingStats {
	result := userViewingStats{SchemaVersion: 1, TotalWatchMS: watchedMS, Genres: []cinemaTasteShare{}, Countries: []cinemaTasteShare{}, CompletedMovieTmdbIDs: []int{}}
	titles := map[string]*viewedTitle{}
	completedMovieIDs := map[int]bool{}
	for _, f := range facts {
		if f.TitleID == "" || (f.Position <= 0 && !f.Completed && !f.EverCompleted) {
			continue
		}
		title := titles[f.TitleID]
		if title == nil {
			title = &viewedTitle{kind: f.Kind, status: f.Status, expected: f.ExpectedEpisodes,
				episodes: map[string]bool{}, regular: map[string]bool{}, genres: f.Genres, countries: f.Countries}
			titles[f.TitleID] = title
		}
		done := f.Completed || f.EverCompleted
		if f.Kind == "movie" {
			title.completed = title.completed || done
			if done && f.TmdbID > 0 {
				completedMovieIDs[f.TmdbID] = true
			}
		}
		if f.EpisodeID != "" && done {
			title.episodes[f.EpisodeID] = true
			if f.Season > 0 {
				title.regular[f.EpisodeID] = true
			}
		}
		if !f.Completed && f.Position > 0 && (f.Duration <= 0 || float64(f.Position) < float64(f.Duration)*.95) {
			title.partial = true
		}
	}
	for id := range completedMovieIDs {
		result.CompletedMovieTmdbIDs = append(result.CompletedMovieTmdbIDs, id)
	}
	sort.Ints(result.CompletedMovieTmdbIDs)
	genreWeights, countryWeights := map[string]float64{}, map[string]float64{}
	genreLabels := map[string]string{}
	for _, title := range titles {
		result.HistoryTitles++
		if title.partial {
			result.CurrentlyWatching++
		}
		if title.kind == "movie" {
			if title.completed {
				result.MoviesWatched++
				result.CompletedTitles++
			}
		} else {
			result.SeriesStarted++
			result.EpisodesWatched += len(title.episodes)
			if title.status == "Ended" && title.expected > 0 && len(title.regular) >= title.expected {
				result.SeriesWatched++
				result.CompletedTitles++
			}
		}
		if !title.completed && len(title.episodes) == 0 {
			continue
		}
		result.TasteSampleSize++
		var countries []string
		_ = json.Unmarshal(title.countries, &countries)
		unique := map[string]bool{}
		for _, code := range countries {
			if code != "" {
				unique[code] = true
			}
		}
		for code := range unique {
			countryWeights[code] += 1 / float64(len(unique))
		}
		var genres []json.RawMessage
		_ = json.Unmarshal(title.genres, &genres)
		names := map[string]string{}
		for _, raw := range genres {
			var object struct {
				ID   int    `json:"id"`
				Name string `json:"name"`
			}
			if json.Unmarshal(raw, &object) == nil && object.Name != "" {
				key := object.Name
				if object.ID > 0 {
					key = strconv.Itoa(object.ID)
				}
				names[key] = object.Name
			} else {
				var text string
				if json.Unmarshal(raw, &text) == nil && text != "" {
					names[text] = text
				}
			}
		}
		for key, label := range names {
			genreWeights[key] += 1 / float64(len(names))
			genreLabels[key] = label
		}
	}
	// Ten distinct completed/episode-completed titles before making taste conclusions.
	if result.TasteSampleSize >= 10 {
		result.Genres = tasteShares(genreWeights, genreLabels)
		result.Countries = tasteShares(countryWeights, nil)
	}
	result.LegacyHistoryWithoutTime = result.HistoryTitles > 0 && watchedMS == 0
	return result
}
func tasteShares(weights map[string]float64, labels map[string]string) []cinemaTasteShare {
	sum := 0.0
	for _, value := range weights {
		sum += value
	}
	result := []cinemaTasteShare{}
	if sum == 0 {
		return result
	}
	for key, value := range weights {
		label := labels[key]
		if label == "" {
			label = key
		}
		result = append(result, cinemaTasteShare{key, label, value / sum})
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Fraction == result[j].Fraction {
			return result[i].Key < result[j].Key
		}
		return result[i].Fraction > result[j].Fraction
	})
	return result
}

func (s *Server) personalViewingStats(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())
	viewerID := s.viewerProfileID(r, userID)
	maturity := s.viewerMaturityLevel(r, userID)
	rows, err := s.db.Query(r.Context(), `
        WITH progress AS (
          SELECT media_version_id,position_ms,duration_ms,completed,ever_completed FROM viewer_watch_progress WHERE viewer_profile_id::text=$2 AND $2<>''
          UNION ALL
          SELECT media_version_id,position_ms,duration_ms,completed,ever_completed FROM watch_progress WHERE user_id=$1 AND $2=''
        )
        SELECT mt.id::text,mt.kind,COALESCE(e.id::text,''),COALESCE(sn.season_number,0),
          wp.position_ms,wp.duration_ms,wp.completed,wp.ever_completed,mt.genres,mt.origin_countries,
          COALESCE(to_jsonb(mt)->>'series_status',to_jsonb(mt)->>'discovery_status',''),
          COALESCE(to_jsonb(mt)->>'episode_count',to_jsonb(mt)->>'total_episode_count','0')::int,COALESCE(mt.tmdb_id,0)
        FROM progress wp JOIN media_versions mv ON mv.id=wp.media_version_id
        LEFT JOIN episodes e ON e.id=mv.episode_id LEFT JOIN seasons sn ON sn.id=e.season_id
        JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
        WHERE ($3='all' OR ($3='teen' AND mt.audience_level IN ('kids','teen')) OR ($3='kids' AND mt.audience_level='kids'))
    `, userID, viewerID, maturity)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	facts := []viewingFact{}
	for rows.Next() {
		var fact viewingFact
		if err := rows.Scan(&fact.TitleID, &fact.Kind, &fact.EpisodeID, &fact.Season, &fact.Position, &fact.Duration, &fact.Completed, &fact.EverCompleted, &fact.Genres, &fact.Countries, &fact.Status, &fact.ExpectedEpisodes, &fact.TmdbID); err != nil {
			rows.Close()
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		facts = append(facts, fact)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	var watched int64
	err = s.db.QueryRow(r.Context(), `
        SELECT COALESCE(SUM(ps.watched_ms),0) FROM playback_sessions ps
        JOIN media_versions mv ON mv.id=ps.started_media_version_id
        LEFT JOIN episodes e ON e.id=mv.episode_id LEFT JOIN seasons sn ON sn.id=e.season_id
        JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
        WHERE ps.user_id=$1 AND (($2<>'' AND ps.viewer_profile_id::text=$2) OR ($2='' AND ps.viewer_profile_id IS NULL))
          AND ($3='all' OR ($3='teen' AND mt.audience_level IN ('kids','teen')) OR ($3='kids' AND mt.audience_level='kids'))
    `, userID, viewerID, maturity).Scan(&watched)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	result := calculateViewingStats(facts, watched)
	err = s.db.QueryRow(r.Context(), `
        WITH saved AS (
          SELECT media_title_id,'watchlist' AS bucket FROM viewer_watchlist WHERE viewer_profile_id::text=$2 AND $2<>''
          UNION ALL SELECT media_title_id,'watchlist' FROM watchlist WHERE user_id=$1 AND $2=''
          UNION ALL SELECT media_title_id,'favorite' FROM viewer_favorites WHERE viewer_profile_id::text=$2 AND $2<>''
          UNION ALL SELECT media_title_id,'favorite' FROM favorites WHERE user_id=$1 AND $2=''
        )
        SELECT COUNT(*) FILTER(WHERE bucket='watchlist'),COUNT(*) FILTER(WHERE bucket='favorite')
        FROM saved JOIN media_titles mt ON mt.id=saved.media_title_id
        WHERE ($3='all' OR ($3='teen' AND mt.audience_level IN ('kids','teen')) OR ($3='kids' AND mt.audience_level='kids'))
    `, userID, viewerID, maturity).Scan(&result.WatchlistCount, &result.FavoriteCount)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}
