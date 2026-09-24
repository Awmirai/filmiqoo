package com.filmiqoo.app

data class ReputationBadge(
    val id:String,
    val title:String,
    val description:String,
    val icon:String,
    val earned:Boolean,
    val current:Long,
    val target:Long
) {
    val progress:Float
        get()=if(target<=0L)1f else (current.toFloat()/target.toFloat()).coerceIn(0f,1f)
}

data class ReputationStats(
    val reviews:Long,
    val reviewLikes:Long,
    val posts:Long,
    val reels:Long,
    val comments:Long,
    val publicCollections:Long,
    val collectionFollowers:Long,
    val reelViews:Long,
    val engagement:Long,
    val topGenre:String,
    val topGenreReviews:Long,
    val animeReviews:Long
)

data class UserReputation(
    val userId:String,
    val username:String,
    val displayName:String,
    val verified:Boolean,
    val score:Long,
    val level:Int,
    val nextLevelScore:Long,
    val stats:ReputationStats,
    val badges:List<ReputationBadge>
)

class ReputationRepository(
    private val backend:BackendRepository
) {
    suspend fun load(userId:String):UserReputation {
        val root=backend.getJson(
            "/v1/social/users/"+userId+"/reputation",
            authorized=false
        )
        val user=root.optJSONObject("user") ?: org.json.JSONObject()
        val stats=root.optJSONObject("stats") ?: org.json.JSONObject()
        val arr=root.optJSONArray("badges")

        val badges=buildList {
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    ReputationBadge(
                        id=x.optString("id"),
                        title=x.optString("title"),
                        description=x.optString("description"),
                        icon=x.optString("icon"),
                        earned=x.optString("progress")=="earned",
                        current=x.optLong("current"),
                        target=x.optLong("target")
                    )
                )
            }
        }

        return UserReputation(
            userId=user.optString("id"),
            username=user.optString("username"),
            displayName=user.optString("displayName"),
            verified=user.optBoolean("verified"),
            score=root.optLong("score"),
            level=root.optInt("level",1),
            nextLevelScore=root.optLong("nextLevelScore"),
            stats=ReputationStats(
                reviews=stats.optLong("reviews"),
                reviewLikes=stats.optLong("reviewLikes"),
                posts=stats.optLong("posts"),
                reels=stats.optLong("reels"),
                comments=stats.optLong("comments"),
                publicCollections=stats.optLong("publicCollections"),
                collectionFollowers=stats.optLong("collectionFollowers"),
                reelViews=stats.optLong("reelViews"),
                engagement=stats.optLong("engagement"),
                topGenre=stats.optString("topGenre"),
                topGenreReviews=stats.optLong("topGenreReviews"),
                animeReviews=stats.optLong("animeReviews")
            ),
            badges=badges
        )
    }
}
