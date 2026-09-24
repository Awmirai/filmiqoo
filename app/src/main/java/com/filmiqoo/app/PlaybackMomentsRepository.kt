package com.filmiqoo.app

import org.json.JSONObject

data class PlaybackMoment(
    val id:String,
    val positionMs:Long,
    val body:String,
    val reaction:String,
    val spoiler:Boolean,
    val likes:Long,
    val liked:Boolean,
    val createdAt:String,
    val author:SocialAuthor
)

class PlaybackMomentsRepository(
    private val backend:BackendRepository
) {
    suspend fun around(
        mediaVersionId:String,
        positionMs:Long,
        windowMs:Long=15_000L
    ):List<PlaybackMoment> {
        val root=backend.getJson(
            "/v1/playback/"+mediaVersionId+
                "/moments?positionMs="+positionMs+
                "&windowMs="+windowMs,
            authorized=true
        )
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val a=x.optJSONObject("author") ?: JSONObject()
                add(
                    PlaybackMoment(
                        id=x.optString("id"),
                        positionMs=x.optLong("positionMs"),
                        body=x.optString("body"),
                        reaction=x.optString("reaction"),
                        spoiler=x.optBoolean("spoiler"),
                        likes=x.optLong("likes"),
                        liked=x.optBoolean("liked"),
                        createdAt=x.optString("createdAt"),
                        author=SocialAuthor(
                            id=a.optString("id"),
                            username=a.optString("username"),
                            displayName=a.optString("displayName"),
                            avatarUrl=a.optString("avatarUrl"),
                            verified=a.optBoolean("verified")
                        )
                    )
                )
            }
        }
    }

    suspend fun create(
        mediaVersionId:String,
        positionMs:Long,
        body:String="",
        reaction:String="",
        spoiler:Boolean=false
    ):String =
        backend.postJson(
            "/v1/playback/"+mediaVersionId+"/moments",
            JSONObject()
                .put("positionMs",positionMs)
                .put("body",body.trim())
                .put("reaction",reaction.trim())
                .put("spoiler",spoiler),
            authorized=true
        ).optString("id")

    suspend fun toggleLike(momentId:String):Boolean =
        backend.postJson(
            "/v1/playback/moments/"+momentId+"/like",
            JSONObject(),
            authorized=true
        ).optBoolean("liked")
}
