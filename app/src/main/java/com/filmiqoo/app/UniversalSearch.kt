package com.filmiqoo.app

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject

data class SearchUser(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String,
    val verified: Boolean,
    val followers: Long,
    val posts: Long,
    val reels: Long
) {
    fun asCreator()=Creator(
        name=displayName,
        handle="@"+username,
        followers=compactSearchCount(followers),
        bio=bio,
        verified=verified,
        id=id,
        entityType="user",
        avatarUrl=avatarUrl
    )
}

data class SearchChannel(
    val id: String,
    val slug: String,
    val name: String,
    val bio: String,
    val avatarUrl: String,
    val verified: Boolean,
    val followers: Long,
    val posts: Long,
    val reels: Long
) {
    fun asCreator()=Creator(
        name=name,
        handle="@"+slug,
        followers=compactSearchCount(followers),
        bio=bio,
        verified=verified,
        id=id,
        entityType="channel",
        avatarUrl=avatarUrl
    )
}

data class SearchReel(
    val id: String,
    val caption: String,
    val coverUrl: String,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val spoiler: Boolean,
    val author: SearchUser,
    val mediaTitle: String?
)

data class UniversalSearchResult(
    val media: List<MediaItem>,
    val users: List<SearchUser>,
    val channels: List<SearchChannel>,
    val reels: List<SearchReel>
)

class UniversalSearchRepository(
    private val context: Context,
    private val backend: BackendRepository
) {
    suspend fun search(query: String): UniversalSearchResult {
        val root=backend.getJson(
            "/v1/search?q="+java.net.URLEncoder.encode(query,"UTF-8"),
            authorized=false
        )

        val media=buildList {
            val arr=root.optJSONArray("media")
            if(arr!=null) for(i in 0 until arr.length()) {
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
                        backendId=x.optString("id"),
                        mediaVersionId=x.optString("mediaVersionId").takeIf(String::isNotBlank),
                        streamReady=x.optBoolean("streamReady"),
                        quality=x.optString("quality")
                    )
                )
            }
        }

        val users=buildList {
            val arr=root.optJSONArray("users")
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parseUser(x))
            }
        }

        val channels=buildList {
            val arr=root.optJSONArray("channels")
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    SearchChannel(
                        id=x.optString("id"),
                        slug=x.optString("slug"),
                        name=x.optString("name"),
                        bio=x.optString("bio"),
                        avatarUrl=x.optString("avatarUrl"),
                        verified=x.optBoolean("verified"),
                        followers=x.optLong("followers"),
                        posts=x.optLong("posts"),
                        reels=x.optLong("reels")
                    )
                )
            }
        }

        val reels=buildList {
            val arr=root.optJSONArray("reels")
            if(arr!=null) for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val authorObj=x.optJSONObject("author") ?: JSONObject()
                val mediaObj=x.optJSONObject("media")
                add(
                    SearchReel(
                        id=x.optString("id"),
                        caption=x.optString("caption"),
                        coverUrl=x.optString("coverUrl"),
                        views=x.optLong("views"),
                        likes=x.optLong("likes"),
                        comments=x.optLong("comments"),
                        spoiler=x.optBoolean("spoiler"),
                        author=parseUser(authorObj),
                        mediaTitle=mediaObj?.optString("title")?.takeIf(String::isNotBlank)
                    )
                )
            }
        }

        if(query.isNotBlank()) saveHistory(query)

        return UniversalSearchResult(media,users,channels,reels)
    }

    fun history(): List<String> {
        val raw=context.getSharedPreferences("filmiqoo_search_history",Context.MODE_PRIVATE)
            .getString("items","").orEmpty()
        return raw.split("\n").map(String::trim).filter(String::isNotBlank).distinct().take(10)
    }

    fun clearHistory() {
        context.getSharedPreferences("filmiqoo_search_history",Context.MODE_PRIVATE)
            .edit().remove("items").apply()
    }

    private fun saveHistory(query: String) {
        val clean=query.trim()
        if(clean.length<2) return
        val next=(listOf(clean)+history()).distinct().take(10)
        context.getSharedPreferences("filmiqoo_search_history",Context.MODE_PRIVATE)
            .edit().putString("items",next.joinToString("\n")).apply()
    }

    private fun parseUser(x: JSONObject)=SearchUser(
        id=x.optString("id"),
        username=x.optString("username"),
        displayName=x.optString("displayName").ifBlank{x.optString("username")},
        bio=x.optString("bio"),
        avatarUrl=x.optString("avatarUrl"),
        verified=x.optBoolean("verified"),
        followers=x.optLong("followers"),
        posts=x.optLong("posts"),
        reels=x.optLong("reels")
    )
}

fun compactSearchCount(value: Long): String = when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
