# Filmiqoo

Filmiqoo is a Persian-first VOD + cinema social platform.

The repository now contains the Android preview and the first production platform core:

- Kotlin + Jetpack Compose Android app
- Go backend API
- PostgreSQL data model
- Redis realtime/cache layer
- S3-compatible development object storage with MinIO
- Docker development stack
- CI for Android and backend

## Local platform stack

Copy .env.example to .env, fill development secrets, then run:

    docker compose up --build

Health endpoints:

    GET http://localhost:8080/healthz
    GET http://localhost:8080/readyz

Current API foundation includes:

    GET  /v1/catalog/home
    GET  /v1/social/reels
    GET  /v1/social/channels
    GET  /v1/realtime/rooms/{id}  (authenticated WebSocket)
    POST /v1/social/reels/{id}/like
    POST /v1/social/channels/{id}/follow
    POST /v1/watch/progress

The next implementation layers are Telegram media ingest and secure streaming, authentication/onboarding, full social write APIs, media transcoding, recommendation/feed ranking and Android API integration.
