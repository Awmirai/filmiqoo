package com.filmiqoo.app

data class PersonalizedHomeBundle(
    val preferredKind:String,
    val preferredLanguage:String,
    val forYou:List<MediaItem>,
    val watchlist:List<MediaItem>,
    val communityHot:List<MediaItem>,
    val newForYou:List<MediaItem>
)

class HomePersonalizationRepository(
    private val backend:BackendRepository
) {
    suspend fun load():PersonalizedHomeBundle {
        val root=backend.getJson("/v1/home/personalized",authorized=true)
        val signals=root.optJSONObject("signals")
        return PersonalizedHomeBundle(
            preferredKind=signals?.optString("preferredKind").orEmpty(),
            preferredLanguage=signals?.optString("preferredLanguage").orEmpty(),
            forYou=parse(root.optJSONArray("forYou")),
            watchlist=parse(root.optJSONArray("watchlist")),
            communityHot=parse(root.optJSONArray("communityHot")),
            newForYou=parse(root.optJSONArray("newForYou"))
        )
    }

    private fun parse(arr:org.json.JSONArray?):List<MediaItem> = buildList {
        if(arr==null) return@buildList
        for(i in 0 until arr.length()) {
            val x=arr.optJSONObject(i) ?: continue
            add(
                MediaItem(
                    id=if(x.isNull("tmdbId"))0 else x.optInt("tmdbId"),
                    type=if(x.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                    title=x.optString("title").ifBlank{x.optString("originalTitle")},
                    originalTitle=x.optString("originalTitle"),
                    overview=x.optString("overview"),
                    posterPath=x.optString("posterUrl").takeIf(String::isNotBlank),
                    backdropPath=x.optString("backdropUrl").takeIf(String::isNotBlank),
                    vote=x.optDouble("rating",0.0),
                    date=x.optInt("year",0).takeIf{it>0}?.toString().orEmpty(),
                    backendId=x.optString("id").takeIf(String::isNotBlank),
                    mediaVersionId=x.optString("mediaVersionId").takeIf(String::isNotBlank),
                    streamReady=x.optBoolean("streamReady"),
                    quality=x.optString("quality")
                )
            )
        }
    }
}
