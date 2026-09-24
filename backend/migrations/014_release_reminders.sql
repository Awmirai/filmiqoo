CREATE TABLE IF NOT EXISTS release_reminders (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tmdb_id bigint NOT NULL,
    kind text NOT NULL CHECK (kind IN ('movie','tv','series')),
    title text NOT NULL,
    release_date date NOT NULL,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    notified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, tmdb_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_release_reminders_due
    ON release_reminders (user_id, release_date, notified_at);
