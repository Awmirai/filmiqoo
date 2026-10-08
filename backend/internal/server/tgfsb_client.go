package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

type tgfsbFileMetadata struct {
	MessageID int64  `json:"messageId"`
	FileName  string `json:"fileName"`
	FileSize  int64  `json:"fileSize"`
	MimeType  string `json:"mimeType"`
	FileID    int64  `json:"fileId"`
	Hash      string `json:"hash"`
}

func (s *Server) tgfsbFileMetadata(
	ctx context.Context,
	messageID int64,
) (tgfsbFileMetadata, error) {
	var out tgfsbFileMetadata

	base := strings.TrimSpace(s.cfg.TelegramStreamBaseURL)
	secret := strings.TrimSpace(s.cfg.TelegramStreamInternalSecret)

	if base == "" {
		return out, errors.New("telegram stream base URL is not configured")
	}
	if secret == "" {
		return out, errors.New("telegram stream internal secret is not configured")
	}
	if messageID <= 0 {
		return out, errors.New("invalid telegram message id")
	}

	endpoint := strings.TrimRight(base, "/") +
		"/internal/filmiqoo/file/" +
		url.PathEscape(strconv.FormatInt(messageID, 10))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return out, err
	}

	req.Header.Set("X-Filmiqoo-Internal-Secret", secret)

	resp, err := s.upstreamClient.Do(req)
	if err != nil {
		return out, fmt.Errorf("telegram stream metadata request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return out, fmt.Errorf(
			"telegram stream metadata returned HTTP %d",
			resp.StatusCode,
		)
	}

	dec := json.NewDecoder(io.LimitReader(resp.Body, 64*1024))
	if err := dec.Decode(&out); err != nil {
		return out, fmt.Errorf("decode telegram stream metadata: %w", err)
	}

	out.FileName = strings.TrimSpace(out.FileName)
	out.MimeType = strings.TrimSpace(out.MimeType)
	out.Hash = strings.TrimSpace(out.Hash)

	if out.MessageID != messageID {
		return out, errors.New("telegram stream metadata message id mismatch")
	}
	if out.FileID == 0 {
		return out, errors.New("telegram stream metadata missing file id")
	}
	if out.FileName == "" {
		return out, errors.New("telegram stream metadata missing file name")
	}
	if out.FileSize <= 0 {
		return out, errors.New("telegram stream metadata missing file size")
	}
	if out.Hash == "" {
		return out, errors.New("telegram stream metadata missing hash")
	}

	return out, nil
}
