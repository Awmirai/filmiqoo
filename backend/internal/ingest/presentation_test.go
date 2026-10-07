package ingest

import "testing"

func TestDetectPresentationPerVersion(t *testing.T) {
	persian := PresentationConvention{GenericDubIsPersian: true, GenericSubIsPersian: true}
	cases := []struct {
		name, file, caption          string
		convention                   PresentationConvention
		dubbed, persianDub, subtitle bool
		confidence                   string
	}{
		{"explicit caption", "Film.1080p.mkv", "نسخه دوبله فارسی", PresentationConvention{}, true, true, false, "HIGH"},
		{"arabic letters and joiners", "Film.mkv", "دوبله‌ي فارسي", PresentationConvention{}, true, true, false, "HIGH"},
		{"persian filename", "Film.FA.Dub.mkv", "", PresentationConvention{}, true, true, false, "HIGH"},
		{"generic unknown language", "Film.DUB.mkv", "", PresentationConvention{}, true, false, false, "MEDIUM"},
		{"configured generic", "Film.DUB.mkv", "", persian, true, true, false, "MEDIUM"},
		{"original caption beats weak marker", "Film.DUB.mkv", "نسخه زبان اصلی", persian, false, false, false, "HIGH"},
		{"no dub", "Film.DUB.mkv", "بدون دوبله", persian, false, false, false, "HIGH"},
		{"filename original beats broad caption", "Film.Original.Audio.mkv", "دوبله فارسی و زیرنویس فارسی", persian, false, false, true, "HIGH"},
		{"mixed dub file", "Film.DUB.mkv", "دو نسخه دوبله فارسی و زبان اصلی با زیرنویس فارسی", persian, true, true, false, "HIGH"},
		{"mixed subtitle file", "Film.SUB.mkv", "دو نسخه دوبله فارسی و زبان اصلی با زیرنویس فارسی", persian, false, false, true, "LOW"},
		{"mixed unmarked file", "Film.1080p.mkv", "دو نسخه دوبله فارسی و زبان اصلی", persian, false, false, false, "LOW"},
		{"subtitle alone never audio", "Film.FA.Sub.mkv", "زیرنویس فارسی", persian, false, false, true, "HIGH"},
		{"foreign subtitle unknown", "Film.SUB.mkv", "", PresentationConvention{}, false, false, false, "NONE"},
		{"title substring false positive", "Dublin.The.DoubleLife.mkv", "", persian, false, false, false, "NONE"},
		{"english explicit", "Film.mkv", "Farsi dubbed version", PresentationConvention{}, true, true, false, "HIGH"},
		{"both legitimate audio and subtitle", "Film.DUB.mkv", "دوبله فارسی با زیرنویس فارسی", persian, true, true, true, "HIGH"},
		{"fullwidth marker", "Film.ＤＵＢ.mkv", "", persian, true, true, false, "MEDIUM"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := DetectPresentation(tc.file, tc.caption, tc.convention)
			if got.IsDubbed != tc.dubbed || got.IsPersianDubbed != tc.persianDub || got.HasPersianSubtitle != tc.subtitle || got.Confidence != tc.confidence {
				t.Fatalf("unexpected detection: %#v", got)
			}
			if got.Evidence == nil {
				t.Fatal("evidence must be a real JSON array")
			}
		})
	}
	for _, marker := range []string{"dub", "dubbed", "duble", "double", "dual audio", "dualaudio"} {
		got := DetectPresentation("Film."+marker+".mkv", "", persian)
		if !got.IsPersianDubbed || got.Confidence != "MEDIUM" {
			t.Fatalf("token %q not detected: %#v", marker, got)
		}
	}
}
func TestNormalizePresentationTextUnicode(t *testing.T) {
	got := NormalizePresentationText(" دُوبله‌ي   فارسي ـ ك ")
	if got != "دوبله ی فارسی ک" {
		t.Fatalf("normalization: %q", got)
	}
}

func TestPresentationNegationAndMixedGenericSources(t *testing.T) {
	convention := PresentationConvention{GenericDubIsPersian: true, GenericSubIsPersian: true}
	cases := []struct {
		file, caption string
		dub, sub      bool
	}{
		{"Film.DUB.mkv", "بدون دوبله فارسی", false, false},
		{"Film.DUB.mkv", "no persian dub", false, false},
		{"Film.DUB.mkv", "دوبله فارسی ندارد", false, false},
		{"Film.DUB.mkv", "دوبله فارسی بدون زیرنویس فارسی", true, false},
		{"Film.SUB.mkv", "بدون زیرنویس فارسی", false, false},
		{"Film.DUB.mkv", "نسخه بدون دوبله فارسی و نسخه دوبله فارسی", true, false},
		{"Film.DUB.mkv", "both versions original audio and dub", true, false},
		{"Film.SUB.mkv", "both versions original audio and dub", false, true},
		{"Film.mkv", "زیر‌نویس فارسی", false, true},
		{"Film.mkv", "HardSub", false, true},
	}
	for _, tc := range cases {
		got := DetectPresentation(tc.file, tc.caption, convention)
		if got.IsPersianDubbed != tc.dub || got.HasPersianSubtitle != tc.sub {
			t.Errorf("%q %q: %#v", tc.file, tc.caption, got)
		}
	}
	if DetectPresentation("Film.mkv", "HardSub", PresentationConvention{}).HasPersianSubtitle {
		t.Fatal("unknown subtitle language inferred Persian")
	}
}

func TestNegatedDubVariantsAndMixedSubtitleAffinity(t *testing.T) {
	c := PresentationConvention{GenericDubIsPersian: true, GenericSubIsPersian: true}
	for _, caption := range []string{"without persian dubbed", "without farsi dubbed", "no fa dubbed"} {
		got := DetectPresentation("Film.DUB.mkv", caption, c)
		if got.IsDubbed || got.IsPersianDubbed || got.Confidence != "HIGH" {
			t.Errorf("negation %q: %#v", caption, got)
		}
	}
	caption := "نسخه بدون زیرنویس فارسی و نسخه زیرنویس فارسی"
	if got := DetectPresentation("Film.1080p.mkv", caption, c); got.HasPersianSubtitle {
		t.Fatalf("mixed subtitle contaminated sibling: %#v", got)
	}
	if got := DetectPresentation("Film.SUB.mkv", caption, c); !got.HasPersianSubtitle {
		t.Fatalf("lost filename affinity: %#v", got)
	}
	if got := DetectPresentation("Film.No.Sub.mkv", "", c); got.HasPersianSubtitle || got.Source != "filename" {
		t.Fatalf("filename evidence: %#v", got)
	}
}
