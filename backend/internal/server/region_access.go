package server

import (
    "net/http"
    "os"
    "strconv"
    "strings"
    "time"

    "github.com/Awmirai/filmiqoo/backend/internal/geoaccess"
)

type regionAccessPolicy struct {
    enabled bool
    dataset *geoaccess.Country
    edgeSecret string
}

func loadRegionAccess(environment string) *regionAccessPolicy {
    production := strings.EqualFold(environment, "production") || strings.EqualFold(environment, "prod")
    enabled := production
    if raw, ok := os.LookupEnv("IRAN_ONLY_ENABLED"); ok {
        value, err := strconv.ParseBool(raw)
        if err == nil { enabled = value } else { enabled = true }
    }
    policy := &regionAccessPolicy{enabled: enabled, edgeSecret: strings.TrimSpace(os.Getenv("GEO_PROXY_HEADER_SECRET"))}
    if !enabled { return policy }
    path := strings.TrimSpace(os.Getenv("GEO_COUNTRY_DATA_PATH"))
    if path == "" { path = "/geo/iran-country.json" }
    file, err := os.Open(path)
    if err != nil { return policy }
    defer file.Close()
    dataset, err := geoaccess.Load(file, time.Now().UTC())
    if err == nil { policy.dataset = dataset }
    return policy
}

func (p *regionAccessPolicy) ready() bool {
    return p == nil || !p.enabled || (len(p.edgeSecret) >= 32 && p.dataset.Fresh(time.Now().UTC()))
}

// Run before middleware.RealIP. Never base authorization on spoofable forwarding/country headers.
func (s *Server) enforceRegion(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        policy := s.regionAccess
        if !strings.HasPrefix(r.URL.Path, "/v1/") || r.URL.Path == "/v1/access" || policy == nil || !policy.enabled {
            next.ServeHTTP(w, r)
            return
        }
        if !policy.ready() {
            regionReply(w, http.StatusServiceUnavailable, "REGION_UNAVAILABLE", "سرویس بررسی محدودهٔ دسترسی آماده نیست. کمی بعد دوباره تلاش کن.", false, "", true)
            return
        }
        ip, err := geoaccess.ClientIP(r, policy.edgeSecret)
        if err != nil || !policy.dataset.Allows(ip, time.Now().UTC()) {
            regionReply(w, http.StatusForbidden, "IRAN_ONLY", "فیلمیکو فقط با اتصال اینترنت داخل ایران در دسترس است.", false, "", true)
            return
        }
        next.ServeHTTP(w, r)
    })
}

// Executed before RealIP so /access and protected requests evaluate the same actual peer.
func (s *Server) regionStatusMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if r.URL.Path != "/v1/access" { next.ServeHTTP(w, r); return }
        if r.Method != http.MethodGet && r.Method != http.MethodHead {
            w.Header().Set("Allow", "GET, HEAD"); w.WriteHeader(http.StatusMethodNotAllowed); return
        }
        policy := s.regionAccess
        if policy == nil || !policy.enabled {
            regionReply(w, http.StatusServiceUnavailable, "REGION_NOT_ENFORCED", "محدودیت منطقه‌ای هنوز روی سرور فعال نشده است.", false, "", false)
            return
        }
        if !policy.ready() {
            regionReply(w, http.StatusServiceUnavailable, "REGION_UNAVAILABLE", "پیکربندی محدودهٔ دسترسی سرور کامل نیست.", false, "", true)
            return
        }
        ip, err := geoaccess.ClientIP(r, policy.edgeSecret)
        if err != nil || !policy.dataset.Allows(ip, time.Now().UTC()) {
            regionReply(w, http.StatusForbidden, "IRAN_ONLY", "این سرویس برای اتصال اینترنت داخل ایران ارائه می‌شود. VPN یا پراکسی خارج ایران می‌تواند باعث مسدود شدن دسترسی شود.", false, "", true)
            return
        }
        regionReply(w, http.StatusOK, "ALLOWED", "دسترسی مجاز است.", true, "IR", true)
    })
}

func regionReply(w http.ResponseWriter, status int, code, message string, allowed bool, country string, enforced bool) {
    w.Header().Set("Cache-Control", "private, no-store, max-age=0")
    w.Header().Set("Pragma", "no-cache")
    w.Header().Set("Vary", "*")
    w.Header().Set("X-Filmiqoo-Region-Policy", "iran-only-v1")
    writeJSON(w, status, map[string]any{
        "allowed": allowed, "country": country, "policy": "iran-only-v1", "enforced": enforced,
        "code": code, "error": message, "leaseSeconds": 60,
        "geolocationAttribution": "IP Geolocation by DB-IP — CC BY 4.0 — https://db-ip.com",
    })
}
