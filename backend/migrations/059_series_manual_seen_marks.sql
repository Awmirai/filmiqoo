-- Manual episode marks are independent of playback, resume and statistics.
-- Legacy completion provenance is ambiguous; preserve old progress safely.
CREATE TABLE IF NOT EXISTS episode_seen_marks (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 episode_id uuid NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (user_id, episode_id)
);
CREATE TABLE IF NOT EXISTS viewer_episode_seen_marks (
 viewer_profile_id uuid NOT NULL REFERENCES viewer_profiles(id) ON DELETE CASCADE,
 episode_id uuid NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (viewer_profile_id, episode_id)
);
