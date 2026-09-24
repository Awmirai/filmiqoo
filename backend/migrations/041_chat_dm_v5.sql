ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS notification_level text NOT NULL DEFAULT 'all'
        CHECK (notification_level IN ('all','mentions','off')),
    ADD COLUMN IF NOT EXISTS archived_at timestamptz;

ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_room_members_inbox
    ON room_members (user_id, archived_at, joined_at DESC);

CREATE TABLE IF NOT EXISTS room_invites (
    room_id uuid PRIMARY KEY REFERENCES rooms(id) ON DELETE CASCADE,
    invite_code text NOT NULL UNIQUE,
    created_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT true,
    usage_count bigint NOT NULL DEFAULT 0,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_room_invites_code_enabled
    ON room_invites (invite_code)
    WHERE enabled=true;
