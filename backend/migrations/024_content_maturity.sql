ALTER TABLE media_titles
    ADD COLUMN IF NOT EXISTS audience_level text NOT NULL DEFAULT 'unrated'
        CHECK (audience_level IN ('kids','teen','adult','unrated'));

CREATE INDEX IF NOT EXISTS idx_media_titles_audience_visibility
    ON media_titles (audience_level, visibility, updated_at DESC);
