package config

import "testing"

func TestRecoveryConfiguration(t *testing.T) {
	good := Config{SMTPHost: "smtp.example.com", SMTPPort: 587, SMTPFrom: "Filmiqoo <security@example.com>", SMTPTLSMode: "starttls"}
	if err := good.validateRecoveryEmail(); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*Config){
		func(c *Config) { c.SMTPHost = "" }, func(c *Config) { c.SMTPPort = 0 }, func(c *Config) { c.SMTPHost = "https://smtp.example.com" },
		func(c *Config) { c.SMTPFrom = "security@example.com\r\nBcc: attacker@example.com" }, func(c *Config) { c.SMTPTLSMode = "none" },
		func(c *Config) { c.SMTPUsername = "user" }, func(c *Config) { c.SMTPPassword = "password" },
	} {
		c := good
		change(&c)
		if c.validateRecoveryEmail() == nil {
			t.Fatal("unsafe SMTP configuration accepted")
		}
	}
	c := strongProductionConfig()
	c.SMTPHost = ""
	c.SMTPFrom = ""
	if c.Validate() == nil {
		t.Fatal("production must configure recovery mail")
	}
}
