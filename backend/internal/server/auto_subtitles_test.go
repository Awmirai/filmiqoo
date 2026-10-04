package server

import "testing"

func TestSubtitleReleaseScoreExactRelease(t *testing.T) {
	score,exact:=subtitleReleaseScore(
		"His.and.Hers.S01E02.1080p.WEB-DL.DDP5.1.H.264-GROUP.mkv",
		"His.and.Hers.S01E02.1080p.WEB-DL.DDP5.1.H.264-GROUP",
	)
	if !exact {
		t.Fatalf("expected exact release match, score=%d",score)
	}
	if score<250 {
		t.Fatalf("expected strong score, got %d",score)
	}
}

func TestSubtitleReleaseScorePrefersEpisodeAndGroup(t *testing.T) {
	source:="Show.Name.S01E02.1080p.WEB-DL.x265-ALPHA.mkv"
	good,_:=subtitleReleaseScore(source,"Show.Name.S01E02.1080p.WEB-DL.x265-ALPHA")
	bad,_:=subtitleReleaseScore(source,"Show.Name.S01E03.1080p.WEB-DL.x265-BETA")
	if good<=bad {
		t.Fatalf("expected correct release to outrank mismatch: good=%d bad=%d",good,bad)
	}
}
