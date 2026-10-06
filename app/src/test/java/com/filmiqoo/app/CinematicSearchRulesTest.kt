package com.filmiqoo.app

import org.junit.Assert.*
import org.junit.Test

class CinematicSearchRulesTest {
    private val playable = MediaItem(1, MediaType.MOVIE, "قابل پخش", vote = 7.2, date = "2022", backendId = "catalog-1", mediaVersionId = "version-1", streamReady = true)
    private val movie = MediaItem(2, MediaType.MOVIE, "فیلم", vote = 6.5, date = "2025")
    private val series = MediaItem(3, MediaType.TV, "سریال", vote = 9.0, date = "2024")

    @Test fun relevanceKeepsServiceOrderAndDoesNotRepeatATitle() {
        assertEquals(listOf(series, playable, movie), cinematicSearchTitles(listOf(series, playable, movie, series.copy(title = "عنوان تکراری"))))
    }

    @Test fun playableFilterRequiresARealServerVersionAndCatalogIdentity() {
        val candidates = listOf(playable, movie.copy(streamReady = true), playable.copy(id = 4, mediaVersionId = null), playable.copy(id = 5, backendId = null))
        assertEquals(listOf(playable), cinematicSearchTitles(candidates, playableOnly = true))
        assertFalse(cinematicSearchPlayable(playable.copy(mediaVersionId = " ")))
        assertFalse(cinematicSearchPlayable(playable.copy(streamReady = false)))
    }

    @Test fun typeAndSortComposeWithoutRemovingCatalogOnlyTitles() {
        val catalogOnly = playable.copy(id = 0, backendId = "catalog-only", date = "2026", vote = 7.2)
        val input = listOf(series, playable, movie, catalogOnly)
        assertEquals(listOf(catalogOnly, movie, playable), cinematicSearchTitles(input, type = 1, order = CinematicSearchOrder.NEWEST))
        assertEquals(listOf(series), cinematicSearchTitles(input, type = 2))
        // Equal scores preserve relevance, and a missing TMDB ID does not collapse real catalog entries.
        assertEquals(listOf(series, playable, catalogOnly, movie), cinematicSearchTitles(input, order = CinematicSearchOrder.RATING))
    }

    @Test fun unknownYearsAndInvalidRatingsStayAtTheEnd() {
        val unknown = movie.copy(id = 4, date = "", vote = Double.NaN)
        assertEquals(listOf(movie, unknown), cinematicSearchTitles(listOf(unknown, movie), order = CinematicSearchOrder.NEWEST))
        assertEquals(listOf(movie, unknown), cinematicSearchTitles(listOf(unknown, movie), order = CinematicSearchOrder.RATING))
    }

    @Test fun artworkIsOnlyReturnedServiceArtworkAndIsBoundedAndDeduplicated() {
        val titles = (1..20).map { movie.copy(id = it, posterPath = "/poster-$it.jpg") }
        val result = cinematicSearchArtwork(listOf(movie, movie.copy(posterPath = "null")) + titles + titles)
        assertEquals(12, result.size)
        assertEquals(12, result.distinct().size)
        assertEquals("https://image.tmdb.org/t/p/w500/poster-1.jpg", result.first())
        assertEquals(listOf("https://catalog.example/real-poster.jpg"), cinematicSearchArtwork(listOf(movie.copy(posterPath = "https://catalog.example/real-poster.jpg"))))
    }
}
