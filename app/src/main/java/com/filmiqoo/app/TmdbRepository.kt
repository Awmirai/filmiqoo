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
        val platform = runCatching {
            if (backend.health()) backend.catalogHome() else emptyList()
        }.getOrDefault(emptyList())
        if (platform.isNotEmpty()) {
            val movies = platform.filter { it.type == MediaType.MOVIE }
            val tv = platform.filter { it.type == MediaType.TV }
            return@coroutineScope HomeBundle(
                trending = platform,
                popularMovies = movies,
                popularTv = tv,
                iranian = platform.filter { it.originalTitle.contains("ایران", ignoreCase = true) }.ifEmpty { movies.take(10) },
                korean = tv.take(10),
                bollywood = movies.drop(3).take(10),
                anime = tv.drop(3).take(10)
            )
        }
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
        if (!hasApiKey()) {
            return runCatching { backend.catalogHome() }.getOrDefault(emptyList())
        }
        return parseList(
            get("trending/all/week", mapOf("language" to "fa-IR")),
            MediaType.MOVIE
        ).filter { it.type == MediaType.MOVIE || it.type == MediaType.TV }
    }

    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        if (!hasApiKey()) {
            return runCatching { backend.catalogHome() }
                .getOrDefault(emptyList())
                .filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.originalTitle.contains(query, ignoreCase = true) ||
                        it.overview.contains(query, ignoreCase = true)
                }
        }
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
        if (!hasApiKey() && !media.backendId.isNullOrBlank()) {
            val platform = backend.detail(media.backendId)
            return MediaDetail(
                media = media.copy(overview = platform.overview.ifBlank { media.overview }),
                tagline = "",
                genres = emptyList(),
                runtime = 0,
                status = "available",
                cast = emptyList(),
                trailerKey = null,
                recommendations = emptyList(),
                seasons = platform.seasons.map {
                    SeasonInfo(
                        number = it.number,
                        name = it.name.ifBlank { "فصل " + it.number },
                        episodes = it.episodes.size,
                        posterPath = it.posterUrl.takeIf(String::isNotBlank),
                        airDate = ""
                    )
                }
            )
        }
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
            popularity = obj.optDouble("popularity", media.popularity),
            backendId = media.backendId,
            mediaVersionId = media.mediaVersionId,
            streamReady = media.streamReady,
            quality = media.quality
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

        val crewArray = obj.optJSONObject("credits")?.optJSONArray("crew") ?: JSONArray()
        val directors = buildList<CastMember> {
            for (i in 0 until crewArray.length()) {
                val c = crewArray.optJSONObject(i) ?: continue
                val job=c.optString("job")
                val department=c.optString("department")
                val isDirector=job.equals("Director",true) ||
                    (media.type==MediaType.TV && (
                        job.equals("Executive Producer",true) ||
                        department.equals("Directing",true)
                    ))
                if(!isDirector) continue
                val id=c.optInt("id")
                if(id<=0 || any { it.id==id }) continue
                add(
                    CastMember(
                        id=id,
                        name=c.optString("name"),
                        character=job.ifBlank { department },
                        profilePath=c.optString("profile_path").takeIf { it.isNotBlank() && it!="null" }
                    )
                )
                if(size>=8) break
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

        val franchise=if(media.type==MediaType.MOVIE) {
            val collectionRef=obj.optJSONObject("belongs_to_collection")
            val collectionId=collectionRef?.optInt("id") ?: 0
            if(collectionId>0) {
                runCatching {
                    val collection=get(
                        "collection/"+collectionId,
                        mapOf("language" to "fa-IR")
                    )
                    val partsArray=collection.optJSONArray("parts") ?: JSONArray()
                    val parts=buildList {
                        for(i in 0 until partsArray.length()) {
                            val x=partsArray.optJSONObject(i) ?: continue
                            add(
                                MediaItem(
                                    id=x.optInt("id"),
                                    type=MediaType.MOVIE,
                                    title=x.optString("title").ifBlank{x.optString("original_title")},
                                    originalTitle=x.optString("original_title"),
                                    overview=x.optString("overview"),
                                    posterPath=x.optString("poster_path").takeIf { it.isNotBlank() && it!="null" },
                                    backdropPath=x.optString("backdrop_path").takeIf { it.isNotBlank() && it!="null" },
                                    vote=x.optDouble("vote_average",0.0),
                                    date=x.optString("release_date"),
                                    popularity=x.optDouble("popularity",0.0)
                                )
                            )
                        }
                    }.sortedWith(
                        compareBy<MediaItem> {
                            it.date.takeIf(String::isNotBlank) ?: "9999-12-31"
                        }.thenBy { it.id }
                    )
                    FranchiseInfo(
                        id=collectionId,
                        name=collection.optString("name")
                            .ifBlank{collectionRef?.optString("name").orEmpty()},
                        posterPath=collection.optString("poster_path")
                            .takeIf { it.isNotBlank() && it!="null" },
                        backdropPath=collection.optString("backdrop_path")
                            .takeIf { it.isNotBlank() && it!="null" },
                        parts=parts
                    )
                }.getOrNull()
            } else null
        } else null

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
            seasons = seasons,
            directors = directors,
            franchise = franchise
        )
    }

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
