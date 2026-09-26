package com.filmiqoo.app

import org.json.JSONArray
import org.json.JSONObject

data class SceneBookmark(
    val id:String,
    val mediaVersionId:String,
    val positionMs:Long,
    val note:String,
    val tag:String,
    val createdAt:String,
    val updatedAt:String,
    val title:String,
    val subtitle:String,
    val posterUrl:String?,
    val media:MediaItem?
) {
    fun asPlaybackTarget():PlaybackTarget = PlaybackTarget(
        mediaVersionId=mediaVersionId,
        title=title,
        mediaTitleId=media?.backendId,
        subtitle=subtitle,
        posterUrl=posterUrl,
        startPositionMs=positionMs
    )
}

class SceneBookmarksRepository(
    private val backend:BackendRepository
) {
    suspend fun all():List<SceneBookmark> =
        parseList(
            backend.getJson(
                "/v1/library/scene-bookmarks",
                authorized=true
            ).optJSONArray("items")
        )

    suspend fun forVersion(mediaVersionId:String):List<SceneBookmark> =
        parseList(
            backend.getJson(
                "/v1/playback/"+mediaVersionId+"/bookmarks",
                authorized=true
            ).optJSONArray("items")
        )

    suspend fun create(
        mediaVersionId:String,
        positionMs:Long,
        note:String="",
        tag:String=""
    ):String =
        backend.postJson(
            "/v1/playback/"+mediaVersionId+"/bookmarks",
            JSONObject()
                .put("positionMs",positionMs.coerceAtLeast(0L))
                .put("note",note.trim())
                .put("tag",tag.trim()),
            authorized=true
        ).optString("id")

    suspend fun update(
        id:String,
        note:String,
        tag:String
    ) {
        backend.postJson(
            "/v1/playback/bookmarks/"+id,
            JSONObject()
                .put("note",note.trim())
                .put("tag",tag.trim()),
            authorized=true
        )
    }

    suspend fun delete(id:String):Boolean =
        backend.postJson(
            "/v1/playback/bookmarks/"+id+"/delete",
            JSONObject(),
            authorized=true
        ).optBoolean("deleted")

    private fun parseList(arr:JSONArray?):List<SceneBookmark> = buildList {
        if(arr==null) return@buildList
        for(i in 0 until arr.length()) {
            val x=arr.optJSONObject(i) ?: continue
            val m=x.optJSONObject("media")
            val media=m?.let {
                val backendId=it.optString("id").takeIf(String::isNotBlank)
                if(backendId==null) null else MediaItem(
                    id=if(it.isNull("tmdbId"))0 else it.optInt("tmdbId"),
                    type=if(it.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                    title=it.optString("title").ifBlank{it.optString("originalTitle")},
                    originalTitle=it.optString("originalTitle"),
                    posterPath=it.optString("posterUrl").takeIf(String::isNotBlank),
                    backdropPath=it.optString("backdropUrl").takeIf(String::isNotBlank),
                    vote=it.optDouble("rating",0.0),
                    date=if(it.isNull("year"))"" else it.optInt("year").toString(),
                    backendId=backendId
                )
            }

            add(
                SceneBookmark(
                    id=x.optString("id"),
                    mediaVersionId=x.optString("mediaVersionId"),
                    positionMs=x.optLong("positionMs"),
                    note=x.optString("note"),
                    tag=x.optString("tag"),
                    createdAt=x.optString("createdAt"),
                    updatedAt=x.optString("updatedAt"),
                    title=x.optString("title"),
                    subtitle=x.optString("subtitle"),
                    posterUrl=x.optString("posterUrl").takeIf(String::isNotBlank),
                    media=media
                )
            )
        }
    }
}
