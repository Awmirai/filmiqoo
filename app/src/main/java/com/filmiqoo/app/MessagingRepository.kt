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
    val unread: Long,
    val notificationLevel: String = "all",
    val archived: Boolean = false
)

data class RoomConversationSettings(
    val id:String,
    val name:String,
    val topic:String,
    val type:String,
    val visibility:String,
    val members:Long,
    val slowModeSeconds:Int,
    val channelId:String?,
    val myRole:String,
    val notificationLevel:String,
    val archived:Boolean,
    val canManage:Boolean
)

data class RoomPreferences(
    val notificationLevel:String,
    val archived:Boolean
)

data class RoomInviteInfo(
    val code:String,
    val deepLink:String,
    val usageCount:Long
)

data class RoomJoinResult(
    val id:String,
    val title:String,
    val existing:Boolean
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
    val actor: SocialAuthor?,
    val media: MediaItem?
)

class MessagingRepository(
    private val backend: BackendRepository
) {
    suspend fun inbox(archived:Boolean=false): List<InboxConversation> {
        val path=if(archived)"/v1/inbox?archived=1" else "/v1/inbox"
        val root=backend.getJson(path,authorized=true)
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
                        unread=x.optLong("unread"),
                        notificationLevel=x.optString("notificationLevel","all"),
                        archived=x.optBoolean("archived")
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

    suspend fun roomSettings(roomId:String):RoomConversationSettings {
        val o=backend.getJson("/v1/rooms/"+roomId+"/settings",authorized=true)
        return RoomConversationSettings(
            id=o.optString("id"),
            name=o.optString("name"),
            topic=o.optString("topic"),
            type=o.optString("type"),
            visibility=o.optString("visibility"),
            members=o.optLong("members"),
            slowModeSeconds=o.optInt("slowModeSeconds"),
            channelId=o.optString("channelId").takeIf(String::isNotBlank),
            myRole=o.optString("myRole"),
            notificationLevel=o.optString("notificationLevel","all"),
            archived=o.optBoolean("archived"),
            canManage=o.optBoolean("canManage")
        )
    }

    suspend fun updateRoomSettings(
        roomId:String,
        name:String,
        topic:String,
        visibility:String,
        slowModeSeconds:Int
    ):RoomConversationSettings {
        backend.postJson(
            "/v1/rooms/"+roomId+"/settings",
            JSONObject()
                .put("name",name)
                .put("topic",topic)
                .put("visibility",visibility)
                .put("slowModeSeconds",slowModeSeconds),
            authorized=true
        )
        return roomSettings(roomId)
    }

    suspend fun updateRoomPreferences(
        roomId:String,
        notificationLevel:String?=null,
        archived:Boolean?=null
    ):RoomPreferences {
        val body=JSONObject()
        if(notificationLevel!=null) body.put("notificationLevel",notificationLevel)
        if(archived!=null) body.put("archived",archived)
        val o=backend.postJson(
            "/v1/rooms/"+roomId+"/preferences",
            body,
            authorized=true
        )
        return RoomPreferences(
            notificationLevel=o.optString("notificationLevel","all"),
            archived=o.optBoolean("archived")
        )
    }

    suspend fun roomInvite(roomId:String):RoomInviteInfo {
        val o=backend.getJson("/v1/rooms/"+roomId+"/invite",authorized=true)
        return RoomInviteInfo(
            code=o.optString("code"),
            deepLink=o.optString("deepLink"),
            usageCount=o.optLong("usageCount")
        )
    }

    suspend fun regenerateRoomInvite(roomId:String):RoomInviteInfo {
        val o=backend.postJson(
            "/v1/rooms/"+roomId+"/invite/regenerate",
            JSONObject(),
            authorized=true
        )
        return RoomInviteInfo(
            code=o.optString("code"),
            deepLink=o.optString("deepLink"),
            usageCount=o.optLong("usageCount")
        )
    }

    suspend fun joinRoomInvite(code:String):RoomJoinResult {
        val o=backend.postJson(
            "/v1/room-invites/"+code+"/join",
            JSONObject(),
            authorized=true
        )
        return RoomJoinResult(
            id=o.optString("id"),
            title=o.optString("title"),
            existing=o.optBoolean("existing")
        )
    }

    suspend fun leaveRoom(roomId:String):Boolean =
        backend.postJson(
            "/v1/rooms/"+roomId+"/leave",
            JSONObject(),
            authorized=true
        ).optBoolean("left")

    suspend fun transferRoomOwnership(
        roomId:String,
        userId:String
    ):String =
        backend.postJson(
            "/v1/rooms/"+roomId+"/members/"+userId+"/transfer-owner",
            JSONObject(),
            authorized=true
        ).optString("ownerId")

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
                val m=x.optJSONObject("media")
                val media=m?.let {
                    val backendId=it.optString("id").takeIf(String::isNotBlank)
                    if(backendId==null) null else MediaItem(
                        id=if(it.isNull("tmdbId"))0 else it.optInt("tmdbId"),
                        type=if(it.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                        title=it.optString("title").ifBlank{it.optString("originalTitle")},
                        originalTitle=it.optString("originalTitle"),
                        overview=it.optString("overview"),
                        posterPath=it.optString("posterUrl").takeIf(String::isNotBlank),
                        backdropPath=it.optString("backdropUrl").takeIf(String::isNotBlank),
                        vote=it.optDouble("rating",0.0),
                        date=if(it.isNull("year"))"" else it.optInt("year").toString(),
                        backendId=backendId,
                        mediaVersionId=it.optString("mediaVersionId").takeIf(String::isNotBlank),
                        streamReady=it.optBoolean("streamReady"),
                        quality=it.optString("quality")
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
                        actor=actor,
                        media=media
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
