package com.filmiqoo.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal suspend fun <T> cinemaOptional(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (denied: IranAccessDeniedException) {
    throw denied
} catch (_: Exception) {
    null
}

data class CinemaPage(val items: List<MediaItem>, val page: Int, val totalPages: Int) {
    val hasMore: Boolean get() = page < totalPages.coerceAtMost(500)
}

data class CinemaTitleData(
    val detail: MediaDetail,
    val platform: PlatformDetail?,
    val originCountries: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val originalLanguage: String = "",
    val englishTitle: String = "",
    val metadataAvailable: Boolean = true
) {
    val playableMovies: List<PlatformVersion> get() = platform?.versions.orEmpty().filter { it.streamReady }
    val playableEpisodes: List<Pair<Int, PlatformEpisode>> get() = platform?.seasons.orEmpty()
        .sortedBy { it.number }.flatMap { season -> season.episodes.sortedBy { it.number }.map { season.number to it } }
        .filter { it.second.streamReady && !it.second.mediaVersionId.isNullOrBlank() }
}

/** Only stores public metadata in a bounded, in-memory cache. Never stores playback URLs or access decisions. */
class CinemaDataRepository(context: Context, private val backend: BackendRepository = BackendRepository(context.applicationContext)) {
    private val resolver = TmdbRepository(context.applicationContext)

    private suspend fun metadata(path: String, parameters: Map<String, String>): JSONObject {
        val key = backend.session.baseUrl + path + parameters.toSortedMap().toString()
        synchronized(cache) {
            cache[key]?.takeIf { SystemClock.elapsedRealtime() - it.at < 300_000L }?.let {
                return JSONObject(it.json)
            }
        }
        val result = backend.tmdbMetadata(path, parameters)
        synchronized(cache) {
            cache[key] = CacheEntry(SystemClock.elapsedRealtime(), result.toString())
            while (cache.size > 48) cache.remove(cache.keys.first())
        }
        return result
    }

    suspend fun catalog(query: CinemaQuery): CinemaPage {
        val raw = metadata(query.path, query.parameters())
        return CinemaPage(parseList(raw.optJSONArray("results"), query.type), query.page, raw.optInt("total_pages", query.page))
    }

    suspend fun title(input: MediaItem): CinemaTitleData = supervisorScope {
        val resolved = if (input.backendId.isNullOrBlank()) cinemaOptional { resolver.resolveCatalogMedia(input) } else input
        val platform = resolved?.backendId?.takeIf(String::isNotBlank)?.let { cinemaOptional { backend.detail(it) } }
        val id = platform?.tmdbId?.takeIf { it > 0 } ?: input.id
        val type = platform?.let { if (it.kind == "movie") MediaType.MOVIE else MediaType.TV } ?: input.type
        val path = (if (type == MediaType.MOVIE) "movie/" else "tv/") + id
        val appended = "credits,videos,recommendations,similar"
        val localizedRequest = async {
            if (id > 0) cinemaOptional { metadata(path, mapOf("language" to "fa-IR", "append_to_response" to appended)) } else null
        }
        val englishRequest = async {
            if (id > 0) cinemaOptional { metadata(path, mapOf("language" to "en-US", "append_to_response" to appended)) } else null
        }
        val fa = localizedRequest.await()
        val en = englishRequest.await()
        if (fa == null && en == null && platform == null) error("اطلاعات این عنوان دریافت نشد. اتصال را بررسی و دوباره تلاش کن.")
        val obj = fa ?: en ?: JSONObject()
        val english = en ?: JSONObject()
        val titleKey = if (type == MediaType.MOVIE) "title" else "name"
        val originalKey = if (type == MediaType.MOVIE) "original_title" else "original_name"
        val origins = buildList {
            val countries = obj.optJSONArray("origin_country")
            if (countries != null) for (i in 0 until countries.length()) add(countries.optString(i))
            val production = obj.optJSONArray("production_countries")
            if (production != null) for (i in 0 until production.length()) production.optJSONObject(i)?.optString("iso_3166_1")?.let(::add)
        }.filter(String::isNotBlank).distinct()
        val language = obj.optString("original_language")
        val title = CinemaTitlePolicy.choose(
            localized = obj.optString(titleKey).ifBlank { platform?.title ?: input.title },
            english = english.optString(titleKey),
            original = obj.optString(originalKey).ifBlank { input.originalTitle },
            preferEnglish = CinemaTitlePolicy.preferEnglish(language, origins.toSet())
        )
        val preferred = platform?.versions?.firstOrNull { it.streamReady && it.preferred }
            ?: platform?.versions?.firstOrNull { it.streamReady }
        val normalized = input.copy(
            id = id, type = type, title = title,
            originalTitle = obj.optString(originalKey).ifBlank { platform?.originalTitle ?: input.originalTitle },
            overview = obj.optString("overview").ifBlank { english.optString("overview").ifBlank { platform?.overview ?: input.overview } },
            posterPath = cleanImage(obj.optString("poster_path")) ?: platform?.posterUrl?.takeIf(String::isNotBlank) ?: input.posterPath,
            backdropPath = cleanImage(obj.optString("backdrop_path")) ?: platform?.backdropUrl?.takeIf(String::isNotBlank) ?: input.backdropPath,
            vote = obj.optDouble("vote_average", platform?.rating ?: input.vote).takeIf { it.isFinite() } ?: 0.0,
            date = obj.optString(if (type == MediaType.MOVIE) "release_date" else "first_air_date").ifBlank { platform?.year?.takeIf { it > 0 }?.toString() ?: input.date },
            backendId = platform?.id ?: resolved?.backendId ?: input.backendId,
            mediaVersionId = preferred?.id,
            streamReady = preferred != null,
            quality = preferred?.quality.orEmpty()
        )
        val credits = obj.optJSONObject("credits") ?: english.optJSONObject("credits") ?: JSONObject()
        val cast = parsePeople(credits.optJSONArray("cast"), "character").take(24)
        val crewArray = credits.optJSONArray("crew") ?: JSONArray()
        val directors = buildList {
            for (i in 0 until crewArray.length()) {
                val c = crewArray.optJSONObject(i) ?: continue
                if (c.optString("job") != "Director") continue
                add(person(c, "job"))
            }
            if (type == MediaType.TV) addAll(parsePeople(obj.optJSONArray("created_by"), "job"))
        }.filter { it.id > 0 }.distinctBy { it.id }.take(12)
        val seasons = buildList {
            val array = obj.optJSONArray("seasons") ?: JSONArray()
            for (i in 0 until array.length()) {
                val s = array.optJSONObject(i) ?: continue
                val number = s.optInt("season_number", -1)
                if (number < 0) continue
                add(SeasonInfo(number, s.optString("name").ifBlank { "فصل $number" }, s.optInt("episode_count"), cleanImage(s.optString("poster_path")), s.optString("air_date")))
            }
            if (isEmpty()) platform?.seasons?.forEach { add(SeasonInfo(it.number, it.name, it.episodes.size, it.posterUrl, "")) }
        }
        var runtime = if (type == MediaType.MOVIE) obj.optInt("runtime") else obj.optJSONArray("episode_run_time")?.optInt(0) ?: 0
        if (runtime <= 0 && type == MediaType.TV) runtime = obj.optJSONObject("last_episode_to_air")?.optInt("runtime") ?: 0
        if (runtime <= 0) runtime = platform?.seasons?.flatMap { it.episodes }?.firstOrNull { it.runtimeMinutes > 0 }?.runtimeMinutes ?: 0
        val videos = trailer(obj.optJSONObject("videos")?.optJSONArray("results"))
            ?: trailer(english.optJSONObject("videos")?.optJSONArray("results"))
        val recommendations = parseList(english.optJSONObject("recommendations")?.optJSONArray("results"), type)
            .ifEmpty { parseList(obj.optJSONObject("recommendations")?.optJSONArray("results"), type) }
        val collectionId = obj.optJSONObject("belongs_to_collection")?.optInt("id") ?: 0
        val franchise = if (collectionId > 0) cinemaOptional {
            val collection = metadata("collection/$collectionId", mapOf("language" to "en-US"))
            FranchiseInfo(collectionId, collection.optString("name"), cleanImage(collection.optString("poster_path")), cleanImage(collection.optString("backdrop_path")),
                parseList(collection.optJSONArray("parts"), MediaType.MOVIE).sortedBy { it.date.ifBlank { "9999" } })
        } else null
        val detail = MediaDetail(normalized, obj.optString("tagline"), names(obj.optJSONArray("genres")), runtime,
            obj.optString("status"), cast, videos, recommendations, seasons, directors, franchise)
        CinemaTitleData(detail, platform, origins, names(obj.optJSONArray("spoken_languages")), language,
            english.optString(titleKey), fa != null || en != null)
    }

    suspend fun home(): HomeBundle = supervisorScope {
        val ready = async { cinemaOptional { backend.catalogHome() }.orEmpty() }
        val trending = async { cinemaOptional { parseList(metadata("trending/all/week", mapOf("language" to "en-US")), null) }.orEmpty() }
        val iranMovies = async { cinemaOptional { catalog(CinemaQuery()) }?.items.orEmpty() }
        val iranSeries = async { cinemaOptional { catalog(CinemaQuery(type = MediaType.TV)) }?.items.orEmpty() }
        val korean = async { cinemaOptional { catalog(CinemaQuery(CinemaRegion.KOREA, MediaType.TV)) }?.items.orEmpty() }
        val india = async { cinemaOptional { catalog(CinemaQuery(CinemaRegion.INDIA)) }?.items.orEmpty() }
        val worldMovies = async { cinemaOptional { catalog(CinemaQuery(CinemaRegion.WORLD)) }?.items.orEmpty() }
        val worldSeries = async { cinemaOptional { catalog(CinemaQuery(CinemaRegion.WORLD, MediaType.TV)) }?.items.orEmpty() }
        val playable = ready.await()
        val bundle = HomeBundle((playable + trending.await()).distinctBy { it.type to it.id }.take(30), worldMovies.await(), worldSeries.await(),
            (iranMovies.await() + iranSeries.await()).distinctBy { it.type to it.id }, korean.await(), india.await())
        if (bundle.trending.isEmpty() && bundle.iranian.isEmpty()) error("دریافت کاتالوگ ناموفق بود. دوباره تلاش کن.")
        bundle
    }

    private fun parseList(obj: JSONObject, fallback: MediaType?): List<MediaItem> = parseList(obj.optJSONArray("results"), fallback)
    private fun parseList(array: JSONArray?, fallback: MediaType?): List<MediaItem> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optBoolean("adult") || o.optString("media_type") == "person") continue
            val type = when (o.optString("media_type")) { "tv" -> MediaType.TV; "movie" -> MediaType.MOVIE; else -> fallback ?: continue }
            val id = o.optInt("id")
            if (id <= 0) continue
            val key = if (type == MediaType.MOVIE) "title" else "name"
            val original = o.optString(if (type == MediaType.MOVIE) "original_title" else "original_name")
            add(MediaItem(id, type, o.optString(key).ifBlank { original }, original, o.optString("overview"),
                cleanImage(o.optString("poster_path")), cleanImage(o.optString("backdrop_path")),
                o.optDouble("vote_average").takeIf { it.isFinite() } ?: 0.0,
                o.optString(if (type == MediaType.MOVIE) "release_date" else "first_air_date"), o.optDouble("popularity", 0.0)))
        }
    }.filter { it.title.isNotBlank() }.distinctBy { it.type to it.id }

    private fun names(array: JSONArray?): List<String> = buildList {
        if (array != null) for (i in 0 until array.length()) array.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
    }
    private fun parsePeople(array: JSONArray?, role: String): List<CastMember> = buildList {
        if (array != null) for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(person(it, role)) }
    }.filter { it.id > 0 }
    private fun person(o: JSONObject, role: String) = CastMember(o.optInt("id"), o.optString("name"), o.optString(role), cleanImage(o.optString("profile_path")))
    private fun trailer(array: JSONArray?): String? {
        if (array == null) return null
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("site") == "YouTube" && o.optString("type") == "Trailer") return o.optString("key").takeIf(String::isNotBlank)
        }
        return null
    }
    private fun cleanImage(value: String): String? = value.takeIf { it.isNotBlank() && it != "null" }
    private data class CacheEntry(val at: Long, val json: String)
    companion object { private val cache = LinkedHashMap<String, CacheEntry>(64, .75f, true) }
}
