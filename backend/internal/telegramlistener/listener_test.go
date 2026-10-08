package telegramlistener

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"

	"github.com/Awmirai/filmiqoo/backend/internal/config"
)

func TestFailedDeliveryIsRetriedBeforeTelegramAcknowledgement(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var offsets []string
	var delivered []int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/updates":
			offsets = append(offsets, r.URL.Query().Get("offset"))
			if len(offsets) == 2 {
				cancel()
				return
			}
			json.NewEncoder(w).Encode(map[string]any{"ok": true, "result": []update{
				{UpdateID: 100, ChannelPost: &message{MessageID: 21, Chat: chat{ID: 1, Username: "filmiqq1"}, Document: &file{FileName: "Union.County.2026.mp4"}}},
				{UpdateID: 101, EditedChannelPost: &message{MessageID: 22, Chat: chat{ID: 1, Username: "filmiqq1"}, Video: &file{FileName: "Next.mp4"}}},
			}})
		case "/internal/telegram/ingest":
			if r.Header.Get("X-Filmiqoo-Ingest-Secret") != "test-secret" {
				t.Error("missing ingest authentication")
			}
			var p struct {
				MessageID int64 `json:"messageId"`
			}
			json.NewDecoder(r.Body).Decode(&p)
			delivered = append(delivered, p.MessageID)
			if len(delivered) == 1 {
				w.WriteHeader(503)
			} else {
				w.WriteHeader(202)
			}
		default:
			t.Errorf("wrong endpoint: %s", r.URL.Path)
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()
	run(ctx, config.Config{PublicAPIBaseURL: srv.URL, TelegramIngestSecret: "test-secret"}, srv.Client(), srv.URL+"/updates", "filmiqq1", time.Millisecond)
	if !reflect.DeepEqual(offsets, []string{"0", "102"}) {
		t.Fatalf("offsets=%v", offsets)
	}
	if !reflect.DeepEqual(delivered, []int64{21, 21, 22}) {
		t.Fatalf("deliveries=%v", delivered)
	}
}

func TestRetryCanBeCancelledWithoutAcknowledging(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/updates" {
			json.NewEncoder(w).Encode(map[string]any{"ok": true, "result": []update{{UpdateID: 100, ChannelPost: &message{MessageID: 21, Chat: chat{Username: "filmiqq1"}, Document: &file{FileName: "Movie.mp4"}}}}})
		} else {
			w.WriteHeader(503)
			cancel()
		}
	}))
	defer srv.Close()
	done := make(chan struct{})
	go func() {
		defer close(done)
		run(ctx, config.Config{PublicAPIBaseURL: srv.URL}, srv.Client(), srv.URL+"/updates", "filmiqq1", time.Hour)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("retry ignored cancellation")
	}
}

func TestUnrelatedChannelAndNonMediaUpdatesAreIgnored(t *testing.T) {
	client := &http.Client{}
	for _, u := range []update{{UpdateID: 1}, {UpdateID: 2, ChannelPost: &message{Chat: chat{Username: "other"}, Document: &file{}}}, {UpdateID: 3, ChannelPost: &message{Chat: chat{Username: "filmiqq1"}}}} {
		if !deliver(context.Background(), config.Config{}, client, "filmiqq1", u) {
			t.Fatalf("unrelated update was retried: %+v", u)
		}
	}
}
