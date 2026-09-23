CREATE TABLE IF NOT EXISTS user_preferences (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    autoplay_next boolean NOT NULL DEFAULT true,
    autoplay_previews boolean NOT NULL DEFAULT true,
    wifi_only_downloads boolean NOT NULL DEFAULT false,
    data_saver boolean NOT NULL DEFAULT false,
    spoiler_shield boolean NOT NULL DEFAULT true,
    skip_intro boolean NOT NULL DEFAULT false,
    skip_recap boolean NOT NULL DEFAULT false,
    default_playback_speed double precision NOT NULL DEFAULT 1.0,
    default_audio_language text NOT NULL DEFAULT 'fa',
    default_subtitle_language text NOT NULL DEFAULT 'fa',
    subtitles_enabled boolean NOT NULL DEFAULT true,
    notifications_social boolean NOT NULL DEFAULT true,
    notifications_messages boolean NOT NULL DEFAULT true,
    notifications_releases boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (default_playback_speed >= 0.5 AND default_playback_speed <= 2.0)
);
