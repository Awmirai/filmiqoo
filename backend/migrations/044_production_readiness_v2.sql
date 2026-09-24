ALTER TABLE telegram_ingest_items
    ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS last_attempt_at timestamptz,
    ADD COLUMN IF NOT EXISTS dead_lettered_at timestamptz,
    ADD COLUMN IF NOT EXISTS source_fingerprint text NOT NULL DEFAULT '';

ALTER TABLE telegram_ingest_items
    DROP CONSTRAINT IF EXISTS telegram_ingest_items_status_check;

ALTER TABLE telegram_ingest_items
    ADD CONSTRAINT telegram_ingest_items_status_check
    CHECK (
        status IN (
            'pending_metadata','resolving','ready','failed','ignored','dead_letter'
        )
    );

CREATE INDEX IF NOT EXISTS idx_telegram_ingest_retry
    ON telegram_ingest_items (next_attempt_at, received_at)
    WHERE status IN ('pending_metadata','failed','resolving');

CREATE TABLE IF NOT EXISTS push_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','sending','sent','failed','dead','no_device')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    sent_at timestamptz,
    last_error text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (notification_id)
);

CREATE INDEX IF NOT EXISTS idx_push_outbox_due
    ON push_outbox (next_attempt_at, created_at)
    WHERE status IN ('pending','failed','sending');

CREATE OR REPLACE FUNCTION enqueue_notification_push()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO push_outbox (notification_id,user_id)
    VALUES (NEW.id,NEW.user_id)
    ON CONFLICT (notification_id) DO NOTHING;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_enqueue_notification_push ON notifications;

CREATE TRIGGER trg_enqueue_notification_push
AFTER INSERT ON notifications
FOR EACH ROW
EXECUTE FUNCTION enqueue_notification_push();

CREATE TABLE IF NOT EXISTS app_telemetry_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id text NOT NULL DEFAULT '',
    session_id text NOT NULL DEFAULT '',
    event_type text NOT NULL,
    severity text NOT NULL DEFAULT 'info'
        CHECK (severity IN ('debug','info','warning','error','fatal')),
    message text NOT NULL DEFAULT '',
    stack_trace text NOT NULL DEFAULT '',
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    app_version text NOT NULL DEFAULT '',
    platform text NOT NULL DEFAULT 'android',
    fingerprint text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_app_telemetry_type_created
    ON app_telemetry_events (event_type,created_at DESC);

CREATE INDEX IF NOT EXISTS idx_app_telemetry_fingerprint_created
    ON app_telemetry_events (fingerprint,created_at DESC)
    WHERE fingerprint<>'';
