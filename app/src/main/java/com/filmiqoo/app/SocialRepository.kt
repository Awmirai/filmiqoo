package com.filmiqoo.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray

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
    val posterUrl: String?,
    val tmdbId: Int? = null,
    val type: MediaType? = null,
    val originalTitle: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null
) {
    fun asMediaItem(): MediaItem? {
        val backendId=id ?: return null
        val safeTitle=title ?: return null
        return MediaItem(
            id=tmdbId ?: 0,
            type=type ?: MediaType.MOVIE,
            title=safeTitle,
            originalTitle=originalTitle.orEmpty(),
            posterPath=posterUrl,
            backdropPath=backdropUrl,
            vote=rating ?: 0.0,
            date=year?.toString().orEmpty(),
            backendId=backendId
        )
    }
}

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

data class PollOption(
    val id: String,
    val label: String,
    val votes: Long
)

data class PollData(
    val options: List<PollOption>,
    val totalVotes: Long
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
    val author: SocialAuthor,
    val attachmentUrl: String? = null,
    val attachmentMime: String? = null,
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val replyAuthor: String? = null,
    val reactions: Map<String,Long> = emptyMap()
)


data class ReelMediaRef(
    val backendId: String?,
    val tmdbId: Int?,
    val type: MediaType,
    val title: String,
    val originalTitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val rating: Double?
) {
    fun asMediaItem(): MediaItem? {
        if (backendId.isNullOrBlank() || title.isBlank()) return null
        return MediaItem(
            id = tmdbId ?: 0,
            type = type,
            title = title,
            originalTitle = originalTitle,
            posterPath = posterUrl,
            backdropPath = backdropUrl,
            vote = rating ?: 0.0,
            date = year?.toString().orEmpty(),
            backendId = backendId
        )
    }
}

data class ReelFeedItem(
    val id: String,
    val caption: String,
    val playbackUrl: String,
    val coverUrl: String,
    val durationMs: Long,
    val likes: Long,
    val comments: Long,
    val saves: Long,
    val shares: Long,
    val views: Long,
    val spoiler: Boolean,
    val author: SocialAuthor,
    val media: ReelMediaRef?
)

