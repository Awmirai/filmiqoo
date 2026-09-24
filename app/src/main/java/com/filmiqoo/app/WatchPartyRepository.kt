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

data class WatchPartyMember(
    val id:String,
    val username:String,
    val displayName:String,
    val avatarUrl:String,
    val verified:Boolean,
    val role:String,
    val ready:Boolean
)

data class WatchPartyJoinRequest(
    val id:String,
    val username:String,
    val displayName:String,
    val avatarUrl:String,
    val verified:Boolean
)

data class WatchPartyLobby(
    val myRole:String,
    val myReady:Boolean,
    val readyCheckEnabled:Boolean,
    val readyCount:Long,
    val participantCount:Long,
    val state:String,
    val members:List<WatchPartyMember>,
    val requests:List<WatchPartyJoinRequest>
)

data class WatchPartyReaction(
    val id:String,
    val emoji:String,
    val userId:String,
    val displayName:String,
    val avatarUrl:String
)

data class WatchPartyQueueItem(
    val id:String,
    val status:String,
    val votes:Long,
    val voted:Boolean,
    val media:MediaItem,
    val suggestedBy:SocialAuthor
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

    suspend fun lobby(id:String):WatchPartyLobby {
        val root=backend.getJson("/v1/watch-parties/"+id+"/lobby",authorized=true)
        val membersArray=root.optJSONArray("members")
        val requestsArray=root.optJSONArray("requests")

        val members=buildList {
            if(membersArray!=null) for(i in 0 until membersArray.length()) {
                val x=membersArray.optJSONObject(i) ?: continue
                add(
                    WatchPartyMember(
                        id=x.optString("id"),
                        username=x.optString("username"),
                        displayName=x.optString("displayName"),
                        avatarUrl=x.optString("avatarUrl"),
                        verified=x.optBoolean("verified"),
                        role=x.optString("role"),
                        ready=x.optBoolean("ready")
                    )
                )
            }
        }

        val requests=buildList {
            if(requestsArray!=null) for(i in 0 until requestsArray.length()) {
                val x=requestsArray.optJSONObject(i) ?: continue
                add(
                    WatchPartyJoinRequest(
                        id=x.optString("id"),
                        username=x.optString("username"),
                        displayName=x.optString("displayName"),
                        avatarUrl=x.optString("avatarUrl"),
                        verified=x.optBoolean("verified")
                    )
                )
            }
        }

        return WatchPartyLobby(
            myRole=root.optString("myRole"),
            myReady=root.optBoolean("myReady"),
            readyCheckEnabled=root.optBoolean("readyCheckEnabled"),
            readyCount=root.optLong("readyCount"),
            participantCount=root.optLong("participantCount"),
            state=root.optString("state"),
            members=members,
            requests=requests
        )
    }

    suspend fun requestJoin(id:String):String =
        backend.postJson(
            "/v1/watch-parties/"+id+"/join-request",
            JSONObject(),
            authorized=true
        ).optString("status")

    suspend fun resolveJoinRequest(id:String,userId:String,accept:Boolean):String =
        backend.postJson(
            "/v1/watch-parties/"+id+"/join-requests/"+userId+"/resolve",
            JSONObject().put("accept",accept),
            authorized=true
        ).optString("status")

    suspend fun toggleReady(id:String):Boolean =
        backend.postJson(
            "/v1/watch-parties/"+id+"/ready",
            JSONObject(),
            authorized=true
        ).optBoolean("ready")

    suspend fun setReadyCheck(id:String,enabled:Boolean):Boolean =
        backend.postJson(
            "/v1/watch-parties/"+id+"/ready-check",
            JSONObject().put("enabled",enabled),
            authorized=true
        ).optBoolean("enabled")

    suspend fun setMemberRole(id:String,userId:String,role:String):String =
        backend.postJson(
            "/v1/watch-parties/"+id+"/members/"+userId+"/role",
            JSONObject().put("role",role),
            authorized=true
        ).optString("role")

    suspend fun leave(id:String):Boolean =
        backend.postJson(
            "/v1/watch-parties/"+id+"/leave",
            JSONObject(),
            authorized=true
        ).optBoolean("left")

    suspend fun react(id:String,emoji:String) {
        backend.postJson(
            "/v1/watch-parties/"+id+"/reactions",
            JSONObject().put("emoji",emoji),
            authorized=true
        )
    }

    suspend fun reactions(id:String):List<WatchPartyReaction> {
        val root=backend.getJson("/v1/watch-parties/"+id+"/reactions",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val u=x.optJSONObject("user") ?: JSONObject()
                add(
                    WatchPartyReaction(
                        id=x.optString("id"),
                        emoji=x.optString("emoji"),
                        userId=u.optString("id"),
                        displayName=u.optString("displayName"),
                        avatarUrl=u.optString("avatarUrl")
                    )
                )
            }
        }
    }

    suspend fun queue(id:String):List<WatchPartyQueueItem> {
        val root=backend.getJson("/v1/watch-parties/"+id+"/queue",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val m=x.optJSONObject("media") ?: JSONObject()
                val a=x.optJSONObject("suggestedBy") ?: JSONObject()
                add(
                    WatchPartyQueueItem(
                        id=x.optString("id"),
                        status=x.optString("status"),
                        votes=x.optLong("votes"),
                        voted=x.optBoolean("voted"),
                        media=MediaItem(
                            id=if(m.isNull("tmdbId"))0 else m.optInt("tmdbId"),
                            type=if(m.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                            title=m.optString("title").ifBlank{m.optString("originalTitle")},
                            originalTitle=m.optString("originalTitle"),
                            posterPath=m.optString("posterUrl").takeIf(String::isNotBlank),
                            backdropPath=m.optString("backdropUrl").takeIf(String::isNotBlank),
                            vote=m.optDouble("rating",0.0),
                            date=if(m.isNull("year"))"" else m.optInt("year").toString(),
                            backendId=m.optString("id")
                        ),
                        suggestedBy=SocialAuthor(
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

    suspend fun addQueueItem(id:String,mediaTitleId:String):String =
        backend.postJson(
            "/v1/watch-parties/"+id+"/queue",
            JSONObject().put("mediaTitleId",mediaTitleId),
            authorized=true
        ).optString("id")

    suspend fun voteQueueItem(id:String,itemId:String):Pair<Boolean,Long> {
        val root=backend.postJson(
            "/v1/watch-parties/"+id+"/queue/"+itemId+"/vote",
            JSONObject(),
            authorized=true
        )
        return root.optBoolean("voted") to root.optLong("votes")
    }

    suspend fun playQueueItem(id:String,itemId:String) {
        backend.postJson(
            "/v1/watch-parties/"+id+"/queue/"+itemId+"/play",
            JSONObject(),
            authorized=true
        )
    }

    suspend fun removeQueueItem(id:String,itemId:String):Boolean =
        backend.postJson(
            "/v1/watch-parties/"+id+"/queue/"+itemId+"/remove",
            JSONObject(),
            authorized=true
        ).optBoolean("removed")

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
