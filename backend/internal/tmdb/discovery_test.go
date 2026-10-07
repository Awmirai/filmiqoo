package tmdb

import "testing"

func TestMetadataKeepsAllReportedCountriesAndUnknownFacets(t *testing.T) {
	meta, err := parseTitleMetadata([]byte(`{"origin_country":["KR","US","KR"],"production_countries":[{"iso_3166_1":"FR"}],"original_language":"ko","genres":[{"id":18,"name":"Drama"},{"id":18,"name":"Drama"}],"first_air_date":"2024-01-02","status":"Ended","type":"Miniseries","number_of_seasons":1,"number_of_episodes":8,"runtime":null,"episode_run_time":[0,42],"vote_average":8.1,"vote_count":320}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(meta.OriginCountries) != 3 || meta.OriginCountries[0] != "KR" || meta.OriginCountries[2] != "FR" {
		t.Fatalf("multi-country lost: %#v", meta)
	}
	if len(meta.GenreIDs) != 1 || meta.GenreIDs[0] != 18 || meta.RuntimeMinutes == nil || *meta.RuntimeMinutes != 42 || meta.EpisodeCount == nil || *meta.EpisodeCount != 8 || meta.SeriesStatus != "Ended" || meta.SeriesType != "Miniseries" {
		t.Fatalf("real facets lost: %#v", meta)
	}
	blank, err := parseTitleMetadata([]byte(`{"original_language":"hi","runtime":null}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(blank.OriginCountries) != 0 || blank.RuntimeMinutes != nil || blank.EpisodeCount != nil {
		t.Fatalf("unknown metadata inferred: %#v", blank)
	}
}
