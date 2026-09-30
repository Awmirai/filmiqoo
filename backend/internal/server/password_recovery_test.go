package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	authpkg "github.com/Awmirai/filmiqoo/backend/internal/auth"
	"github.com/Awmirai/filmiqoo/backend/internal/config"
	"github.com/jackc/pgx/v5/pgxpool"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestRecoveryEnvelope(t *testing.T) {
	plain := []byte(`{"code":"01234567"}`)
	encrypted, err := encryptRecovery("secret", "challenge", plain)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(encrypted, plain) {
		t.Fatal("plaintext in envelope")
	}
	got, err := decryptRecovery("secret", "challenge", encrypted)
	if err != nil || !bytes.Equal(got, plain) {
		t.Fatal("round trip failed", err)
	}
	for _, v := range []struct {
		secret, id string
		body       []byte
	}{{"other", "challenge", encrypted}, {"secret", "other", encrypted}, {"secret", "challenge", []byte("short")}} {
		if _, err := decryptRecovery(v.secret, v.id, v.body); err == nil {
			t.Fatal("invalid envelope accepted")
		}
	}
	encrypted[len(encrypted)-1] ^= 1
	if _, err := decryptRecovery("secret", "challenge", encrypted); err == nil {
		t.Fatal("tampering accepted")
	}
	if recoveryHash("secret", "a", "01234567") == recoveryHash("secret", "b", "01234567") {
		t.Fatal("hash not bound to challenge")
	}
	for i := 0; i < 100; i++ {
		code, err := newRecoveryCode()
		if err != nil || len(code) != 8 || strings.Trim(code, "0123456789") != "" {
			t.Fatal("invalid code")
		}
	}
}
func TestRecoveryEmailValidation(t *testing.T) {
	for _, v := range []struct {
		input, want string
		ok          bool
	}{{" User@Example.com ", "user@example.com", true}, {"Person <user@example.com>", "", false}, {"user@example.com\r\nBcc: other@example.com", "", false}, {"invalid", "", false}} {
		got, ok := normalizeRecoveryEmail(v.input)
		if ok != v.ok || ok && got != v.want {
			t.Errorf("%q: got %q %v", v.input, got, ok)
		}
	}
}
func TestPasswordRecoveryE2E(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated PostgreSQL")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{db: db, cfg: config.Config{JWTSecret: "recovery-test-secret", AuthAccessTTLMinutes: 15, SMTPHost: "smtp.example.com", SMTPFrom: "security@example.com"}}
	email := "recovery-" + time.Now().Format("150405.000000000") + "@example.com"
	hash, err := authpkg.HashPassword("old-password-123")
	if err != nil {
		t.Fatal(err)
	}
	var user string
	if err = db.QueryRow(ctx, "INSERT INTO users(email,password_hash,status) VALUES($1,$2,'active') RETURNING id::text", email, hash).Scan(&user); err != nil {
		t.Fatal(err)
	}
	defer db.Exec(context.Background(), "DELETE FROM users WHERE id=$1", user)
	exec := func(sql string, args ...any) {
		t.Helper()
		if _, err := db.Exec(ctx, sql, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec("INSERT INTO auth_sessions(user_id,refresh_token_hash,expires_at) VALUES($1,$2,now()+interval '1 day')", user, "recovery-test-"+user)
	oldToken, _, err := s.issueAccessToken(user)
	if err != nil {
		t.Fatal(err)
	}
	status := func(token string) int {
		r := httptest.NewRequest("GET", "/", nil)
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		s.auth(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })).ServeHTTP(w, r)
		return w.Code
	}
	if got := status(oldToken); got != 204 {
		t.Fatalf("initial token status %d", got)
	}
	request := func(address string) *httptest.ResponseRecorder {
		b, _ := json.Marshal(map[string]string{"email": address})
		w := httptest.NewRecorder()
		s.requestPasswordReset(w, httptest.NewRequest("POST", "/", bytes.NewReader(b)))
		return w
	}
	known, unknown, limited := request(email), request("absent-"+email), request(email)
	if known.Code != 202 || unknown.Code != 202 || limited.Code != 202 || known.Body.String() != unknown.Body.String() || known.Body.String() != limited.Body.String() {
		t.Fatal("response discloses account/cooldown")
	}
	var code string
	s.recoveryMailer = func(_ context.Context, m recoveryEmail) error {
		if m.To != email {
			t.Fatal("wrong recipient")
		}
		code = m.Code
		return errors.New("provider failure")
	}
	if _, err := s.deliverRecoveryEmail(ctx); err == nil {
		t.Fatal("delivery failure hidden")
	}
	if ok, err := s.deliverRecoveryEmail(ctx); ok || err != nil {
		t.Fatal("retry ignored schedule", err)
	}
	exec("UPDATE password_reset_delivery SET next_attempt_at=now() WHERE challenge_id IN (SELECT id FROM password_reset_challenges WHERE user_id=$1)", user)
	s.recoveryMailer = func(_ context.Context, m recoveryEmail) error { code = m.Code; return nil }
	if ok, err := s.deliverRecoveryEmail(ctx); !ok || err != nil {
		t.Fatal("delivery retry failed", err)
	}
	var remaining int
	if err := db.QueryRow(ctx, "SELECT octet_length(encrypted_payload) FROM password_reset_delivery WHERE challenge_id IN (SELECT id FROM password_reset_challenges WHERE user_id=$1)", user).Scan(&remaining); err != nil || remaining != 0 {
		t.Fatal("secret not scrubbed", err)
	}
	if ok, err := s.deliverRecoveryEmail(ctx); ok || err != nil {
		t.Fatal("duplicate delivery", err)
	}
	if ok, err := s.consumePasswordRecovery(ctx, email, code, "new-password-123"); !ok || err != nil {
		t.Fatal("reset failed", err)
	}
	if ok, err := s.consumePasswordRecovery(ctx, email, code, "replayed-password"); ok || err != nil {
		t.Fatal("code reused", err)
	}
	if got := status(oldToken); got != 401 {
		t.Fatalf("old token survived reset: %d", got)
	}
	newToken, _, err := s.issueAccessToken(user, 1)
	if err != nil {
		t.Fatal(err)
	}
	if got := status(newToken); got != 204 {
		t.Fatalf("new token rejected %d", got)
	}
	var active int
	if err := db.QueryRow(ctx, "SELECT count(*) FROM auth_sessions WHERE user_id=$1 AND revoked_at IS NULL", user).Scan(&active); err != nil || active != 0 {
		t.Fatal("refresh sessions survived reset", err)
	}
	if err := db.QueryRow(ctx, "SELECT password_hash FROM users WHERE id=$1", user).Scan(&hash); err != nil {
		t.Fatal(err)
	}
	if !authpkg.VerifyPassword("new-password-123", hash) || authpkg.VerifyPassword("old-password-123", hash) {
		t.Fatal("password did not change")
	}
	fresh := func() string {
		t.Helper()
		exec("DELETE FROM password_reset_challenges WHERE user_id=$1", user)
		if ok, err := s.queuePasswordRecovery(ctx, email); !ok || err != nil {
			t.Fatal("queue failed", err)
		}
		if ok, err := s.deliverRecoveryEmail(ctx); !ok || err != nil {
			t.Fatal("delivery failed", err)
		}
		return code
	}
	t.Run("attempt limit", func(t *testing.T) {
		correct := fresh()
		wrong := "00000000"
		if correct == wrong {
			wrong = "11111111"
		}
		for i := 0; i < 5; i++ {
			if ok, err := s.consumePasswordRecovery(ctx, email, wrong, "new-password-456"); ok || err != nil {
				t.Fatal("wrong code accepted", err)
			}
		}
		if ok, err := s.consumePasswordRecovery(ctx, email, correct, "new-password-456"); ok || err != nil {
			t.Fatal("attempt limit bypassed", err)
		}
	})
	t.Run("expiry", func(t *testing.T) {
		correct := fresh()
		exec("UPDATE password_reset_challenges SET expires_at=now()-interval '1 second' WHERE user_id=$1", user)
		if ok, err := s.consumePasswordRecovery(ctx, email, correct, "new-password-456"); ok || err != nil {
			t.Fatal("expired code accepted", err)
		}
	})
	t.Run("resend and hourly limit", func(t *testing.T) {
		correct := fresh()
		exec("UPDATE password_reset_challenges SET created_at=now()-interval '61 seconds' WHERE user_id=$1", user)
		if ok, err := s.queuePasswordRecovery(ctx, email); !ok || err != nil {
			t.Fatal("resend failed", err)
		}
		if ok, err := s.consumePasswordRecovery(ctx, email, correct, "new-password-456"); ok || err != nil {
			t.Fatal("previous code accepted", err)
		}
		exec("UPDATE password_reset_challenges SET created_at=now()-interval '61 seconds' WHERE user_id=$1", user)
		if ok, err := s.queuePasswordRecovery(ctx, email); !ok || err != nil {
			t.Fatal(err)
		}
		exec("UPDATE password_reset_challenges SET created_at=now()-interval '61 seconds' WHERE user_id=$1", user)
		if ok, err := s.queuePasswordRecovery(ctx, email); ok || err != nil {
			t.Fatal("hourly limit bypassed", err)
		}
	})
}
