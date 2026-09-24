ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS skip_credits boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS subtitle_scale double precision NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS subtitle_bottom_padding double precision NOT NULL DEFAULT 0.08,
    ADD COLUMN IF NOT EXISTS player_resize_mode text NOT NULL DEFAULT 'fit';

ALTER TABLE user_preferences
    DROP CONSTRAINT IF EXISTS user_preferences_subtitle_scale_check;
ALTER TABLE user_preferences
    ADD CONSTRAINT user_preferences_subtitle_scale_check
    CHECK (subtitle_scale >= 0.7 AND subtitle_scale <= 1.6);

ALTER TABLE user_preferences
    DROP CONSTRAINT IF EXISTS user_preferences_subtitle_padding_check;
ALTER TABLE user_preferences
    ADD CONSTRAINT user_preferences_subtitle_padding_check
    CHECK (subtitle_bottom_padding >= 0.02 AND subtitle_bottom_padding <= 0.28);

ALTER TABLE user_preferences
    DROP CONSTRAINT IF EXISTS user_preferences_resize_mode_check;
ALTER TABLE user_preferences
    ADD CONSTRAINT user_preferences_resize_mode_check
    CHECK (player_resize_mode IN ('fit','fill','zoom'));
