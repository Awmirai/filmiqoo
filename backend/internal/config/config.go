package config

import (
	"encoding/json"
	"errors"
	"fmt"
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
	AllowedOrigins []string
	MaxJSONBodyBytes int64
	AuthLoginRateLimit int
	AuthRegisterRateLimit int
	AuthRefreshRateLimit int
	AuthenticatedWriteRateLimit int
	BuildVersion string
	BuildCommit string
	FirebasePushEnabled bool
	FirebaseProjectID string
	FirebaseServiceAccountJSON string
	PushMaxAttempts int
	TelegramIngestMaxAttempts int
	TelegramIngestRetryBaseSeconds int
	TelemetryRetentionDays int
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
		AllowedOrigins: envList("ALLOWED_ORIGINS", ""),
		MaxJSONBodyBytes: envInt64("MAX_JSON_BODY_BYTES", 2*1024*1024),
		AuthLoginRateLimit: envInt("AUTH_LOGIN_RATE_LIMIT_PER_MINUTE", 10),
		AuthRegisterRateLimit: envInt("AUTH_REGISTER_RATE_LIMIT_PER_HOUR", 8),
		AuthRefreshRateLimit: envInt("AUTH_REFRESH_RATE_LIMIT_PER_MINUTE", 30),
		AuthenticatedWriteRateLimit: envInt("AUTHENTICATED_WRITE_RATE_LIMIT_PER_MINUTE", 240),
		BuildVersion: env("BUILD_VERSION", "dev"),
		BuildCommit: env("BUILD_COMMIT", "unknown"),
		FirebasePushEnabled: envBool("FIREBASE_PUSH_ENABLED", false),
		FirebaseProjectID: strings.TrimSpace(os.Getenv("FIREBASE_PROJECT_ID")),
		FirebaseServiceAccountJSON: strings.TrimSpace(os.Getenv("FIREBASE_SERVICE_ACCOUNT_JSON")),
		PushMaxAttempts: envInt("PUSH_MAX_ATTEMPTS", 6),
		TelegramIngestMaxAttempts: envInt("TELEGRAM_INGEST_MAX_ATTEMPTS", 8),
		TelegramIngestRetryBaseSeconds: envInt("TELEGRAM_INGEST_RETRY_BASE_SECONDS", 30),
		TelemetryRetentionDays: envInt("TELEMETRY_RETENTION_DAYS", 30),
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


func (c Config) Validate() error {
	if strings.TrimSpace(c.DatabaseURL)=="" {
		return errors.New("DATABASE_URL is required")
	}
	if strings.TrimSpace(c.RedisAddr)=="" {
		return errors.New("REDIS_ADDR is required")
	}
	if c.MaxJSONBodyBytes < 64*1024 {
		return errors.New("MAX_JSON_BODY_BYTES must be at least 65536")
	}

	envName:=strings.ToLower(strings.TrimSpace(c.Environment))
	if envName=="production" || envName=="prod" {
		weak:=map[string]string{
			"JWT_SECRET":c.JWTSecret,
			"TELEGRAM_INGEST_SECRET":c.TelegramIngestSecret,
			"PLAYBACK_SIGNING_SECRET":c.PlaybackSigningSecret,
			"OBJECT_STORAGE_SECRET":c.ObjectStorageSecret,
		}
		for name,value:=range weak {
			v:=strings.TrimSpace(value)
			if len(v)<32 ||
				strings.Contains(strings.ToLower(v),"dev") ||
				strings.Contains(strings.ToLower(v),"change-me") ||
				strings.Contains(strings.ToLower(v),"filmiqoo-dev") {
				return fmt.Errorf("%s must be a strong production secret",name)
			}
		}
		if len(c.AllowedOrigins)==0 {
			return errors.New("ALLOWED_ORIGINS must be configured in production")
		}
		for _,origin:=range c.AllowedOrigins {
			if origin=="*" {
				return errors.New("ALLOWED_ORIGINS cannot contain * in production")
			}
		}
		if strings.Contains(strings.ToLower(c.DatabaseURL),"sslmode=disable") {
			return errors.New("production DATABASE_URL must not disable TLS")
		}
	}

	if c.AuthLoginRateLimit<=0 || c.AuthRegisterRateLimit<=0 || c.AuthRefreshRateLimit<=0 {
		return errors.New("auth rate limits must be greater than zero")
	}
	if c.AuthenticatedWriteRateLimit<=0 {
		return errors.New("authenticated write rate limit must be greater than zero")
	}
	if c.PushMaxAttempts<=0 || c.TelegramIngestMaxAttempts<=0 ||
		c.TelegramIngestRetryBaseSeconds<=0 || c.TelemetryRetentionDays<=0 {
		return errors.New("worker retry and telemetry retention settings must be greater than zero")
	}
	if c.FirebasePushEnabled {
		if strings.TrimSpace(c.FirebaseProjectID)=="" {
			return errors.New("FIREBASE_PROJECT_ID is required when push delivery is enabled")
		}
		raw:=strings.TrimSpace(c.FirebaseServiceAccountJSON)
		if raw=="" {
			return errors.New("FIREBASE_SERVICE_ACCOUNT_JSON is required when push delivery is enabled")
		}
		var serviceAccount map[string]any
		if err:=json.Unmarshal([]byte(raw),&serviceAccount); err!=nil {
			return errors.New("FIREBASE_SERVICE_ACCOUNT_JSON must be valid JSON")
		}
		if strings.TrimSpace(fmt.Sprint(serviceAccount["client_email"]))=="" ||
			strings.TrimSpace(fmt.Sprint(serviceAccount["private_key"]))=="" {
			return errors.New("Firebase service account JSON is missing client_email or private_key")
		}
	}
	return nil
}

func (c Config) IsProduction() bool {
	v:=strings.ToLower(strings.TrimSpace(c.Environment))
	return v=="production" || v=="prod"
}

func envInt64(key string, fallback int64) int64 {
	v:=strings.TrimSpace(os.Getenv(key))
	if v=="" { return fallback }
	n,err:=strconv.ParseInt(v,10,64)
	if err!=nil { return fallback }
	return n
}

func envList(key, fallback string) []string {
	raw:=strings.TrimSpace(os.Getenv(key))
	if raw=="" { raw=strings.TrimSpace(fallback) }
	if raw=="" { return nil }
	seen:=map[string]struct{}{}
	out:=make([]string,0)
	for _,part:=range strings.Split(raw,",") {
		v:=strings.TrimSpace(part)
		if v=="" { continue }
		if _,ok:=seen[v]; ok { continue }
		seen[v]=struct{}{}
		out=append(out,v)
	}
	return out
}


func envBool(key string,fallback bool) bool {
	v:=strings.ToLower(strings.TrimSpace(os.Getenv(key)))
	if v=="" { return fallback }
	switch v {
	case "1","true","yes","on":
		return true
	case "0","false","no","off":
		return false
	default:
		return fallback
	}
}
