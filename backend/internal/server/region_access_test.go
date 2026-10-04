package server

import (
    "encoding/json"
    "net/http"
    "net/http/httptest"
    "strings"
    "testing"
    "time"

    "github.com/Awmirai/filmiqoo/backend/internal/geoaccess"
    "github.com/go-chi/chi/v5/middleware"
)

func regionFixture(t *testing.T) *regionAccessPolicy {
    t.Helper()
    raw, err := json.Marshal(geoaccess.Dataset{
        Country: "IR", Published: time.Now().UTC().Format("2006-01-02"),
        Source: "https://download.db-ip.com/free/dbip-country-lite-test.csv.gz",
        SourceSHA256: strings.Repeat("a", 64),
        Ranges: []geoaccess.Range{{Start: "5.112.0.0", End: "5.112.0.255"}, {Start: "2a01:5ec0::", End: "2a01:5ec0::ffff"}},
    })
    if err != nil { t.Fatal(err) }
    data, err := geoaccess.Load(strings.NewReader(string(raw)), time.Now().UTC())
    if err != nil { t.Fatal(err) }
    return &regionAccessPolicy{enabled: true, dataset: data, edgeSecret: strings.Repeat("s", 64)}
}

func TestRegionProtectsLoginCatalogStreamingAndDownloads(t *testing.T) {
    s := &Server{regionAccess: regionFixture(t)}
    for _, path := range []string{"/v1/auth/login", "/v1/auth/register", "/v1/catalog/home", "/v1/tmdb", "/v1/playback/token", "/v1/playback/version?download=1", "/v1/realtime/rooms/test"} {
        t.Run(path, func(t *testing.T) {
            reached := false
            handler := s.enforceRegion(middleware.RealIP(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { reached = true })))
            request := httptest.NewRequest(http.MethodGet, path, nil)
            request.RemoteAddr = "8.8.8.8:5000"
            request.Header.Set("X-Forwarded-For", "5.112.0.5")
            request.Header.Set("X-Real-IP", "5.112.0.5")
            request.Header.Set("CF-IPCountry", "IR")
            request.Header.Set("X-Filmiqoo-Client-IP", "5.112.0.5")
            response := httptest.NewRecorder()
            handler.ServeHTTP(response, request)
            if response.Code != http.StatusForbidden || reached { t.Fatalf("foreign request reached %s: %d", path, response.Code) }
            if !strings.Contains(response.Body.String(), "IRAN_ONLY") { t.Fatal("missing machine-readable denial") }
            if response.Header().Get("Cache-Control") != "private, no-store, max-age=0" { t.Fatal("region denial may be cached") }
        })
    }
}

func TestRegionStatusAndAPIAgreeForTrustedProxyIPv4AndIPv6(t *testing.T) {
    policy := regionFixture(t)
    s := &Server{regionAccess: policy}
    for _, ip := range []string{"5.112.0.5", "::ffff:5.112.0.5", "2a01:5ec0::1", "8.8.8.8", "2606:4700::1111"} {
        t.Run(ip, func(t *testing.T) {
            statuses := make([]int, 0, 2)
            for _, path := range []string{"/v1/access", "/v1/catalog/home"} {
                handler := s.regionStatusMiddleware(s.enforceRegion(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) })))
                request := httptest.NewRequest(http.MethodGet, path, nil)
                request.RemoteAddr = "10.0.0.2:4000"
                request.Header.Set("X-Filmiqoo-Client-IP", ip)
                request.Header.Set("X-Filmiqoo-Edge-Secret", policy.edgeSecret)
                response := httptest.NewRecorder()
                handler.ServeHTTP(response, request)
                statuses = append(statuses, response.Code)
            }
            if statuses[0] != statuses[1] { t.Fatalf("access and API disagree: %v", statuses) }
            allowed := strings.Contains(ip, "5.112.0.5") || ip == "2a01:5ec0::1"
            if (statuses[0] == http.StatusOK) != allowed { t.Fatalf("unexpected decision: %v", statuses) }
        })
    }
}

func TestUnavailableCountryDataDeniesButDoesNotBlockHealth(t *testing.T) {
    s := &Server{regionAccess: &regionAccessPolicy{enabled: true}}
    for _, path := range []string{"/v1/access", "/v1/auth/login", "/healthz"} {
        handler := s.regionStatusMiddleware(s.enforceRegion(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })))
        response := httptest.NewRecorder()
        handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, path, nil))
        want := http.StatusServiceUnavailable
        if path == "/healthz" { want = http.StatusNoContent }
        if response.Code != want { t.Fatalf("%s: got %d, want %d", path, response.Code, want) }
    }
}

func TestProductionCannotAccidentallyDisableRegionEnforcement(t *testing.T) {
    t.Setenv("IRAN_ONLY_ENABLED", "false")
    t.Setenv("GEO_COUNTRY_DATA_PATH", t.TempDir()+"/absent.json")
    for _, env := range []string{"production", "prod", "PRODUCTION"} {
        policy := loadRegionAccess(env)
        if !policy.enabled || policy.ready() { t.Fatal("production must fail closed without country data") }
    }
    if loadRegionAccess("development").enabled { t.Fatal("explicit development mode should remain usable") }
}
