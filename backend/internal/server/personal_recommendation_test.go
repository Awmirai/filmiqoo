package server

import "testing"

func TestCompletedMovieRecommendationExclusionsAreUniqueAndTruthful(t *testing.T) {
	result := calculateViewingStats([]viewingFact{
		{TitleID: "one", Kind: "movie", TmdbID: 100, Completed: true},
		{TitleID: "one", Kind: "movie", TmdbID: 100, EverCompleted: true, Position: 1},
		{TitleID: "two", Kind: "movie", TmdbID: 20, EverCompleted: true},
		{TitleID: "partial", Kind: "movie", TmdbID: 30, Position: 50, Duration: 1000},
		{TitleID: "series", Kind: "series", TmdbID: 40, EpisodeID: "ep", Season: 1, Completed: true},
		{TitleID: "unmatched", Kind: "movie", Completed: true},
	}, 42)
	if len(result.CompletedMovieTmdbIDs) != 2 || result.CompletedMovieTmdbIDs[0] != 20 || result.CompletedMovieTmdbIDs[1] != 100 {
		t.Fatalf("wrong completion exclusions: %+v", result.CompletedMovieTmdbIDs)
	}
	if result.MoviesWatched != 3 || result.TotalWatchMS != 42 {
		t.Fatalf("metadata-only identifier changed actual stats: %+v", result)
	}
	empty := calculateViewingStats(nil, 0)
	if empty.CompletedMovieTmdbIDs == nil || len(empty.CompletedMovieTmdbIDs) != 0 {
		t.Fatal("new account must return an authoritative empty exclusion array")
	}
}
