package com.filmiqoo.app

data class SocialCollectionOwner(
    val id:String,
    val username:String,
    val displayName:String,
    val avatarUrl:String,
    val verified:Boolean
) {
    fun asCreator()=Creator(
        name=displayName,
        handle="@"+username,
        followers="",
        bio="Collection curator",
        verified=verified,
        id=id,
        entityType="user",
        avatarUrl=avatarUrl
    )
}

data class SocialCollection(
    val id:String,
    val name:String,
    val description:String,
    val emoji:String,
    val visibility:String,
    val itemCount:Int,
    val followers:Long,
    val posterUrl:String?,
    val owner:SocialCollectionOwner,
    val following:Boolean=false
)

data class SocialCollectionDetail(
    val summary:SocialCollection,
    val items:List<MediaItem>
)

class SocialCollectionsRepository(
    private val backend:BackendRepository
) {
    suspend fun discover():List<SocialCollection> =
        parseList(backend.getJson("/v1/social/collections",authorized=false),false)

    suspend fun following():List<SocialCollection> =
        parseList(backend.getJson("/v1/social/collections/following",authorized=true),true)

    suspend fun detail(id:String):SocialCollectionDetail {
        val root=backend.getJson("/v1/social/collections/"+id,authorized=false)
        return SocialCollectionDetail(
            summary=parseCollection(root,false),
            items=parseMedia(root.optJSONArray("items"))
        )
    }

    suspend fun toggleFollow(id:String):Boolean =
        backend.postJson(
            "/v1/social/collections/"+id+"/follow",
            org.json.JSONObject(),
            authorized=true
        ).optBoolean("following")

    private fun parseList(root:org.json.JSONObject,forcedFollowing:Boolean):List<SocialCollection> {
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parseCollection(x,forcedFollowing || x.optBoolean("following")))
            }
        }
    }

    private fun parseCollection(x:org.json.JSONObject,following:Boolean):SocialCollection {
        val o=x.optJSONObject("owner") ?: org.json.JSONObject()
        return SocialCollection(
            id=x.optString("id"),
            name=x.optString("name"),
            description=x.optString("description"),
            emoji=x.optString("emoji","🎬"),
            visibility=x.optString("visibility"),
            itemCount=x.optInt("itemCount"),
            followers=x.optLong("followers"),
            posterUrl=x.optString("posterUrl").takeIf(String::isNotBlank),
            owner=SocialCollectionOwner(
                id=o.optString("id"),
                username=o.optString("username"),
                displayName=o.optString("displayName"),
                avatarUrl=o.optString("avatarUrl"),
                verified=o.optBoolean("verified")
            ),
            following=following
        )
    }

    private fun parseMedia(arr:org.json.JSONArray?):List<MediaItem> = buildList {
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
                    backendId=x.optString("id").takeIf(String::isNotBlank)
                )
            )
        }
    }
}
