package config

import (
	"errors"
	"net/mail"
	"strings"
)

func (c Config) RecoveryEmailConfigured() bool {
	return strings.TrimSpace(c.SMTPHost) != "" && strings.TrimSpace(c.SMTPFrom) != ""
}

func (c Config) validateRecoveryEmail() error {
	if !c.RecoveryEmailConfigured() {
		if c.IsProduction() || c.SMTPHost != "" || c.SMTPFrom != "" || c.SMTPUsername != "" || c.SMTPPassword != "" {
			return errors.New("SMTP_HOST and SMTP_FROM are required for password recovery")
		}
		return nil
	}
	if strings.ContainsAny(c.SMTPHost, "\r\n /:") || c.SMTPPort < 1 || c.SMTPPort > 65535 {
		return errors.New("invalid SMTP host or port")
	}
	if _, err := mail.ParseAddress(c.SMTPFrom); err != nil || strings.ContainsAny(c.SMTPFrom, "\r\n") {
		return errors.New("SMTP_FROM must be a valid email address")
	}
	if c.SMTPTLSMode != "starttls" && c.SMTPTLSMode != "tls" {
		return errors.New("SMTP_TLS_MODE must be starttls or tls")
	}
	if (c.SMTPUsername == "") != (c.SMTPPassword == "") {
		return errors.New("SMTP_USERNAME and SMTP_PASSWORD must be configured together")
	}
	return nil
}
