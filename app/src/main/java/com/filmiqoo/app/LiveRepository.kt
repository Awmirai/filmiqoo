package com.filmiqoo.app

import org.json.JSONObject

data class LiveEvent(
    val id:String,
    val eventType:String,
    val title:String,
    val description:String,
    val visibility:String,
    val state:String,
    val playbackUrl:String,
    val coverUrl:String,
    val allowChat:Boolean,
    val scheduledAt:String?,
    val startedAt:String?,
    val endedAt:String?,
    val viewers:Long,
    val peakViewers:Long,
    val roomId:String?,
    val host:SocialAuthor,
    val media:MediaItem?
)

class LiveRepository(
    private val backend:BackendRepository
) {
    suspend fun events():List<LiveEvent> {
        val root=backend.getJson("/v1/live-events",authorized=false)
        return parseList(root)
    }

    suspend fun detail(id:String):LiveEvent =
        parseEvent(backend.getJson("/v1/live-events/"+id,authorized=false))

    suspend fun create(
        eventType:String,
        title:String,
        description:String,
        visibility:String,
        playbackUrl:String,
        coverUrl:String,
        allowChat:Boolean,
        scheduledAtIso:String?,
        mediaTitleId:String?
    ):Pair<String,String?> {
        val body=JSONObject()
            .put("eventType",eventType)
            .put("title",title.trim())
            .put("description",description.trim())
            .put("visibility",visibility)
            .put("playbackUrl",playbackUrl.trim())
            .put("coverUrl",coverUrl.trim())
            .put("allowChat",allowChat)
        if(!scheduledAtIso.isNullOrBlank()) body.put("scheduledAt",scheduledAtIso)
        if(!mediaTitleId.isNullOrBlank()) body.put("mediaTitleId",mediaTitleId)

        val root=backend.postJson("/v1/live-events",body,authorized=true)
        return root.optString("id") to root.optString("roomId").takeIf(String::isNotBlank)
    }

    suspend fun join(id:String):Long =
        backend.postJson("/v1/live-events/"+id+"/join",JSONObject(),authorized=true)
            .optLong("viewers")

    suspend fun heartbeat(id:String):Long =
        backend.postJson("/v1/live-events/"+id+"/heartbeat",JSONObject(),authorized=true)
            .optLong("viewers")

    suspend fun leave(id:String):Long =
        backend.postJson("/v1/live-events/"+id+"/leave",JSONObject(),authorized=true)
            .optLong("viewers")

    suspend fun updateState(
        id:String,
        state:String,
        playbackUrl:String?=null
    ):String {
        val body=JSONObject().put("state",state)
        if(playbackUrl!=null) body.put("playbackUrl",playbackUrl)
        return backend.postJson(
            "/v1/live-events/"+id+"/state",
            body,
            authorized=true
        ).optString("state")
    }

    private fun parseList(root:JSONObject):List<LiveEvent> {
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parseEvent(x))
            }
        }
    }

    private fun parseEvent(x:JSONObject):LiveEvent {
        val h=x.optJSONObject("host") ?: JSONObject()
        val m=x.optJSONObject("media")
        val media=m?.let {
            val backendId=it.optString("id").takeIf(String::isNotBlank)
            if(backendId==null) null else MediaItem(
                id=if(it.isNull("tmdbId"))0 else it.optInt("tmdbId"),
                type=if(it.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                title=it.optString("title").ifBlank { it.optString("originalTitle") },
                originalTitle=it.optString("originalTitle"),
                posterPath=it.optString("posterUrl").takeIf(String::isNotBlank),
                backdropPath=it.optString("backdropUrl").takeIf(String::isNotBlank),
                vote=it.optDouble("rating",0.0),
                date=if(it.isNull("year"))"" else it.optInt("year").toString(),
                backendId=backendId
            )
        }

        return LiveEvent(
            id=x.optString("id"),
            eventType=x.optString("eventType"),
            title=x.optString("title"),
            description=x.optString("description"),
            visibility=x.optString("visibility","public"),
            state=x.optString("state","scheduled"),
            playbackUrl=x.optString("playbackUrl"),
            coverUrl=x.optString("coverUrl"),
            allowChat=x.optBoolean("allowChat",true),
            scheduledAt=x.optString("scheduledAt").takeIf(String::isNotBlank),
            startedAt=x.optString("startedAt").takeIf(String::isNotBlank),
            endedAt=x.optString("endedAt").takeIf(String::isNotBlank),
            viewers=x.optLong("viewers"),
            peakViewers=x.optLong("peakViewers"),
            roomId=x.optString("roomId").takeIf(String::isNotBlank),
            host=SocialAuthor(
                id=h.optString("id"),
                username=h.optString("username"),
                displayName=h.optString("displayName"),
                avatarUrl=h.optString("avatarUrl"),
                verified=h.optBoolean("verified")
            ),
            media=media
        )
    }
}
