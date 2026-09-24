ALTER TABLE rooms
    DROP CONSTRAINT IF EXISTS rooms_room_type_check;

ALTER TABLE rooms
    ADD CONSTRAINT rooms_room_type_check CHECK (
        room_type IN ('community','group','dm','episode','watch_party','live')
    );

CREATE TABLE IF NOT EXISTS live_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_id uuid REFERENCES channels(id) ON DELETE SET NULL,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    room_id uuid REFERENCES rooms(id) ON DELETE SET NULL,
    event_type text NOT NULL CHECK (event_type IN ('live','premiere')),
    title text NOT NULL,
    description text NOT NULL DEFAULT '',
    visibility text NOT NULL DEFAULT 'public' CHECK (visibility IN ('public','private','invite')),
    state text NOT NULL DEFAULT 'scheduled' CHECK (state IN ('scheduled','live','ended','cancelled')),
    playback_url text NOT NULL DEFAULT '',
    cover_url text NOT NULL DEFAULT '',
    allow_chat boolean NOT NULL DEFAULT true,
    scheduled_at timestamptz,
    started_at timestamptz,
    ended_at timestamptz,
    viewer_count bigint NOT NULL DEFAULT 0,
    peak_viewer_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS live_event_viewers (
    live_event_id uuid NOT NULL REFERENCES live_events(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    active boolean NOT NULL DEFAULT true,
    joined_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (live_event_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_live_events_discovery
    ON live_events (state,scheduled_at,created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_event_viewers_active
    ON live_event_viewers (live_event_id,active,last_seen_at DESC);
