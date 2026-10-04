package server

// A selected viewer has an independent timeline. Account progress is used only
// for requests without a viewer, including older clients.
const scopedWatchProgress = `
    SELECT media_version_id,position_ms,duration_ms,completed,updated_at
      FROM viewer_watch_progress
     WHERE viewer_profile_id::text=$2 AND $2<>''
    UNION ALL
    SELECT media_version_id,position_ms,duration_ms,completed,updated_at
      FROM watch_progress
     WHERE user_id=$1 AND $2=''
`

// Resume across quality variants of the same movie or episode, but never from
// another episode of a series or another viewer's progress.
const playbackResumeQuery = `
    WITH progress AS (` + scopedWatchProgress + `)
    SELECT wp.position_ms,wp.duration_ms,wp.completed
      FROM progress wp
      JOIN media_versions watched ON watched.id=wp.media_version_id
     WHERE ($3::uuid IS NOT NULL AND watched.episode_id=$3)
        OR ($3::uuid IS NULL AND watched.media_title_id=$4)
     ORDER BY wp.updated_at DESC,wp.media_version_id DESC
     LIMIT 1
`

// Rank before filtering completion: a stale quality variant or earlier episode
// must not reappear after the most recently watched item has been completed.
const continueWatchingQuery = `
    WITH progress AS (` + scopedWatchProgress + `), ranked AS (
        SELECT wp.*,COALESCE(mv.media_title_id,s.media_title_id) AS title_id,
               ROW_NUMBER() OVER (
                   PARTITION BY COALESCE(mv.media_title_id,s.media_title_id)
                   ORDER BY wp.updated_at DESC,wp.media_version_id DESC
               ) AS latest
          FROM progress wp
          JOIN media_versions mv ON mv.id=wp.media_version_id
          LEFT JOIN episodes e ON e.id=mv.episode_id
          LEFT JOIN seasons s ON s.id=e.season_id
    )
    SELECT wp.media_version_id::text,wp.position_ms,wp.duration_ms,wp.completed,wp.updated_at,
           mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
           mt.year,mt.rating,mv.quality_label,
           e.id::text,e.episode_number,e.name,s.season_number
      FROM ranked wp
      JOIN media_versions mv ON mv.id=wp.media_version_id
      LEFT JOIN episodes e ON e.id=mv.episode_id
      LEFT JOIN seasons s ON s.id=e.season_id
      JOIN media_titles mt ON mt.id=wp.title_id
     WHERE wp.latest=1 AND wp.completed=false
       AND mv.stream_ready=true AND mt.visibility='public'
       AND (
           $3='all'
           OR ($3='teen' AND mt.audience_level IN ('kids','teen'))
           OR ($3='kids' AND mt.audience_level='kids')
       )
       AND wp.position_ms>0
       AND (wp.duration_ms=0 OR wp.position_ms < wp.duration_ms*0.95)
     ORDER BY wp.updated_at DESC,wp.media_version_id DESC
     LIMIT 30
`
