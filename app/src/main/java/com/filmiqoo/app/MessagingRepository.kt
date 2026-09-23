package com.filmiqoo.app

import org.json.JSONObject

data class InboxConversation(
    val id: String,
    val title: String,
    val topic: String,
    val type: String,
    val members: Long,
    val otherUserId: String,
    val otherUsername: String,
    val avatarUrl: String,
    val lastMessage: String,
    val lastMessageAt: String?,
    val unread: Long
)

data class DmRoom(
    val id: String,
    val title: String
)

data class FilmiqooNotification(
    val id: String,
    val type: String,
    val entityType: String,
    val entityId: String?,
    val title: String,
    val body: String,
    val read: Boolean,
    val createdAt: String,
    val actor: SocialAuthor?
)

class MessagingRepository(
    private val backend: BackendRepository
) {
    suspend fun inbox(): List<InboxConversation> {
        val root=backend.getJson("/v1/inbox",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    InboxConversation(
                        id=x.optString("id"),
                        title=x.optString("title"),
                        topic=x.optString("topic"),
                        type=x.optString("type"),
                        members=x.optLong("members"),
                        otherUserId=x.optString("otherUserId"),
                        otherUsername=x.optString("otherUsername"),
                        avatarUrl=x.optString("avatarUrl"),
                        lastMessage=x.optString("lastMessage"),
                        lastMessageAt=x.optString("lastMessageAt").takeIf(String::isNotBlank),
                        unread=x.optLong("unread")
                    )
                )
            }
        }
    }

    suspend fun ensureDm(userId: String): DmRoom {
        val o=backend.postJson("/v1/dm/"+userId,JSONObject(),authorized=true)
        return DmRoom(
            id=o.optString("id"),
            title=o.optString("title")
        )
    }

    suspend fun markRoomRead(roomId: String) {
        backend.postJson("/v1/rooms/"+roomId+"/read",JSONObject(),authorized=true)
    }

    suspend fun notifications(): Pair<List<FilmiqooNotification>,Long> {
        val root=backend.getJson("/v1/notifications",authorized=true)
        val arr=root.optJSONArray("items")
        val items=buildList {
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val a=x.optJSONObject("actor")
                val actor=a?.let {
                    val id=it.optString("id")
                    if(id.isBlank()) null else SocialAuthor(
                        id=id,
                        username=it.optString("username"),
                        displayName=it.optString("displayName"),
                        avatarUrl=it.optString("avatarUrl"),
                        verified=it.optBoolean("verified")
                    )
                }
                add(
                    FilmiqooNotification(
                        id=x.optString("id"),
                        type=x.optString("type"),
                        entityType=x.optString("entityType"),
                        entityId=x.optString("entityId").takeIf(String::isNotBlank),
                        title=x.optString("title"),
                        body=x.optString("body"),
                        read=x.optBoolean("read"),
                        createdAt=x.optString("createdAt"),
                        actor=actor
                    )
                )
            }
        }
        return items to root.optLong("unread")
    }

    suspend fun markNotificationRead(id: String) {
        backend.postJson("/v1/notifications/"+id+"/read",JSONObject(),authorized=true)
    }

    suspend fun markAllNotificationsRead() {
        backend.postJson("/v1/notifications/read-all",JSONObject(),authorized=true)
    }
}
