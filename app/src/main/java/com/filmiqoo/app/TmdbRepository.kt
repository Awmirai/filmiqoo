package com.filmiqoo.app

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TmdbRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("filmiqoo_tmdb", Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = prefs.getString("credential", "").orEmpty().isNotBlank()

    fun setApiKey(value: String) {
        prefs.edit().putString("credential", value.trim()).apply()
    }

    private fun credential(): String {
        return prefs.getString("credential", "").orEmpty().trim().also {
            if (it.isBlank()) error("TMDB credential is not configured")
        }
    }

    private val base = "https://api.themoviedb.org/3/"
    val imageW500 = "https://image.tmdb.org/t/p/w500"
    val imageW780 = "https://image.tmdb.org/t/p/w780"
    val imageW1280 = "https://image.tmdb.org/t/p/w1280"
    val imageOriginal = "https://image.tmdb.org/t/p/original"

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val token = credential()
        val builder = (base + path).toHttpUrl().newBuilder()
        if (!token.startsWith("eyJ")) {
            builder.addQueryParameter("api_key", token)
        }
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }

        val requestBuilder = Request.Builder()
            .url(builder.build())
            .header("accept", "application/json")

        if (token.startsWith("eyJ")) {
            requestBuilder.header("Authorization", "Bearer " + token)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("TMDB " + response.code + " for " + path)
            }
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun mediaType(value: String?, fallback: MediaType): MediaType {
        return when(value) {
            "tv" -> MediaType.TV
            "movie" -> MediaType.MOVIE
            else -> fallback
        }
    }

    private fun parseMedia(obj: JSONObject, fallback: MediaType): MediaItem {
        val type = mediaType(obj.optString("media_type"), fallback)
        val title = when(type) {
            MediaType.MOVIE -> obj.optString("title").ifBlank { obj.optString("original_title") }
            MediaType.TV -> obj.optString("name").ifBlank { obj.optString("original_name") }
        }
        val original = when(type) {
            MediaType.MOVIE -> obj.optString("original_title")
            MediaType.TV -> obj.optString("original_name")
        }
        val date = when(type) {
            MediaType.MOVIE -> obj.optString("release_date")
            MediaType.TV -> obj.optString("first_air_date")
        }
        return MediaItem(
            id = obj.optInt("id"),
            type = type,
            title = title.ifBlank { original.ifBlank { "بدون عنوان" } },
            originalTitle = original,
            overview = obj.optString("overview"),
            posterPath = obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
            backdropPath = obj.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            vote = obj.optDouble("vote_average", 0.0),
            date = date,
            popularity = obj.optDouble("popularity", 0.0)
        )
    }

    private fun parseList(obj: JSONObject, fallback: MediaType): List<MediaItem> {
        val arr = obj.optJSONArray("results") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val media = parseMedia(item, fallback)
                if (media.id > 0 && media.title.isNotBlank()) add(media)
            }
        }
    }

    suspend fun home(): HomeBundle = coroutineScope {
        val trending = async {
            parseList(get("trending/all/day", mapOf("language" to "fa-IR")), MediaType.MOVIE)
                .filter { it.type == MediaType.MOVIE || it.type == MediaType.TV }
        }
        val movies = async {
            parseList(get("movie/popular", mapOf("language" to "fa-IR", "page" to "1")), MediaType.MOVIE)
        }
        val tv = async {
            parseList(get("tv/popular", mapOf("language" to "fa-IR", "page" to "1")), MediaType.TV)
        }
        val iranian = async {
            val a = parseList(
                get("discover/movie", mapOf(
                    "language" to "fa-IR",
                    "sort_by" to "popularity.desc",
                    "with_origin_country" to "IR",
                    "include_adult" to "false"
                )),
                MediaType.MOVIE
            )
            val b = parseList(
                get("discover/tv", mapOf(
                    "language" to "fa-IR",
                    "sort_by" to "popularity.desc",
                    "with_origin_country" to "IR"
                )),
                MediaType.TV
            )
            (a + b).sortedByDescending { it.popularity }.take(20)
        }
        val korean = async {
            parseList(
                get("discover/tv", mapOf(
                    "language" to "fa-IR",
                    "sort_by" to "popularity.desc",
                    "with_original_language" to "ko"
                )),
                MediaType.TV
            )
        }
        val bollywood = async {
            parseList(
                get("discover/movie", mapOf(
                    "language" to "fa-IR",
                    "sort_by" to "popularity.desc",
                    "with_original_language" to "hi",
                    "include_adult" to "false"
                )),
                MediaType.MOVIE
            )
        }
        val anime = async {
            parseList(
                get("discover/tv", mapOf(
                    "language" to "fa-IR",
                    "sort_by" to "popularity.desc",
                    "with_genres" to "16",
                    "with_original_language" to "ja"
                )),
                MediaType.TV
            )
        }
        HomeBundle(
            trending = trending.await(),
            popularMovies = movies.await(),
            popularTv = tv.await(),
            iranian = iranian.await(),
            korean = korean.await(),
            bollywood = bollywood.await(),
            anime = anime.await()
        )
    }

    suspend fun trending(): List<MediaItem> {
        return parseList(
            get("trending/all/week", mapOf("language" to "fa-IR")),
            MediaType.MOVIE
        ).filter { it.type == MediaType.MOVIE || it.type == MediaType.TV }
    }

    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        return parseList(
            get("search/multi", mapOf(
                "language" to "fa-IR",
                "query" to query,
                "include_adult" to "false",
                "page" to "1"
            )),
            MediaType.MOVIE
        ).filter { it.type == MediaType.MOVIE || it.type == MediaType.TV }
    }

    suspend fun detail(media: MediaItem): MediaDetail {
        val typePath = if (media.type == MediaType.MOVIE) "movie" else "tv"
        var obj = get(
            typePath + "/" + media.id,
            mapOf(
                "language" to "fa-IR",
                "append_to_response" to "credits,videos,recommendations,similar"
            )
        )

        if (obj.optString("overview").isBlank()) {
            val english = get(
                typePath + "/" + media.id,
                mapOf(
                    "language" to "en-US",
                    "append_to_response" to "credits,videos,recommendations,similar"
                )
            )
            val merged = JSONObject(obj.toString())
            if (merged.optString("overview").isBlank()) merged.put("overview", english.optString("overview"))
            if (merged.optString("tagline").isBlank()) merged.put("tagline", english.optString("tagline"))
            if (merged.optJSONArray("credits") == null) merged.put("credits", english.optJSONObject("credits"))
            obj = merged
        }

        val normalized = MediaItem(
            id = media.id,
            type = media.type,
            title = when(media.type) {
                MediaType.MOVIE -> obj.optString("title").ifBlank { media.title }
                MediaType.TV -> obj.optString("name").ifBlank { media.title }
            },
            originalTitle = media.originalTitle,
            overview = obj.optString("overview").ifBlank { media.overview },
            posterPath = obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" } ?: media.posterPath,
            backdropPath = obj.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" } ?: media.backdropPath,
            vote = obj.optDouble("vote_average", media.vote),
            date = when(media.type) {
                MediaType.MOVIE -> obj.optString("release_date").ifBlank { media.date }
                MediaType.TV -> obj.optString("first_air_date").ifBlank { media.date }
            },
            popularity = obj.optDouble("popularity", media.popularity)
        )

        val genresArray = obj.optJSONArray("genres") ?: JSONArray()
        val genres = buildList {
            for (i in 0 until genresArray.length()) {
                genresArray.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        val castArray = obj.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
        val cast = buildList {
            for (i in 0 until minOf(castArray.length(), 16)) {
                val c = castArray.optJSONObject(i) ?: continue
                add(
                    CastMember(
                        id = c.optInt("id"),
                        name = c.optString("name"),
                        character = c.optString("character"),
                        profilePath = c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }

        val videos = obj.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
        var trailer: String? = null
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optString("site") == "YouTube" && v.optString("type") == "Trailer") {
                trailer = v.optString("key").takeIf { it.isNotBlank() }
                if (v.optBoolean("official")) break
            }
        }

        val recObj = obj.optJSONObject("recommendations") ?: obj.optJSONObject("similar") ?: JSONObject()
        val recommendations = parseList(recObj, media.type)

        val seasonsArray = obj.optJSONArray("seasons") ?: JSONArray()
        val seasons = buildList {
            for (i in 0 until seasonsArray.length()) {
                val s = seasonsArray.optJSONObject(i) ?: continue
                val number = s.optInt("season_number")
                if (number < 0) continue
                add(
                    SeasonInfo(
                        number = number,
                        name = s.optString("name").ifBlank { "فصل " + number },
                        episodes = s.optInt("episode_count"),
                        posterPath = s.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
                        airDate = s.optString("air_date")
                    )
                )
            }
        }

        val runtime = if (media.type == MediaType.MOVIE) {
            obj.optInt("runtime")
        } else {
            val arr = obj.optJSONArray("episode_run_time") ?: JSONArray()
            if (arr.length() > 0) arr.optInt(0) else 0
        }

        return MediaDetail(
            media = normalized,
            tagline = obj.optString("tagline"),
            genres = genres,
            runtime = runtime,
            status = obj.optString("status"),
            cast = cast,
            trailerKey = trailer,
            recommendations = recommendations,
            seasons = seasons
        )
    }

    fun poster(path: String?): String? = path?.let { imageW500 + it }
    fun backdrop(path: String?): String? = path?.let { imageW1280 + it }
    fun profile(path: String?): String? = path?.let { imageW500 + it }
}
