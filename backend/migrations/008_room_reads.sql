CREATE TABLE IF NOT EXISTS room_reads (
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_room_reads_user
    ON room_reads (user_id, last_read_at DESC);
