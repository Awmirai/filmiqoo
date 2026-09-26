package com.filmiqoo.app

import org.json.JSONObject

data class PulseState(
    val watchingNow: Long,
    val reactions: Map<String,Long>,
    val recent: Long,
    val live: Boolean
)

data class PulseTrendItem(
    val media:MediaItem,
    val watchingNow:Long,
    val reactions:Long,
    val live:Boolean
)

class PulseRepository(
    private val backend: BackendRepository
) {
    suspend fun load(mediaId:String):PulseState =
        parse(
            backend.getJson(
                "/v1/catalog/"+mediaId+"/pulse",
                authorized=false
            )
        )

    suspend fun trending():List<PulseTrendItem> {
        val root=backend.getJson("/v1/pulse/trending",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val id=x.optString("id")
                val title=x.optString("title")
                if(id.isBlank() || title.isBlank()) continue
                add(
                    PulseTrendItem(
                        media=MediaItem(
                            id=if(x.isNull("tmdbId"))0 else x.optInt("tmdbId"),
                            type=if(x.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                            title=title,
                            originalTitle=x.optString("originalTitle"),
                            posterPath=x.optString("posterUrl").takeIf(String::isNotBlank),
                            backdropPath=x.optString("backdropUrl").takeIf(String::isNotBlank),
                            vote=if(x.isNull("rating"))0.0 else x.optDouble("rating"),
                            date=x.optInt("year").takeIf { it>0 }?.toString().orEmpty(),
                            backendId=id
                        ),
                        watchingNow=x.optLong("watchingNow"),
                        reactions=x.optLong("reactions"),
                        live=x.optBoolean("live")
                    )
                )
            }
        }
    }

    suspend fun react(
        mediaId:String,
        emoji:String,
        positionMs:Long=0L
    ):PulseState =
        parse(
            backend.postJson(
                "/v1/catalog/"+mediaId+"/pulse/react",
                JSONObject()
                    .put("emoji",emoji)
                    .put("positionMs",positionMs.coerceAtLeast(0L)),
                authorized=true
            )
        )

    private fun parse(root:JSONObject):PulseState {
        val reactionsObject=root.optJSONObject("reactions") ?: JSONObject()
        val supported=listOf("🔥","😱","😂","❤️","👀")
        val reactions=buildMap {
            supported.forEach { emoji ->
                put(emoji,reactionsObject.optLong(emoji))
            }
        }
        return PulseState(
            watchingNow=root.optLong("watchingNow"),
            reactions=reactions,
            recent=root.optLong("recent"),
            live=root.optBoolean("live")
        )
    }
}
