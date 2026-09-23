CREATE TABLE IF NOT EXISTS media_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 10),
    body text NOT NULL DEFAULT '',
    spoiler boolean NOT NULL DEFAULT false,
    like_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (media_title_id,user_id),
    CHECK (char_length(body) <= 5000)
);

CREATE TABLE IF NOT EXISTS media_review_likes (
    review_id uuid NOT NULL REFERENCES media_reviews(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id,user_id)
);

CREATE INDEX IF NOT EXISTS idx_media_reviews_title_updated
    ON media_reviews (media_title_id,updated_at DESC);
