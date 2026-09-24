package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

func (s *Server) telemetryEvent(w http.ResponseWriter,r *http.Request) {
	var body struct {
		DeviceID string `json:"deviceId"`
		SessionID string `json:"sessionId"`
		EventType string `json:"eventType"`
		Severity string `json:"severity"`
		Message string `json:"message"`
		StackTrace string `json:"stackTrace"`
		Metadata map[string]any `json:"metadata"`
		AppVersion string `json:"appVersion"`
		Platform string `json:"platform"`
	}
	decoder:=json.NewDecoder(r.Body)
	decoder.UseNumber()
	if err:=decoder.Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}

	body.DeviceID=truncateTelemetry(body.DeviceID,160)
	body.SessionID=truncateTelemetry(body.SessionID,160)
	body.EventType=strings.ToLower(truncateTelemetry(body.EventType,80))
	body.Severity=strings.ToLower(truncateTelemetry(body.Severity,16))
	body.Message=truncateTelemetry(body.Message,2000)
	body.StackTrace=truncateTelemetry(body.StackTrace,12000)
	body.AppVersion=truncateTelemetry(body.AppVersion,80)
	body.Platform=strings.ToLower(truncateTelemetry(body.Platform,32))

	if body.EventType=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"eventType is required"})
		return
	}
	switch body.Severity {
	case "debug","info","warning","error","fatal":
	default:
		body.Severity="info"
	}
	if body.Platform=="" { body.Platform="android" }
	if body.Metadata==nil { body.Metadata=map[string]any{} }

	metadataRaw,err:=json.Marshal(body.Metadata)
	if err!=nil || len(metadataRaw)>32*1024 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"telemetry metadata is too large"})
		return
	}

	fingerprint:=telemetryFingerprint(
		body.EventType,
		body.Message,
		body.StackTrace,
	)
	var id string
	err=s.db.QueryRow(r.Context(),`
		INSERT INTO app_telemetry_events (
			device_id,session_id,event_type,severity,message,stack_trace,
			metadata,app_version,platform,fingerprint
		) VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb,$8,$9,$10)
		RETURNING id::text
	`,
		body.DeviceID,
		body.SessionID,
		body.EventType,
		body.Severity,
		body.Message,
		body.StackTrace,
		string(metadataRaw),
		body.AppVersion,
		body.Platform,
		fingerprint,
	).Scan(&id)
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	writeJSON(w,http.StatusAccepted,map[string]any{
		"id":id,
		"accepted":true,
		"fingerprint":fingerprint,
	})
}

func truncateTelemetry(value string,max int) string {
	value=strings.TrimSpace(value)
	if max<=0 { return "" }
	runes:=[]rune(value)
	if len(runes)>max { return string(runes[:max]) }
	return value
}

func telemetryFingerprint(eventType,message,stack string) string {
	stackLines:=strings.Split(stack,"\n")
	if len(stackLines)>8 { stackLines=stackLines[:8] }
	base:=strings.ToLower(strings.TrimSpace(eventType))+"\n"+
		strings.TrimSpace(message)+"\n"+
		strings.Join(stackLines,"\n")
	sum:=sha256.Sum256([]byte(base))
	return hex.EncodeToString(sum[:12])
}

func (s *Server) pruneTelemetry(ctx context.Context) error {
	days:=s.cfg.TelemetryRetentionDays
	if days<=0 { days=30 }
	_,err:=s.db.Exec(ctx,`
		DELETE FROM app_telemetry_events
		 WHERE created_at<now()-($1::text || ' days')::interval
	`,days)
	return err
}

func (s *Server) runTelemetryMaintenanceWorker(ctx context.Context) {
	timer:=time.NewTimer(2*time.Minute)
	defer timer.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			runCtx,cancel:=context.WithTimeout(ctx,30*time.Second)
			_ = s.pruneTelemetry(runCtx)
			cancel()
			timer.Reset(24*time.Hour)
		}
	}
}
