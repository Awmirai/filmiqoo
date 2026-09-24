ALTER TABLE messages
    DROP CONSTRAINT IF EXISTS messages_message_type_check;

ALTER TABLE messages
    ADD CONSTRAINT messages_message_type_check
    CHECK (
        message_type IN (
            'text','image','video','voice','document','location','contact',
            'reel','movie','episode','system'
        )
    );

CREATE TABLE IF NOT EXISTS room_drafts (
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body text NOT NULL DEFAULT '',
    reply_to_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    spoiler boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id,user_id)
);

CREATE TABLE IF NOT EXISTS scheduled_room_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    author_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reply_to_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    body text NOT NULL DEFAULT '',
    message_type text NOT NULL DEFAULT 'text'
        CHECK (
            message_type IN (
                'text','image','video','voice','document','location','contact',
                'reel','movie','episode'
            )
        ),
    attachment jsonb NOT NULL DEFAULT '{}'::jsonb,
    spoiler boolean NOT NULL DEFAULT false,
    scheduled_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'scheduled'
        CHECK (status IN ('scheduled','sending','sent','cancelled','failed')),
    sent_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    last_error text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_scheduled_room_messages_due
    ON scheduled_room_messages (scheduled_at)
    WHERE status='scheduled';

CREATE INDEX IF NOT EXISTS idx_scheduled_room_messages_user
    ON scheduled_room_messages (author_user_id,scheduled_at DESC);
