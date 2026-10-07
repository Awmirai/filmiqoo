package tmdb

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
)

type TitleMetadata struct {
	GenreIDs         []int
	Genres           []string
	OriginCountries  []string
	OriginalLanguage string
	Year             int
	Rating           float64
	Popularity       float64
	VoteCount        int64
	RuntimeMinutes   *int
	SeriesStatus     string
	SeriesType       string
	SeasonCount      *int
	EpisodeCount     *int
}

// Metadata uses source-reported countries and facets only. Language never implies nationality.
func (c *Client) Metadata(ctx context.Context, kind string, id int64) (TitleMetadata, error) {
	endpoint := fmt.Sprintf("movie/%d", id)
	if kind == "series" || kind == "anime" {
		endpoint = fmt.Sprintf("tv/%d", id)
	}
	var raw json.RawMessage
	if err := c.get(ctx, endpoint, url.Values{"language": {"en-US"}}, &raw); err != nil {
		return TitleMetadata{}, err
	}
	return parseTitleMetadata(raw)
}
func parseTitleMetadata(raw []byte) (TitleMetadata, error) {
	var source struct {
		Genres []struct {
			ID   int    `json:"id"`
			Name string `json:"name"`
		} `json:"genres"`
		OriginCountries     []string `json:"origin_country"`
		ProductionCountries []struct {
			Code string `json:"iso_3166_1"`
		} `json:"production_countries"`
		Language       string  `json:"original_language"`
		ReleaseDate    string  `json:"release_date"`
		FirstAirDate   string  `json:"first_air_date"`
		Rating         float64 `json:"vote_average"`
		Popularity     float64 `json:"popularity"`
		VoteCount      int64   `json:"vote_count"`
		Runtime        *int    `json:"runtime"`
		EpisodeRuntime []int   `json:"episode_run_time"`
		Status         string  `json:"status"`
		Type           string  `json:"type"`
		Seasons        *int    `json:"number_of_seasons"`
		Episodes       *int    `json:"number_of_episodes"`
	}
	if err := json.Unmarshal(raw, &source); err != nil {
		return TitleMetadata{}, err
	}
	result := TitleMetadata{GenreIDs: []int{}, Genres: []string{}, OriginCountries: []string{}, OriginalLanguage: strings.ToLower(strings.TrimSpace(source.Language)), Year: parseYear(source.ReleaseDate), Rating: source.Rating, Popularity: source.Popularity, VoteCount: source.VoteCount, SeriesStatus: source.Status, SeriesType: source.Type, SeasonCount: source.Seasons, EpisodeCount: source.Episodes}
	if result.Year == 0 {
		result.Year = parseYear(source.FirstAirDate)
	}
	if source.Runtime != nil && *source.Runtime > 0 {
		result.RuntimeMinutes = source.Runtime
	} else {
		for _, runtime := range source.EpisodeRuntime {
			if runtime > 0 {
				value := runtime
				result.RuntimeMinutes = &value
				break
			}
		}
	}
	countries := map[string]bool{}
	add := func(raw string) {
		code := strings.ToUpper(strings.TrimSpace(raw))
		if len(code) == 2 && !countries[code] {
			countries[code] = true
			result.OriginCountries = append(result.OriginCountries, code)
		}
	}
	for _, code := range source.OriginCountries {
		add(code)
	}
	for _, country := range source.ProductionCountries {
		add(country.Code)
	}
	genres := map[int]bool{}
	for _, genre := range source.Genres {
		if genre.ID > 0 && !genres[genre.ID] {
			genres[genre.ID] = true
			result.GenreIDs = append(result.GenreIDs, genre.ID)
			if genre.Name != "" {
				result.Genres = append(result.Genres, genre.Name)
			}
		}
	}
	return result, nil
}
