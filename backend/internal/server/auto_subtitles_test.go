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


func TestSecureSubtitleURL(t *testing.T) {
	got,ok:=secureSubtitleURL("http://stremio.alirostami.com/subtitles/download/123")
	if !ok {
		t.Fatal("expected known subtitle host to be upgraded to https")
	}
	if got!="https://stremio.alirostami.com/subtitles/download/123" {
		t.Fatalf("unexpected upgraded url: %s",got)
	}

	if _,ok:=secureSubtitleURL("http://example.com/subtitle.srt"); ok {
		t.Fatal("expected insecure third-party URL to be rejected")
	}
}

func TestPersianLanguageAliases(t *testing.T) {
	for _,language:=range []string{"fa","fas","per","Persian","farsi"} {
		if !isPersianLanguage(language) {
			t.Fatalf("expected %q to be recognized as Persian",language)
		}
	}
	if isPersianLanguage("en") {
		t.Fatal("english must not be recognized as Persian")
	}
}
