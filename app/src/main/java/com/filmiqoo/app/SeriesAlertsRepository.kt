package com.filmiqoo.app

import org.json.JSONObject

data class SeriesSubscriptionState(
    val following:Boolean,
    val notifyNewEpisode:Boolean,
    val notifyStreamReady:Boolean
)

data class SeriesCalendarItem(
    val media:MediaItem,
    val episodeId:String,
    val seasonNumber:Int,
    val episodeNumber:Int,
    val episodeName:String,
    val overview:String,
    val stillUrl:String?,
    val runtimeMinutes:Int,
    val airDate:String,
    val streamReady:Boolean
) {
    val episodeLabel:String
        get()="S"+seasonNumber.toString().padStart(2,'0')+
            "E"+episodeNumber.toString().padStart(2,'0')
}

class SeriesAlertsRepository(
    private val backend:BackendRepository
) {
    suspend fun status(mediaId:String):SeriesSubscriptionState {
        val o=backend.getJson(
            "/v1/series/"+mediaId+"/subscription",
            authorized=true
        )
        return parseState(o)
    }

    suspend fun update(
        mediaId:String,
        following:Boolean,
        notifyNewEpisode:Boolean=true,
        notifyStreamReady:Boolean=true
    ):SeriesSubscriptionState {
        val o=backend.postJson(
            "/v1/series/"+mediaId+"/subscription",
            JSONObject()
                .put("following",following)
                .put("notifyNewEpisode",notifyNewEpisode)
                .put("notifyStreamReady",notifyStreamReady),
            authorized=true
        )
        return parseState(o)
    }

    suspend fun calendar(days:Int=60):List<SeriesCalendarItem> {
        val root=backend.getJson(
            "/v1/series/calendar?days="+days.coerceIn(7,180),
            authorized=true
        )
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val m=x.optJSONObject("media") ?: continue
                val e=x.optJSONObject("episode") ?: continue
                val backendId=m.optString("id").takeIf(String::isNotBlank) ?: continue
                add(
                    SeriesCalendarItem(
                        media=MediaItem(
                            id=if(m.isNull("tmdbId"))0 else m.optInt("tmdbId"),
                            type=MediaType.TV,
                            title=m.optString("title").ifBlank{m.optString("originalTitle")},
                            originalTitle=m.optString("originalTitle"),
                            posterPath=m.optString("posterUrl").takeIf(String::isNotBlank),
                            backdropPath=m.optString("backdropUrl").takeIf(String::isNotBlank),
                            vote=m.optDouble("rating",0.0),
                            backendId=backendId
                        ),
                        episodeId=e.optString("id"),
                        seasonNumber=e.optInt("seasonNumber"),
                        episodeNumber=e.optInt("episodeNumber"),
                        episodeName=e.optString("name"),
                        overview=e.optString("overview"),
                        stillUrl=e.optString("stillUrl").takeIf(String::isNotBlank),
                        runtimeMinutes=e.optInt("runtimeMinutes"),
                        airDate=e.optString("airDate"),
                        streamReady=e.optBoolean("streamReady")
                    )
                )
            }
        }
    }

    private fun parseState(o:JSONObject)=SeriesSubscriptionState(
        following=o.optBoolean("following"),
        notifyNewEpisode=o.optBoolean("notifyNewEpisode",true),
        notifyStreamReady=o.optBoolean("notifyStreamReady",true)
    )
}
