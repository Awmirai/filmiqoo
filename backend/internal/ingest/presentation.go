package ingest

import (
	"golang.org/x/text/unicode/norm"
	"strings"
	"unicode"
)

const PresentationDetectionVersion = 1

type PresentationConvention struct {
	GenericDubIsPersian bool
	GenericSubIsPersian bool
}
type PresentationDetection struct {
	IsDubbed           bool     `json:"isDubbed"`
	IsPersianDubbed    bool     `json:"isPersianDubbed"`
	HasPersianSubtitle bool     `json:"hasPersianSubtitle"`
	Source             string   `json:"detectionSource"`
	Confidence         string   `json:"detectionConfidence"`
	Evidence           []string `json:"detectionEvidence"`
}

// Token normalization avoids matching DUB inside unrelated words or title names.
func NormalizePresentationText(text string) string {
	text = norm.NFKC.String(strings.ToLower(text))
	var out strings.Builder
	for _, char := range text {
		switch char {
		case 'ي', 'ى':
			char = 'ی'
		case 'ك':
			char = 'ک'
		case '\u200c', '\u200d':
			char = ' '
		case '\u0640':
			continue
		}
		if unicode.Is(unicode.Mn, char) || unicode.Is(unicode.Mc, char) {
			continue
		}
		if unicode.IsLetter(char) || unicode.IsDigit(char) {
			out.WriteRune(char)
		} else {
			out.WriteByte(' ')
		}
	}
	return strings.Join(strings.Fields(out.String()), " ")
}
func presentationPhrase(text string, phrases ...string) bool {
	padded := " " + text + " "
	for _, phrase := range phrases {
		if strings.Contains(padded, " "+phrase+" ") {
			return true
		}
	}
	return false
}
func presentationDubMarker(text string) bool {
	return presentationPhrase(text, "dub", "dubbed", "duble", "double", "dual audio", "dualaudio")
}
func presentationSubMarker(text string) bool {
	return presentationPhrase(text, "sub", "subtitle", "subtitles", "hardsub", "hard sub", "softsub", "soft sub", "هاردساب", "زیرنویس چسبیده")
}
func presentationNegative(text string) bool {
	return presentationPhrase(text, "بدون دوبله", "زبان اصلی", "نسخه زبان اصلی", "original audio", "originalaudio", "no dub", "nodub", "دوبله فارسی ندارد", "دوبله پارسی ندارد", "دوبله ندارد", "بدون زبان فارسی", "بدون صوت فارسی", "no persian dub", "no persian dubbed", "no farsi dub", "no farsi dubbed", "no fa dub", "not persian dubbed", "without persian dubbed", "without farsi dubbed", "no fa dubbed", "without persian dub", "without farsi dub", "without dub", "not dubbed")
}
func presentationPersianDub(text string) bool {
	return presentationPhrase(text, "دوبله فارسی", "دوبله ی فارسی", "دوبله پارسی", "دوبله ی پارسی", "زبان فارسی", "صوت فارسی", "persian dub", "persian dubbed", "farsi dub", "farsi dubbed", "fa dub", "fa dubbed")
}
func presentationPersianSub(text string) bool {
	return presentationPhrase(text, "زیرنویس فارسی", "زیرنویس پارسی", "زیر نویس فارسی", "زیر نویس پارسی", "persian subtitle", "persian subtitles", "persian sub", "farsi sub", "farsi subtitle", "fa sub")
}

