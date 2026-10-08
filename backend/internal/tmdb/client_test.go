package tmdb

import "testing"

func TestNormalizeAmpersandEquivalent(t *testing.T) {
	got := normalize("HIS & HERS")
	if got != "his and hers" {
		t.Fatalf("normalize ampersand: got %q", got)
	}
}

func TestTitleScoreAmpersandEquivalent(t *testing.T) {
	got := titleScore(
		normalize("His And Hers"),
		normalize("HIS & HERS"),
		normalize("HIS & HERS"),
	)
	if got != 100 {
		t.Fatalf("title score: got %d, want 100", got)
	}
}
