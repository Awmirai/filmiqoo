CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE IF NOT EXISTS users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext UNIQUE,
    phone text UNIQUE,
    password_hash text,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled','banned','pending')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS profiles (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    username citext NOT NULL UNIQUE,
    display_name text NOT NULL,
    bio text NOT NULL DEFAULT '',
    avatar_url text NOT NULL DEFAULT '',
    cover_url text NOT NULL DEFAULT '',
    verified boolean NOT NULL DEFAULT false,
    private_account boolean NOT NULL DEFAULT false,
    follower_count bigint NOT NULL DEFAULT 0,
    following_count bigint NOT NULL DEFAULT 0,
    post_count bigint NOT NULL DEFAULT 0,
    reel_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_follows (
    follower_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followed_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_user_id, followed_user_id),
    CHECK (follower_user_id <> followed_user_id)
);

CREATE TABLE IF NOT EXISTS blocks (
    blocker_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CHECK (blocker_user_id <> blocked_user_id)
);

CREATE TABLE IF NOT EXISTS media_titles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tmdb_id bigint,
    imdb_id text,
    kind text NOT NULL CHECK (kind IN ('movie','series','anime')),
    title text NOT NULL,
    original_title text NOT NULL DEFAULT '',
    overview text NOT NULL DEFAULT '',
    year integer NOT NULL DEFAULT 0,
    runtime_minutes integer NOT NULL DEFAULT 0,
    poster_url text NOT NULL DEFAULT '',
    backdrop_url text NOT NULL DEFAULT '',
    trailer_url text NOT NULL DEFAULT '',
    rating double precision,
    genres jsonb NOT NULL DEFAULT '[]'::jsonb,
    origin_countries jsonb NOT NULL DEFAULT '[]'::jsonb,
    original_language text NOT NULL DEFAULT '',
    visibility text NOT NULL DEFAULT 'public' CHECK (visibility IN ('public','unlisted','hidden')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tmdb_id, kind)
);

CREATE TABLE IF NOT EXISTS seasons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    season_number integer NOT NULL,
    name text NOT NULL DEFAULT '',
    overview text NOT NULL DEFAULT '',
    poster_url text NOT NULL DEFAULT '',
    air_date date,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (media_title_id, season_number)
);

CREATE TABLE IF NOT EXISTS episodes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id uuid NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    episode_number integer NOT NULL,
    name text NOT NULL DEFAULT '',
    overview text NOT NULL DEFAULT '',
    still_url text NOT NULL DEFAULT '',
    runtime_minutes integer NOT NULL DEFAULT 0,
    air_date date,
    tmdb_episode_id bigint,
    intro_start_ms bigint,
    intro_end_ms bigint,
    recap_start_ms bigint,
    recap_end_ms bigint,
    credits_start_ms bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (season_id, episode_number)
);

CREATE TABLE IF NOT EXISTS media_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    media_title_id uuid REFERENCES media_titles(id) ON DELETE CASCADE,
    episode_id uuid REFERENCES episodes(id) ON DELETE CASCADE,
    source_kind text NOT NULL DEFAULT 'telegram' CHECK (source_kind IN ('telegram','s3','local')),
    source_ref text NOT NULL,
    telegram_chat_id bigint,
    telegram_message_id bigint,
    file_name text NOT NULL DEFAULT '',
    file_size_bytes bigint NOT NULL DEFAULT 0,
    container text NOT NULL DEFAULT '',
    width integer NOT NULL DEFAULT 0,
    height integer NOT NULL DEFAULT 0,
    quality_label text NOT NULL DEFAULT '',
    video_codec text NOT NULL DEFAULT '',
    hdr_type text NOT NULL DEFAULT '',
    bitrate bigint NOT NULL DEFAULT 0,
    duration_ms bigint NOT NULL DEFAULT 0,
    audio_tracks jsonb NOT NULL DEFAULT '[]'::jsonb,
    subtitle_tracks jsonb NOT NULL DEFAULT '[]'::jsonb,
    playback_url text NOT NULL DEFAULT '',
    download_url text NOT NULL DEFAULT '',
    preferred boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((media_title_id IS NOT NULL) <> (episode_id IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS people (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tmdb_id bigint UNIQUE,
    name text NOT NULL,
    biography text NOT NULL DEFAULT '',
    profile_url text NOT NULL DEFAULT '',
    known_for text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS media_people (
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    person_id uuid NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    role text NOT NULL,
    character_name text NOT NULL DEFAULT '',
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (media_title_id, person_id, role, character_name)
);

CREATE TABLE IF NOT EXISTS favorites (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_title_id uuid NOT NULL REFERENCES media_titles(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_title_id)
);

CREATE TABLE IF NOT EXISTS watch_progress (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    position_ms bigint NOT NULL DEFAULT 0,
    duration_ms bigint NOT NULL DEFAULT 0,
    completed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, media_version_id)
);

CREATE TABLE IF NOT EXISTS downloads (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_version_id uuid NOT NULL REFERENCES media_versions(id) ON DELETE CASCADE,
    device_id text NOT NULL,
    state text NOT NULL DEFAULT 'queued' CHECK (state IN ('queued','downloading','paused','complete','failed','deleted')),
    bytes_downloaded bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, media_version_id, device_id)
);

CREATE TABLE IF NOT EXISTS channels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    slug citext NOT NULL UNIQUE,
    name text NOT NULL,
    bio text NOT NULL DEFAULT '',
    avatar_url text NOT NULL DEFAULT '',
    cover_url text NOT NULL DEFAULT '',
    visibility text NOT NULL DEFAULT 'public' CHECK (visibility IN ('public','private','invite')),
    verified boolean NOT NULL DEFAULT false,
    follower_count bigint NOT NULL DEFAULT 0,
    post_count bigint NOT NULL DEFAULT 0,
    reel_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS channel_members (
    channel_id uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role text NOT NULL DEFAULT 'member' CHECK (role IN ('owner','admin','moderator','member')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, user_id)
);

CREATE TABLE IF NOT EXISTS channel_followers (
    channel_id uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, user_id)
);