// Mixed captions cannot label every sibling file as dubbed; filename affinity resolves each version.
func DetectPresentation(fileName, caption string, convention PresentationConvention) PresentationDetection {
	filename := NormalizePresentationText(fileName)
	post := NormalizePresentationText(caption)
	fileDub := presentationDubMarker(filename)
	fileSub := presentationSubMarker(filename)
	fileNegative := presentationNegative(filename)
	filePersianDub := presentationPersianDub(presentationUnnegatedAudio(filename))
	filePersianSub := presentationPersianSub(presentationUnnegatedSubtitle(filename))
	captionDub := presentationPersianDub(presentationUnnegatedAudio(post))
	captionSub := presentationPersianSub(presentationUnnegatedSubtitle(post))
	captionNegative := presentationNegative(post)
	mixed := (captionDub || presentationDubMarker(presentationUnnegatedAudio(post))) && (captionNegative || presentationPhrase(post, "دو نسخه", "هر دو نسخه", "both versions", "dub and sub"))
	result := PresentationDetection{Confidence: "NONE", Evidence: []string{}}
	evidence := func(value string) { result.Evidence = append(result.Evidence, value) }
	if fileDub {
		evidence("filename:dub_marker")
	}
	if fileSub {
		evidence("filename:subtitle_marker")
	}
	if captionDub {
		evidence("caption:explicit_persian_audio")
	}
	if captionSub {
		evidence("caption:explicit_persian_subtitle")
	}
	if captionNegative {
		evidence("caption:original_or_no_dub")
	}
	if fileNegative {
		evidence("filename:original_or_no_dub")
	}
	if mixed {
		evidence("caption:mixed_versions")
	}
	switch {
	case fileNegative:
		result.Source = "filename"
		result.Confidence = "HIGH"
	case captionNegative && !mixed:
		result.Source = "caption"
		result.Confidence = "HIGH"
	case filePersianDub:
		result.IsDubbed = true
		result.IsPersianDubbed = true
		result.Source = "filename"
		result.Confidence = "HIGH"
		evidence("filename:explicit_persian_audio")
	case captionDub && (!mixed || (fileDub && !fileSub)):
		result.IsDubbed = true
		result.IsPersianDubbed = true
		result.Source = "caption"
		result.Confidence = "HIGH"
	case fileDub:
		result.IsDubbed = true
		result.IsPersianDubbed = convention.GenericDubIsPersian
		result.Source = "filename"
		result.Confidence = "MEDIUM"
		if convention.GenericDubIsPersian {
			evidence("source:generic_dub_is_persian")
		}
	case mixed:
		result.Source = "mixed"
		result.Confidence = "LOW"
	}
	fileSubNegative := presentationNegativeSubtitle(filename)
	captionSubNegative := presentationNegativeSubtitle(post)
	mixedSub := captionSubNegative && captionSub
	if fileSubNegative || (captionSubNegative && !mixedSub) {
		if fileSubNegative {
			evidence("filename:no_subtitle")
		} else {
			evidence("caption:no_subtitle")
		}
		if result.Source == "" {
			if fileSubNegative {
				result.Source = "filename"
			} else {
				result.Source = "caption"
			}
			result.Confidence = "HIGH"
		}
	} else if filePersianSub {
		result.HasPersianSubtitle = true
		evidence("filename:explicit_persian_subtitle")
	} else if captionSub && (!mixed || fileSub) && (!mixedSub || fileSub) {
		result.HasPersianSubtitle = true
	} else if (fileSub || (!mixed && !mixedSub && presentationSubMarker(presentationUnnegatedSubtitle(post)))) && convention.GenericSubIsPersian {
		result.HasPersianSubtitle = true
		evidence("source:generic_sub_is_persian")
	}
	if result.Source == "" && result.HasPersianSubtitle {
		result.Source = "subtitle"
		result.Confidence = "HIGH"
	}
	return result
}

func presentationWithoutPhrases(text string, phrases ...string) string {
	value := " " + text + " "
	for _, phrase := range phrases {
		value = strings.ReplaceAll(value, " "+phrase+" ", " ")
	}
	return strings.Join(strings.Fields(value), " ")
}
func presentationUnnegatedAudio(text string) string {
	return presentationWithoutPhrases(text,
		"دوبله فارسی ندارد", "دوبله پارسی ندارد", "دوبله ندارد", "بدون دوبله", "بدون زبان فارسی", "بدون صوت فارسی",
		"no persian dub", "no persian dubbed", "no farsi dub", "no farsi dubbed", "no fa dub", "no dub", "not persian dubbed", "without persian dubbed", "without farsi dubbed", "no fa dubbed", "without persian dub", "without farsi dub", "without dub", "not dubbed")
}
func presentationNegativeSubtitle(text string) bool {
	return presentationPhrase(text,
		"بدون زیرنویس", "بدون زیر نویس", "زیرنویس ندارد", "زیر نویس ندارد", "no sub", "nosub", "no subtitles", "no subtitle", "no persian subtitle", "no persian subtitles", "no farsi sub", "without subtitles", "without persian subtitles")
}
func presentationUnnegatedSubtitle(text string) string {
	return presentationWithoutPhrases(text,
		"بدون زیرنویس", "بدون زیر نویس", "زیرنویس ندارد", "زیر نویس ندارد", "no persian subtitles", "no persian subtitle", "no farsi sub", "without persian subtitles", "without subtitles", "no subtitles", "no subtitle", "no sub", "nosub")
}
