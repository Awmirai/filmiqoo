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
