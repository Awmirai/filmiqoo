package telegramlistener

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/Awmirai/filmiqoo/backend/internal/config"
)

type update struct {
	UpdateID          int64    `json:"update_id"`
	ChannelPost       *message `json:"channel_post"`
	EditedChannelPost *message `json:"edited_channel_post"`
}
type message struct {
	MessageID int64  `json:"message_id"`
	Chat      chat   `json:"chat"`
	Video     *file  `json:"video"`
	Document  *file  `json:"document"`
	Caption   string `json:"caption"`
}
type chat struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
}
type file struct {
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id"`
	FileName     string `json:"file_name"`
	FileSize     int64  `json:"file_size"`
	MimeType     string `json:"mime_type"`
}
type updatesResponse struct {
	OK     bool     `json:"ok"`
	Result []update `json:"result"`
}

func Run(ctx context.Context, cfg config.Config) {
	token := strings.TrimSpace(cfg.TelegramBotToken)
	if token == "" {
		log.Printf("telegram listener disabled: TELEGRAM_BOT_TOKEN is empty")
		return
	}
	source := strings.TrimPrefix(strings.ToLower(strings.TrimSpace(cfg.TelegramSourceChannel)), "@")
	client := &http.Client{Timeout: 40 * time.Second}
	run(ctx, cfg, client, "https://api.telegram.org/bot"+token+"/getUpdates", source, 3*time.Second)
}

func run(ctx context.Context, cfg config.Config, client *http.Client, updatesURL, source string, retryDelay time.Duration) {
	var offset int64
	for ctx.Err() == nil {
		endpoint := fmt.Sprintf("%s?timeout=30&allowed_updates=%%5B%%22channel_post%%22,%%22edited_channel_post%%22%%5D&offset=%d", updatesURL, offset)
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		res, err := client.Do(req)
		if err != nil {
			// Transport errors include the bot token in the URL; never log them verbatim.
			if ctx.Err() == nil {
				log.Printf("telegram listener: updates transport failed")
			}
			if !waitRetry(ctx, retryDelay) {
				return
			}
			continue
		}
		raw, readErr := io.ReadAll(io.LimitReader(res.Body, 2<<20))
		res.Body.Close()
		var batch updatesResponse
		if res.StatusCode != 200 || readErr != nil || json.Unmarshal(raw, &batch) != nil || !batch.OK {
			log.Printf("telegram listener: updates rejected status=%d", res.StatusCode)
			if !waitRetry(ctx, retryDelay) {
				return
			}
			continue
		}
		for _, u := range batch.Result {
			if u.UpdateID < offset {
				continue
			}
			// Telegram acknowledges every update below the next requested offset.
			// Never advance it until our durable ingest endpoint has accepted this post.
			for !deliver(ctx, cfg, client, source, u) {
				if !waitRetry(ctx, retryDelay) {
					return
				}
			}
			offset = u.UpdateID + 1
		}
	}
}

func waitRetry(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func deliver(ctx context.Context, cfg config.Config, client *http.Client, source string, u update) bool {
	m := u.ChannelPost
	if m == nil {
		m = u.EditedChannelPost
	}
	if m == nil {
		return true
	}
	if source != "" && strings.ToLower(m.Chat.Username) != source {
		return true
	}
	media := m.Document
	if media == nil {
		media = m.Video
	}
	if media == nil {
		return true
	}
	name := strings.TrimSpace(media.FileName)
	if name == "" {
		name = fmt.Sprintf("telegram_%d.mp4", m.MessageID)
	}
	payload := map[string]any{"chatId": m.Chat.ID, "messageId": m.MessageID, "fileId": media.FileID, "fileUniqueId": media.FileUniqueID, "fileName": name, "fileSizeBytes": media.FileSize, "mimeType": media.MimeType, "caption": m.Caption}
	body, _ := json.Marshal(payload)
	ingestURL := strings.TrimRight(cfg.PublicAPIBaseURL, "/") + "/internal/telegram/ingest"
	ir, err := http.NewRequestWithContext(ctx, http.MethodPost, ingestURL, bytes.NewReader(body))
	if err != nil {
		return false
	}
	ir.Header.Set("Content-Type", "application/json")
	ir.Header.Set("X-Filmiqoo-Ingest-Secret", cfg.TelegramIngestSecret)
	rr, err := client.Do(ir)
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("telegram ingest transport failed message=%d", m.MessageID)
		}
		return false
	}
	io.Copy(io.Discard, io.LimitReader(rr.Body, 1<<20))
	rr.Body.Close()
	if rr.StatusCode < 200 || rr.StatusCode >= 300 {
		log.Printf("telegram ingest rejected message=%d status=%d", m.MessageID, rr.StatusCode)
		return false
	}
	return true
}
