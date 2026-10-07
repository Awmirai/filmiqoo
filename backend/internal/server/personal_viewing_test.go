package server

import (
	"fmt"
	"math"
	"testing"
)

func TestViewingStatsDoNotTurnOpeningSeekingOrRuntimeIntoWatchTime(t *testing.T) {
	result := calculateViewingStats([]viewingFact{
		{TitleID: "opened", Kind: "movie", Duration: 7200000},
		{TitleID: "partial", Kind: "movie", Position: 3600000, Duration: 7200000},
		{TitleID: "finished", Kind: "movie", Completed: true, Position: 7200000, Duration: 7200000},
	}, 42000)
	if result.TotalWatchMS != 42000 || result.MoviesWatched != 1 || result.HistoryTitles != 2 || result.CurrentlyWatching != 1 {
		t.Fatalf("unexpected result %+v", result)
	}
	if len(result.Genres) != 0 || len(result.Countries) != 0 {
		t.Fatal("insufficient data produced a taste conclusion")
	}
}

func TestViewingStatsDeduplicateVersionsEpisodesAndPreservePartialRewatch(t *testing.T) {
	facts := []viewingFact{
		{TitleID: "movie", Kind: "movie", Completed: true},
		{TitleID: "movie", Kind: "movie", EverCompleted: true, Position: 50, Duration: 100},
		{TitleID: "series", Kind: "series", EpisodeID: "ep1", Season: 1, Status: "Ended", ExpectedEpisodes: 2, Completed: true},
		{TitleID: "series", Kind: "series", EpisodeID: "ep1", Season: 1, Status: "Ended", ExpectedEpisodes: 2, Completed: true},
		{TitleID: "series", Kind: "series", EpisodeID: "special", Season: 0, Status: "Ended", ExpectedEpisodes: 2, Completed: true},
	}
	stats := calculateViewingStats(facts, 0)
	if stats.MoviesWatched != 1 || stats.EpisodesWatched != 2 || stats.SeriesWatched != 0 || stats.SeriesStarted != 1 || stats.CurrentlyWatching != 1 {
		t.Fatalf("bad unique counts %+v", stats)
	}
	facts = append(facts, viewingFact{TitleID: "series", Kind: "series", EpisodeID: "ep2", Season: 1, Status: "Ended", ExpectedEpisodes: 2, EverCompleted: true})
	stats = calculateViewingStats(facts, 0)
	if stats.SeriesWatched != 1 || stats.EpisodesWatched != 3 || stats.CompletedTitles != 2 {
		t.Fatalf("full ended series not counted %+v", stats)
	}
	for _, status := range []string{"", "Returning Series", "Canceled"} {
		for i := range facts {
			if facts[i].TitleID == "series" {
				facts[i].Status = status
			}
		}
		if calculateViewingStats(facts, 0).SeriesWatched != 0 {
			t.Fatalf("%q falsely completed", status)
		}
	}
}

func TestViewingTasteUsesUniqueTitlesAndSharesMultinationalCredit(t *testing.T) {
	facts := []viewingFact{}
	for i := 0; i < 10; i++ {
		facts = append(facts, viewingFact{TitleID: fmt.Sprint(i), Kind: "movie", Completed: true,
			Genres: []byte(`[{"id":18,"name":"Drama"},{"id":80,"name":"Crime"}]`), Countries: []byte(`["US","KR","US"]`)})
	}
	stats := calculateViewingStats(facts, 1000)
	if stats.TasteSampleSize != 10 || len(stats.Genres) != 2 || len(stats.Countries) != 2 {
		t.Fatalf("missing real taste %+v", stats)
	}
	for _, share := range append(stats.Genres, stats.Countries...) {
		if math.Abs(share.Fraction-.5) > .000001 {
			t.Fatalf("unequal or inflated share %+v", share)
		}
	}
	facts = append(facts, facts[0])
	if calculateViewingStats(facts, 1000).TasteSampleSize != 10 {
		t.Fatal("rewatch inflated taste sample")
	}
}

func TestNewProfileHasNoSyntheticMetrics(t *testing.T) {
	stats := calculateViewingStats(nil, 0)
	if stats.TotalWatchMS != 0 || stats.CompletedTitles != 0 || stats.HistoryTitles != 0 || stats.LegacyHistoryWithoutTime || len(stats.Genres) > 0 {
		t.Fatalf("new profile data invented %+v", stats)
	}
}
