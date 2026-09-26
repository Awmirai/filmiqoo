package tmdb

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Client struct {
	token string
	http *http.Client
}

type TitleMatch struct {
	TMDBID int64
	Kind string
	Title string
	OriginalTitle string
	Overview string
	Year int
	PosterURL string
	BackdropURL string
	Rating float64
	OriginalLanguage string
	Genres []string
	AudienceLevel string
	Confidence int
}

type Episode struct {
	Name string
	Overview string
	StillURL string
	AirDate string
	RuntimeMinutes int
	TMDBEpisodeID int64
}

func New(token string) *Client {
	return &Client{
		token: strings.TrimSpace(token),
		http: &http.Client{Timeout: 12*time.Second},
	}
}

func (c *Client) Enabled() bool { return c != nil && c.token != "" }

func (c *Client) RawJSON(ctx context.Context, path string, params url.Values) (json.RawMessage, error) {
	if !c.Enabled() { return nil, errors.New("TMDB token is not configured") }
	var raw json.RawMessage
	if err := c.get(ctx, path, params, &raw); err != nil { return nil, err }
	return raw, nil
}

func (c *Client) Search(ctx context.Context, kind, title string, year *int) (TitleMatch, error) {
	if !c.Enabled() { return TitleMatch{}, errors.New("TMDB token is not configured") }
	endpoint := "search/movie"
	if kind == "series" || kind == "anime" { endpoint = "search/tv" }

	params := url.Values{}
	params.Set("query", title)
	params.Set("language", "fa-IR")
	params.Set("include_adult", "false")
	if year != nil {
		if endpoint == "search/movie" { params.Set("year", strconv.Itoa(*year)) } else { params.Set("first_air_date_year", strconv.Itoa(*year)) }
	}

	var response struct {
		Results []struct {
			ID int64 `json:"id"`
			Title string `json:"title"`
			OriginalTitle string `json:"original_title"`
			Name string `json:"name"`
			OriginalName string `json:"original_name"`
			Overview string `json:"overview"`
			PosterPath string `json:"poster_path"`
			BackdropPath string `json:"backdrop_path"`
			ReleaseDate string `json:"release_date"`
			FirstAirDate string `json:"first_air_date"`
			VoteAverage float64 `json:"vote_average"`
			OriginalLanguage string `json:"original_language"`
			GenreIDs []int `json:"genre_ids"`
			Adult bool `json:"adult"`
		} `json:"results"`
	}
	if err := c.get(ctx, endpoint, params, &response); err != nil { return TitleMatch{}, err }
	if len(response.Results) == 0 {
		return TitleMatch{}, fmt.Errorf("no TMDB match for %q", title)
	}

	want := normalize(title)
	bestScore := -1
	bestAdult := false
	var best TitleMatch
	for _, r := range response.Results {
		candidateTitle := r.Title
		originalTitle := r.OriginalTitle
		date := r.ReleaseDate
		resolvedKind := "movie"
		if endpoint == "search/tv" {
			candidateTitle = r.Name
			originalTitle = r.OriginalName
			date = r.FirstAirDate
			resolvedKind = kind
		}
		score := titleScore(want, normalize(candidateTitle), normalize(originalTitle))
		resolvedYear := parseYear(date)
		if year != nil && resolvedYear != 0 {
			if resolvedYear == *year { score += 10 } else { score -= 8 }
		}
		if score > bestScore {
			bestScore = score
			best = TitleMatch{
				TMDBID:r.ID, Kind:resolvedKind, Title:candidateTitle, OriginalTitle:originalTitle,
				Overview:r.Overview, Year:resolvedYear,
				PosterURL:imageURL("w500",r.PosterPath),
				BackdropURL:imageURL("w1280",r.BackdropPath),
				Rating:r.VoteAverage, OriginalLanguage:r.OriginalLanguage,
				Genres:genreNames(resolvedKind,r.GenreIDs),
				AudienceLevel:"unrated", Confidence:score,
			}
			bestAdult=r.Adult
		}
	}
	if bestScore < 60 {
		return TitleMatch{}, fmt.Errorf("low-confidence TMDB match for %q: %d", title, bestScore)
	}
	if bestAdult {
		best.AudienceLevel="adult"
	} else if level,err:=c.AudienceLevel(ctx,best.Kind,best.TMDBID); err==nil && level!="" {
		best.AudienceLevel=level
	}
	return best, nil
}

