package com.filmiqoo.app

data class ReleaseCenterItem(
    val media: MediaItem,
    val releaseDate: String,
    val daysAway: Int?,
    val originalLanguage: String
)

class ReleaseCenterRepository(
    private val backend: BackendRepository
) {
    suspend fun releases(): List<ReleaseCenterItem> {
        val root=backend.getJson("/v1/releases",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    ReleaseCenterItem(
                        media=MediaItem(
                            id=x.optInt("tmdbId"),
                            type=if(x.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                            title=x.optString("title").ifBlank{x.optString("originalTitle")},
                            originalTitle=x.optString("originalTitle"),
                            overview=x.optString("overview"),
                            posterPath=x.optString("posterUrl").takeIf(String::isNotBlank),
                            backdropPath=x.optString("backdropUrl").takeIf(String::isNotBlank),
                            vote=x.optDouble("rating"),
                            date=x.optString("date"),
                            backendId=x.optString("backendId").takeIf(String::isNotBlank),
                            mediaVersionId=x.optString("mediaVersionId").takeIf(String::isNotBlank),
                            streamReady=x.optBoolean("streamReady"),
                            quality=x.optString("quality")
                        ),
                        releaseDate=x.optString("date"),
                        daysAway=if(x.isNull("daysAway"))null else x.optInt("daysAway"),
                        originalLanguage=x.optString("originalLanguage")
                    )
                )
            }
        }
    }
}
