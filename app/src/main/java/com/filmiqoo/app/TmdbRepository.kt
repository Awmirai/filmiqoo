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
    private val backend = BackendRepository(context.applicationContext)
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

    internal suspend fun discoveryMetadata(path:String,params:Map<String,String> = emptyMap(),backendOverride:BackendRepository=backend):JSONObject = get(path,params,backendOverride)

    private suspend fun get(path: String, params: Map<String, String> = emptyMap(),backendOverride:BackendRepository=backend): JSONObject = withContext(Dispatchers.IO) {
        val serverResult = runCatching { backendOverride.tmdbMetadata(path, params) }
        serverResult.exceptionOrNull()?.let {
            if (it is IranAccessDeniedException || it is kotlinx.coroutines.CancellationException) throw it
        }
        serverResult.getOrNull()?.let { return@withContext it }

        val localToken = prefs.getString("credential", "").orEmpty().trim()
        if (localToken.isBlank()) {
            throw (serverResult.exceptionOrNull()
                ?: IllegalStateException("Filmiqoo metadata service is unavailable"))
        }

        val builder = (base + path).toHttpUrl().newBuilder()
        if (!localToken.startsWith("eyJ")) {
            builder.addQueryParameter("api_key", localToken)
        }
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }

        val requestBuilder = Request.Builder()
            .url(builder.build())
            .header("accept", "application/json")

        if (localToken.startsWith("eyJ")) {
            requestBuilder.header("Authorization", "Bearer " + localToken)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("TMDB " + response.code + " for " + path)
            }
            val raw=response.body?.string().orEmpty()
            val result=if(raw.trimStart().startsWith("[")) JSONObject().put("results",JSONArray(raw)) else JSONObject(raw)
            result.put("_filmiqooDiscovery",JSONObject().put("version",1).put("provider","tmdb_direct").put("appliedParameters",JSONObject(params)))
            result
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
            title = (if (obj.optString("original_language") == "fa") original.ifBlank { title } else title).ifBlank { original.ifBlank { "بدون عنوان" } },
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
                if (item.optString("media_type") == "person" || item.optBoolean("adult")) continue
                val media = parseMedia(item, fallback)
                if (media.id > 0 && media.title.isNotBlank()) add(media)
            }
        }
    }

    suspend fun home(): HomeBundle = CinemaDataRepository(context, backend).home()

    suspend fun trending(): List<MediaItem> {
        val platform = runCatching { backend.catalogHome() }.getOrDefault(emptyList())
        if (platform.isNotEmpty()) return platform
        return parseList(
            get("trending/all/week", mapOf("language" to "fa-IR")),
            MediaType.MOVIE
        ).filter { it.type == MediaType.MOVIE || it.type == MediaType.TV }
    }

    suspend fun resolveCatalogMedia(media: MediaItem): MediaItem? {
        if(!media.backendId.isNullOrBlank()) {
            return runCatching {
                backend.detail(media.backendId).asMediaItem()
            }.getOrDefault(media)
        }
        if(media.id<=0) return null

        val homeMatch=runCatching {
            backend.catalogHome().firstOrNull {
                it.id==media.id && it.type==media.type && !it.backendId.isNullOrBlank()
            }
        }.getOrNull()
        if(homeMatch!=null) {
            return runCatching {
                backend.detail(homeMatch.backendId!!).asMediaItem()
            }.getOrDefault(homeMatch)
        }

        val searchRepo=UniversalSearchRepository(context.applicationContext,backend)
        val queries=listOf(media.title,media.originalTitle)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        for(query in queries) {
            val match=runCatching {
                searchRepo.search(query).media.firstOrNull {
                    it.id==media.id && it.type==media.type && !it.backendId.isNullOrBlank()
                }
            }.getOrNull()
            if(match!=null) {
                return runCatching {
                    backend.detail(match.backendId!!).asMediaItem()
                }.getOrDefault(match)
            }
        }
        return null
    }

    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val platform = runCatching { backend.catalogHome() }
            .getOrDefault(emptyList())
            .filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.originalTitle.contains(query, ignoreCase = true) ||
                    it.overview.contains(query, ignoreCase = true)
            }
        val metadata = parseList(
            get("search/multi", mapOf(
                "language" to "en-US",
                "query" to query,
                "include_adult" to "false",
                "page" to "1"
            )),
            MediaType.MOVIE
        ).filter { it.type == MediaType.MOVIE || it.type == MediaType.TV }
        return (platform + metadata)
            .distinctBy(::cinemaMediaKey)
            .take(60)
    }

    suspend fun detail(media: MediaItem): MediaDetail = CinemaDataRepository(context, backend).title(media).detail

    suspend fun person(id:Int):PersonDetail {
        require(id>0) { "Invalid person id" }

        var obj=get(
            "person/"+id,
            mapOf(
                "language" to "fa-IR",
                "append_to_response" to "combined_credits,images,external_ids"
            )
        )

        if(obj.optString("biography").isBlank()) {
            val en=get(
                "person/"+id,
                mapOf(
                    "language" to "en-US",
                    "append_to_response" to "combined_credits,images,external_ids"
                )
            )
            val merged=JSONObject(obj.toString())
            if(merged.optString("biography").isBlank()) {
                merged.put("biography",en.optString("biography"))
            }
            if(merged.optString("place_of_birth").isBlank()) {
                merged.put("place_of_birth",en.optString("place_of_birth"))
            }
            obj=merged
        }

        val combined=obj.optJSONObject("combined_credits") ?: JSONObject()
        val credits=buildList {
            val cast=combined.optJSONArray("cast") ?: JSONArray()
            for(i in 0 until cast.length()) {
                val c=cast.optJSONObject(i) ?: continue
                val mt=c.optString("media_type")
                if(mt!="movie" && mt!="tv") continue
                val media=parseMedia(c,if(mt=="tv")MediaType.TV else MediaType.MOVIE)
                if(media.id<=0 || media.title.isBlank()) continue
                add(
                    PersonCredit(
                        media=media,
                        role=c.optString("character"),
                        department="Acting"
                    )
                )
            }

            val crew=combined.optJSONArray("crew") ?: JSONArray()
            for(i in 0 until crew.length()) {
                val c=crew.optJSONObject(i) ?: continue
                val mt=c.optString("media_type")
                if(mt!="movie" && mt!="tv") continue
                val media=parseMedia(c,if(mt=="tv")MediaType.TV else MediaType.MOVIE)
                if(media.id<=0 || media.title.isBlank()) continue
                val job=c.optString("job")
                val department=c.optString("department")
                add(
                    PersonCredit(
                        media=media,
                        role=job,
                        department=department
                    )
                )
            }
        }
            .distinctBy { it.media.key+"|"+it.role }
            .sortedWith(
                compareByDescending<PersonCredit> { it.media.date }
                    .thenByDescending { it.media.popularity }
            )

        val imageArr=obj.optJSONObject("images")?.optJSONArray("profiles") ?: JSONArray()
        val images=buildList {
            for(i in 0 until minOf(imageArr.length(),24)) {
                val path=imageArr.optJSONObject(i)?.optString("file_path").orEmpty()
                if(path.isNotBlank() && path!="null") add(path)
            }
        }.distinct()

        val aliasesArr=obj.optJSONArray("also_known_as") ?: JSONArray()
        val aliases=buildList {
            for(i in 0 until aliasesArr.length()) {
                aliasesArr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct().take(12)

        val external=obj.optJSONObject("external_ids")
        return PersonDetail(
            id=id,
            name=obj.optString("name").ifBlank { "بدون نام" },
            biography=obj.optString("biography"),
            birthday=obj.optString("birthday"),
            deathday=obj.optString("deathday").takeIf { it.isNotBlank() && it!="null" },
            placeOfBirth=obj.optString("place_of_birth"),
            knownForDepartment=obj.optString("known_for_department"),
            profilePath=obj.optString("profile_path").takeIf { it.isNotBlank() && it!="null" },
            alsoKnownAs=aliases,
            imdbId=external?.optString("imdb_id")?.takeIf { it.isNotBlank() && it!="null" },
            images=images,
            credits=credits
        )
    }

    fun poster(path: String?): String? = path?.let { if (it.startsWith("http")) it else imageW500 + it }
    fun backdrop(path: String?): String? = path?.let { if (it.startsWith("http")) it else imageW1280 + it }
    fun profile(path: String?): String? = path?.let { if (it.startsWith("http")) it else imageW500 + it }
}
