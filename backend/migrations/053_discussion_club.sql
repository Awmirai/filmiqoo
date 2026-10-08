-- Additive: existing comments, messages, lists and viewing history remain intact.
ALTER TABLE title_comments ADD COLUMN title_label text NOT NULL DEFAULT '' CHECK (char_length(title_label)<=240);
ALTER TABLE title_comments ADD COLUMN poster_path text NOT NULL DEFAULT '' CHECK (char_length(poster_path)<=500);
ALTER TABLE title_comments ADD COLUMN edited_at timestamptz;
CREATE INDEX title_comments_club_page ON title_comments(created_at DESC,id DESC) WHERE parent_id IS NULL AND NOT deleted;
-- Recover labels for previously catalogued discussions without rewriting their identity or body.
UPDATE title_comments c SET title_label=left(m.title,240),poster_path=left(COALESCE(m.poster_url,''),500)
FROM media_titles m WHERE c.scope='catalog:'||m.id::text
 OR c.scope=(CASE WHEN m.kind='movie' THEN 'movie:' ELSE 'series:' END)||m.tmdb_id::text;
