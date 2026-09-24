CREATE TABLE IF NOT EXISTS viewer_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name text NOT NULL,
    avatar_url text NOT NULL DEFAULT '',
    kids_mode boolean NOT NULL DEFAULT false,
    maturity_level text NOT NULL DEFAULT 'all'
        CHECK (maturity_level IN ('kids','teen','all')),
    preferred_audio_language text NOT NULL DEFAULT 'fa',
    preferred_subtitle_language text NOT NULL DEFAULT 'fa',
    autoplay_next boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (char_length(name) BETWEEN 1 AND 40)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_viewer_profiles_user_name
    ON viewer_profiles (user_id, lower(name));

CREATE INDEX IF NOT EXISTS idx_viewer_profiles_user_created
    ON viewer_profiles (user_id, created_at ASC);

CREATE TABLE IF NOT EXISTS viewer_watch_progress (
    viewer_profile_id uuid NOT NULL REFERENCES viewer_profiles(id) ON DELETE CASCADE,
    media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    position_ms bigint NOT NULL DEFAULT 0,
    duration_ms bigint NOT NULL DEFAULT 0,
    completed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_profile_id, media_version_id)
);

CREATE INDEX IF NOT EXISTS idx_viewer_watch_progress_updated
    ON viewer_watch_progress (viewer_profile_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS viewer_favorites (
    viewer_profile_id uuid NOT NULL REFERENCES viewer_profiles(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_profile_id, media_title_id)
);

CREATE TABLE IF NOT EXISTS viewer_watchlist (
    viewer_profile_id uuid NOT NULL REFERENCES viewer_profiles(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_profile_id, media_title_id)
);

INSERT INTO viewer_profiles (user_id,name,avatar_url)
SELECT p.user_id,
       CASE
         WHEN char_length(trim(p.display_name)) BETWEEN 1 AND 40 THEN trim(p.display_name)
         ELSE 'Profile'
       END,
       p.avatar_url
  FROM profiles p
 WHERE NOT EXISTS (
       SELECT 1 FROM viewer_profiles vp WHERE vp.user_id=p.user_id
 )
ON CONFLICT DO NOTHING;

INSERT INTO viewer_watch_progress (
    viewer_profile_id,media_version_id,position_ms,duration_ms,completed,updated_at
)
SELECT vp.id,wp.media_version_id,wp.position_ms,wp.duration_ms,wp.completed,wp.updated_at
  FROM watch_progress wp
  JOIN LATERAL (
      SELECT id
        FROM viewer_profiles
       WHERE user_id=wp.user_id
       ORDER BY created_at ASC,id ASC
       LIMIT 1
  ) vp ON true
ON CONFLICT (viewer_profile_id,media_version_id)
DO UPDATE SET
  position_ms=EXCLUDED.position_ms,
  duration_ms=EXCLUDED.duration_ms,
  completed=EXCLUDED.completed,
  updated_at=GREATEST(viewer_watch_progress.updated_at,EXCLUDED.updated_at);

INSERT INTO viewer_favorites (viewer_profile_id,media_title_id,created_at)
SELECT vp.id,f.media_title_id,f.created_at
  FROM favorites f
  JOIN LATERAL (
      SELECT id
        FROM viewer_profiles
       WHERE user_id=f.user_id
       ORDER BY created_at ASC,id ASC
       LIMIT 1
  ) vp ON true
ON CONFLICT DO NOTHING;

INSERT INTO viewer_watchlist (viewer_profile_id,media_title_id,created_at)
SELECT vp.id,w.media_title_id,w.created_at
  FROM watchlist w
  JOIN LATERAL (
      SELECT id
        FROM viewer_profiles
       WHERE user_id=w.user_id
       ORDER BY created_at ASC,id ASC
       LIMIT 1
  ) vp ON true
ON CONFLICT DO NOTHING;
