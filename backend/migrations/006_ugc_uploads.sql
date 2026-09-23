CREATE TABLE IF NOT EXISTS ugc_uploads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    object_key text NOT NULL UNIQUE,
    kind text NOT NULL,
    mime_type text NOT NULL,
    original_name text NOT NULL DEFAULT '',
    size_bytes bigint NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'presigned'
        CHECK (status IN ('presigned','uploaded','processing','attached','failed','deleted')),
    created_at timestamptz NOT NULL DEFAULT now(),
    uploaded_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_ugc_uploads_user_created
    ON ugc_uploads (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ugc_uploads_status
    ON ugc_uploads (status, created_at);
