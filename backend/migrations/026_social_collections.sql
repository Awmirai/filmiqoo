ALTER TABLE collections
    ADD COLUMN IF NOT EXISTS follower_count bigint NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS collection_followers (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_collection_followers_user_created
    ON collection_followers (user_id,created_at DESC);

CREATE INDEX IF NOT EXISTS idx_collections_public_followers
    ON collections (visibility,follower_count DESC,updated_at DESC);
