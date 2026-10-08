package ingest

import "testing"

func TestCaptionEvidence(t *testing.T) {
	tests := []struct {
		name, file, caption, title, kind string
		season, episode, year            int
	}{
		{"bilingual", "video_0042.mp4", "🎬 نام فارسی: بازی مرکب\nEnglish title: Squid Game\nفصل ۲ قسمت ۳\nسال: ۲۰۲۱\nخلاصه: داستان در سال 1998 آغاز می شود", "Squid Game", "series", 2, 3, 2021},
		{"release wins", "Reacher.S01E02.1080p.WEB-DL.x265.mkv", "Title: Reacher\nSeason 2 Episode 3", "Reacher", "series", 1, 2, 0},
		{"persian", "file.mp4", "نام فیلم: جدایی نادر از سیمین\nسال ساخت: ۲۰۱۱", "جدایی نادر از سیمین", "movie", 0, 0, 2011},
		{"no synopsis match", "Union.County.2026.1080p.mp4", "خلاصه: فصل 2 قسمت 3 از زندگی در سال 1990\nhttps://t.me/filmiqq1/21", "Union County", "movie", 0, 0, 2026},
		{"html", "file.mkv", "<b>Title:</b> RRR (2022)\n@filmiqq1", "RRR", "movie", 0, 0, 2022},
		{"arabic digits", "video.mp4", "نام سریال: پوست شیر\nفصل ٣\nقسمت ٨", "پوست شیر", "series", 3, 8, 0},
		{"unlabelled heading", "video_42.mp4", "🎬 Squid Game 2021\nفصل ۲ قسمت ۱", "Squid Game", "series", 2, 1, 2021},
	}
	value := func(p *int) int {
		if p == nil {
			return 0
		}
		return *p
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p, candidates := ParseTelegramMedia(tt.file, tt.caption)
			if p.Title != tt.title || p.Kind != tt.kind || value(p.Season) != tt.season || value(p.Episode) != tt.episode || value(p.Year) != tt.year {
				t.Fatalf("parsed %+v; candidates %v", p, candidates)
			}
			if p.RawFileName != tt.file {
				t.Fatal("original filename changed")
			}
		})
	}
}

func TestCaptionKeepsTechnicalMetadataAndFallback(t *testing.T) {
	p, candidates := ParseTelegramMedia("Original.2024.1080p.WEB-DL.x265.mkv", "Title: Alternative\nنام فارسی: جایگزین")
	if p.Quality != "1080p" || p.Codec != "x265" || p.Source != "WEB-DL" {
		t.Fatalf("lost filename metadata: %+v", p)
	}
	if len(candidates) != 3 || candidates[2] != "Original" {
		t.Fatalf("missing fallback: %v", candidates)
	}
}
