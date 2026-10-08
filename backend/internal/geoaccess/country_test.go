package geoaccess

import (
	"encoding/json"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func fixture(now time.Time) string {
	data := Dataset{Country: "IR", Published: now.Format("2006-01-02"), Source: "https://download.db-ip.com/free/dbip-country-lite-2026-10.csv.gz",
		SourceSHA256: strings.Repeat("a", 64), Ranges: []Range{{"5.112.0.0", "5.112.0.255"}, {"2a01:5ec0::", "2a01:5ec0::ffff"}}}
	raw, _ := json.Marshal(data)
	return string(raw)
}
func TestCountryBoundaries(t *testing.T) {
	now := time.Date(2026, 10, 4, 12, 0, 0, 0, time.UTC)
	c, err := Load(strings.NewReader(fixture(now)), now)
	if err != nil {
		t.Fatal(err)
	}
	tests := map[string]bool{
		"5.112.0.0": true, "5.112.0.255": true, "5.112.1.0": false, "5.111.255.255": false,
		"8.8.8.8": false, "127.0.0.1": false, "10.0.0.1": false, "::1": false,
		"2a01:5ec0::1": true, "2a01:5ec0::ffff": true, "2a01:5ec0::1:0": false,
		"2606:4700::1111": false, "::ffff:5.112.0.10": true,
	}
	for ip, want := range tests {
		if got := c.Allows(netip.MustParseAddr(ip), now); got != want {
			t.Errorf("%s: got %v want %v", ip, got, want)
		}
	}
	if c.Allows(netip.MustParseAddr("5.112.0.10"), now.Add(MaxAge+time.Hour)) {
		t.Fatal("stale data must deny")
	}
	var absent *Country
	if absent.Allows(netip.MustParseAddr("5.112.0.1"), now) {
		t.Fatal("nil dataset allowed")
	}
}
func TestInvalidCountryData(t *testing.T) {
	now := time.Now().UTC()
	for _, raw := range []string{"", "{}", "null", fixture(now) + "{}", strings.Replace(fixture(now), "\"IR\"", "\"US\"", 1),
		strings.Replace(fixture(now), "5.112.0.0", "0.0.0.0", 1), fixture(now.Add(-90 * 24 * time.Hour)),
		strings.Replace(fixture(now), strings.Repeat("a", 64), "no-checksum", 1)} {
		if _, err := Load(strings.NewReader(raw), now); err == nil {
			t.Errorf("accepted invalid data %s", raw)
		}
	}
}
func TestClientIPIgnoresForgedHeaders(t *testing.T) {
	req := httptest.NewRequest("GET", "/v1/access", nil)
	req.RemoteAddr = "8.8.8.8:5000"
	req.Header.Set("X-Forwarded-For", "5.112.0.1")
	req.Header.Set("X-Real-IP", "5.112.0.1")
	req.Header.Set("CF-IPCountry", "IR")
	req.Header.Set("X-Filmiqoo-Client-IP", "5.112.0.1")
	secret := strings.Repeat("s", 64)
	for _, forged := range []string{"", "wrong", strings.Repeat("x", 64)} {
		req.Header.Set("X-Filmiqoo-Edge-Secret", forged)
		ip, err := ClientIP(req, secret)
		if err != nil || ip.String() != "8.8.8.8" {
			t.Fatalf("forged headers trusted: %v %v", ip, err)
		}
	}
	req.Header.Set("X-Filmiqoo-Edge-Secret", secret)
	ip, err := ClientIP(req, secret)
	if err != nil || ip.String() != "5.112.0.1" {
		t.Fatal("valid edge assertion rejected")
	}
	req.Header.Set("X-Filmiqoo-Client-IP", "5.112.0.1,8.8.8.8")
	if _, err := ClientIP(req, secret); err == nil {
		t.Fatal("multiple client addresses accepted")
	}
	req.Header.Set("X-Filmiqoo-Client-IP", "::ffff:5.112.0.1")
	ip, err = ClientIP(req, secret)
	if err != nil || ip.String() != "5.112.0.1" {
		t.Fatal("mapped IPv4 not normalized")
	}
}
