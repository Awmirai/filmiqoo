package auth

import "testing"

func TestPasswordRoundTrip(t *testing.T) {
	hash,err:=HashPassword("A-strong-passphrase-123")
	if err!=nil { t.Fatal(err) }
	if !VerifyPassword("A-strong-passphrase-123",hash) { t.Fatal("valid password rejected") }
	if VerifyPassword("wrong-password",hash) { t.Fatal("invalid password accepted") }
}
