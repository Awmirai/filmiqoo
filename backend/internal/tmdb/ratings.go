package tmdb

import (
    "context"
    "net/url"
    "strconv"
    "strings"
)

func (c *Client) AudienceLevel(ctx context.Context, kind string, tmdbID int64) (string,error) {
    if kind=="movie" {
        return c.movieAudienceLevel(ctx,tmdbID)
    }
    return c.tvAudienceLevel(ctx,tmdbID)
}

func (c *Client) movieAudienceLevel(ctx context.Context,tmdbID int64) (string,error) {
    var response struct {
        Results []struct {
            Country string `json:"iso_3166_1"`
            ReleaseDates []struct {
                Certification string `json:"certification"`
            } `json:"release_dates"`
        } `json:"results"`
    }
    if err:=c.get(ctx,"movie/"+strconv.FormatInt(tmdbID,10)+"/release_dates",url.Values{},&response); err!=nil {
        return "unrated",err
    }

    preferred:=[]string{"DE","US","GB"}
    for _,country:=range preferred {
        values:=make([]string,0)
        for _,group:=range response.Results {
            if strings.EqualFold(group.Country,country) {
                for _,item:=range group.ReleaseDates {
                    if strings.TrimSpace(item.Certification)!="" {
                        values=append(values,item.Certification)
                    }
                }
            }
        }
        if level:=maxAudienceLevel(values); level!="unrated" { return level,nil }
    }

    fallback:=make([]string,0)
    for _,group:=range response.Results {
        for _,item:=range group.ReleaseDates {
            if strings.TrimSpace(item.Certification)!="" {
                fallback=append(fallback,item.Certification)
            }
        }
    }
    return maxAudienceLevel(fallback),nil
}

func (c *Client) tvAudienceLevel(ctx context.Context,tmdbID int64) (string,error) {
    var response struct {
        Results []struct {
            Country string `json:"iso_3166_1"`
            Rating string `json:"rating"`
        } `json:"results"`
    }
    if err:=c.get(ctx,"tv/"+strconv.FormatInt(tmdbID,10)+"/content_ratings",url.Values{},&response); err!=nil {
        return "unrated",err
    }

    preferred:=[]string{"DE","US","GB"}
    for _,country:=range preferred {
        values:=make([]string,0)
        for _,item:=range response.Results {
            if strings.EqualFold(item.Country,country) && strings.TrimSpace(item.Rating)!="" {
                values=append(values,item.Rating)
            }
        }
        if level:=maxAudienceLevel(values); level!="unrated" { return level,nil }
    }

    fallback:=make([]string,0)
    for _,item:=range response.Results {
        if strings.TrimSpace(item.Rating)!="" { fallback=append(fallback,item.Rating) }
    }
    return maxAudienceLevel(fallback),nil
}

func maxAudienceLevel(values []string) string {
    best:="unrated"
    bestScore:=0
    for _,value:=range values {
        level,score:=classifyCertification(value)
        if score>bestScore {
            best=level
            bestScore=score
        }
    }
    return best
}

func classifyCertification(value string) (string,int) {
    compact:=strings.ToUpper(strings.TrimSpace(value))
    compact=strings.NewReplacer(" ","","-","","_","").Replace(compact)

    switch compact {
    case "G","TVY","TVY7","TVG","U","0","FSK0","6","FSK6":
        return "kids",1
    case "PG","PG13","TVPG","TV14","12","12A","FSK12","13","14","15","16","FSK16","MA15+":
        return "teen",2
    case "R","NC17","TVMA","18","FSK18","R18+","X":
        return "adult",3
    default:
        return "unrated",0
    }
}
