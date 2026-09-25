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

	var openReports,criticalReports,staleUploads int64
	_ = s.db.QueryRow(ctx,`
		SELECT COUNT(*),
		       COUNT(*) FILTER (WHERE priority>=90)
		  FROM reports
		 WHERE status IN ('open','reviewing')
	`).Scan(&openReports,&criticalReports)
	_ = s.db.QueryRow(ctx,`
		SELECT COUNT(*)
		  FROM ugc_uploads
		 WHERE (status='presigned' AND created_at<now()-interval '2 hours')
		    OR (status='failed' AND created_at<now()-interval '1 hour')
	`).Scan(&staleUploads)

	dbPool:=s.db.Stat()
	redisPool:=s.redis.PoolStats()

	hourKey:=time.Now().UTC().Format("2006010215")
	playbackSuccess,_:=s.redis.Get(
		ctx,
		"metrics:playback-origin:success:"+hourKey,
	).Int64()
	playbackErrors,_:=s.redis.Get(
		ctx,
		"metrics:playback-origin:error:"+hourKey,
	).Int64()

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
				"pool":map[string]any{
					"hits":redisPool.Hits,
					"misses":redisPool.Misses,
					"timeouts":redisPool.Timeouts,
					"totalConns":redisPool.TotalConns,
					"idleConns":redisPool.IdleConns,
					"staleConns":redisPool.StaleConns,
				},
			},
			"postgresPool":map[string]any{
				"maxConns":dbPool.MaxConns(),
				"totalConns":dbPool.TotalConns(),
				"acquiredConns":dbPool.AcquiredConns(),
				"idleConns":dbPool.IdleConns(),
				"constructingConns":dbPool.ConstructingConns(),
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
			"openModerationReports":openReports,
			"criticalModerationReports":criticalReports,
			"staleUploads":staleUploads,
		},
		"playbackOriginCurrentHour":map[string]any{
			"success":playbackSuccess,
			"errors":playbackErrors,
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
