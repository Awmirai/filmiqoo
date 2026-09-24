CREATE TABLE IF NOT EXISTS media_availability_alerts (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    alert_type text NOT NULL CHECK (
        alert_type IN ('persian_dub','persian_subtitle','uhd_4k','hdr')
    ),
    notified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_title_id, alert_type)
);

CREATE INDEX IF NOT EXISTS idx_media_availability_alerts_user_pending
    ON media_availability_alerts (user_id, notified_at, updated_at DESC);
