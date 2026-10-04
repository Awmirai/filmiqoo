CREATE TABLE title_comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scope text NOT NULL CHECK (length(scope) BETWEEN 3 AND 80),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id uuid REFERENCES title_comments(id) ON DELETE CASCADE,
    client_id uuid NOT NULL,
    body text NOT NULL DEFAULT '' CHECK (char_length(body) <= 3000),
    spoiler boolean NOT NULL DEFAULT false,
    sticker text NOT NULL DEFAULT '',
    upload_id uuid REFERENCES ugc_uploads(id) ON DELETE SET NULL,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id, client_id)
);
CREATE INDEX title_comments_page ON title_comments(scope, parent_id, created_at DESC, id DESC);
CREATE TABLE title_comment_likes (
    comment_id uuid NOT NULL REFERENCES title_comments(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY(comment_id, user_id)
);
