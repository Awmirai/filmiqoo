package com.filmiqoo.app

import org.json.JSONObject

data class CloseFriendCandidate(
    val id:String,
    val username:String,
    val displayName:String,
    val avatarUrl:String,
    val verified:Boolean,
    val closeFriend:Boolean
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

class CloseFriendsRepository(
    private val backend:BackendRepository
) {
    suspend fun list():List<CloseFriendCandidate> {
        val root=backend.getJson("/v1/social/close-friends",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    CloseFriendCandidate(
                        id=x.optString("id"),
                        username=x.optString("username"),
                        displayName=x.optString("displayName"),
                        avatarUrl=x.optString("avatarUrl"),
                        verified=x.optBoolean("verified"),
                        closeFriend=x.optBoolean("closeFriend")
                    )
                )
            }
        }
    }

    suspend fun toggle(userId:String):Boolean =
        backend.postJson(
            "/v1/social/close-friends/"+userId+"/toggle",
            JSONObject(),
            authorized=true
        ).optBoolean("closeFriend")
}
