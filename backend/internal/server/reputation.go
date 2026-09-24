package server

import (
    "net/http"
    "strings"

    "github.com/go-chi/chi/v5"
)

func (s *Server) userReputation(w http.ResponseWriter,r *http.Request) {
    userID:=chi.URLParam(r,"id")

    var displayName,username string
    var verified bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT display_name,username::text,verified
          FROM profiles
         WHERE user_id=$1
    `,userID).Scan(&displayName,&username,&verified); err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"user not found"})
        return
    }

    var reviewCount,reviewLikes int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*),COALESCE(SUM(like_count),0)
          FROM media_reviews
         WHERE user_id=$1
    `,userID).Scan(&reviewCount,&reviewLikes)

    var postCount,postEngagement int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*),
               COALESCE(SUM(like_count+comment_count+save_count+share_count),0)
          FROM posts
         WHERE author_user_id=$1 AND status='published'
    `,userID).Scan(&postCount,&postEngagement)

    var reelCount,reelEngagement,reelViews int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*),
               COALESCE(SUM(like_count+comment_count+save_count+share_count),0),
               COALESCE(SUM(view_count),0)
          FROM reels
         WHERE creator_user_id=$1 AND status='published'
    `,userID).Scan(&reelCount,&reelEngagement,&reelViews)

    var commentCount int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*) FROM comments WHERE author_user_id=$1
    `,userID).Scan(&commentCount)

    var collectionCount,collectionFollowers int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*),COALESCE(SUM(follower_count),0)
          FROM collections
         WHERE owner_user_id=$1 AND visibility='public'
    `,userID).Scan(&collectionCount,&collectionFollowers)

    var animeReviews int64
    _=s.db.QueryRow(r.Context(),`
        SELECT COUNT(*)
          FROM media_reviews mr
          JOIN media_titles mt ON mt.id=mr.media_title_id
         WHERE mr.user_id=$1 AND mt.kind='anime'
    `,userID).Scan(&animeReviews)

    topGenre:=""
    var topGenreCount int64
    _=s.db.QueryRow(r.Context(),`
        SELECT genre,COUNT(*) AS c
          FROM media_reviews mr
          JOIN media_titles mt ON mt.id=mr.media_title_id
          CROSS JOIN LATERAL jsonb_array_elements_text(mt.genres) AS genre
         WHERE mr.user_id=$1
         GROUP BY genre
         ORDER BY c DESC,genre ASC
         LIMIT 1
    `,userID).Scan(&topGenre,&topGenreCount)

    score:=
        reviewCount*25+
        reviewLikes*2+
        postCount*5+
        postEngagement+
        reelCount*7+
        reelEngagement+
        min64(reelViews/100,500)+
        commentCount*2+
        collectionCount*20+
        collectionFollowers*3

    if score<0 { score=0 }
    level:=int(score/250)+1
    if level>50 { level=50 }
    nextLevelScore:=int64(level)*250
    if level>=50 { nextLevelScore=score }

    badges:=make([]map[string]any,0)

    addBadge:=func(id,title,description,icon,progress string,current,target int64) {
        badges=append(badges,map[string]any{
            "id":id,
            "title":title,
            "description":description,
            "icon":icon,
            "progress":progress,
            "current":current,
            "target":target,
        })
    }

    if reviewCount>=5 {
        addBadge(
            "active_reviewer",
            "نقدنویس فعال",
            "حداقل ۵ Review عمومی در Filmiqoo",
            "rate_review",
            "earned",
            reviewCount,
            5,
        )
    }
    if reviewCount>=20 && reviewLikes>=50 {
        addBadge(
            "top_reviewer",
            "Top Reviewer",
            "۲۰ Review عمومی با حداقل ۵۰ Like روی Reviewها",
            "star",
            "earned",
            reviewLikes,
            50,
        )
    }
    if collectionCount>=3 {
        addBadge(
            "curator",
            "Curator",
            "حداقل ۳ Collection عمومی ساخته شده",
            "collections",
            "earned",
            collectionCount,
            3,
        )
    }
    if collectionFollowers>=100 {
        addBadge(
            "followed_curator",
            "Curator محبوب",
            "Collectionهای عمومی در مجموع حداقل ۱۰۰ Follower دارند",
            "groups",
            "earned",
            collectionFollowers,
            100,
        )
    }
    if postCount+reelCount>=30 && postEngagement+reelEngagement>=100 {
        addBadge(
            "community_voice",
            "Community Voice",
            "فعالیت عمومی پیوسته با Engagement واقعی Community",
            "campaign",
            "earned",
            postEngagement+reelEngagement,
            100,
        )
    }
    if animeReviews>=5 {
        addBadge(
            "anime_specialist",
            "Anime Specialist",
            "حداقل ۵ Review عمومی برای عنوان‌های Anime",
            "animation",
            "earned",
            animeReviews,
            5,
        )
    }
    if topGenre!="" && topGenreCount>=5 {
        title:=strings.TrimSpace(topGenre)+" Expert"
        addBadge(
            "genre_"+strings.ToLower(strings.ReplaceAll(topGenre," ","_")),
            title,
            "بر اساس حداقل ۵ Review عمومی در این ژانر",
            "local_movies",
            "earned",
            topGenreCount,
            5,
        )
    }

    // Surface useful progress even before a badge is earned.
    if reviewCount<5 {
        addBadge(
            "active_reviewer_progress",
            "نقدنویس فعال",
            "برای گرفتن Badge، ۵ Review عمومی ثبت کن",
            "rate_review",
            "progress",
            reviewCount,
            5,
        )
    }
    if collectionCount<3 {
        addBadge(
            "curator_progress",
            "Curator",
            "برای گرفتن Badge، ۳ Collection عمومی بساز",
            "collections",
            "progress",
            collectionCount,
            3,
        )
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "user":map[string]any{
            "id":userID,
            "username":username,
            "displayName":displayName,
            "verified":verified,
        },
        "score":score,
        "level":level,
        "nextLevelScore":nextLevelScore,
        "stats":map[string]any{
            "reviews":reviewCount,
            "reviewLikes":reviewLikes,
            "posts":postCount,
            "reels":reelCount,
            "comments":commentCount,
            "publicCollections":collectionCount,
            "collectionFollowers":collectionFollowers,
            "reelViews":reelViews,
            "engagement":postEngagement+reelEngagement,
            "topGenre":topGenre,
            "topGenreReviews":topGenreCount,
            "animeReviews":animeReviews,
        },
        "badges":badges,
    })
}

func min64(a,b int64) int64 {
    if a<b { return a }
    return b
}
