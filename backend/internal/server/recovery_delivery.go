package server

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"mime"
	"net"
	"net/mail"
	"net/smtp"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

type recoveryEmail struct {
	To   string `json:"to"`
	Code string `json:"code"`
}

func (s *Server) runRecoveryDeliveryWorker(ctx context.Context) {
	if !s.cfg.RecoveryEmailConfigured() {
		return
	}
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	lastCleanup := time.Time{}
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		if time.Since(lastCleanup) > time.Hour {
			_, _ = s.db.Exec(ctx, "DELETE FROM password_reset_challenges WHERE created_at<now()-interval '2 days'")
			lastCleanup = time.Now()
		}
		for i := 0; i < 10; i++ {
			delivered, err := s.deliverRecoveryEmail(ctx)
			if err != nil {
				log.Print("password recovery delivery will be retried")
				break
			}
			if !delivered {
				break
			}
		}
	}
}

func (s *Server) deliverRecoveryEmail(ctx context.Context) (bool, error) {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	var id string
	var encrypted []byte
	var attempts int
	err = tx.QueryRow(ctx, `SELECT d.challenge_id::text,d.encrypted_payload,d.attempts
		FROM password_reset_delivery d JOIN password_reset_challenges c ON c.id=d.challenge_id
		WHERE d.delivered_at IS NULL AND d.attempts<5 AND d.next_attempt_at<=now()
		AND c.consumed_at IS NULL AND c.expires_at>now()
		ORDER BY d.next_attempt_at LIMIT 1 FOR UPDATE OF d SKIP LOCKED`).Scan(&id, &encrypted, &attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	plain, sendErr := decryptRecovery(s.cfg.JWTSecret, id, encrypted)
	var message recoveryEmail
	if sendErr == nil {
		sendErr = json.Unmarshal(plain, &message)
	}
	if sendErr == nil {
		if s.recoveryMailer != nil {
			sendErr = s.recoveryMailer(ctx, message)
		} else {
			sendErr = s.sendRecoveryEmail(ctx, message)
		}
	}
	if sendErr != nil {
		_, err = tx.Exec(ctx, `UPDATE password_reset_delivery SET attempts=attempts+1,
			next_attempt_at=now()+($2*interval '30 seconds') WHERE challenge_id=$1`, id, attempts+1)
	} else {
		_, err = tx.Exec(ctx, `UPDATE password_reset_delivery SET attempts=attempts+1,
			delivered_at=now(),encrypted_payload=''::bytea WHERE challenge_id=$1`, id)
	}
	if err != nil {
		return false, err
	}
	if err = tx.Commit(ctx); err != nil {
		return false, err
	}
	return sendErr == nil, sendErr
}

func (s *Server) sendRecoveryEmail(ctx context.Context, message recoveryEmail) error {
	from, err := mail.ParseAddress(s.cfg.SMTPFrom)
	if err != nil {
		return err
	}
	to, valid := normalizeRecoveryEmail(message.To)
	if !valid {
		return errors.New("invalid recovery email recipient")
	}
	address := net.JoinHostPort(s.cfg.SMTPHost, strconv.Itoa(s.cfg.SMTPPort))
	dialer := net.Dialer{Timeout: 10 * time.Second}
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return err
	}
	defer conn.Close()
	deadline := time.Now().Add(10 * time.Second)
	if until, ok := ctx.Deadline(); ok && until.Before(deadline) {
		deadline = until
	}
	if err = conn.SetDeadline(deadline); err != nil {
		return err
	}
	tlsConfig := &tls.Config{ServerName: s.cfg.SMTPHost, MinVersion: tls.VersionTLS12}
	if s.cfg.SMTPTLSMode == "tls" {
		secure := tls.Client(conn, tlsConfig)
		if err = secure.HandshakeContext(ctx); err != nil {
			return err
		}
		conn = secure
	}
	client, err := smtp.NewClient(conn, s.cfg.SMTPHost)
	if err != nil {
		return err
	}
	defer client.Close()
	if s.cfg.SMTPTLSMode != "tls" {
		if ok, _ := client.Extension("STARTTLS"); !ok {
			return errors.New("SMTP server must support STARTTLS")
		}
		if err = client.StartTLS(tlsConfig); err != nil {
			return err
		}
	}
	if s.cfg.SMTPUsername != "" {
		if err = client.Auth(smtp.PlainAuth("", s.cfg.SMTPUsername, s.cfg.SMTPPassword, s.cfg.SMTPHost)); err != nil {
			return err
		}
	}
	if err = client.Mail(from.Address); err != nil {
		return err
	}
	if err = client.Rcpt(to); err != nil {
		return err
	}
	writer, err := client.Data()
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(writer, "From: %s\r\nTo: %s\r\nSubject: %s\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n%s\r\n",
		from.String(), to, mime.QEncoding.Encode("utf-8", "کد بازیابی رمز Filmiqoo"),
		"کد بازیابی رمز شما: "+message.Code+"\r\n\r\nاین کد تا ۱۵ دقیقه اعتبار دارد و فقط یک بار قابل استفاده است.\r\nکد را در اختیار هیچ‌کس قرار ندهید. اگر شما این درخواست را ثبت نکرده‌اید، این ایمیل را نادیده بگیرید.\r\n\r\nYour Filmiqoo recovery code: "+message.Code+"\r\nValid for 15 minutes. Never share this code. If you did not request it, ignore this email.")
	if err != nil {
		writer.Close()
		return err
	}
	if err = writer.Close(); err != nil {
		return err
	}
	return client.Quit()
}
