package com.filmiqoo.app

import org.json.JSONObject

data class EpisodeWatchState(
    val seasonId:String,
    val seasonNumber:Int,
    val episodeId:String,
    val episodeNumber:Int,
    val positionMs:Long,
    val durationMs:Long,
    val completed:Boolean,
    val progress:Float
)

data class SeriesWatchProgress(
    val mediaTitleId:String,
    val watchedCount:Long,
    val totalCount:Long,
    val progress:Float,
    val episodes:Map<String,EpisodeWatchState>
)

class SeriesProgressRepository(
    private val backend:BackendRepository
) {
    suspend fun load(mediaTitleId:String):SeriesWatchProgress {
        val root=backend.getJson(
            "/v1/watch/series/"+mediaTitleId+"/progress",
            authorized=true
        )
        val arr=root.optJSONArray("items")
        val states=buildMap<String,EpisodeWatchState> {
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val state=EpisodeWatchState(
                    seasonId=x.optString("seasonId"),
                    seasonNumber=x.optInt("seasonNumber"),
                    episodeId=x.optString("episodeId"),
                    episodeNumber=x.optInt("episodeNumber"),
                    positionMs=x.optLong("positionMs"),
                    durationMs=x.optLong("durationMs"),
                    completed=x.optBoolean("completed"),
                    progress=x.optDouble("progress",0.0).toFloat().coerceIn(0f,1f)
                )
                put(state.episodeId,state)
            }
        }
        return SeriesWatchProgress(
            mediaTitleId=root.optString("mediaTitleId"),
            watchedCount=root.optLong("watchedCount"),
            totalCount=root.optLong("totalCount"),
            progress=root.optDouble("progress",0.0).toFloat().coerceIn(0f,1f),
            episodes=states
        )
    }

    suspend fun setEpisodeWatched(episodeId:String,watched:Boolean) {
        backend.postJson(
            "/v1/watch/episodes/"+episodeId+"/status",
            JSONObject().put("watched",watched),
            authorized=true
        )
    }

    suspend fun setSeasonWatched(seasonId:String,watched:Boolean) {
        backend.postJson(
            "/v1/watch/seasons/"+seasonId+"/status",
            JSONObject().put("watched",watched),
            authorized=true
        )
    }
}
