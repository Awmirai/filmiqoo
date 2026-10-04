package com.filmiqoo.app

import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil

/** Country is a catalog origin filter, never a claim about streaming rights. */
enum class CinemaRegion(val label: String, val country: String?, val locale: String, val caption: String) {
    IRAN("ایران", "IR", "fa-IR", "از خاطره‌های سینما تا سریال‌های امروز"),
    KOREA("کره", "KR", "en-US", "K-drama و سینمای کره، با نام انگلیسی"),
    INDIA("هند", "IN", "en-US", "سینمای هند؛ فراتر از یک زبان"),
    BOLLYWOOD("بالیوود", "IN", "en-US", "سینمای هندی‌زبان، با نام انگلیسی"),
    WORLD("جهان", null, "en-US", "هالیوود و انتخاب‌هایی از سراسر جهان")
}

enum class CinemaEra(val label: String) { ALL("همهٔ سال‌ها"), RECENT("تازه‌ها"), CLASSIC("کلاسیک‌ها") }
enum class CinemaSort(val label: String) { POPULAR("محبوب‌ترین"), NEWEST("جدیدترین"), RATING("بالاترین امتیاز") }

data class CinemaQuery(
    val region: CinemaRegion = CinemaRegion.IRAN,
    val type: MediaType = MediaType.MOVIE,
    val era: CinemaEra = CinemaEra.ALL,
    val sort: CinemaSort = CinemaSort.POPULAR,
    val page: Int = 1,
    val genreId: Int? = null
) {
    init { require(page in 1..500) { "Catalog page must be between 1 and 500" } }
    val path: String get() = if (type == MediaType.MOVIE) "discover/movie" else "discover/tv"

    fun parameters(today: LocalDate = LocalDate.now()): Map<String, String> = buildMap {
        put("language", region.locale)
        put("include_adult", "false")
        put("page", page.toString())
        region.country?.let { put("with_origin_country", it) }
        if (region == CinemaRegion.BOLLYWOOD) put("with_original_language", "hi")
        genreId?.takeIf { it > 0 }?.let { put("with_genres", it.toString()) }
        val dateKey = if (type == MediaType.MOVIE) "primary_release_date" else "first_air_date"
        put("$dateKey.lte", if (era == CinemaEra.CLASSIC) "2000-12-31" else today.toString())
        if (era == CinemaEra.RECENT) put("$dateKey.gte", today.minusYears(2).toString())
        put("sort_by", when (sort) {
            CinemaSort.POPULAR -> "popularity.desc"
            CinemaSort.NEWEST -> "$dateKey.desc"
            CinemaSort.RATING -> "vote_average.desc"
        })
        if (sort == CinemaSort.RATING) put("vote_count.gte", if (region == CinemaRegion.IRAN) "10" else "50")
    }
}

object CinemaTitlePolicy {
    private val indianLanguages = setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "or", "as", "ur")
    fun preferEnglish(language: String, origins: Set<String>): Boolean =
        language.lowercase(Locale.ROOT) == "ko" || language.lowercase(Locale.ROOT) in indianLanguages ||
            origins.any { it.uppercase(Locale.ROOT) in setOf("KR", "IN") }

    fun choose(localized: String, english: String, original: String, preferEnglish: Boolean): String =
        (if (preferEnglish) listOf(english, localized, original) else listOf(localized, english, original))
            .firstOrNull { it.isNotBlank() && it != "null" } ?: "بدون عنوان"
}

object CinemaPlanning {
    fun remainingNights(remainingEpisodes: Int, episodesPerNight: Int): Int {
        require(episodesPerNight > 0)
        return ceil(remainingEpisodes.coerceAtLeast(0).toDouble() / episodesPerNight).toInt()
    }
    fun playbackMinutes(runtimeMinutes: Int, speed: Float): Int {
        require(speed.isFinite() && speed > 0)
        return ceil(runtimeMinutes.coerceAtLeast(0) / speed.toDouble()).toInt()
    }
}

internal fun cinemaDuration(minutes: Int): String = when {
    minutes <= 0 -> "مدت اعلام نشده"
    minutes < 60 -> "$minutes دقیقه"
    minutes % 60 == 0 -> "${minutes / 60} ساعت"
    else -> "${minutes / 60} ساعت و ${minutes % 60} دقیقه"
}

internal fun cinemaBytes(bytes: Long): String = when {
    bytes <= 0L -> "حجم نامشخص"
    bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    else -> String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
}

internal fun cinemaEpisodeLabel(season: Int, episode: Int): String =
    String.format(Locale.US, "S%02dE%02d", season, episode)