CREATE TABLE IF NOT EXISTS posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    author_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_id uuid REFERENCES channels(id) ON DELETE CASCADE,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    episode_id uuid REFERENCES episodes(id) ON DELETE SET NULL,
    post_type text NOT NULL DEFAULT 'post' CHECK (post_type IN ('post','review','poll','announcement')),
    body text NOT NULL DEFAULT '',
    spoiler boolean NOT NULL DEFAULT false,
    status text NOT NULL DEFAULT 'published' CHECK (status IN ('draft','scheduled','published','hidden','removed')),
    like_count bigint NOT NULL DEFAULT 0,
    comment_count bigint NOT NULL DEFAULT 0,
    save_count bigint NOT NULL DEFAULT 0,
    share_count bigint NOT NULL DEFAULT 0,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS post_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    media_type text NOT NULL CHECK (media_type IN ('image','video','gif')),
    url text NOT NULL,
    thumbnail_url text NOT NULL DEFAULT '',
    width integer NOT NULL DEFAULT 0,
    height integer NOT NULL DEFAULT 0,
    duration_ms bigint NOT NULL DEFAULT 0,
    sort_order integer NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS post_reactions (
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction text NOT NULL DEFAULT 'like',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid REFERENCES posts(id) ON DELETE CASCADE,
    reel_id uuid,
    parent_comment_id uuid REFERENCES comments(id) ON DELETE CASCADE,
    author_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body text NOT NULL,
    spoiler boolean NOT NULL DEFAULT false,
    like_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_id uuid REFERENCES channels(id) ON DELETE CASCADE,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    episode_id uuid REFERENCES episodes(id) ON DELETE SET NULL,
    caption text NOT NULL DEFAULT '',
    source_url text NOT NULL DEFAULT '',
    playback_url text NOT NULL DEFAULT '',
    cover_url text NOT NULL DEFAULT '',
    duration_ms integer NOT NULL DEFAULT 0,
    aspect_ratio text NOT NULL DEFAULT '9:16',
    audio_name text NOT NULL DEFAULT '',
    spoiler boolean NOT NULL DEFAULT false,
    allow_comments boolean NOT NULL DEFAULT true,
    status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','processing','scheduled','published','hidden','removed','failed')),
    like_count bigint NOT NULL DEFAULT 0,
    comment_count bigint NOT NULL DEFAULT 0,
    save_count bigint NOT NULL DEFAULT 0,
    share_count bigint NOT NULL DEFAULT 0,
    view_count bigint NOT NULL DEFAULT 0,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE comments
    ADD CONSTRAINT comments_target_check CHECK (
        (post_id IS NOT NULL AND reel_id IS NULL) OR
        (post_id IS NULL AND reel_id IS NOT NULL)
    );

ALTER TABLE comments
    ADD CONSTRAINT comments_reel_fk FOREIGN KEY (reel_id) REFERENCES reels(id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS reel_likes (
    reel_id uuid NOT NULL REFERENCES reels(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (reel_id, user_id)
);

CREATE TABLE IF NOT EXISTS reel_saves (
    reel_id uuid NOT NULL REFERENCES reels(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (reel_id, user_id)
);

CREATE TABLE IF NOT EXISTS stories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    author_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_id uuid REFERENCES channels(id) ON DELETE CASCADE,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    story_type text NOT NULL CHECK (story_type IN ('image','video','text')),
    media_url text NOT NULL DEFAULT '',
    thumbnail_url text NOT NULL DEFAULT '',
    caption text NOT NULL DEFAULT '',
    stickers jsonb NOT NULL DEFAULT '[]'::jsonb,
    spoiler boolean NOT NULL DEFAULT false,
    close_friends_only boolean NOT NULL DEFAULT false,
    view_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS story_views (
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    viewer_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, viewer_user_id)
);

CREATE TABLE IF NOT EXISTS story_reactions (
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, user_id)
);

CREATE TABLE IF NOT EXISTS rooms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    channel_id uuid REFERENCES channels(id) ON DELETE CASCADE,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE CASCADE,
    episode_id uuid REFERENCES episodes(id) ON DELETE CASCADE,
    name text NOT NULL,
    topic text NOT NULL DEFAULT '',
    room_type text NOT NULL DEFAULT 'community' CHECK (room_type IN ('community','group','dm','episode','watch_party')),
    visibility text NOT NULL DEFAULT 'public' CHECK (visibility IN ('public','private','invite')),
    member_count bigint NOT NULL DEFAULT 0,
    slow_mode_seconds integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS room_members (
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role text NOT NULL DEFAULT 'member' CHECK (role IN ('owner','admin','moderator','member')),
    muted_until timestamptz,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    author_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reply_to_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    body text NOT NULL DEFAULT '',
    message_type text NOT NULL DEFAULT 'text' CHECK (message_type IN ('text','image','video','voice','reel','movie','episode','system')),
    attachment jsonb NOT NULL DEFAULT '{}'::jsonb,
    spoiler boolean NOT NULL DEFAULT false,
    edited_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS message_reactions (
    message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id, reaction)
);

CREATE TABLE IF NOT EXISTS watch_parties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_title_id uuid REFERENCES media_titles(id) ON DELETE SET NULL,
    episode_id uuid REFERENCES episodes(id) ON DELETE SET NULL,
    room_id uuid REFERENCES rooms(id) ON DELETE SET NULL,
    title text NOT NULL,
    visibility text NOT NULL DEFAULT 'public' CHECK (visibility IN ('public','private','invite')),
    state text NOT NULL DEFAULT 'scheduled' CHECK (state IN ('scheduled','live','ended','cancelled')),
    scheduled_at timestamptz,
    playback_position_ms bigint NOT NULL DEFAULT 0,
    is_playing boolean NOT NULL DEFAULT false,
    participant_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS watch_party_members (
    watch_party_id uuid NOT NULL REFERENCES watch_parties(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role text NOT NULL DEFAULT 'viewer' CHECK (role IN ('host','cohost','moderator','viewer')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (watch_party_id, user_id)
);

CREATE TABLE IF NOT EXISTS notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    notification_type text NOT NULL,
    entity_type text NOT NULL DEFAULT '',
    entity_id uuid,
    title text NOT NULL,
    body text NOT NULL DEFAULT '',
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    reason text NOT NULL,
    detail text NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'open' CHECK (status IN ('open','reviewing','resolved','dismissed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);

CREATE TABLE IF NOT EXISTS moderation_actions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    moderator_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    action text NOT NULL,
    reason text NOT NULL DEFAULT '',
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS hashtags (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tag citext NOT NULL UNIQUE,
    usage_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS content_hashtags (
    hashtag_id uuid NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
    content_type text NOT NULL CHECK (content_type IN ('post','reel')),
    content_id uuid NOT NULL,
    PRIMARY KEY (hashtag_id, content_type, content_id)
);

CREATE INDEX IF NOT EXISTS idx_media_titles_created ON media_titles (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_titles_tmdb ON media_titles (tmdb_id);
CREATE INDEX IF NOT EXISTS idx_episodes_season ON episodes (season_id, episode_number);
CREATE INDEX IF NOT EXISTS idx_media_versions_episode ON media_versions (episode_id);
CREATE INDEX IF NOT EXISTS idx_media_versions_title ON media_versions (media_title_id);
CREATE INDEX IF NOT EXISTS idx_posts_author_created ON posts (author_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_posts_channel_created ON posts (channel_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reels_published ON reels (published_at DESC) WHERE status='published';
CREATE INDEX IF NOT EXISTS idx_reels_creator ON reels (creator_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stories_author_expiry ON stories (author_user_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_room_created ON messages (room_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_watch_progress_user_updated ON watch_progress (user_id, updated_at DESC);
