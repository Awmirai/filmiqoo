package com.filmiqoo.app

/** Ordering applies to the returned titles; it does not claim to search an entire catalog. */
internal enum class CinematicSearchOrder(val label: String) {
    RELEVANCE("مرتبط‌ترین"),
    RATING("بالاترین امتیاز"),
    NEWEST("جدیدترین سال")
}

internal fun cinematicSearchTitles(
    titles: List<MediaItem>,
    type: Int = 0,
    playableOnly: Boolean = false,
    order: CinematicSearchOrder = CinematicSearchOrder.RELEVANCE
): List<MediaItem> {
    val filtered = titles.distinctBy(::cinemaMediaKey).filter { media ->
        (type == 0 || media.type == if (type == 1) MediaType.MOVIE else MediaType.TV) &&
            (!playableOnly || cinematicSearchPlayable(media))
    }
    return when (order) {
        CinematicSearchOrder.RELEVANCE -> filtered
        // Stable sorting retains the server's relevance order when two values are equal.
        CinematicSearchOrder.RATING -> filtered.sortedByDescending { it.vote.takeIf(Double::isFinite) ?: 0.0 }
        CinematicSearchOrder.NEWEST -> filtered.sortedByDescending { it.year.toIntOrNull() ?: 0 }
    }
}

/** An availability flag alone is insufficient to open a real playback version. */
internal fun cinematicSearchPlayable(media: MediaItem): Boolean =
    media.streamReady && !media.mediaVersionId.isNullOrBlank() && !media.backendId.isNullOrBlank()

/** The wall uses only distinct artwork returned by the catalog/metadata service. */
internal fun cinematicSearchArtwork(titles: List<MediaItem>): List<String> = titles
    .mapNotNull { cinemaImage(it.posterPath) }
    .distinct()
    .take(12)

internal data class CinematicSearchFacets(
    val firstYear: Int? = null, val lastYear: Int? = null, val minimumRating: Double? = null,
    val genreId: Int? = null, val originalLanguage: String? = null,
    val persianDubbedOnly: Boolean = false, val persianSubtitleOnly: Boolean = false
) {
    val active: Boolean get() = firstYear != null || lastYear != null || minimumRating != null ||
        genreId != null || !originalLanguage.isNullOrBlank() || persianDubbedOnly || persianSubtitleOnly
}

internal fun cinematicSearchDiscoveryTitles(
    titles: List<DiscoveryTitle>, type: Int = 0, playableOnly: Boolean = false,
    order: CinematicSearchOrder = CinematicSearchOrder.RELEVANCE,
    facets: CinematicSearchFacets = CinematicSearchFacets()
): List<DiscoveryTitle> {
    if (type == 3) return emptyList()
    val matching = titles.distinctBy { cinemaMediaKey(it.media) }.filter { title ->
        val year = title.media.year.toIntOrNull()
        val rating = title.media.vote.takeIf { it.isFinite() && it > 0 }
        (type == 0 || title.media.type == if (type == 1) MediaType.MOVIE else MediaType.TV) &&
            (!playableOnly || cinematicSearchPlayable(title.media)) &&
            (facets.firstYear == null || year != null && year >= facets.firstYear) &&
            (facets.lastYear == null || year != null && year <= facets.lastYear) &&
            (facets.minimumRating == null || rating != null && rating >= facets.minimumRating) &&
            (facets.genreId == null || facets.genreId in title.genreIds) &&
            (facets.originalLanguage.isNullOrBlank() || title.originalLanguage == facets.originalLanguage) &&
            (!facets.persianDubbedOnly || title.isPersianDubbed) &&
            (!facets.persianSubtitleOnly || title.hasPersianSubtitle)
    }
    val byKey = matching.associateBy { cinemaMediaKey(it.media) }
    return cinematicSearchTitles(matching.map { it.media }, order = order).mapNotNull { byKey[cinemaMediaKey(it)] }
}
