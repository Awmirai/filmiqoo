package ingest

import (
	"html"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

var captionTitle = regexp.MustCompile(`(?i)^(?:نام\s*(?:انگلیسی|فارسی)?\s*(?:فیلم|سریال|انیمه)?|عنوان\s*(?:فیلم|سریال)?|نام انگلیسی|نام فارسی|english title|original title|movie title|series title|title|movie|series)\s*[:：|\-]\s*(.+)$`)
var captionDownload = regexp.MustCompile(`^(?:دانلود|تماشای)\s+(?:فیلم|سریال|انیمه)\s+(.+)$`)
var captionSeason = regexp.MustCompile(`(?i)(?:فصل|season)\s*[:：]?\s*(\d{1,2})(?:\D|$)`)
var captionEpisode = regexp.MustCompile(`(?i)(?:قسمت|episode|ep)\s*[:：]?\s*(\d{1,3})(?:\D|$)`)
var captionYear = regexp.MustCompile(`(?i)^(?:سال(?: ساخت| انتشار)?|year|release year)\s*[:：]?\s*(19\d{2}|20\d{2})(?:\D|$)`)
var captionTags = regexp.MustCompile(`<[^>]*>`)
var captionLinks = regexp.MustCompile(`(?i)(https?://\S+|t\.me/\S+|@[\w_]+)`)

// ParseTelegramMedia treats release fields in the filename as authoritative and
// uses explicit, short caption headings as additional title evidence. Plot text,
// advertisements and links are never submitted as a title to the metadata service.
func ParseTelegramMedia(filename, caption string) (ParsedMedia, []string) {
	out := ParseFileName(normalizeCaptionDigits(filename))
	out.RawFileName = filename
	var headings []string
	caption = html.UnescapeString(captionTags.ReplaceAllString(caption, ""))
	caption = normalizeCaptionDigits(caption)
	for _, raw := range strings.Split(caption, "\n") {
		line := strings.TrimSpace(raw)
		line = strings.TrimLeftFunc(line, func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) })
		if len([]rune(line)) > 180 || line == "" {
			continue
		}
		var heading string
		if m := captionTitle.FindStringSubmatch(line); len(m) > 1 {
			heading = m[1]
		}
		if m := captionDownload.FindStringSubmatch(line); len(m) > 1 {
			heading = m[1]
		}
		if heading != "" {
			heading = strings.TrimSpace(captionLinks.ReplaceAllString(heading, ""))
			// A label is stronger evidence than an arbitrary first line. Strip only
			// explicit release markers, retaining meaningful words in the title.
			p := ParseFileName(strings.Trim(heading, "\"«»“”# ") + ".mp4")
			if len([]rune(p.Title)) >= 2 && len([]rune(p.Title)) <= 100 {
				headings = append(headings, p.Title)
				if out.Year == nil {
					out.Year = p.Year
				}
			}
			if strings.Contains(line, "سریال") || strings.HasPrefix(strings.ToLower(line), "series") {
				out.Kind = "series"
			}
		}
		// Only standalone episode/year headings; a synopsis can mention other seasons.
		lower := strings.ToLower(line)
		if strings.HasPrefix(line, "فصل") || strings.HasPrefix(lower, "season") || strings.HasPrefix(line, "قسمت") || strings.HasPrefix(lower, "episode") {
			if m := captionSeason.FindStringSubmatch(line); out.Season == nil && len(m) > 1 {
				v, _ := strconv.Atoi(m[1])
				out.Season = &v
			}
			if m := captionEpisode.FindStringSubmatch(line); out.Episode == nil && len(m) > 1 {
				v, _ := strconv.Atoi(m[1])
				if v > 0 {
					out.Episode = &v
				}
			}
		}
		if m := captionYear.FindStringSubmatch(line); out.Year == nil && len(m) > 1 {
			v, _ := strconv.Atoi(m[1])
			out.Year = &v
		}
		// Common compact caption release line, e.g. S02E03 / 1080p.
		if strings.HasPrefix(strings.ToUpper(line), "S") {
			p := ParseFileName(line + ".mp4")
			if out.Season == nil {
				out.Season = p.Season
			}
			if out.Episode == nil {
				out.Episode = p.Episode
			}
		}
	}
	if out.Season != nil || out.Episode != nil {
		out.Kind = "series"
	}
	// Prefer an explicit Latin heading, keeping Persian and filename candidates
	// for fallback. This also avoids displaying Korean/Indian native-script names.
	var candidates []string
	add := func(title string) {
		if title == "" {
			return
		}
		for _, existing := range candidates {
			if strings.EqualFold(existing, title) {
				return
			}
		}
		if len(candidates) < 4 {
			candidates = append(candidates, title)
		}
	}
	for _, title := range headings {
		if strings.IndexFunc(title, func(r rune) bool { return unicode.In(r, unicode.Latin) }) >= 0 {
			add(title)
		}
	}
	for _, title := range headings {
		add(title)
	}
	add(out.Title)
	if len(candidates) > 0 {
		out.Title = candidates[0]
	}
	return out, candidates
}

func normalizeCaptionDigits(s string) string {
	return strings.Map(func(r rune) rune {
		if r >= '۰' && r <= '۹' {
			return '0' + r - '۰'
		}
		if r >= '٠' && r <= '٩' {
			return '0' + r - '٠'
		}
		if r == 'ي' {
			return 'ی'
		}
		if r == 'ك' {
			return 'ک'
		}
		if r == '\u200c' {
			return ' '
		}
		return r
	}, s)
}
