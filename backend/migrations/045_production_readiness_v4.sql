CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_media_titles_title_trgm
    ON media_titles USING gin (title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_media_titles_original_title_trgm
    ON media_titles USING gin (original_title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_media_titles_overview_trgm
    ON media_titles USING gin (overview gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_profiles_username_trgm
    ON profiles USING gin ((username::text) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_profiles_display_name_trgm
    ON profiles USING gin (display_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_channels_slug_trgm
    ON channels USING gin ((slug::text) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_channels_name_trgm
    ON channels USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_reels_caption_trgm
    ON reels USING gin (caption gin_trgm_ops)
    WHERE status='published';

CREATE INDEX IF NOT EXISTS idx_media_versions_title_ready
    ON media_versions (media_title_id,preferred DESC,height DESC,file_size_bytes DESC)
    WHERE stream_ready=true;

CREATE INDEX IF NOT EXISTS idx_media_versions_episode_ready
    ON media_versions (episode_id,preferred DESC,height DESC,file_size_bytes DESC)
    WHERE stream_ready=true;

CREATE INDEX IF NOT EXISTS idx_auth_sessions_active_user
    ON auth_sessions (user_id,expires_at DESC)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ugc_uploads_cleanup
    ON ugc_uploads (status,created_at)
    WHERE status IN ('presigned','failed');

ALTER TABLE reports
    ADD COLUMN IF NOT EXISTS priority smallint NOT NULL DEFAULT 40
        CHECK (priority BETWEEN 0 AND 100),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY reporter_user_id,target_type,target_id,reason
               ORDER BY created_at ASC,id ASC
           ) AS rn
      FROM reports
     WHERE status IN ('open','reviewing')
)
UPDATE reports r
   SET status='dismissed',
       resolved_at=COALESCE(resolved_at,now()),
       updated_at=now()
  FROM ranked d
 WHERE r.id=d.id
   AND d.rn>1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_reports_active_reporter_target_reason
    ON reports (reporter_user_id,target_type,target_id,reason)
    WHERE status IN ('open','reviewing');

CREATE INDEX IF NOT EXISTS idx_reports_moderation_queue
    ON reports (priority DESC,created_at ASC)
    WHERE status IN ('open','reviewing');

CREATE INDEX IF NOT EXISTS idx_reports_target_recent
    ON reports (target_type,target_id,created_at DESC);
