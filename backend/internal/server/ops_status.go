package server

import (
	"context"
	"crypto/subtle"
	"net/http"
	"strings"
	"time"
)

func secureSecretEqual(expected,actual string) bool {
	expected=strings.TrimSpace(expected)
	actual=strings.TrimSpace(actual)
	if expected=="" || actual=="" || len(expected)!=len(actual) { return false }
	return subtle.ConstantTimeCompare([]byte(expected),[]byte(actual))==1
}

func (s *Server) validOpsSecret(value string) bool {
	return secureSecretEqual(s.cfg.OpsSecret,value)
}

func (s *Server) opsStatus(w http.ResponseWriter,r *http.Request) {
	if !s.validOpsSecret(r.Header.Get("X-Filmiqoo-Ops-Secret")) {
		writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"invalid operations secret"})
		return
	}

	ctx,cancel:=context.WithTimeout(r.Context(),3*time.Second)
	defer cancel()

	dbStarted:=time.Now()
	dbErr:=s.db.Ping(ctx)
	dbLatency:=time.Since(dbStarted)

	redisStarted:=time.Now()
	redisErr:=s.redis.Ping(ctx).Err()
	redisLatency:=time.Since(redisStarted)

	push:=s.statusCounts(ctx,`
		SELECT status,COUNT(*)
		  FROM push_outbox
		 GROUP BY status
	`)
	telegram:=s.statusCounts(ctx,`
		SELECT status,COUNT(*)
		  FROM telegram_ingest_items
		 GROUP BY status
	`)

	var telemetryErrors,telemetryFatal,telemetryFingerprints int64
	_ = s.db.QueryRow(ctx,`
		SELECT
		  COUNT(*) FILTER (WHERE severity='error'),
		  COUNT(*) FILTER (WHERE severity='fatal'),
		  COUNT(DISTINCT fingerprint) FILTER (WHERE fingerprint<>'')
		  FROM app_telemetry_events
		 WHERE created_at>=now()-interval '24 hours'
	`).Scan(&telemetryErrors,&telemetryFatal,&telemetryFingerprints)

	var activeSessions,readyMedia,enabledPushDevices int64
	_ = s.db.QueryRow(ctx,`
		SELECT COUNT(*)
		  FROM auth_sessions
		 WHERE revoked_at IS NULL AND expires_at>now()
	`).Scan(&activeSessions)
	_ = s.db.QueryRow(ctx,"SELECT COUNT(*) FROM media_versions WHERE stream_ready=true").Scan(&readyMedia)
	_ = s.db.QueryRow(ctx,"SELECT COUNT(*) FROM push_devices WHERE enabled=true").Scan(&enabledPushDevices)

	status:="ok"
	code:=http.StatusOK
	if dbErr!=nil || redisErr!=nil || (s.cfg.FirebasePushEnabled && s.fcm==nil) {
		status="degraded"
		code=http.StatusServiceUnavailable
	}

	writeJSON(w,code,map[string]any{
		"status":status,
		"service":"filmiqoo-api",
		"environment":s.cfg.Environment,
		"version":s.cfg.BuildVersion,
		"commit":s.cfg.BuildCommit,
		"time":time.Now().UTC(),
		"dependencies":map[string]any{
			"postgres":map[string]any{
				"ok":dbErr==nil,
				"latencyMs":dbLatency.Milliseconds(),
			},
			"redis":map[string]any{
				"ok":redisErr==nil,
				"latencyMs":redisLatency.Milliseconds(),
			},
			"firebasePush":map[string]any{
				"enabled":s.cfg.FirebasePushEnabled,
				"ready":!s.cfg.FirebasePushEnabled || s.fcm!=nil,
			},
		},
		"queues":map[string]any{
			"push":push,
			"telegramIngest":telegram,
		},
		"telemetry24h":map[string]any{
			"errors":telemetryErrors,
			"fatal":telemetryFatal,
			"uniqueFingerprints":telemetryFingerprints,
		},
		"usage":map[string]any{
			"activeSessions":activeSessions,
			"enabledPushDevices":enabledPushDevices,
			"streamReadyMediaVersions":readyMedia,
		},
	})
}

func (s *Server) statusCounts(ctx context.Context,query string) map[string]int64 {
	result:=map[string]int64{}
	rows,err:=s.db.Query(ctx,query)
	if err!=nil { return result }
	defer rows.Close()

	for rows.Next() {
		var status string
		var count int64
		if rows.Scan(&status,&count)==nil {
			result[status]=count
		}
	}
	return result
}
