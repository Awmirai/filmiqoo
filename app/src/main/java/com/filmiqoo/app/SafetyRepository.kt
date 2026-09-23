package com.filmiqoo.app

import org.json.JSONObject

data class SafetyUser(
    val id:String,
    val username:String,
    val displayName:String,
    val avatarUrl:String,
    val verified:Boolean
) {
    fun asCreator()=Creator(
        name=displayName,
        handle="@"+username,
        followers="",
        bio="",
        verified=verified,
        id=id,
        entityType="user",
        avatarUrl=avatarUrl
    )
}

data class SafetyState(
    val blocked:List<SafetyUser>,
    val muted:List<SafetyUser>
)

class SafetyRepository(
    private val backend:BackendRepository
) {
    suspend fun state():SafetyState {
        val root=backend.getJson("/v1/moderation/safety",authorized=true)
        return SafetyState(
            blocked=parseUsers(root.optJSONArray("blocked")),
            muted=parseUsers(root.optJSONArray("muted"))
        )
    }

    suspend fun toggleBlock(userId:String):Boolean =
        backend.postJson(
            "/v1/social/users/"+userId+"/block",
            JSONObject(),
            authorized=true
        ).optBoolean("blocked")

    suspend fun toggleMute(userId:String):Boolean =
        backend.postJson(
            "/v1/social/users/"+userId+"/mute",
            JSONObject(),
            authorized=true
        ).optBoolean("muted")

    suspend fun report(
        targetType:String,
        targetId:String,
        reason:String,
        detail:String=""
    ):String =
        backend.postJson(
            "/v1/moderation/report",
            JSONObject()
                .put("targetType",targetType)
                .put("targetId",targetId)
                .put("reason",reason)
                .put("detail",detail.trim()),
            authorized=true
        ).optString("id")

    private fun parseUsers(arr:org.json.JSONArray?):List<SafetyUser> = buildList {
        if(arr==null) return@buildList
        for(i in 0 until arr.length()) {
            val x=arr.optJSONObject(i) ?: continue
            add(
                SafetyUser(
                    id=x.optString("id"),
                    username=x.optString("username"),
                    displayName=x.optString("displayName"),
                    avatarUrl=x.optString("avatarUrl"),
                    verified=x.optBoolean("verified")
                )
            )
        }
    }
}
