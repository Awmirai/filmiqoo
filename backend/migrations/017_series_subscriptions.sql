CREATE TABLE IF NOT EXISTS series_subscriptions (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    notify_new_episode boolean NOT NULL DEFAULT true,
    notify_stream_ready boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_title_id)
);

CREATE TABLE IF NOT EXISTS episode_alert_log (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id uuid NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    alert_type text NOT NULL CHECK (alert_type IN ('new_episode','stream_ready')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, episode_id, alert_type)
);

CREATE INDEX IF NOT EXISTS idx_series_subscriptions_user
    ON series_subscriptions (user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_episode_alert_log_user
    ON episode_alert_log (user_id, created_at DESC);
