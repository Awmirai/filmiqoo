package server

import "testing"

func TestPlaybackContentType(t *testing.T) {
	tests:=map[string]string{
		"movie.mp4":"video/mp4",
		"episode.WEBM":"video/webm",
		"stream.m3u8":"application/x-mpegURL",
		"manifest.mpd":"application/dash+xml",
		"release.mkv":"video/x-matroska",
		"clip.mov":"video/quicktime",
		"unknown.bin":"video/mp4",
	}
	for file,want:=range tests {
		if got:=playbackContentType(file); got!=want {
			t.Fatalf("%s: got %q want %q",file,got,want)
		}
	}
}
