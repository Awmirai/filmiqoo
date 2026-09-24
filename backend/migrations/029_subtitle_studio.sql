ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS subtitle_text_color text NOT NULL DEFAULT 'white',
    ADD COLUMN IF NOT EXISTS subtitle_background_opacity double precision NOT NULL DEFAULT 0.45,
    ADD COLUMN IF NOT EXISTS subtitle_edge_style text NOT NULL DEFAULT 'outline';

ALTER TABLE user_preferences
    DROP CONSTRAINT IF EXISTS chk_subtitle_background_opacity;

ALTER TABLE user_preferences
    ADD CONSTRAINT chk_subtitle_background_opacity
    CHECK (subtitle_background_opacity >= 0.0 AND subtitle_background_opacity <= 1.0);
