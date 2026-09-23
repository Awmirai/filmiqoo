package ingest

import (
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

type ParsedMedia struct {
	RawFileName string `json:"rawFileName"`
	Title string `json:"title"`
	Kind string `json:"kind"`
	Season *int `json:"season,omitempty"`
	Episode *int `json:"episode,omitempty"`
	Year *int `json:"year,omitempty"`
	Quality string `json:"quality,omitempty"`
	Source string `json:"source,omitempty"`
	Codec string `json:"codec,omitempty"`
}

var (
	seasonEpisode = regexp.MustCompile(`(?i)(^|[ ._\-])S(\d{1,2})[ ._\-]*E(\d{1,3})($|[ ._\-])`)
	seasonEpisodeLong = regexp.MustCompile(`(?i)(season|فصل)[ ._\-]*(\d{1,2}).*(episode|ep|قسمت)[ ._\-]*(\d{1,3})`)
	yearPattern = regexp.MustCompile(`(^|[ ._\-(])(19\d{2}|20\d{2})($|[ ._\-)])`)
	qualityPattern = regexp.MustCompile(`(?i)(2160p|4k|1440p|1080p|720p|576p|540p|480p|360p)`)
)

var sourcePatterns = []struct{name, pattern string}{
	{"WEB-DL", "web-dl"},
	{"WEBRip", "webrip"},
	{"BluRay", "bluray"},
	{"HDTV", "hdtv"},
	{"DVDRip", "dvdrip"},
}

var codecPatterns = []struct{name, pattern string}{
	{"HEVC", "hevc"},
	{"H.265", "h265"},
	{"x265", "x265"},
	{"H.264", "h264"},
	{"x264", "x264"},
	{"AV1", "av1"},
}

func ParseFileName(name string) ParsedMedia {
	base := strings.TrimSuffix(filepath.Base(name), filepath.Ext(name))
	normalized := normalizeSeparators(base)
	out := ParsedMedia{RawFileName:name, Kind:"movie"}

	if m := seasonEpisode.FindStringSubmatch(normalized); len(m) == 5 {
		s, _ := strconv.Atoi(m[2])
		e, _ := strconv.Atoi(m[3])
		out.Season = &s
		out.Episode = &e
		out.Kind = "series"
		normalized = seasonEpisode.ReplaceAllString(normalized, " ")
	} else if m := seasonEpisodeLong.FindStringSubmatch(normalized); len(m) == 5 {
		s, _ := strconv.Atoi(m[2])
		e, _ := strconv.Atoi(m[4])
		out.Season = &s
		out.Episode = &e
		out.Kind = "series"
		normalized = seasonEpisodeLong.ReplaceAllString(normalized, " ")
	}

	if m := yearPattern.FindStringSubmatch(normalized); len(m) == 4 {
		y, _ := strconv.Atoi(m[2])
		out.Year = &y
		normalized = yearPattern.ReplaceAllString(normalized, " ")
	}

	if m := qualityPattern.FindStringSubmatch(normalized); len(m) >= 2 {
		q := strings.ToLower(m[1])
		if q == "4k" { q = "2160p" }
		out.Quality = q
		normalized = qualityPattern.ReplaceAllString(normalized, " ")
	}

	lower := strings.ToLower(normalized)
	for _, p := range sourcePatterns {
		if strings.Contains(lower, p.pattern) {
			out.Source = p.name
			normalized = replaceInsensitive(normalized, p.pattern, " ")
			break
		}
	}
	lower = strings.ToLower(normalized)
	for _, p := range codecPatterns {
		if strings.Contains(lower, p.pattern) {
			out.Codec = p.name
			normalized = replaceInsensitive(normalized, p.pattern, " ")
			break
		}
	}

	noise := []string{
		"hdr10+", "hdr10", "hdr", "dolby vision", "dovi", "dv",
		"aac", "ac3", "eac3", "ddp5.1", "ddp", "dts", "atmos",
		"10bit", "8bit", "remux", "proper", "repack", "extended",
		"multi", "dual audio", "dubbed", "subbed", "farsi", "persian",
	}
	for _, token := range noise {
		normalized = replaceInsensitive(normalized, token, " ")
	}

	out.Title = cleanTitle(normalized)
	if out.Title == "" { out.Title = cleanTitle(base) }
	return out
}

func normalizeSeparators(s string) string {
	s = strings.NewReplacer(".", " ", "_", " ", "[", " ", "]", " ", "{", " ", "}", " ").Replace(s)
	return strings.Join(strings.Fields(s), " ")
}

func cleanTitle(s string) string {
	s = strings.TrimSpace(s)
	s = strings.Trim(s, "-_. ")
	s = strings.Join(strings.Fields(s), " ")
	if s == "" { return "" }
	parts := strings.Fields(s)
	for i, p := range parts {
		if isMostlyUpper(p) || isMostlyLower(p) { parts[i] = smartTitleWord(p) }
	}
	return strings.Join(parts, " ")
}

func smartTitleWord(s string) string {
	if len([]rune(s)) <= 3 { return s }
	rs := []rune(strings.ToLower(s))
	rs[0] = unicode.ToUpper(rs[0])
	return string(rs)
}

func isMostlyUpper(s string) bool {
	var letters, upper int
	for _, r := range s {
		if unicode.IsLetter(r) { letters++; if unicode.IsUpper(r) { upper++ } }
	}
	return letters > 2 && upper == letters
}

func isMostlyLower(s string) bool {
	var letters, lower int
	for _, r := range s {
		if unicode.IsLetter(r) { letters++; if unicode.IsLower(r) { lower++ } }
	}
	return letters > 2 && lower == letters
}

func replaceInsensitive(input, token, replacement string) string {
	re := regexp.MustCompile(`(?i)` + regexp.QuoteMeta(token))
	return re.ReplaceAllString(input, replacement)
}
