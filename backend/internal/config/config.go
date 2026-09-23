package config

import (
	"os"
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
	ObjectStorageBucket string
	ObjectStorageKey string
	ObjectStorageSecret string
	PublicMediaBaseURL string
	Environment string
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
		ObjectStorageBucket: env("OBJECT_STORAGE_BUCKET", "filmiqoo-media"),
		ObjectStorageKey: env("OBJECT_STORAGE_KEY", "filmiqoo"),
		ObjectStorageSecret: env("OBJECT_STORAGE_SECRET", "filmiqoo-dev-secret"),
		PublicMediaBaseURL: env("PUBLIC_MEDIA_BASE_URL", "http://localhost:9000/filmiqoo-media"),
		Environment: env("APP_ENV", "development"),
	}
}

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" { return v }
	return fallback
}
