package com.filmiqoo.app

import org.json.JSONObject

data class FriendWatchingNow(
    val user: SocialAuthor,
    val media: MediaItem,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val positionMs: Long,
    val updatedAt: String
) {
    val episodeLabel:String
        get()=when {
            seasonNumber!=null && episodeNumber!=null -> "S"+seasonNumber+" • E"+episodeNumber
            episodeNumber!=null -> "قسمت "+episodeNumber
            else -> ""
        }
}

class FriendActivityRepository(
    private val backend:BackendRepository
) {
    suspend fun followingWatching():List<FriendWatchingNow> {
        val root=backend.getJson(
            "/v1/social/activity/following",
            authorized=true
        )
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val u=x.optJSONObject("user") ?: JSONObject()
                val m=x.optJSONObject("media") ?: JSONObject()
                val e=x.optJSONObject("episode") ?: JSONObject()

                add(
                    FriendWatchingNow(
                        user=SocialAuthor(
                            id=u.optString("id"),
                            username=u.optString("username"),
                            displayName=u.optString("displayName"),
                            avatarUrl=u.optString("avatarUrl"),
                            verified=u.optBoolean("verified")
                        ),
                        media=MediaItem(
                            id=if(m.isNull("tmdbId"))0 else m.optInt("tmdbId"),
                            type=if(m.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                            title=m.optString("title").ifBlank{m.optString("originalTitle")},
                            originalTitle=m.optString("originalTitle"),
                            overview=m.optString("overview"),
                            posterPath=m.optString("posterUrl").takeIf(String::isNotBlank),
                            backdropPath=m.optString("backdropUrl").takeIf(String::isNotBlank),
                            vote=m.optDouble("rating",0.0),
                            date=if(m.isNull("year"))"" else m.optInt("year").toString(),
                            backendId=m.optString("id").takeIf(String::isNotBlank)
                        ),
                        seasonNumber=if(e.isNull("seasonNumber"))null else e.optInt("seasonNumber"),
                        episodeNumber=if(e.isNull("episodeNumber"))null else e.optInt("episodeNumber"),
                        episodeTitle=e.optString("title").takeIf(String::isNotBlank),
                        positionMs=x.optLong("positionMs"),
                        updatedAt=x.optString("updatedAt")
                    )
                )
            }
        }
    }
}
