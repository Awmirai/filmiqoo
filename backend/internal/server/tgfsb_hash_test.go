package server

import "testing"

func TestComputeTGFSBHashStable(t *testing.T) {
	got:=computeTGFSBHash("movie.mkv",123456789,"video/x-matroska",987654321,6)
	if len(got)!=6 { t.Fatalf("len=%d hash=%s",len(got),got) }
	again:=computeTGFSBHash("movie.mkv",123456789,"video/x-matroska",987654321,6)
	if got!=again { t.Fatalf("hash not stable") }
}
