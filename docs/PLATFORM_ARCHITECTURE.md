# Filmiqoo Platform Architecture

Filmiqoo is being built as one product with four first-class systems:

- Watch: catalog, streaming, downloads, subtitles, audio tracks and watch progress.
- Discover: home, search, recommendations, releases, people and collections.
- Social: feed, reels, stories, creator pages, channels, rooms, DMs, comments and reactions.
- Create: reel/story/post/review/live creation, creator studio and analytics.

## Runtime services

### API
Go service serving catalog, social APIs, user state and authenticated mutations.

### PostgreSQL
Durable source of truth for catalog, social graph, content, watch state and moderation state.

### Redis
Presence, realtime fan-out, rate limits, feed caches and ephemeral watch-party state.

### Object storage
Development uses MinIO. Production should use an S3-compatible object store plus CDN for user-generated media.

### Telegram media storage
Telegram remains the source storage for licensed VOD media during the initial architecture. It is intentionally separated from user-generated social media.

## Realtime
WebSocket transport is exposed at /v1/realtime. Room chat, typing, presence, reactions and watch-party synchronization will be layered on top.

## Android
The existing Compose app remains the visual client while API-backed repositories replace preview-local state feature by feature.

## Security
TMDB and Telegram credentials belong on the server in production. Android should receive signed application APIs and short-lived playback URLs, never raw Telegram credentials.

## Data domains
The first migration includes users/profiles, follows/blocks, media catalog, seasons/episodes/versions, watch state, downloads, creator channels, posts, reels, stories, rooms/messages, watch parties, notifications and moderation.
