package tmdb

import (
	"context"
	"net/url"
	"sort"
	"strings"
)

type ReleaseItem struct {
	TMDBID int64
	Kind string
	Title string
	OriginalTitle string
	Overview string
	Date string
	PosterURL string
	BackdropURL string
	Rating float64
	OriginalLanguage string
}

func (c *Client) Releases(ctx context.Context) ([]ReleaseItem,error) {
	if !c.Enabled() { return nil, nil }

	movies,err:=c.movieUpcoming(ctx)
	if err!=nil { return nil,err }
	tv,err:=c.tvOnAir(ctx)
	if err!=nil { return nil,err }

	items:=append(movies,tv...)
	sort.SliceStable(items,func(i,j int) bool {
		a:=strings.TrimSpace(items[i].Date)
		b:=strings.TrimSpace(items[j].Date)
		if a=="" { return false }
		if b=="" { return true }
		return a<b
	})
	return items,nil
}

func (c *Client) movieUpcoming(ctx context.Context) ([]ReleaseItem,error) {
	var response struct {
		Results []struct {
			ID int64 `json:"id"`
			Title string `json:"title"`
			OriginalTitle string `json:"original_title"`
			Overview string `json:"overview"`
			ReleaseDate string `json:"release_date"`
			PosterPath string `json:"poster_path"`
			BackdropPath string `json:"backdrop_path"`
			VoteAverage float64 `json:"vote_average"`
			OriginalLanguage string `json:"original_language"`
		} `json:"results"`
	}
	params:=url.Values{}
	params.Set("language","fa-IR")
	params.Set("page","1")
	if err:=c.get(ctx,"movie/upcoming",params,&response); err!=nil { return nil,err }

	items:=make([]ReleaseItem,0,len(response.Results))
	for _,r:=range response.Results {
		items=append(items,ReleaseItem{
			TMDBID:r.ID,Kind:"movie",Title:r.Title,OriginalTitle:r.OriginalTitle,
			Overview:r.Overview,Date:r.ReleaseDate,
			PosterURL:imageURL("w500",r.PosterPath),
			BackdropURL:imageURL("w1280",r.BackdropPath),
			Rating:r.VoteAverage,OriginalLanguage:r.OriginalLanguage,
		})
	}
	return items,nil
}

func (c *Client) tvOnAir(ctx context.Context) ([]ReleaseItem,error) {
	var response struct {
		Results []struct {
			ID int64 `json:"id"`
			Name string `json:"name"`
			OriginalName string `json:"original_name"`
			Overview string `json:"overview"`
			FirstAirDate string `json:"first_air_date"`
			PosterPath string `json:"poster_path"`
			BackdropPath string `json:"backdrop_path"`
			VoteAverage float64 `json:"vote_average"`
			OriginalLanguage string `json:"original_language"`
		} `json:"results"`
	}
	params:=url.Values{}
	params.Set("language","fa-IR")
	params.Set("page","1")
	if err:=c.get(ctx,"tv/on_the_air",params,&response); err!=nil { return nil,err }

	items:=make([]ReleaseItem,0,len(response.Results))
	for _,r:=range response.Results {
		items=append(items,ReleaseItem{
			TMDBID:r.ID,Kind:"tv",Title:r.Name,OriginalTitle:r.OriginalName,
			Overview:r.Overview,Date:r.FirstAirDate,
			PosterURL:imageURL("w500",r.PosterPath),
			BackdropURL:imageURL("w1280",r.BackdropPath),
			Rating:r.VoteAverage,OriginalLanguage:r.OriginalLanguage,
		})
	}
	return items,nil
}
