package server

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"

	"github.com/Awmirai/filmiqoo/backend/internal/ingest"
)

type telegramIngestRequest struct {
	ChatID int64 `json:"chatId"`
	MessageID int64 `json:"messageId"`
	FileID string `json:"fileId"`
	FileNumericID int64 `json:"fileNumericId"`
	FileName string `json:"fileName"`
	FileSizeBytes int64 `json:"fileSizeBytes"`
	MimeType string `json:"mimeType"`
	Caption string `json:"caption"`
	StreamHash string `json:"streamHash"`
}

func (s *Server) telegramIngest(w http.ResponseWriter, r *http.Request) {
	if !s.validIngestSecret(r.Header.Get("X-Filmiqoo-Ingest-Secret")) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid ingest secret"})
		return
	}

	var body telegramIngestRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	if body.ChatID == 0 || body.MessageID == 0 || strings.TrimSpace(body.FileName) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error":"chatId, messageId and fileName are required"})
		return
	}

	parsed := ingest.ParseFileName(body.FileName)

	var id string
	err := s.db.QueryRow(r.Context(),
		`INSERT INTO telegram_ingest_items (
			telegram_chat_id,telegram_message_id,telegram_file_id,telegram_file_numeric_id,file_name,file_size_bytes,
			mime_type,caption,stream_hash,parsed_kind,parsed_title,parsed_season,parsed_episode,
			parsed_year,parsed_quality,parsed_source,parsed_codec,updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,now())
		ON CONFLICT (telegram_chat_id,telegram_message_id)
		DO UPDATE SET
			telegram_file_id=EXCLUDED.telegram_file_id,
			telegram_file_numeric_id=EXCLUDED.telegram_file_numeric_id,
			file_name=EXCLUDED.file_name,
			file_size_bytes=EXCLUDED.file_size_bytes,
			mime_type=EXCLUDED.mime_type,
			caption=EXCLUDED.caption,
			stream_hash=EXCLUDED.stream_hash,
			parsed_kind=EXCLUDED.parsed_kind,
			parsed_title=EXCLUDED.parsed_title,
			parsed_season=EXCLUDED.parsed_season,
			parsed_episode=EXCLUDED.parsed_episode,
			parsed_year=EXCLUDED.parsed_year,
			parsed_quality=EXCLUDED.parsed_quality,
			parsed_source=EXCLUDED.parsed_source,
			parsed_codec=EXCLUDED.parsed_codec,
			updated_at=now()
		RETURNING id::text`,
		body.ChatID,body.MessageID,body.FileID,body.FileNumericID,body.FileName,body.FileSizeBytes,
		body.MimeType,body.Caption,body.StreamHash,parsed.Kind,parsed.Title,parsed.Season,
		parsed.Episode,parsed.Year,parsed.Quality,parsed.Source,parsed.Codec,
	).Scan(&id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	status := "pending_metadata"
	if s.tmdb != nil && s.tmdb.Enabled() {
		if err := s.retryTelegramResolve(r.Context(), id); err == nil {
			status = "ready"
		} else {
			status = "failed"
		}
	}

	writeJSON(w, http.StatusAccepted, map[string]any{
		"ingestId":id,
		"status":status,
		"parsed":parsed,
	})
}

func (s *Server) pendingTelegramIngest(w http.ResponseWriter, r *http.Request) {
	if !s.validIngestSecret(r.Header.Get("X-Filmiqoo-Ingest-Secret")) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid ingest secret"})
		return
	}

	rows, err := s.db.Query(r.Context(),
		`SELECT id::text,telegram_chat_id,telegram_message_id,file_name,parsed_kind,parsed_title,
		        parsed_season,parsed_episode,parsed_year,parsed_quality,parsed_source,parsed_codec,
		        stream_hash,status,error_text,received_at
		   FROM telegram_ingest_items
		  WHERE status IN ('pending_metadata','failed')
		  ORDER BY received_at ASC
		  LIMIT 100`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	defer rows.Close()

	items := make([]map[string]any,0)
	for rows.Next() {
		var id,fileName,kind,title,quality,source,codec,streamHash,status,errorText string
		var chatID,messageID int64
		var season,episode,year *int
		var receivedAt any
		if err := rows.Scan(&id,&chatID,&messageID,&fileName,&kind,&title,&season,&episode,&year,
			&quality,&source,&codec,&streamHash,&status,&errorText,&receivedAt); err != nil {
			writeError(w,http.StatusInternalServerError,err)
			return
		}
		items=append(items,map[string]any{
			"id":id,"chatId":chatID,"messageId":messageID,"fileName":fileName,
			"kind":kind,"title":title,"season":season,"episode":episode,"year":year,
			"quality":quality,"source":source,"codec":codec,"hasStreamHash":streamHash!="",
			"status":status,"error":errorText,"receivedAt":receivedAt,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) validIngestSecret(value string) bool {
	expected := strings.TrimSpace(s.cfg.TelegramIngestSecret)
	return expected != "" && value == expected
}


func (s *Server) resolveTelegramIngestNow(w http.ResponseWriter, r *http.Request) {
	if !s.validIngestSecret(r.Header.Get("X-Filmiqoo-Ingest-Secret")) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid ingest secret"})
		return
	}
	id := strings.TrimSpace(chi.URLParam(r,"id"))
	if id=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"ingest id is required"})
		return
	}
	if s.tmdb==nil || !s.tmdb.Enabled() {
		writeJSON(w,http.StatusServiceUnavailable,map[string]string{"error":"TMDB token is not configured on backend"})
		return
	}
	if err:=s.retryTelegramResolve(r.Context(),id); err!=nil {
		writeJSON(w,http.StatusUnprocessableEntity,map[string]string{"error":err.Error()})
		return
	}
	writeJSON(w,http.StatusOK,map[string]any{"id":id,"status":"ready"})
}
