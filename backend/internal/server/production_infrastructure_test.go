package server

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/Awmirai/filmiqoo/backend/internal/config"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

func TestProductionInfrastructureSmoke(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E")!="1" {
		t.Skip("set FILMIQOO_E2E=1 to run")
	}

	ctx,cancel:=context.WithTimeout(context.Background(),40*time.Second)
	defer cancel()

	db,err:=pgxpool.New(ctx,os.Getenv("DATABASE_URL"))
	if err!=nil { t.Fatal(err) }
	defer db.Close()

	redisAddr:=os.Getenv("REDIS_ADDR")
	if redisAddr=="" { redisAddr="localhost:6379" }
	redisClient:=redis.NewClient(&redis.Options{Addr:redisAddr})
	defer redisClient.Close()
	if err:=redisClient.Ping(ctx).Err(); err!=nil { t.Fatal(err) }

	cfg:=config.Config{
		HTTPAddr:":0",
		DatabaseURL:os.Getenv("DATABASE_URL"),
		RedisAddr:redisAddr,
		JWTSecret:strings.Repeat("j",64),
		TelegramIngestSecret:strings.Repeat("i",64),
		PlaybackSigningSecret:strings.Repeat("p",64),
		ObjectStorageEndpoint:"http://localhost:9000",
		ObjectStoragePublicEndpoint:"http://localhost:9000",
		ObjectStorageBucket:"test",
		ObjectStorageKey:"test",
		ObjectStorageSecret:strings.Repeat("o",64),
		Environment:"development",
		AuthAccessTTLMinutes:15,
		AuthRefreshTTLDays:30,
		MaxJSONBodyBytes:2*1024*1024,
		AuthLoginRateLimit:50,
		AuthRegisterRateLimit:50,
		AuthRefreshRateLimit:50,
		AuthenticatedWriteRateLimit:500,
		PushMaxAttempts:6,
		TelegramIngestMaxAttempts:8,
		TelegramIngestRetryBaseSeconds:30,
		TelemetryRetentionDays:30,
	}

	s:=New(cfg,db,redisClient)
	if s.workersCancel!=nil { defer s.workersCancel() }
	ts:=httptest.NewServer(s.http.Handler)
	defer ts.Close()

	assertHTTPStatus(t,http.MethodGet,ts.URL+"/healthz",nil,http.StatusOK)
	assertHTTPStatus(t,http.MethodGet,ts.URL+"/readyz",nil,http.StatusOK)

	telemetry:=map[string]any{
		"deviceId":"ci-device",
		"sessionId":"ci-session",
		"eventType":"ci_smoke",
		"severity":"info",
		"message":"pipeline",
		"metadata":map[string]any{"ci":true},
		"appVersion":"ci",
		"platform":"android",
	}
	assertHTTPStatus(
		t,http.MethodPost,ts.URL+"/v1/telemetry/events",
		telemetry,http.StatusAccepted,
	)

	var telemetryCount int
	if err:=db.QueryRow(
		ctx,
		"SELECT COUNT(*) FROM app_telemetry_events WHERE event_type='ci_smoke'",
	).Scan(&telemetryCount); err!=nil || telemetryCount<1 {
		t.Fatalf("telemetry count=%d err=%v",telemetryCount,err)
	}

	var userID string
	err=db.QueryRow(ctx,
		"INSERT INTO users (status) VALUES ('active') RETURNING id::text",
	).Scan(&userID)
	if err!=nil { t.Fatal(err) }
	defer db.Exec(context.Background(),"DELETE FROM users WHERE id=$1",userID)

	var notificationID string
	err=db.QueryRow(ctx,
		"INSERT INTO notifications (user_id,notification_type,entity_type,title,body) VALUES ($1,'ci','system','CI','push') RETURNING id::text",
		userID,
	).Scan(&notificationID)
	if err!=nil { t.Fatal(err) }

	var outboxCount int
	err=db.QueryRow(ctx,
		"SELECT COUNT(*) FROM push_outbox WHERE notification_id=$1",
		notificationID,
	).Scan(&outboxCount)
	if err!=nil || outboxCount!=1 {
		t.Fatalf("push outbox count=%d err=%v",outboxCount,err)
	}

	var ingestID string
	err=db.QueryRow(ctx,`
		INSERT INTO telegram_ingest_items (
			telegram_chat_id,telegram_message_id,file_name,parsed_title,
			status,next_attempt_at,source_fingerprint
		) VALUES (1,1,'ci.mkv','CI','pending_metadata',now(),'ci')
		ON CONFLICT (telegram_chat_id,telegram_message_id)
		DO UPDATE SET status='pending_metadata',next_attempt_at=now()
		RETURNING id::text
	`).Scan(&ingestID)
	if err!=nil { t.Fatal(err) }

	item,err:=s.claimTelegramRetryOne(ctx,ingestID,false)
	if err!=nil { t.Fatal(err) }
	if item.id!=ingestID || item.attempt!=1 {
		t.Fatalf("unexpected retry claim: %#v",item)
	}

	if err:=s.finishTelegramResolveAttempt(
		ctx,
		item,
		context.DeadlineExceeded,
	); err!=nil {
		t.Fatal(err)
	}

	var status string
	var attempts int
	if err:=db.QueryRow(ctx,
		"SELECT status,attempt_count FROM telegram_ingest_items WHERE id=$1",
		ingestID,
	).Scan(&status,&attempts); err!=nil {
		t.Fatal(err)
	}
	if status!="failed" || attempts!=1 {
		t.Fatalf("unexpected retry state status=%s attempts=%d",status,attempts)
	}
}

func assertHTTPStatus(
	t *testing.T,
	method,url string,
	body any,
	expected int,
) {
	t.Helper()
	var reader io.Reader
	if body!=nil {
		raw,err:=json.Marshal(body)
		if err!=nil { t.Fatal(err) }
		reader=bytes.NewReader(raw)
	}
	req,err:=http.NewRequest(method,url,reader)
	if err!=nil { t.Fatal(err) }
	if body!=nil { req.Header.Set("Content-Type","application/json") }

	res,err:=(&http.Client{Timeout:10*time.Second}).Do(req)
	if err!=nil { t.Fatal(err) }
	defer res.Body.Close()
	raw,_:=io.ReadAll(res.Body)
	if res.StatusCode!=expected {
		t.Fatalf(
			"%s %s status=%d want=%d body=%s",
			method,url,res.StatusCode,expected,string(raw),
		)
	}
}
