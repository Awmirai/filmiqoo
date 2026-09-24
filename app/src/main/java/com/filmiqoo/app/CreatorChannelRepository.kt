package com.filmiqoo.app

import org.json.JSONObject

data class PublicCreatorProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String,
    val coverUrl: String,
    val verified: Boolean,
    val privateAccount: Boolean,
    val followers: Long,
    val following: Long,
    val posts: Long,
    val reels: Long
)

data class PublicChannelProfile(
    val id: String,
    val ownerUserId: String,
    val slug: String,
    val name: String,
    val bio: String,
    val avatarUrl: String,
    val coverUrl: String,
    val visibility: String,
    val verified: Boolean,
    val followers: Long,
    val posts: Long,
    val reels: Long
)

data class ChannelMember(
    val id: String,
    val role: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val verified: Boolean
)

data class ManagedChannelRoom(
    val id:String,
    val name:String,
    val topic:String,
    val type:String,
    val visibility:String,
    val members:Long,
    val slowModeSeconds:Int
)

data class ChannelManageOverview(
    val id:String,
    val ownerUserId:String,
    val slug:String,
    val name:String,
    val bio:String,
    val avatarUrl:String,
    val coverUrl:String,
    val visibility:String,
    val verified:Boolean,
    val followers:Long,
    val posts:Long,
    val reels:Long,
    val myRole:String
)

data class ScheduledCreatorItem(
    val id:String,
    val kind:String,
    val contentType:String,
    val preview:String,
    val scheduledAt:String,
    val channelId:String?,
    val channelName:String,
    val mediaTitleId:String?,
    val mediaTitle:String,
    val spoiler:Boolean
)

data class CreatorStudioAnalytics(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val followers: Long,
    val following: Long,
    val posts: Long,
    val reels: Long,
    val reelViews: Long,
    val reelLikes: Long,
    val reelComments: Long,
    val reelSaves: Long,
    val reelShares: Long,
    val storyViews: Long,
    val channels: Long,
    val channelFollowers: Long
)

