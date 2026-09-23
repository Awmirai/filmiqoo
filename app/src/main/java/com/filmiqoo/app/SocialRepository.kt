package com.filmiqoo.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject

data class SocialAuthor(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val verified: Boolean
)

data class SocialMediaRef(
    val id: String?,
    val title: String?,
    val posterUrl: String?
)

data class SocialPost(
    val id: String,
    val type: String,
    val body: String,
    val spoiler: Boolean,
    val likes: Long,
    val comments: Long,
    val saves: Long,
    val shares: Long,
    val publishedAt: String?,
    val author: SocialAuthor,
    val media: SocialMediaRef?
)

data class SocialStory(
    val id: String,
    val type: String,
    val mediaUrl: String,
    val thumbnailUrl: String,
    val caption: String,
    val spoiler: Boolean,
    val views: Long,
    val createdAt: String,
    val expiresAt: String,
    val author: SocialAuthor,
    val media: SocialMediaRef?
)

data class SocialChannel(
    val id: String,
    val slug: String,
    val name: String,
    val bio: String,
    val avatarUrl: String,
    val coverUrl: String,
    val followers: Long,
    val verified: Boolean
)

data class SocialComment(
    val id: String,
    val parentCommentId: String?,
    val body: String,
    val spoiler: Boolean,
    val likes: Long,
    val createdAt: String,
    val author: SocialAuthor
)

data class SocialRoom(
    val id: String,
    val name: String,
    val topic: String,
    val type: String,
    val members: Long,
    val media: SocialMediaRef?
)

data class RoomMessageItem(
    val id: String,
    val body: String,
    val type: String,
    val spoiler: Boolean,
    val createdAt: String,
    val author: SocialAuthor
)

