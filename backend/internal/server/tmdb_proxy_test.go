package server

import "testing"

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
