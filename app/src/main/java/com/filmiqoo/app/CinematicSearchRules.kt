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
