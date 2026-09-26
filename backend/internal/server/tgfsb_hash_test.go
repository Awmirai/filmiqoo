package server

import "testing"

func TestComputeTGFSBHashMatchesTGFileStreamBot(t *testing.T) {
	got := computeTGFSBHash("AgADExampleUniqueId123", 6)
	if got != "2a1c52" {
		t.Fatalf("hash=%s want=2a1c52", got)
	}
}

func TestComputeTGFSBHashBoundsAndEmpty(t *testing.T) {
	if got := computeTGFSBHash("", 6); got != "" {
		t.Fatalf("empty unique id hash=%s", got)
	}
	if got := computeTGFSBHash("stable-id", 5); len(got) != 6 {
		t.Fatalf("minimum len=%d hash=%s", len(got), got)
	}
	if got := computeTGFSBHash("stable-id", 100); len(got) != 63 {
		t.Fatalf("maximum len=%d hash=%s", len(got), got)
	}
}
