package com.filmiqoo.app

import org.json.JSONObject

data class MediaReview(
    val id:String,
    val rating:Int,
    val body:String,
    val spoiler:Boolean,
    val likes:Long,
    val updatedAt:String,
    val author:SocialAuthor
)

data class MediaReviewsBundle(
    val average:Double,
    val count:Long,
    val distribution:Map<Int,Long>,
    val items:List<MediaReview>
)

class ReviewsRepository(
    private val backend:BackendRepository
) {
    suspend fun load(mediaId:String):MediaReviewsBundle {
        val root=backend.getJson("/v1/catalog/"+mediaId+"/reviews",authorized=false)
        val dist=root.optJSONObject("distribution")
        val distribution=buildMap<Int,Long> {
            for(i in 1..10) put(i,dist?.optLong(i.toString()) ?: 0L)
        }
        val arr=root.optJSONArray("items")
        val items=buildList {
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val a=x.optJSONObject("author") ?: JSONObject()
                add(
                    MediaReview(
                        id=x.optString("id"),
                        rating=x.optInt("rating"),
                        body=x.optString("body"),
                        spoiler=x.optBoolean("spoiler"),
                        likes=x.optLong("likes"),
                        updatedAt=x.optString("updatedAt"),
                        author=SocialAuthor(
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
        return MediaReviewsBundle(
            average=root.optDouble("average",0.0),
            count=root.optLong("count"),
            distribution=distribution,
            items=items
        )
    }

    suspend fun save(
        mediaId:String,
        rating:Int,
        body:String,
        spoiler:Boolean
    ):String =
        backend.postJson(
            "/v1/catalog/"+mediaId+"/reviews",
            JSONObject()
                .put("rating",rating)
                .put("body",body)
                .put("spoiler",spoiler),
            authorized=true
        ).optString("id")

    suspend fun toggleLike(reviewId:String):Boolean =
        backend.postJson(
            "/v1/reviews/"+reviewId+"/like",
            JSONObject(),
            authorized=true
        ).optBoolean("liked")
}
