package server

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/mail"
	"strings"
	"time"

	authpkg "github.com/Awmirai/filmiqoo/backend/internal/auth"
	"github.com/jackc/pgx/v5"
)

const recoveryMessage = "اگر حسابی با این ایمیل وجود داشته باشد، کد بازیابی برای آن ارسال می‌شود."
const invalidRecoveryCode = "کد بازیابی نامعتبر یا منقضی شده است."

func normalizeRecoveryEmail(value string) (string, bool) {
	value = strings.ToLower(strings.TrimSpace(value))
	address, err := mail.ParseAddress(value)
	return value, err == nil && address.Address == value && len(value) <= 254 && !strings.ContainsAny(value, "\r\n")
}

func newRecoveryCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(100000000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%08d", n.Int64()), nil
}

func recoveryHash(secret, challengeID, code string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte("filmiqoo-password-reset\x00" + challengeID + "\x00" + code))
	return hex.EncodeToString(mac.Sum(nil))
}

func recoveryCipher(secret string) (cipher.AEAD, error) {
	key := sha256.Sum256([]byte("filmiqoo-recovery-delivery\x00" + secret))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func encryptRecovery(secret, id string, value []byte) ([]byte, error) {
	aead, err := recoveryCipher(secret)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return nil, err
	}
	return aead.Seal(nonce, nonce, value, []byte(id)), nil
}

func decryptRecovery(secret, id string, value []byte) ([]byte, error) {
	aead, err := recoveryCipher(secret)
	if err != nil {
		return nil, err
	}
	if len(value) < aead.NonceSize() {
		return nil, errors.New("invalid recovery envelope")
	}
	return aead.Open(nil, value[:aead.NonceSize()], value[aead.NonceSize():], []byte(id))
}

func (s *Server) requestPasswordReset(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.RecoveryEmailConfigured() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "بازیابی رمز در حال حاضر در دسترس نیست. لطفاً بعداً دوباره تلاش کنید."})
		return
	}
	var body struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid email"})
		return
	}
	email, valid := normalizeRecoveryEmail(body.Email)
	if !valid {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid email"})
		return
	}
	// Delivery runs separately. Account existence and resend throttling share one
	// response and minimum duration; no token or email-provider error is returned.
	started := time.Now()
	_, err := s.queuePasswordRecovery(r.Context(), email)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	if wait := 300*time.Millisecond - time.Since(started); wait > 0 {
		timer := time.NewTimer(wait)
		defer timer.Stop()
		select {
		case <-timer.C:
		case <-r.Context().Done():
			return
		}
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"message": recoveryMessage, "retryAfterSeconds": 60, "expiresInSeconds": 900})
}

func (s *Server) queuePasswordRecovery(ctx context.Context, email string) (bool, error) {
	code, err := newRecoveryCode()
	if err != nil {
		return false, err
	}
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	var userID, id string
	err = tx.QueryRow(ctx, "SELECT id::text FROM users WHERE lower(email::text)=$1 AND status='active' FOR UPDATE", email).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	var limited bool
	err = tx.QueryRow(ctx, `SELECT COUNT(*)>=3 OR COALESCE(MAX(created_at)>now()-interval '60 seconds',false)
		FROM password_reset_challenges WHERE user_id=$1 AND created_at>now()-interval '1 hour'`, userID).Scan(&limited)
	if err != nil || limited {
		return false, err
	}
	if err = tx.QueryRow(ctx, "SELECT gen_random_uuid()::text").Scan(&id); err != nil {
		return false, err
	}
	payload, err := json.Marshal(recoveryEmail{To: email, Code: code})
	if err != nil {
		return false, err
	}
	encrypted, err := encryptRecovery(s.cfg.JWTSecret, id, payload)
	if err != nil {
		return false, err
	}
	if _, err = tx.Exec(ctx, "UPDATE password_reset_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL", userID); err != nil {
		return false, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO password_reset_challenges (id,user_id,code_hash,expires_at)
		VALUES ($1,$2,$3,now()+interval '15 minutes')`, id, userID, recoveryHash(s.cfg.JWTSecret, id, code)); err != nil {
		return false, err
	}
	if _, err = tx.Exec(ctx, "INSERT INTO password_reset_delivery (challenge_id,encrypted_payload) VALUES ($1,$2)", id, encrypted); err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}

func (s *Server) resetPassword(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Email    string `json:"email"`
		Code     string `json:"code"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": invalidRecoveryCode})
		return
	}
	email, valid := normalizeRecoveryEmail(body.Email)
	body.Code = strings.TrimSpace(body.Code)
	if !valid || len(body.Code) != 8 || strings.Trim(body.Code, "0123456789") != "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": invalidRecoveryCode})
		return
	}
	if len(body.Password) < 10 || len(body.Password) > 128 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "password must be 10-128 characters"})
		return
	}
	changed, err := s.consumePasswordRecovery(r.Context(), email, body.Code, body.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	if !changed {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": invalidRecoveryCode})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"message": "رمز عبور تغییر کرد. با رمز جدید وارد شوید."})
}

func (s *Server) consumePasswordRecovery(ctx context.Context, email, code, password string) (bool, error) {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	// Lock the user before the challenge, consistently with request/resend.
	var userID, id, expected string
	err = tx.QueryRow(ctx, "SELECT id::text FROM users WHERE lower(email::text)=$1 AND status='active' FOR UPDATE", email).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	err = tx.QueryRow(ctx, `SELECT id::text,code_hash FROM password_reset_challenges
		WHERE user_id=$1 AND consumed_at IS NULL AND expires_at>now() AND attempts<5
		ORDER BY created_at DESC LIMIT 1 FOR UPDATE`, userID).Scan(&id, &expected)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	actual := recoveryHash(s.cfg.JWTSecret, id, code)
	if subtle.ConstantTimeCompare([]byte(actual), []byte(expected)) != 1 {
		if _, err = tx.Exec(ctx, "UPDATE password_reset_challenges SET attempts=attempts+1 WHERE id=$1", id); err != nil {
			return false, err
		}
		return false, tx.Commit(ctx)
	}
	hash, err := authpkg.HashPassword(password)
	if err != nil {
		return false, err
	}
	if _, err = tx.Exec(ctx, "UPDATE users SET password_hash=$2,auth_version=auth_version+1 WHERE id=$1", userID, hash); err != nil {
		return false, err
	}
	if _, err = tx.Exec(ctx, "UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL", userID); err != nil {
		return false, err
	}
	if _, err = tx.Exec(ctx, "UPDATE password_reset_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL", userID); err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}
