package ingest

import "testing"

func TestChannelReleaseNameDoesNotPolluteMetadataSearch(t *testing.T) {
	got := ParseFileName("Union.County.2026.1080p.WEBRip.YTS.AlphaDL.mp4")
	if got.Title != "Union County" || got.Year == nil || *got.Year != 2026 || got.Quality != "1080p" {
		t.Fatalf("channel post 21 parsed incorrectly: %+v", got)
	}
}

func TestTitleWordsAreNotStrippedAsCodecNoise(t *testing.T) {
	for _, name := range []string{"Adventure.mp4", "Adventure.2026.1080p.WEBRip.mp4"} {
		if got := ParseFileName(name); got.Title != "Adventure" {
			t.Fatalf("%s: %+v", name, got)
		}
	}
}

func TestParseSeries(t *testing.T) {
	got := ParseFileName("The.Last.of.Us.S02E03.1080p.WEB-DL.x265.mkv")
	if got.Kind != "series" {
		t.Fatalf("kind=%s", got.Kind)
	}
	if got.Season == nil || *got.Season != 2 {
		t.Fatalf("season=%v", got.Season)
	}
	if got.Episode == nil || *got.Episode != 3 {
		t.Fatalf("episode=%v", got.Episode)
	}
	if got.Quality != "1080p" {
		t.Fatalf("quality=%s", got.Quality)
	}
	if got.Source != "WEB-DL" {
		t.Fatalf("source=%s", got.Source)
	}
	if got.Codec != "x265" {
		t.Fatalf("codec=%s", got.Codec)
	}
	if got.Title != "The Last of Us" {
		t.Fatalf("title=%q", got.Title)
	}
}

func TestParseMovie(t *testing.T) {
	got := ParseFileName("Dune.Part.Two.2024.2160p.BluRay.HEVC.DTS.mkv")
	if got.Kind != "movie" {
		t.Fatalf("kind=%s", got.Kind)
	}
	if got.Year == nil || *got.Year != 2024 {
		t.Fatalf("year=%v", got.Year)
	}
	if got.Quality != "2160p" {
		t.Fatalf("quality=%s", got.Quality)
	}
	if got.Source != "BluRay" {
		t.Fatalf("source=%s", got.Source)
	}
	if got.Codec != "HEVC" {
		t.Fatalf("codec=%s", got.Codec)
	}
	if got.Title != "Dune Part Two" {
		t.Fatalf("title=%q", got.Title)
	}
}

func TestParsePersianLongEpisode(t *testing.T) {
	got := ParseFileName("Shahrzad.Season.2.Episode.5.720p.WEBRip.mkv")
	if got.Kind != "series" {
		t.Fatalf("kind=%s", got.Kind)
	}
	if got.Season == nil || *got.Season != 2 {
		t.Fatalf("season=%v", got.Season)
	}
	if got.Episode == nil || *got.Episode != 5 {
		t.Fatalf("episode=%v", got.Episode)
	}
}

func TestParseHisAndHersRelease(t *testing.T) {
	got := ParseFileName("His.And.Hers.2026.S01E02.480p.WEB.dl.RMT.AlphaDL.mkv")

	if got.Kind != "series" {
		t.Fatalf("kind=%q", got.Kind)
	}
	if got.Title != "His And Hers" {
		t.Fatalf("title=%q", got.Title)
	}
	if got.Season == nil || *got.Season != 1 {
		t.Fatalf("season=%v", got.Season)
	}
	if got.Episode == nil || *got.Episode != 2 {
		t.Fatalf("episode=%v", got.Episode)
	}
	if got.Year == nil || *got.Year != 2026 {
		t.Fatalf("year=%v", got.Year)
	}
	if got.Quality != "480p" {
		t.Fatalf("quality=%q", got.Quality)
	}
	if got.Source != "WEB-DL" {
		t.Fatalf("source=%q", got.Source)
	}
}
