package com.filmiqoo.app

import org.json.JSONObject

data class ReleaseCenterItem(
    val media: MediaItem,
    val releaseDate: String,
    val daysAway: Int?,
    val originalLanguage: String
)

data class ReleaseReminder(
    val tmdbId:Int,
    val kind:String,
    val title:String,
    val releaseDate:String,
    val mediaTitleId:String?,
    val notified:Boolean
) {
    val key:String get()=kind+":"+tmdbId
}

class ReleaseCenterRepository(
    private val backend: BackendRepository
) {
    suspend fun reminders(): List<ReleaseReminder> {
        val root=backend.getJson("/v1/release-reminders",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    ReleaseReminder(
                        tmdbId=x.optInt("tmdbId"),
                        kind=x.optString("kind"),
                        title=x.optString("title"),
                        releaseDate=x.optString("releaseDate"),
                        mediaTitleId=x.optString("mediaTitleId").takeIf(String::isNotBlank),
                        notified=x.optBoolean("notified")
                    )
                )
            }
        }
    }

    suspend fun toggleReminder(item:ReleaseCenterItem):Boolean {
        val kind=if(item.media.type==MediaType.MOVIE)"movie" else "tv"
        val body=JSONObject()
            .put("tmdbId",item.media.id)
            .put("kind",kind)
            .put("title",item.media.title)
            .put("releaseDate",item.releaseDate)
        item.media.backendId?.let { body.put("mediaTitleId",it) }
        return backend.postJson(
            "/v1/release-reminders/toggle",
            body,
            authorized=true
        ).optBoolean("reminded")
    }

    fun reminderKey(item:ReleaseCenterItem):String =
        (if(item.media.type==MediaType.MOVIE)"movie" else "tv")+":"+item.media.id

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
