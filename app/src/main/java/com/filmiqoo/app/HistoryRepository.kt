package com.filmiqoo.app

import org.json.JSONObject

data class WatchHistoryItem(
    val target: PlaybackTarget,
    val media: MediaItem,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: String,
    val episodeLabel: String,
    val episodeId:String?=null,val seasonNumber:Int?=null,val episodeNumber:Int?=null
) {
    val progress: Float
        get()=if(durationMs>0) (positionMs.toFloat()/durationMs.toFloat()).coerceIn(0f,1f) else 0f
}

data class WatchHistoryPage(val items:List<WatchHistoryItem>,val page:Int,val hasMore:Boolean)

class HistoryRepository(
    private val backend: BackendRepository
) {
    suspend fun history():List<WatchHistoryItem> = historyPage().items
    suspend fun historyPage(page:Int=1):WatchHistoryPage {
        require(page in 1..10000)
        val root=backend.getJson("/v1/watch/history?page="+page,authorized=true)
        if(page>1 && root.optInt("historyVersion")<1)error("نمایش ادامهٔ تاریخچه به ارتقای سرور نیاز دارد.")
        val arr=root.optJSONArray("items") ?: return WatchHistoryPage(emptyList(),page,false)
        val values=buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val m=x.optJSONObject("media") ?: continue
                val e=x.optJSONObject("episode")
                val versionId=x.optString("mediaVersionId")
                if(versionId.isBlank()) continue

                val media=MediaItem(
                    id=if(m.isNull("tmdbId"))0 else m.optInt("tmdbId"),
                    type=if(m.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                    title=m.optString("title").ifBlank{m.optString("originalTitle")},
                    originalTitle=m.optString("originalTitle"),
                    posterPath=m.optString("posterUrl").takeIf(String::isNotBlank),
                    backdropPath=m.optString("backdropUrl").takeIf(String::isNotBlank),
                    vote=m.optDouble("rating"),
                    date=m.optInt("year",0).takeIf{it>0}?.toString().orEmpty(),
                    backendId=m.optString("id"),
                    mediaVersionId=versionId,
                    streamReady=x.optBoolean("streamReady",true),
                    quality=x.optString("quality"),
                    hasPersianDub=x.optBoolean("isPersianDubbed"),hasPersianSubtitle=x.optBoolean("hasPersianSubtitle"),
                    originalLanguage=m.optString("originalLanguage"),
                    originCountries=buildList { val countries=m.optJSONArray("originCountries");if(countries!=null)for(j in 0 until countries.length())add(countries.optString(j)) }
                )

                val season=if(e==null || e.isNull("seasonNumber"))null else e.optInt("seasonNumber")
                val episode=if(e==null || e.isNull("episodeNumber"))null else e.optInt("episodeNumber")
                val episodeName=e?.optString("name").orEmpty()
                val label=if(season!=null && episode!=null) {
                    "S"+season.toString().padStart(2,'0')+
                        "E"+episode.toString().padStart(2,'0')+
                        if(episodeName.isBlank())"" else " • "+episodeName
                } else x.optString("quality")

                add(
                    WatchHistoryItem(
                        target=PlaybackTarget(
                            mediaVersionId=versionId,
                            title=media.title,
                            mediaTitleId=media.backendId,
                            subtitle=label,
                            posterUrl=media.posterPath,
                            startPositionMs=x.optLong("positionMs")
                        ),
                        media=media,
                        positionMs=x.optLong("positionMs"),
                        durationMs=x.optLong("durationMs"),
                        completed=x.optBoolean("completed"),
                        updatedAt=x.optString("updatedAt"),
                        episodeLabel=label,episodeId=e?.optString("id")?.takeUnless{it.isBlank()||it=="null"},seasonNumber=season,episodeNumber=episode
                    )
                )
            }
        }
        return WatchHistoryPage(values,page,root.optBoolean("hasMore"))
    }

    suspend fun remove(versionId: String) {
        backend.postJson(
            "/v1/watch/history/"+versionId+"/remove",
            JSONObject(),
            authorized=true
        )
    }

    suspend fun clear() {
        backend.postJson("/v1/watch/history/clear",JSONObject(),authorized=true)
    }
}
