package config

import (
	"os"
	"strconv"
	"strings"
)

type Config struct {
	HTTPAddr string
	DatabaseURL string
	RedisAddr string
	RedisPassword string
	JWTSecret string
	TMDBToken string
	ObjectStorageEndpoint string
	ObjectStoragePublicEndpoint string
	ObjectStorageBucket string
	ObjectStorageKey string
	ObjectStorageSecret string
	PublicMediaBaseURL string
	Environment string
	TelegramIngestSecret string
	TelegramStreamBaseURL string
	PlaybackSigningSecret string
	PublicAPIBaseURL string
	PlaybackTokenTTLSeconds int
	TelegramStreamHashLength int
	AuthAccessTTLMinutes int
	AuthRefreshTTLDays int
}

func Load() Config {
	return Config{
		HTTPAddr: env("HTTP_ADDR", ":8080"),
		DatabaseURL: env("DATABASE_URL", "postgres://filmiqoo:filmiqoo@localhost:5432/filmiqoo?sslmode=disable"),
		RedisAddr: env("REDIS_ADDR", "localhost:6379"),
		RedisPassword: os.Getenv("REDIS_PASSWORD"),
		JWTSecret: env("JWT_SECRET", "dev-only-change-me"),
		TMDBToken: strings.TrimSpace(os.Getenv("TMDB_TOKEN")),
		ObjectStorageEndpoint: env("OBJECT_STORAGE_ENDPOINT", "http://localhost:9000"),
		ObjectStoragePublicEndpoint: env("OBJECT_STORAGE_PUBLIC_ENDPOINT", "http://10.0.2.2:9000"),
		ObjectStorageBucket: env("OBJECT_STORAGE_BUCKET", "filmiqoo-media"),
		ObjectStorageKey: env("OBJECT_STORAGE_KEY", "filmiqoo"),
		ObjectStorageSecret: env("OBJECT_STORAGE_SECRET", "filmiqoo-dev-secret"),
		PublicMediaBaseURL: env("PUBLIC_MEDIA_BASE_URL", "http://localhost:9000/filmiqoo-media"),
		Environment: env("APP_ENV", "development"),
		TelegramIngestSecret: env("TELEGRAM_INGEST_SECRET", "dev-ingest-change-me"),
		TelegramStreamBaseURL: env("TELEGRAM_STREAM_BASE_URL", "http://localhost:8081"),
		PlaybackSigningSecret: env("PLAYBACK_SIGNING_SECRET", "dev-playback-change-me"),
		PublicAPIBaseURL: env("PUBLIC_API_BASE_URL", "http://localhost:8080"),
		PlaybackTokenTTLSeconds: envInt("PLAYBACK_TOKEN_TTL_SECONDS", 300),
		TelegramStreamHashLength: envInt("TELEGRAM_STREAM_HASH_LENGTH", 6),
		AuthAccessTTLMinutes: envInt("AUTH_ACCESS_TTL_MINUTES", 15),
		AuthRefreshTTLDays: envInt("AUTH_REFRESH_TTL_DAYS", 30),
	}
}

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" { return v }
	return fallback
}

func envInt(key string, fallback int) int {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" { return fallback }
	n, err := strconv.Atoi(v)
	if err != nil { return fallback }
	return n
}
