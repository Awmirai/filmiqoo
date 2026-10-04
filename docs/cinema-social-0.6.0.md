# Cinema experience 0.6.0

The application now opens with a short original brand animation, then continues to the existing access and authentication flow. State restoration skips the introduction after rotation. Disabled system animations use a short static introduction.

## Experience

- Home: swipeable cinematic artwork, movie/series choices, title previews, existing live catalog updates, regional shelves and continue watching. Availability remains based on actual catalog files.
- Title: large artwork and typography, immediate episode browsing for series, season/search filters, still images, separate play/download buttons, progress and seen state. Plot text stays behind the spoiler preference. Inline discussions are below each tab and directly reachable through the discussions tab.
- Cinema club: persistent posts, comments, reactions, saved posts, creator profiles, stories, public collections, rooms, clips, moderation controls and paginated feed. Network failures preserve loaded content and offer retry.
- Navigation: home, discovery, cinema club, messages, profile. Personal lists, history and downloads remain accessible from the profile. Kids navigation continues to omit social routes.
- Player: tools sheet accepts outside-tap, swipe and Back dismissal; dedicated control-hide action; a Compose gesture layer above PlayerView; auto-hide pauses during scrubbing and sheets. Device coverage uses an owned H.264/AAC stream over HTTP, not third-party movie media.

## Title discussion contract

Migration `052_title_discussions.sql` adds comments and idempotent likes. Keys are `movie:<tmdbId>`, `series:<tmdbId>`, or `catalog:<uuid>` for titles without TMDB metadata. This allows a title without a playback file to have a discussion.

`GET /v1/discussions/{scope}` is public. Authenticated clients use `GET /v1/discussions/{scope}/viewer` for ownership and liked state. `parent` selects replies; `cursor` is a keyset cursor of creation time and UUID. Pages contain at most 20 items. Loading an older page replaces the displayed page, bounding the inline Compose tree.

`POST /v1/discussions/{scope}` takes a UUID `clientId`, body (maximum 3,000 characters), optional root `parentId`, spoiler flag, and either an approved sticker ID or owned GIF upload ID. Retries reuse identity until the draft changes. Cross-title replies and replies to blocked/deleted authors are rejected. Replies have one level of nesting. Deleting a root preserves replies and returns a tombstone.

`POST /v1/discussion-comments/{id}/like` accepts an explicit boolean `liked`; repeated requests cannot inflate counts. `/remove` checks ownership. Existing authenticated write rate limits apply. Both directions of user blocking filter personalized discussion reads. Reports use the existing moderation queue with target type `discussion`. Operations may resolve a report with `removeContent: true` and a required note; this removes the content in the same transaction as the moderation audit.

GIFs use the existing object storage upload flow with kind `title-comment`, MIME `image/gif`, maximum 10 MiB and bounded dimensions (2,048 pixels per side). The server decodes a bounded header directly from object storage. Comment creation accepts only completed GIF uploads owned by the author. No user-supplied remote GIF URL is fetched by the server. Android uses Coil's animated image decoder. The original vector sticker pack works offline and requires no external search key.

## Telegram interpretation

Ingestion and resolution both parse filename plus caption. Short labeled Persian/English headings and conservative release headings supply title candidates; Persian/Arabic digits and Arabic letter variants are normalized. Explicit caption season/episode/year fields fill missing filename fields. Filename release metadata has priority. Up to four distinct title candidates may be searched, preserving the existing TMDB confidence threshold. Unrelated synopsis text, channel links and adverts are not treated as metadata headings. This is deterministic parsing, not a guarantee that every arbitrary caption can be identified.

## Delivery boundary

The signed preview uses the same local preview certificate as 0.5.2, version code 8, and the existing preview application ID. It can update 0.5.2 without uninstalling. Signing material is outside the repository and is excluded from source archives.

The new discussion API and caption resolution require deployment of the backend and migration 052. An APK alone cannot activate them on an older server. The existing preview compatibility behavior remains limited to debug preview builds; production still requires server-side Iran access enforcement. No merge or server deployment is authorized by the earlier files-only delivery instruction.

Validation is recorded with the delivered APK: Android unit/lint, device tests (including restore, inline discussion, episode reachability, large fonts, player gestures), R8 build, Go tests/race with migrated PostgreSQL/Redis and production contracts. Physical Samsung testing and production deployment are separate from emulator validation.