class SocialRepository(
    private val backend: BackendRepository
) {
    suspend fun feed(): List<SocialPost> {
        val root=backend.getJson("/v1/social/feed",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val author=parseAuthor(x.optJSONObject("author") ?: JSONObject())
                val mediaObj=x.optJSONObject("media")
                add(
                    SocialPost(
                        id=x.optString("id"),
                        type=x.optString("type","post"),
                        body=x.optString("body"),
                        spoiler=x.optBoolean("spoiler"),
                        likes=x.optLong("likes"),
                        comments=x.optLong("comments"),
                        saves=x.optLong("saves"),
                        shares=x.optLong("shares"),
                        publishedAt=x.optString("publishedAt").takeIf(String::isNotBlank),
                        author=author,
                        media=mediaObj?.let(::parseMedia)
                    )
                )
            }
        }
    }

    suspend fun stories(): List<SocialStory> {
        val root=backend.getJson("/v1/social/stories",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    SocialStory(
                        id=x.optString("id"),
                        type=x.optString("type"),
                        mediaUrl=x.optString("mediaUrl"),
                        thumbnailUrl=x.optString("thumbnailUrl"),
                        caption=x.optString("caption"),
                        spoiler=x.optBoolean("spoiler"),
                        views=x.optLong("views"),
                        createdAt=x.optString("createdAt"),
                        expiresAt=x.optString("expiresAt"),
                        author=parseAuthor(x.optJSONObject("author") ?: JSONObject()),
                        media=x.optJSONObject("media")?.let(::parseMedia)
                    )
                )
            }
        }
    }

    suspend fun channels(): List<SocialChannel> {
        val root=backend.getJson("/v1/social/channels",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    SocialChannel(
                        id=x.optString("id"),
                        slug=x.optString("slug"),
                        name=x.optString("name"),
                        bio=x.optString("bio"),
                        avatarUrl=x.optString("avatarUrl"),
                        coverUrl=x.optString("coverUrl"),
                        followers=x.optLong("followers"),
                        verified=x.optBoolean("verified")
                    )
                )
            }
        }
    }

    suspend fun createPost(
        body: String,
        type: String="post",
        spoiler: Boolean=false,
        channelId: String?=null,
        mediaTitleId: String?=null
    ): String {
        val o=JSONObject()
            .put("type",type)
            .put("body",body)
            .put("spoiler",spoiler)
        if(!channelId.isNullOrBlank()) o.put("channelId",channelId)
        if(!mediaTitleId.isNullOrBlank()) o.put("mediaTitleId",mediaTitleId)
        return backend.postJson("/v1/social/posts",o,authorized=true).getString("id")
    }

    suspend fun togglePostLike(id: String): Boolean =
        backend.postJson("/v1/social/posts/"+id+"/like",JSONObject(),authorized=true)
            .optBoolean("liked")

    suspend fun comments(postId: String): List<SocialComment> {
        val root=backend.getJson("/v1/social/posts/"+postId+"/comments",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    SocialComment(
                        id=x.optString("id"),
                        parentCommentId=x.optString("parentCommentId").takeIf(String::isNotBlank),
                        body=x.optString("body"),
                        spoiler=x.optBoolean("spoiler"),
                        likes=x.optLong("likes"),
                        createdAt=x.optString("createdAt"),
                        author=parseAuthor(x.optJSONObject("author") ?: JSONObject())
                    )
                )
            }
        }
    }

    suspend fun addComment(postId: String, body: String, spoiler: Boolean=false): String =
        backend.postJson(
            "/v1/social/posts/"+postId+"/comments",
            JSONObject().put("body",body).put("spoiler",spoiler),
            authorized=true
        ).getString("id")

    suspend fun createChannel(
        name: String,
        slug: String,
        bio: String,
        visibility: String="public"
    ): String =
        backend.postJson(
            "/v1/social/channels",
            JSONObject()
                .put("name",name)
                .put("slug",slug)
                .put("bio",bio)
                .put("visibility",visibility),
            authorized=true
        ).getString("id")

    suspend fun toggleChannelFollow(id: String): Boolean =
        backend.postJson("/v1/social/channels/"+id+"/follow",JSONObject(),authorized=true)
            .optBoolean("following")

    suspend fun uploadMedia(context: Context, uri: Uri, kind: String): UploadTicket =
        backend.uploadMedia(context,uri,kind)

    suspend fun createReel(
        uploadId: String,
        caption: String,
        spoiler: Boolean=false,
        allowComments: Boolean=true,
        mediaTitleId: String?=null
    ): String {
        val body=JSONObject()
            .put("uploadId",uploadId)
            .put("caption",caption)
            .put("spoiler",spoiler)
            .put("allowComments",allowComments)
            .put("durationMs",0)
        if(!mediaTitleId.isNullOrBlank()) body.put("mediaTitleId",mediaTitleId)
        return backend.postJson("/v1/social/reels",body,authorized=true).getString("id")
    }

    suspend fun createMediaStory(
        ticket: UploadTicket,
        caption: String,
        spoiler: Boolean=false,
        mediaTitleId: String?=null
    ): String {
        val type=if(ticket.mimeType.startsWith("video/")) "video" else "image"
        val body=JSONObject()
            .put("type",type)
            .put("mediaUrl",ticket.mediaUrl)
            .put("thumbnailUrl",if(type=="image")ticket.mediaUrl else "")
            .put("caption",caption)
            .put("spoiler",spoiler)
        if(!mediaTitleId.isNullOrBlank()) body.put("mediaTitleId",mediaTitleId)
        return backend.postJson("/v1/social/stories",body,authorized=true).getString("id")
    }

    suspend fun createTextStory(
        caption: String,
        spoiler: Boolean=false,
        mediaTitleId: String?=null
    ): String {
        val body=JSONObject()
            .put("type","text")
            .put("caption",caption)
            .put("spoiler",spoiler)
        if(!mediaTitleId.isNullOrBlank()) body.put("mediaTitleId",mediaTitleId)
        return backend.postJson("/v1/social/stories",body,authorized=true).getString("id")
    }

    suspend fun markStoryViewed(id: String) {
        backend.postJson("/v1/social/stories/"+id+"/view",JSONObject(),authorized=true)
    }

    suspend fun toggleUserFollow(id: String): Boolean =
        backend.postJson("/v1/social/users/"+id+"/follow",JSONObject(),authorized=true)
            .optBoolean("following")

    suspend fun rooms(): List<SocialRoom> {
        val root=backend.getJson("/v1/rooms",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    SocialRoom(
                        id=x.optString("id"),
                        name=x.optString("name"),
                        topic=x.optString("topic"),
                        type=x.optString("type"),
                        members=x.optLong("members"),
                        media=x.optJSONObject("media")?.let(::parseMedia)
                    )
                )
            }
        }
    }

    suspend fun roomMessages(roomId: String): List<RoomMessageItem> {
        val root=backend.getJson("/v1/rooms/"+roomId+"/messages",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    RoomMessageItem(
                        id=x.optString("id"),
                        body=x.optString("body"),
                        type=x.optString("type"),
                        spoiler=x.optBoolean("spoiler"),
                        createdAt=x.optString("createdAt"),
                        author=parseAuthor(x.optJSONObject("author") ?: JSONObject())
                    )
                )
            }
        }
    }

    suspend fun sendMessage(
        roomId: String,
        body: String,
        spoiler: Boolean=false
    ): String =
        backend.postJson(
            "/v1/rooms/"+roomId+"/messages",
            JSONObject().put("body",body).put("type","text").put("spoiler",spoiler),
            authorized=true
        ).optString("id")

    suspend fun toggleReelLike(id: String): Boolean =
        backend.postJson("/v1/social/reels/"+id+"/like",JSONObject(),authorized=true)
            .optBoolean("liked")

    suspend fun toggleReelSave(id: String): Boolean =
        backend.postJson("/v1/social/reels/"+id+"/save",JSONObject(),authorized=true)
            .optBoolean("saved")

    suspend fun markReelViewed(id: String) {
        backend.postJson("/v1/social/reels/"+id+"/view",JSONObject(),authorized=true)
    }

    private fun parseAuthor(o: JSONObject)=SocialAuthor(
        id=o.optString("id"),
        username=o.optString("username"),
        displayName=o.optString("displayName"),
        avatarUrl=o.optString("avatarUrl"),
        verified=o.optBoolean("verified")
    )

    private fun parseMedia(o: JSONObject)=SocialMediaRef(
        id=o.optString("id").takeIf(String::isNotBlank),
        title=o.optString("title").takeIf(String::isNotBlank),
        posterUrl=o.optString("posterUrl").takeIf(String::isNotBlank)
    )
}