class SocialRepository(
    private val backend: BackendRepository
) {
    suspend fun reels(): List<ReelFeedItem> {
        val loggedIn=backend.session.isLoggedIn
        val root=backend.getJson(
            if(loggedIn)"/v1/social/reels/personalized" else "/v1/social/reels",
            authorized=loggedIn
        )
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val mediaObj=x.optJSONObject("media")
                val kind=mediaObj?.optString("kind").orEmpty()
                val media=mediaObj?.let {
                    val backendId=it.optString("id").takeIf(String::isNotBlank)
                    if(backendId==null) null else ReelMediaRef(
                        backendId=backendId,
                        tmdbId=if(it.isNull("tmdbId")) null else it.optInt("tmdbId"),
                        type=if(kind=="movie") MediaType.MOVIE else MediaType.TV,
                        title=it.optString("title"),
                        originalTitle=it.optString("originalTitle"),
                        posterUrl=it.optString("posterUrl").takeIf(String::isNotBlank),
                        backdropUrl=it.optString("backdropUrl").takeIf(String::isNotBlank),
                        year=if(it.isNull("year")) null else it.optInt("year"),
                        rating=if(it.isNull("rating")) null else it.optDouble("rating")
                    )
                }
                add(
                    ReelFeedItem(
                        id=x.optString("id"),
                        caption=x.optString("caption"),
                        playbackUrl=x.optString("playbackUrl"),
                        coverUrl=x.optString("coverUrl"),
                        durationMs=x.optLong("durationMs"),
                        likes=x.optLong("likes"),
                        comments=x.optLong("comments"),
                        saves=x.optLong("saves"),
                        shares=x.optLong("shares"),
                        views=x.optLong("views"),
                        spoiler=x.optBoolean("spoiler"),
                        author=parseAuthor(x.optJSONObject("author") ?: JSONObject()),
                        media=media
                    )
                )
            }
        }
    }

    suspend fun feed(): List<SocialPost> {
        val loggedIn=backend.session.isLoggedIn
        val root=backend.getJson(
            if(loggedIn)"/v1/social/feed/personalized" else "/v1/social/feed",
            authorized=loggedIn
        )
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
        val loggedIn=backend.session.isLoggedIn
        val root=backend.getJson(
            if(loggedIn)"/v1/social/stories/personalized" else "/v1/social/stories",
            authorized=loggedIn
        )
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
        mediaTitleId: String?=null,
        pollOptions: List<String> = emptyList()
    ): String {
        val o=JSONObject()
            .put("type",type)
            .put("body",body)
            .put("spoiler",spoiler)
        if(!channelId.isNullOrBlank()) o.put("channelId",channelId)
        if(!mediaTitleId.isNullOrBlank()) o.put("mediaTitleId",mediaTitleId)
        if(pollOptions.isNotEmpty()) {
            val arr=JSONArray()
            pollOptions.forEach { arr.put(it) }
            o.put("pollOptions",arr)
        }
        return backend.postJson("/v1/social/posts",o,authorized=true).getString("id")
    }

    suspend fun poll(postId: String): PollData {
        val root=backend.getJson("/v1/social/posts/"+postId+"/poll",authorized=false)
        val arr=root.optJSONArray("options")
        val options=buildList {
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    PollOption(
                        id=x.optString("id"),
                        label=x.optString("label"),
                        votes=x.optLong("votes")
                    )
                )
            }
        }
        return PollData(options,root.optLong("totalVotes"))
    }

    suspend fun votePoll(postId: String, optionId: String): String =
        backend.postJson(
            "/v1/social/posts/"+postId+"/poll/vote",
            JSONObject().put("optionId",optionId),
            authorized=true
        ).optString("selectedOptionId")

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

    suspend fun reactToStory(id: String,reaction: String): String =
        backend.postJson(
            "/v1/social/stories/"+id+"/reaction",
            JSONObject().put("reaction",reaction),
            authorized=true
        ).optString("reaction")

    suspend fun replyToStory(id: String,body: String): String =
        backend.postJson(
            "/v1/social/stories/"+id+"/reply",
            JSONObject().put("body",body),
            authorized=true
        ).getString("id")


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
                        author=parseAuthor(x.optJSONObject("author") ?: JSONObject()),
                        attachmentUrl=x.optJSONObject("attachment")?.optString("url")?.takeIf(String::isNotBlank),
                        attachmentMime=x.optJSONObject("attachment")?.optString("mimeType")?.takeIf(String::isNotBlank),
                        replyToId=x.optJSONObject("replyTo")?.optString("id")?.takeIf(String::isNotBlank),
                        replyPreview=x.optJSONObject("replyTo")?.optString("body")?.takeIf(String::isNotBlank),
                        replyAuthor=x.optJSONObject("replyTo")?.optString("author")?.takeIf(String::isNotBlank),
                        reactions=buildMap {
                            val ro=x.optJSONObject("reactions") ?: JSONObject()
                            val keys=ro.keys()
                            while(keys.hasNext()) {
                                val key=keys.next()
                                put(key,ro.optLong(key))
                            }
                        }
                    )
                )
            }
        }
    }

    suspend fun sendMessage(
        roomId: String,
        body: String,
        spoiler: Boolean=false,
        replyToMessageId: String?=null
    ): String {
        val payload=JSONObject()
            .put("body",body)
            .put("type","text")
            .put("spoiler",spoiler)
        if(!replyToMessageId.isNullOrBlank()) payload.put("replyToMessageId",replyToMessageId)
        return backend.postJson(
            "/v1/rooms/"+roomId+"/messages",
            payload,
            authorized=true
        ).optString("id")
    }

    suspend fun sendMediaMessage(
        context: Context,
        roomId: String,
        uri: Uri,
        caption: String="",
        spoiler: Boolean=false,
        replyToMessageId: String?=null
    ): String {
        val ticket=uploadMedia(context,uri,"chat")
        val type=if(ticket.mimeType.startsWith("video/")) "video" else "image"
        val payload=JSONObject()
            .put("body",caption)
            .put("type",type)
            .put("spoiler",spoiler)
            .put(
                "attachment",
                JSONObject()
                    .put("url",ticket.mediaUrl)
                    .put("mimeType",ticket.mimeType)
                    .put("fileName",ticket.fileName)
                    .put("sizeBytes",ticket.sizeBytes)
            )
        if(!replyToMessageId.isNullOrBlank()) payload.put("replyToMessageId",replyToMessageId)
        return backend.postJson(
            "/v1/rooms/"+roomId+"/messages",
            payload,
            authorized=true
        ).optString("id")
    }

    suspend fun toggleMessageReaction(
        roomId: String,
        messageId: String,
        reaction: String
    ): Boolean =
        backend.postJson(
            "/v1/rooms/"+roomId+"/messages/"+messageId+"/reaction",
            JSONObject().put("reaction",reaction),
            authorized=true
        ).optBoolean("active")

    suspend fun toggleReelLike(id: String): Boolean =
        backend.postJson("/v1/social/reels/"+id+"/like",JSONObject(),authorized=true)
            .optBoolean("liked")

    suspend fun toggleReelSave(id: String): Boolean =
        backend.postJson("/v1/social/reels/"+id+"/save",JSONObject(),authorized=true)
            .optBoolean("saved")

    suspend fun markReelViewed(id: String) {
        backend.postJson("/v1/social/reels/"+id+"/view",JSONObject(),authorized=true)
    }

    suspend fun reelComments(reelId: String): List<SocialComment> {
        val root=backend.getJson("/v1/social/reels/"+reelId+"/comments",authorized=false)
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

    suspend fun addReelComment(reelId: String, body: String, spoiler: Boolean=false): String =
        backend.postJson(
            "/v1/social/reels/"+reelId+"/comments",
            JSONObject().put("body",body).put("spoiler",spoiler),
            authorized=true
        ).getString("id")


    private fun parseAuthor(o: JSONObject)=SocialAuthor(
        id=o.optString("id"),
        username=o.optString("username"),
        displayName=o.optString("displayName"),
        avatarUrl=o.optString("avatarUrl"),
        verified=o.optBoolean("verified")
    )

    private fun parseMedia(o: JSONObject): SocialMediaRef {
        val kind=o.optString("kind")
        return SocialMediaRef(
            id=o.optString("id").takeIf(String::isNotBlank),
            title=o.optString("title").takeIf(String::isNotBlank),
            posterUrl=o.optString("posterUrl").takeIf(String::isNotBlank),
            tmdbId=if(o.isNull("tmdbId")) null else o.optInt("tmdbId"),
            type=when(kind) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.TV
                else -> null
            },
            originalTitle=o.optString("originalTitle").takeIf(String::isNotBlank),
            backdropUrl=o.optString("backdropUrl").takeIf(String::isNotBlank),
            year=if(o.isNull("year")) null else o.optInt("year"),
            rating=if(o.isNull("rating")) null else o.optDouble("rating")
        )
    }
}
