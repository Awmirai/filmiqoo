package config

import "testing"

func strongProductionConfig() Config {
	return Config{
		Environment:"production",
		DatabaseURL:"postgres://filmiqoo:secret@db.example.com:5432/filmiqoo?sslmode=require",
		RedisAddr:"redis.example.com:6379",
		JWTSecret:"0123456789abcdef0123456789abcdef0123456789abcdef",
		TelegramIngestSecret:"abcdef0123456789abcdef0123456789abcdef0123456789",
		PlaybackSigningSecret:"fedcba9876543210fedcba9876543210fedcba9876543210",
		ObjectStorageSecret:"00112233445566778899aabbccddeeff0011223344556677",
		AllowedOrigins:[]string{"https://app.filmiqoo.example"},
		MaxJSONBodyBytes:2*1024*1024,
		AuthLoginRateLimit:10,
		AuthRegisterRateLimit:8,
		AuthRefreshRateLimit:30,
		AuthenticatedWriteRateLimit:240,
		PushMaxAttempts:6,
		TelegramIngestMaxAttempts:8,
		TelegramIngestRetryBaseSeconds:30,
		TelemetryRetentionDays:30,
	}
}

func TestValidateProductionConfigAcceptsStrongConfig(t *testing.T) {
	cfg:=strongProductionConfig()
	if err:=cfg.Validate(); err!=nil {
		t.Fatalf("expected valid production config, got %v",err)
	}
}

func TestValidateProductionConfigRejectsWeakSecret(t *testing.T) {
	cfg:=strongProductionConfig()
	cfg.JWTSecret="dev-only-change-me"
	if err:=cfg.Validate(); err==nil {
		t.Fatal("expected weak JWT secret to be rejected")
	}
}

func TestValidateProductionConfigRejectsWildcardOrigin(t *testing.T) {
	cfg:=strongProductionConfig()
	cfg.AllowedOrigins=[]string{"*"}
	if err:=cfg.Validate(); err==nil {
		t.Fatal("expected wildcard production origin to be rejected")
	}
}

func TestValidateProductionConfigRequiresDatabaseTLS(t *testing.T) {
	cfg:=strongProductionConfig()
	cfg.DatabaseURL="postgres://filmiqoo:secret@db.example.com:5432/filmiqoo?sslmode=disable"
	if err:=cfg.Validate(); err==nil {
		t.Fatal("expected sslmode=disable to be rejected in production")
	}
}

func TestDevelopmentAllowsDefaultStyleSettings(t *testing.T) {
	cfg:=Config{
		Environment:"development",
		DatabaseURL:"postgres://localhost/filmiqoo?sslmode=disable",
		RedisAddr:"localhost:6379",
		JWTSecret:"dev",
		TelegramIngestSecret:"dev",
		PlaybackSigningSecret:"dev",
		ObjectStorageSecret:"dev",
		MaxJSONBodyBytes:2*1024*1024,
		AuthLoginRateLimit:10,
		AuthRegisterRateLimit:8,
		AuthRefreshRateLimit:30,
		AuthenticatedWriteRateLimit:240,
	}
	if err:=cfg.Validate(); err!=nil {
		t.Fatalf("development config should remain usable: %v",err)
	}
}