class CreatorChannelRepository(
    private val backend: BackendRepository
) {
    suspend fun userProfile(id: String): PublicCreatorProfile {
        val o=backend.getJson("/v1/social/users/"+id,authorized=false)
        return PublicCreatorProfile(
            id=o.optString("id"),
            username=o.optString("username"),
            displayName=o.optString("displayName"),
            bio=o.optString("bio"),
            avatarUrl=o.optString("avatarUrl"),
            coverUrl=o.optString("coverUrl"),
            verified=o.optBoolean("verified"),
            privateAccount=o.optBoolean("private"),
            followers=o.optLong("followers"),
            following=o.optLong("following"),
            posts=o.optLong("posts"),
            reels=o.optLong("reels")
        )
    }

    suspend fun userPosts(id: String): List<SocialPost> =
        parsePosts(backend.getJson("/v1/social/users/"+id+"/posts",authorized=false))

    suspend fun userReels(id: String): List<ReelFeedItem> =
        parseReels(backend.getJson("/v1/social/users/"+id+"/reels",authorized=false))

    suspend fun channelProfile(id: String): PublicChannelProfile {
        val o=backend.getJson("/v1/social/channels/"+id,authorized=false)
        return PublicChannelProfile(
            id=o.optString("id"),
            ownerUserId=o.optString("ownerUserId"),
            slug=o.optString("slug"),
            name=o.optString("name"),
            bio=o.optString("bio"),
            avatarUrl=o.optString("avatarUrl"),
            coverUrl=o.optString("coverUrl"),
            visibility=o.optString("visibility"),
            verified=o.optBoolean("verified"),
            followers=o.optLong("followers"),
            posts=o.optLong("posts"),
            reels=o.optLong("reels")
        )
    }

    suspend fun channelPosts(id: String): List<SocialPost> =
        parsePosts(backend.getJson("/v1/social/channels/"+id+"/posts",authorized=false))

    suspend fun channelReels(id: String): List<ReelFeedItem> =
        parseReels(backend.getJson("/v1/social/channels/"+id+"/reels",authorized=false))

    suspend fun channelStories(id: String): List<SocialStory> =
        parseStories(backend.getJson("/v1/social/channels/"+id+"/stories",authorized=false))

    suspend fun channelMembers(id: String): List<ChannelMember> {
        val root=backend.getJson("/v1/social/channels/"+id+"/members",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    ChannelMember(
                        id=x.optString("id"),
                        role=x.optString("role"),
                        username=x.optString("username"),
                        displayName=x.optString("displayName"),
                        avatarUrl=x.optString("avatarUrl"),
                        verified=x.optBoolean("verified")
                    )
                )
            }
        }
    }

    suspend fun channelRooms(id: String): List<SocialRoom> {
        val root=backend.getJson("/v1/social/channels/"+id+"/rooms",authorized=false)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val m=x.optJSONObject("media")
                add(
                    SocialRoom(
                        id=x.optString("id"),
                        name=x.optString("name"),
                        topic=x.optString("topic"),
                        type=x.optString("type"),
                        members=x.optLong("members"),
                        media=m?.let {
                            SocialMediaRef(
                                id=it.optString("id").takeIf(String::isNotBlank),
                                title=it.optString("title").takeIf(String::isNotBlank),
                                posterUrl=it.optString("posterUrl").takeIf(String::isNotBlank)
                            )
                        }
                    )
                )
            }
        }
    }

    suspend fun managedRooms(id:String):List<ManagedChannelRoom> {
        val root=backend.getJson("/v1/social/channels/"+id+"/manage/rooms",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    ManagedChannelRoom(
                        id=x.optString("id"),
                        name=x.optString("name"),
                        topic=x.optString("topic"),
                        type=x.optString("type"),
                        visibility=x.optString("visibility"),
                        members=x.optLong("members"),
                        slowModeSeconds=x.optInt("slowModeSeconds")
                    )
                )
            }
        }
    }

    suspend fun manageOverview(id:String):ChannelManageOverview {
        val o=backend.getJson("/v1/social/channels/"+id+"/manage",authorized=true)
        return ChannelManageOverview(
            id=o.optString("id"),
            ownerUserId=o.optString("ownerUserId"),
            slug=o.optString("slug"),
            name=o.optString("name"),
            bio=o.optString("bio"),
            avatarUrl=o.optString("avatarUrl"),
            coverUrl=o.optString("coverUrl"),
            visibility=o.optString("visibility"),
            verified=o.optBoolean("verified"),
            followers=o.optLong("followers"),
            posts=o.optLong("posts"),
            reels=o.optLong("reels"),
            myRole=o.optString("myRole")
        )
    }

    suspend fun updateChannelSettings(
        id:String,
        name:String,
        bio:String,
        visibility:String
    ):ChannelManageOverview {
        val o=backend.postJson(
            "/v1/social/channels/"+id+"/settings",
            JSONObject()
                .put("name",name.trim())
                .put("bio",bio.trim())
                .put("visibility",visibility),
            authorized=true
        )
        return ChannelManageOverview(
            id=o.optString("id"),
            ownerUserId=o.optString("ownerUserId"),
            slug=o.optString("slug"),
            name=o.optString("name"),
            bio=o.optString("bio"),
            avatarUrl=o.optString("avatarUrl"),
            coverUrl=o.optString("coverUrl"),
            visibility=o.optString("visibility"),
            verified=o.optBoolean("verified"),
            followers=o.optLong("followers"),
            posts=o.optLong("posts"),
            reels=o.optLong("reels"),
            myRole=o.optString("myRole")
        )
    }

    suspend fun changeMemberRole(channelId:String,userId:String,role:String):String =
        backend.postJson(
            "/v1/social/channels/"+channelId+"/members/"+userId+"/role",
            JSONObject().put("role",role),
            authorized=true
        ).optString("role")

    suspend fun removeMember(channelId:String,userId:String):Boolean =
        backend.postJson(
            "/v1/social/channels/"+channelId+"/members/"+userId+"/remove",
            JSONObject(),
            authorized=true
        ).optBoolean("removed")

    suspend fun createRoom(
        channelId:String,
        name:String,
        topic:String,
        visibility:String,
        slowModeSeconds:Int
    ):String =
        backend.postJson(
            "/v1/social/channels/"+channelId+"/rooms",
            JSONObject()
                .put("name",name.trim())
                .put("topic",topic.trim())
                .put("visibility",visibility)
                .put("slowModeSeconds",slowModeSeconds),
            authorized=true
        ).optString("id")

    suspend fun updateRoom(
        channelId:String,
        roomId:String,
        topic:String,
        visibility:String,
        slowModeSeconds:Int
    ) {
        backend.postJson(
            "/v1/social/channels/"+channelId+"/rooms/"+roomId+"/settings",
            JSONObject()
                .put("topic",topic.trim())
                .put("visibility",visibility)
                .put("slowModeSeconds",slowModeSeconds),
            authorized=true
        )
    }

    suspend fun scheduledContent():List<ScheduledCreatorItem> {
        val root=backend.getJson("/v1/creator/scheduled",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    ScheduledCreatorItem(
                        id=x.optString("id"),
                        kind=x.optString("kind"),
                        contentType=x.optString("contentType"),
                        preview=x.optString("preview"),
                        scheduledAt=x.optString("scheduledAt"),
                        channelId=x.optString("channelId").takeIf(String::isNotBlank),
                        channelName=x.optString("channelName"),
                        mediaTitleId=x.optString("mediaTitleId").takeIf(String::isNotBlank),
                        mediaTitle=x.optString("mediaTitle"),
                        spoiler=x.optBoolean("spoiler")
                    )
                )
            }
        }
    }

    suspend fun publishScheduledNow(kind:String,id:String):Boolean =
        backend.postJson(
            "/v1/creator/scheduled/"+kind+"/"+id+"/publish-now",
            JSONObject(),
            authorized=true
        ).optBoolean("published")

    suspend fun unschedule(kind:String,id:String):Boolean =
        backend.postJson(
            "/v1/creator/scheduled/"+kind+"/"+id+"/unschedule",
            JSONObject(),
            authorized=true
        ).optBoolean("unscheduled")

    suspend fun creatorStudio(): CreatorStudioAnalytics {
        val root=backend.getJson("/v1/creator/studio",authorized=true)
        val p=root.optJSONObject("profile") ?: JSONObject()
        val a=root.optJSONObject("analytics") ?: JSONObject()
        return CreatorStudioAnalytics(
            id=p.optString("id"),
            username=p.optString("username"),
            displayName=p.optString("displayName"),
            avatarUrl=p.optString("avatarUrl"),
            followers=p.optLong("followers"),
            following=p.optLong("following"),
            posts=p.optLong("posts"),
            reels=p.optLong("reels"),
            reelViews=a.optLong("reelViews"),
            reelLikes=a.optLong("reelLikes"),
            reelComments=a.optLong("reelComments"),
            reelSaves=a.optLong("reelSaves"),
            reelShares=a.optLong("reelShares"),
            storyViews=a.optLong("storyViews"),
            channels=a.optLong("channels"),
            channelFollowers=a.optLong("channelFollowers")
        )
    }

    private fun parsePosts(root: JSONObject): List<SocialPost> {
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val authorObj=x.optJSONObject("author") ?: JSONObject()
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
                        author=SocialAuthor(
                            id=authorObj.optString("id"),
                            username=authorObj.optString("username"),
                            displayName=authorObj.optString("displayName"),
                            avatarUrl=authorObj.optString("avatarUrl"),
                            verified=authorObj.optBoolean("verified")
                        ),
                        media=mediaObj?.let {
                            SocialMediaRef(
                                id=it.optString("id").takeIf(String::isNotBlank),
                                title=it.optString("title").takeIf(String::isNotBlank),
                                posterUrl=it.optString("posterUrl").takeIf(String::isNotBlank)
                            )
                        }
                    )
                )
            }
        }
    }

    private fun parseReels(root: JSONObject): List<ReelFeedItem> {
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val a=x.optJSONObject("author") ?: JSONObject()
                val m=x.optJSONObject("media")
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
                        author=SocialAuthor(
                            id=a.optString("id"),
                            username=a.optString("username"),
                            displayName=a.optString("displayName"),
                            avatarUrl=a.optString("avatarUrl"),
                            verified=a.optBoolean("verified")
                        ),
                        media=m?.let {
                            val id=it.optString("id").takeIf(String::isNotBlank)
                            if(id==null) null else ReelMediaRef(
                                backendId=id,
                                tmdbId=if(it.isNull("tmdbId"))null else it.optInt("tmdbId"),
                                type=if(it.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                                title=it.optString("title"),
                                originalTitle=it.optString("originalTitle"),
                                posterUrl=it.optString("posterUrl").takeIf(String::isNotBlank),
                                backdropUrl=it.optString("backdropUrl").takeIf(String::isNotBlank),
                                year=if(it.isNull("year"))null else it.optInt("year"),
                                rating=if(it.isNull("rating"))null else it.optDouble("rating")
                            )
                        }
                    )
                )
            }
        }
    }

    private fun parseStories(root: JSONObject): List<SocialStory> {
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val a=x.optJSONObject("author") ?: JSONObject()
                val m=x.optJSONObject("media")
                val kind=m?.optString("kind").orEmpty()
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
                        author=SocialAuthor(
                            id=a.optString("id"),
                            username=a.optString("username"),
                            displayName=a.optString("displayName"),
                            avatarUrl=a.optString("avatarUrl"),
                            verified=a.optBoolean("verified")
                        ),
                        media=m?.let {
                            SocialMediaRef(
                                id=it.optString("id").takeIf(String::isNotBlank),
                                title=it.optString("title").takeIf(String::isNotBlank),
                                posterUrl=it.optString("posterUrl").takeIf(String::isNotBlank),
                                tmdbId=if(it.isNull("tmdbId"))null else it.optInt("tmdbId"),
                                type=when(kind) {
                                    "movie" -> MediaType.MOVIE
                                    "tv","series","anime" -> MediaType.TV
                                    else -> null
                                },
                                originalTitle=it.optString("originalTitle").takeIf(String::isNotBlank),
                                backdropUrl=it.optString("backdropUrl").takeIf(String::isNotBlank),
                                year=if(it.isNull("year"))null else it.optInt("year"),
                                rating=if(it.isNull("rating"))null else it.optDouble("rating")
                            )
                        }
                    )
                )
            }
        }
    }
}
