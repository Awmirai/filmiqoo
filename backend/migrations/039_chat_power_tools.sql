CREATE TABLE IF NOT EXISTS room_message_pins (
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    pinned_by_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pinned_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_room_message_pins_room_time
    ON room_message_pins (room_id, pinned_at DESC);
