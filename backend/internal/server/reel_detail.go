package server

import (
    "net/http"

    "github.com/go-chi/chi/v5"
)

func (s *Server) reelDetail(w http.ResponseWriter,r *http.Request) {
    id:=chi.URLParam(r,"id")
    row:=s.db.QueryRow(r.Context(), `
        SELECT rl.id::text,rl.caption,rl.playback_url,rl.cover_url,rl.duration_ms,
               rl.like_count,rl.comment_count,rl.save_count,rl.share_count,rl.view_count,rl.spoiler,
               p.user_id::text,p.display_name,p.username::text,p.avatar_url,p.verified,
               mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
               mt.year,mt.rating
          FROM reels rl
          JOIN profiles p ON p.user_id=rl.creator_user_id
          LEFT JOIN media_titles mt ON mt.id=rl.media_title_id
         WHERE rl.id=$1 AND rl.status='published'
    `,id)

    var reelID,caption,playbackURL,coverURL,authorID,displayName,username,avatar string
    var duration int64
    var likes,comments,saves,shares,views int64
    var spoiler,verified bool
    var mediaID,kind,title,originalTitle,poster,backdrop *string
    var tmdbID *int64
    var year *int
    var rating *float64

    if err:=row.Scan(
        &reelID,&caption,&playbackURL,&coverURL,&duration,
        &likes,&comments,&saves,&shares,&views,&spoiler,
        &authorID,&displayName,&username,&avatar,&verified,
        &mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,
    ); err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"reel not found"})
        return
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "id":reelID,"caption":caption,"playbackUrl":playbackURL,"coverUrl":coverURL,
        "durationMs":duration,"likes":likes,"comments":comments,"saves":saves,
        "shares":shares,"views":views,"spoiler":spoiler,
        "author":map[string]any{
            "id":authorID,"displayName":displayName,"username":username,
            "avatarUrl":avatar,"verified":verified,
        },
        "media":map[string]any{
            "id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,
            "originalTitle":originalTitle,"posterUrl":poster,"backdropUrl":backdrop,
            "year":year,"rating":rating,
        },
    })
}
