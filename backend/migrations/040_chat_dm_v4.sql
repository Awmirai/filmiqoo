ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS forwarded_from_message_id uuid REFERENCES messages(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_messages_forwarded_from
    ON messages (forwarded_from_message_id)
    WHERE forwarded_from_message_id IS NOT NULL;

ALTER TABLE user_presence
    ADD COLUMN IF NOT EXISTS last_seen_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE user_presence
    DROP CONSTRAINT IF EXISTS user_presence_state_check;

ALTER TABLE user_presence
    ADD CONSTRAINT user_presence_state_check
    CHECK (state IN ('offline','online','watching'));

CREATE INDEX IF NOT EXISTS idx_room_members_role
    ON room_members (room_id, role, joined_at);
