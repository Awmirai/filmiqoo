CREATE TABLE IF NOT EXISTS watchlist (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_title_id)
);

CREATE TABLE IF NOT EXISTS collections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name text NOT NULL,
    description text NOT NULL DEFAULT '',
    emoji text NOT NULL DEFAULT '🎬',
    visibility text NOT NULL DEFAULT 'private' CHECK (visibility IN ('private','public','unlisted')),
    item_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (char_length(name) BETWEEN 1 AND 80)
);

CREATE TABLE IF NOT EXISTS collection_items (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    sort_order integer NOT NULL DEFAULT 0,
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, media_title_id)
);

CREATE INDEX IF NOT EXISTS idx_watchlist_user_created
    ON watchlist (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_collections_owner_updated
    ON collections (owner_user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_collection_items_collection_added
    ON collection_items (collection_id, added_at DESC);
