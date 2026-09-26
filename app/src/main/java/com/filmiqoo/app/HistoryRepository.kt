package com.filmiqoo.app

import org.json.JSONObject

data class WatchHistoryItem(
    val target: PlaybackTarget,
    val media: MediaItem,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: String,
    val episodeLabel: String
) {
    val progress: Float
        get()=if(durationMs>0) (positionMs.toFloat()/durationMs.toFloat()).coerceIn(0f,1f) else 0f
}

class HistoryRepository(
    private val backend: BackendRepository
) {
    suspend fun history(): List<WatchHistoryItem> {
        val root=backend.getJson("/v1/watch/history",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
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
                    streamReady=true,
                    quality=x.optString("quality")
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
                        episodeLabel=label
                    )
                )
            }
        }
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
