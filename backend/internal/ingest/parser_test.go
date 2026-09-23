package ingest

import "testing"

func TestParseSeries(t *testing.T) {
	got := ParseFileName("The.Last.of.Us.S02E03.1080p.WEB-DL.x265.mkv")
	if got.Kind != "series" { t.Fatalf("kind=%s", got.Kind) }
	if got.Season == nil || *got.Season != 2 { t.Fatalf("season=%v", got.Season) }
	if got.Episode == nil || *got.Episode != 3 { t.Fatalf("episode=%v", got.Episode) }
	if got.Quality != "1080p" { t.Fatalf("quality=%s", got.Quality) }
	if got.Source != "WEB-DL" { t.Fatalf("source=%s", got.Source) }
	if got.Codec != "x265" { t.Fatalf("codec=%s", got.Codec) }
	if got.Title != "The Last of Us" { t.Fatalf("title=%q", got.Title) }
}

func TestParseMovie(t *testing.T) {
	got := ParseFileName("Dune.Part.Two.2024.2160p.BluRay.HEVC.DTS.mkv")
	if got.Kind != "movie" { t.Fatalf("kind=%s", got.Kind) }
	if got.Year == nil || *got.Year != 2024 { t.Fatalf("year=%v", got.Year) }
	if got.Quality != "2160p" { t.Fatalf("quality=%s", got.Quality) }
	if got.Source != "BluRay" { t.Fatalf("source=%s", got.Source) }
	if got.Codec != "HEVC" { t.Fatalf("codec=%s", got.Codec) }
	if got.Title != "Dune Part Two" { t.Fatalf("title=%q", got.Title) }
}

func TestParsePersianLongEpisode(t *testing.T) {
	got := ParseFileName("Shahrzad.Season.2.Episode.5.720p.WEBRip.mkv")
	if got.Kind != "series" { t.Fatalf("kind=%s", got.Kind) }
	if got.Season == nil || *got.Season != 2 { t.Fatalf("season=%v", got.Season) }
	if got.Episode == nil || *got.Episode != 5 { t.Fatalf("episode=%v", got.Episode) }
}
