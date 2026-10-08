// Package geoaccess implements country-of-connection access, not proof of residence.
// Data is derived from DB-IP IP-to-Country Lite, CC BY 4.0: https://db-ip.com.
package geoaccess

import (
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"sort"
	"strings"
	"time"
)

const MaxAge = 62 * 24 * time.Hour

type Range struct {
	Start string `json:"start"`
	End   string `json:"end"`
}
type Dataset struct {
	Country      string  `json:"country"`
	Published    string  `json:"published"`
	Source       string  `json:"source"`
	SourceSHA256 string  `json:"sourceSha256"`
	Ranges       []Range `json:"ranges"`
}
type interval struct {
	start netip.Addr
	end   netip.Addr
}
type Country struct {
	published time.Time
	ranges    []interval
}

func Load(reader io.Reader, now time.Time) (*Country, error) {
	var data Dataset
	decoder := json.NewDecoder(io.LimitReader(reader, 8*1024*1024))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&data); err != nil {
		return nil, err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return nil, errors.New("unexpected country data after JSON")
	}
	if data.Country != "IR" || len(data.Ranges) == 0 || len(data.Ranges) > 100000 {
		return nil, errors.New("invalid Iran country dataset")
	}
	if !strings.HasPrefix(data.Source, "https://download.db-ip.com/free/dbip-country-lite-") {
		return nil, errors.New("unknown country data source")
	}
	hash, err := hex.DecodeString(data.SourceSHA256)
	if err != nil || len(hash) != 32 {
		return nil, errors.New("missing country source checksum")
	}
	published, err := time.Parse("2006-01-02", data.Published)
	if err != nil || published.After(now.Add(24*time.Hour)) || now.Sub(published) > MaxAge {
		return nil, errors.New("country data is stale or invalid")
	}
	c := &Country{published: published}
	for _, entry := range data.Ranges {
		start, e1 := netip.ParseAddr(entry.Start)
		end, e2 := netip.ParseAddr(entry.End)
		start, end = start.Unmap(), end.Unmap()
		if e1 != nil || e2 != nil || start.BitLen() != end.BitLen() || start.Compare(end) > 0 ||
			!publicAddress(start) || !publicAddress(end) {
			return nil, errors.New("invalid country address range")
		}
		c.ranges = append(c.ranges, interval{start, end})
	}
	sort.Slice(c.ranges, func(i, j int) bool { return c.ranges[i].start.Less(c.ranges[j].start) })
	for i := 1; i < len(c.ranges); i++ {
		if c.ranges[i-1].end.Compare(c.ranges[i].start) >= 0 {
			return nil, errors.New("overlapping country ranges")
		}
	}
	return c, nil
}

func (c *Country) Fresh(now time.Time) bool {
	return c != nil && now.Sub(c.published) <= MaxAge && !c.published.After(now.Add(24*time.Hour))
}
func (c *Country) Allows(ip netip.Addr, now time.Time) bool {
	ip = ip.Unmap()
	if !c.Fresh(now) || !publicAddress(ip) {
		return false
	}
	i := sort.Search(len(c.ranges), func(i int) bool { return c.ranges[i].start.Compare(ip) > 0 }) - 1
	return i >= 0 && c.ranges[i].start.BitLen() == ip.BitLen() && c.ranges[i].end.Compare(ip) >= 0
}
func publicAddress(ip netip.Addr) bool {
	return ip.IsValid() && ip.IsGlobalUnicast() && !ip.IsPrivate() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() && ip.Zone() == ""
}

// ClientIP must run before RealIP middleware. Public forwarded/country headers are never trusted.
// The edge must overwrite BOTH private headers and the origin must not expose a public port.
func ClientIP(r *http.Request, edgeSecret string) (netip.Addr, error) {
	peer, err := parsePeer(r.RemoteAddr)
	if err != nil {
		return netip.Addr{}, err
	}
	supplied := r.Header.Get("X-Filmiqoo-Edge-Secret")
	if len(edgeSecret) >= 32 && len(supplied) == len(edgeSecret) &&
		subtle.ConstantTimeCompare([]byte(supplied), []byte(edgeSecret)) == 1 {
		raw := strings.TrimSpace(r.Header.Get("X-Filmiqoo-Client-IP"))
		if raw == "" || strings.ContainsAny(raw, ", \t\r\n") {
			return netip.Addr{}, errors.New("invalid edge client address")
		}
		ip, err := netip.ParseAddr(raw)
		if err != nil || ip.Zone() != "" {
			return netip.Addr{}, errors.New("invalid edge client address")
		}
		return ip.Unmap(), nil
	}
	return peer, nil
}
func parsePeer(raw string) (netip.Addr, error) {
	host, _, err := net.SplitHostPort(raw)
	if err != nil {
		host = raw
	}
	ip, err := netip.ParseAddr(host)
	if err != nil || ip.Zone() != "" {
		return netip.Addr{}, errors.New("invalid peer address")
	}
	return ip.Unmap(), nil
}
