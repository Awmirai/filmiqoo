package com.filmiqoo.app

import org.json.JSONObject

data class PulseState(
    val watchingNow: Long,
    val reactions: Map<String,Long>,
    val recent: Long,
    val live: Boolean
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
