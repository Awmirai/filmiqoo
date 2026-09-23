package com.filmiqoo.app

import org.json.JSONObject

data class MediaCollection(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val visibility: String,
    val itemCount: Int,
    val posterUrl: String?
)

data class MediaCollectionDetail(
    val summary: MediaCollection,
    val items: List<MediaItem>
)

class LibraryRepository(
    private val backend: BackendRepository
) {
    suspend fun favorites(): List<MediaItem> =
        parseMediaList(backend.getJson("/v1/library/favorites",authorized=true))

    suspend fun watchlist(): List<MediaItem> =
        parseMediaList(backend.getJson("/v1/library/watchlist",authorized=true))

    suspend fun toggleWatchlist(mediaId:String): Boolean =
        backend.postJson(
            "/v1/library/watchlist/"+mediaId+"/toggle",
            JSONObject(),
            authorized=true
        ).optBoolean("inWatchlist")

    suspend fun collections(): List<MediaCollection> {
        val root=backend.getJson("/v1/library/collections",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parseCollection(x))
            }
        }
    }

    suspend fun createCollection(
        name:String,
        description:String="",
        emoji:String="🎬",
        visibility:String="private"
    ): MediaCollection {
        val x=backend.postJson(
            "/v1/library/collections",
            JSONObject()
                .put("name",name)
                .put("description",description)
                .put("emoji",emoji)
                .put("visibility",visibility),
            authorized=true
        )
        return parseCollection(x)
    }

    suspend fun collection(id:String): MediaCollectionDetail {
        val root=backend.getJson("/v1/library/collections/"+id,authorized=true)
        val summary=MediaCollection(
            id=root.optString("id"),
            name=root.optString("name"),
            description=root.optString("description"),
            emoji=root.optString("emoji","🎬"),
            visibility=root.optString("visibility","private"),
            itemCount=root.optInt("itemCount"),
            posterUrl=null
        )
        return MediaCollectionDetail(
            summary=summary,
            items=parseMediaArray(root.optJSONArray("items"))
        )
    }

    suspend fun toggleCollectionItem(collectionId:String,mediaId:String):Boolean =
        backend.postJson(
            "/v1/library/collections/"+collectionId+"/items/"+mediaId+"/toggle",
            JSONObject(),
            authorized=true
        ).optBoolean("included")

    suspend fun deleteCollection(id:String):Boolean =
        backend.postJson(
            "/v1/library/collections/"+id+"/delete",
            JSONObject(),
            authorized=true
        ).optBoolean("deleted")

    private fun parseMediaList(root:JSONObject):List<MediaItem> =
        parseMediaArray(root.optJSONArray("items"))

    private fun parseMediaArray(arr:org.json.JSONArray?):List<MediaItem> = buildList {
        if(arr==null) return@buildList
        for(i in 0 until arr.length()) {
            val x=arr.optJSONObject(i) ?: continue
            add(
                MediaItem(
                    id=if(x.isNull("tmdbId"))0 else x.optInt("tmdbId"),
                    type=if(x.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
                    title=x.optString("title").ifBlank{x.optString("originalTitle")},
                    originalTitle=x.optString("originalTitle"),
                    overview=x.optString("overview"),
                    posterPath=x.optString("posterUrl").takeIf(String::isNotBlank),
                    backdropPath=x.optString("backdropUrl").takeIf(String::isNotBlank),
                    vote=x.optDouble("rating",0.0),
                    date=x.optInt("year",0).takeIf{it>0}?.toString().orEmpty(),
                    backendId=x.optString("id")
                )
            )
        }
    }

    private fun parseCollection(x:JSONObject)=MediaCollection(
        id=x.optString("id"),
        name=x.optString("name"),
        description=x.optString("description"),
        emoji=x.optString("emoji","🎬"),
        visibility=x.optString("visibility","private"),
        itemCount=x.optInt("itemCount"),
        posterUrl=x.optString("posterUrl").takeIf(String::isNotBlank)
    )
}