func (c *Client) Episode(ctx context.Context, tmdbSeriesID int64, season, episode int) (Episode, error) {
	var response struct {
		ID int64 `json:"id"`
		Name string `json:"name"`
		Overview string `json:"overview"`
		StillPath string `json:"still_path"`
		AirDate string `json:"air_date"`
		Runtime int `json:"runtime"`
	}
	path := fmt.Sprintf("tv/%d/season/%d/episode/%d", tmdbSeriesID, season, episode)
	params := url.Values{}
	params.Set("language","fa-IR")
	if err := c.get(ctx,path,params,&response); err != nil { return Episode{},err }
	if strings.TrimSpace(response.Overview)=="" || strings.TrimSpace(response.Name)=="" {
		var en struct {
			ID int64 `json:"id"`
			Name string `json:"name"`
			Overview string `json:"overview"`
			StillPath string `json:"still_path"`
			AirDate string `json:"air_date"`
			Runtime int `json:"runtime"`
		}
		p2:=url.Values{}
		p2.Set("language","en-US")
		if err:=c.get(ctx,path,p2,&en); err==nil {
			if response.Name=="" { response.Name=en.Name }
			if response.Overview=="" { response.Overview=en.Overview }
			if response.StillPath=="" { response.StillPath=en.StillPath }
			if response.AirDate=="" { response.AirDate=en.AirDate }
			if response.Runtime==0 { response.Runtime=en.Runtime }
			if response.ID==0 { response.ID=en.ID }
		}
	}
	return Episode{
		Name:response.Name,Overview:response.Overview,StillURL:imageURL("w780",response.StillPath),
		AirDate:response.AirDate,RuntimeMinutes:response.Runtime,TMDBEpisodeID:response.ID,
	},nil
}

func (c *Client) get(ctx context.Context, path string, params url.Values, out any) error {
	base, _ := url.Parse("https://api.themoviedb.org/3/" + strings.TrimLeft(path,"/"))
	q := base.Query()
	for k, values := range params {
		for _, v := range values { q.Add(k,v) }
	}
	if !strings.HasPrefix(c.token,"eyJ") { q.Set("api_key",c.token) }
	base.RawQuery=q.Encode()

	req,err:=http.NewRequestWithContext(ctx,http.MethodGet,base.String(),nil)
	if err!=nil { return err }
	req.Header.Set("Accept","application/json")
	if strings.HasPrefix(c.token,"eyJ") { req.Header.Set("Authorization","Bearer "+c.token) }

	resp,err:=c.http.Do(req)
	if err!=nil { return err }
	defer resp.Body.Close()
	if resp.StatusCode<200 || resp.StatusCode>=300 {
		return fmt.Errorf("TMDB %d for %s",resp.StatusCode,path)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func titleScore(want, candidate, original string) int {
	if want=="" { return 0 }
	if want==candidate || want==original { return 100 }
	if strings.Contains(candidate,want) || strings.Contains(want,candidate) { return 82 }
	if strings.Contains(original,want) || strings.Contains(want,original) { return 78 }
	wantParts:=strings.Fields(want)
	if len(wantParts)==0 { return 0 }
	hits:=0
	for _,p:=range wantParts {
		if strings.Contains(candidate,p) || strings.Contains(original,p) { hits++ }
	}
	return hits*70/len(wantParts)
}

func normalize(s string) string {
	s=strings.ToLower(strings.TrimSpace(s))
	var b strings.Builder
	lastSpace:=false
	for _,r:=range s {
		if (r>='a'&&r<='z') || (r>='0'&&r<='9') || r>127 {
			b.WriteRune(r); lastSpace=false
		} else if !lastSpace {
			b.WriteByte(' '); lastSpace=true
		}
	}
	return strings.Join(strings.Fields(b.String())," ")
}

func parseYear(date string) int {
	if len(date)<4 { return 0 }
	n,_:=strconv.Atoi(date[:4])
	return n
}

func imageURL(size,path string) string {
	if strings.TrimSpace(path)=="" { return "" }
	return "https://image.tmdb.org/t/p/"+size+path
}


func genreNames(kind string, ids []int) []string {
	movie:=map[int]string{
		28:"Action",12:"Adventure",16:"Animation",35:"Comedy",80:"Crime",99:"Documentary",
		18:"Drama",10751:"Family",14:"Fantasy",36:"History",27:"Horror",10402:"Music",
		9648:"Mystery",10749:"Romance",878:"Science Fiction",10770:"TV Movie",
		53:"Thriller",10752:"War",37:"Western",
	}
	tv:=map[int]string{
		10759:"Action & Adventure",16:"Animation",35:"Comedy",80:"Crime",99:"Documentary",
		18:"Drama",10751:"Family",10762:"Kids",9648:"Mystery",10763:"News",10764:"Reality",
		10765:"Sci-Fi & Fantasy",10766:"Soap",10767:"Talk",10768:"War & Politics",37:"Western",
	}
	source:=movie
	if kind=="series" || kind=="anime" { source=tv }
	out:=make([]string,0,len(ids))
	seen:=map[string]bool{}
	for _,id:=range ids {
		name:=source[id]
		if name=="" || seen[name] { continue }
		seen[name]=true
		out=append(out,name)
	}
	return out
}
