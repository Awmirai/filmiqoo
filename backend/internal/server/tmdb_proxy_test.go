package server

import (
	"encoding/json"
	"net/url"
	"testing"
)

func TestAllowedTMDBProxyPath(t *testing.T) {
	allowed := []string{
		"trending/all/day",
		"trending/all/week",
		"movie/popular",
		"tv/popular",
		"discover/movie",
		"discover/tv",
		"search/multi",
		"movie/550",
		"tv/1399",
		"person/287",
		"collection/10",
		"movie/550/external_ids",
		"tv/1399/external_ids",
	}
	for _, path := range allowed {
		if !allowedTMDBProxyPath(path) {
			t.Fatalf("expected %q to be allowed", path)
		}
	}

	blocked := []string{
		"",
		"/movie/550",
		"movie/-1",
		"movie/0",
		"movie/abc",
		"movie/550/videos",
		"person/287/external_ids",
		"collection/10/external_ids",
		"authentication/token/new",
		"account/1",
		"../../configuration",
	}
	for _, path := range blocked {
		if allowedTMDBProxyPath(path) {
			t.Fatalf("expected %q to be blocked", path)
		}
	}
}
func TestDiscoveryProxyFiltersAreAppliedOrRejected(t *testing.T) {
	params, err := tmdbProxyParameters("discover/tv", url.Values{"path": {"discover/tv"}, "with_status": {"3|4"}, "with_type": {"2"}, "with_runtime.lte": {"60"}, "vote_average.gte": {"8"}, "with_origin_country": {"KR"}, "api_key": {"must-not-forward"}})
	if err != nil {
		t.Fatal(err)
	}
	if params.Get("with_status") != "3|4" || params.Get("with_runtime.lte") != "60" || params.Get("api_key") != "" {
		t.Fatalf("wrong applied params: %v", params)
	}
	for _, query := range []url.Values{{"with_status": {"6"}}, {"vote_average.gte": {"NaN"}}, {"page": {"501"}}, {"with_runtime.lte": {"0"}}, {"with_unsupported": {"1"}}, {"page": {"1", "2"}}} {
		if _, err := tmdbProxyParameters("discover/tv", query); err == nil {
			t.Fatalf("unsafe query accepted: %v", query)
		}
	}
	if _, err := tmdbProxyParameters("discover/movie", url.Values{"with_status": {"3"}}); err == nil {
		t.Fatal("TV status silently accepted for movies")
	}
	if _, err := tmdbProxyParameters("search/multi", url.Values{"with_runtime.lte": {"90"}}); err == nil {
		t.Fatal("discover filter silently accepted for search")
	}
}
func TestDiscoveryEnvelopeIncludesActualFiltersAndWrapsRegistryArrays(t *testing.T) {
	envelope, err := tmdbDiscoveryEnvelope(json.RawMessage(`[{"iso_3166_1":"TR","english_name":"Turkey"}]`), url.Values{"language": {"fa-IR"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(envelope["results"].([]any)) != 1 {
		t.Fatal("registry lost")
	}
	marker := envelope["_filmiqooDiscovery"].(map[string]any)
	if marker["appliedParameters"].(map[string]string)["language"] != "fa-IR" {
		t.Fatal("applied language not echoed")
	}
	for _, path := range []string{"configuration/countries", "configuration/languages", "genre/movie/list", "genre/tv/list", "trending/tv/week", "tv/77/season/0"} {
		if !allowedTMDBProxyPath(path) {
			t.Fatalf("supported path rejected: %s", path)
		}
	}
	for _, path := range []string{"tv/77/season/-1", "configuration/../../account", "configuration", "genre/movie/../../account"} {
		if allowedTMDBProxyPath(path) {
			t.Fatalf("unsafe path accepted: %s", path)
		}
	}
}
