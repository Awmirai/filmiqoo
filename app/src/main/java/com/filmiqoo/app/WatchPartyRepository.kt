package com.filmiqoo.app

import org.json.JSONObject

data class WatchPartyHost(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val verified: Boolean
)

data class WatchPartyMedia(
    val id: String?,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val mediaVersionId: String?,
    val quality: String?
)

data class WatchPartyCreateResult(
    val id:String,
    val roomId:String,
    val state:String,
    val inviteCode:String,
    val scheduledAt:String?
)

data class WatchPartyInviteInfo(
    val inviteCode:String,
    val visibility:String,
    val state:String,
    val scheduledAt:String?
)

data class WatchPartyInfo(
    val id: String,
    val title: String,
    val state: String,
    val visibility: String,
    val scheduledAt: String?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val participants: Long,
    val roomId: String,
    val host: WatchPartyHost,
    val media: WatchPartyMedia
)

class WatchPartyRepository(
    private val backend: BackendRepository
) {
    suspend fun list(): List<WatchPartyInfo> {
        val root=backend.getJson("/v1/watch-parties",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parse(x))
            }
        }
    }

    suspend fun detail(id: String): WatchPartyInfo =
        parse(backend.getJson("/v1/watch-parties/"+id,authorized=false))

    suspend fun create(
        mediaTitleId: String,
        title: String,
        visibility: String="public",
        scheduledAt: String?=null
    ): WatchPartyCreateResult {
        val body=JSONObject()
            .put("mediaTitleId",mediaTitleId)
            .put("title",title)
            .put("visibility",visibility)
        if(!scheduledAt.isNullOrBlank()) body.put("scheduledAt",scheduledAt)

        val root=backend.postJson(
            "/v1/watch-parties",
            body,
            authorized=true
        )
        return WatchPartyCreateResult(
            id=root.optString("id"),
            roomId=root.optString("roomId"),
            state=root.optString("state"),
            inviteCode=root.optString("inviteCode"),
            scheduledAt=root.optString("scheduledAt").takeIf(String::isNotBlank)
        )
    }

    suspend fun join(id: String,inviteCode:String?=null): String {
        val body=JSONObject()
        if(!inviteCode.isNullOrBlank()) body.put("inviteCode",inviteCode.trim())
        return backend.postJson(
            "/v1/watch-parties/"+id+"/join",
            body,
            authorized=true
        ).optString("roomId")
    }

    suspend fun inviteInfo(id:String):WatchPartyInviteInfo {
        val root=backend.getJson(
            "/v1/watch-parties/"+id+"/invite",
            authorized=true
        )
        return WatchPartyInviteInfo(
            inviteCode=root.optString("inviteCode"),
            visibility=root.optString("visibility"),
            state=root.optString("state"),
            scheduledAt=root.optString("scheduledAt").takeIf(String::isNotBlank)
        )
    }

    suspend fun regenerateInvite(id:String):String =
        backend.postJson(
            "/v1/watch-parties/"+id+"/invite/regenerate",
            JSONObject(),
            authorized=true
        ).optString("inviteCode")

    suspend fun reminderEnabled(id:String):Boolean =
        backend.getJson(
            "/v1/watch-parties/"+id+"/reminder",
            authorized=true
        ).optBoolean("enabled")

    suspend fun toggleReminder(id:String):Boolean =
        backend.postJson(
            "/v1/watch-parties/"+id+"/reminder",
            JSONObject(),
            authorized=true
        ).optBoolean("enabled")

    suspend fun updateState(
        id: String,
        positionMs: Long,
        isPlaying: Boolean,
        state: String="live"
    ) {
        backend.postJson(
            "/v1/watch-parties/"+id+"/state",
            JSONObject()
                .put("positionMs",positionMs)
                .put("isPlaying",isPlaying)
                .put("state",state),
            authorized=true
        )
    }

    private fun parse(o: JSONObject): WatchPartyInfo {
        val h=o.optJSONObject("host") ?: JSONObject()
        val m=o.optJSONObject("media") ?: JSONObject()
        return WatchPartyInfo(
            id=o.optString("id"),
            title=o.optString("title"),
            state=o.optString("state"),
            visibility=o.optString("visibility"),
            scheduledAt=o.optString("scheduledAt").takeIf(String::isNotBlank),
            positionMs=o.optLong("positionMs"),
            isPlaying=o.optBoolean("isPlaying"),
            participants=o.optLong("participants"),
            roomId=o.optString("roomId"),
            host=WatchPartyHost(
                id=h.optString("id"),
                username=h.optString("username"),
                displayName=h.optString("displayName"),
                avatarUrl=h.optString("avatarUrl"),
                verified=h.optBoolean("verified")
            ),
            media=WatchPartyMedia(
                id=m.optString("id").takeIf(String::isNotBlank),
                title=m.optString("title"),
                posterUrl=m.optString("posterUrl").takeIf(String::isNotBlank),
                backdropUrl=m.optString("backdropUrl").takeIf(String::isNotBlank),
                mediaVersionId=m.optString("mediaVersionId").takeIf(String::isNotBlank),
                quality=m.optString("quality").takeIf(String::isNotBlank)
            )
        )
    }
}
