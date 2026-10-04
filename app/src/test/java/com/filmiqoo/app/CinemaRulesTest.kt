package com.filmiqoo.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CinemaRulesTest {
    private val today = LocalDate.of(2026, 10, 4)

    @Test fun iranCatalogIsOriginBasedAndNotLimitedToPersianLanguage() {
        val query = CinemaQuery()
        val params = query.parameters(today)
        assertEquals("discover/movie", query.path)
        assertEquals("IR", params["with_origin_country"])
        assertEquals("fa-IR", params["language"])
        assertFalse(params.containsKey("with_original_language"))
        assertEquals("false", params["include_adult"])
        assertEquals("2026-10-04", params["primary_release_date.lte"])
    }
    @Test fun koreanAndIndianCatalogsUseEnglishNames() {
        for (region in listOf(CinemaRegion.KOREA, CinemaRegion.INDIA, CinemaRegion.BOLLYWOOD)) {
            assertEquals("en-US", CinemaQuery(region).parameters(today)["language"])
        }
        assertEquals("KR", CinemaQuery(CinemaRegion.KOREA).parameters(today)["with_origin_country"])
        assertEquals("IN", CinemaQuery(CinemaRegion.INDIA).parameters(today)["with_origin_country"])
    }
    @Test fun bollywoodIsHindiButIndiaIncludesAllLanguages() {
        assertEquals("hi", CinemaQuery(CinemaRegion.BOLLYWOOD).parameters(today)["with_original_language"])
        assertNull(CinemaQuery(CinemaRegion.INDIA).parameters(today)["with_original_language"])
    }
    @Test fun televisionUsesTelevisionDatesAndPagination() {
        val query = CinemaQuery(CinemaRegion.KOREA, MediaType.TV, CinemaEra.RECENT, CinemaSort.NEWEST, 3)
        assertEquals("discover/tv", query.path)
        assertEquals("3", query.parameters(today)["page"])
        assertEquals("2024-10-04", query.parameters(today)["first_air_date.gte"])
        assertEquals("first_air_date.desc", query.parameters(today)["sort_by"])
        assertFalse(query.parameters(today).containsKey("primary_release_date.gte"))
    }
    @Test fun classicAndRatingFiltersAreExplicit() {
        val params = CinemaQuery(era = CinemaEra.CLASSIC, sort = CinemaSort.RATING).parameters(today)
        assertEquals("2000-12-31", params["primary_release_date.lte"])
        assertEquals("vote_average.desc", params["sort_by"])
        assertEquals("10", params["vote_count.gte"])
    }
    @Test fun worldDoesNotPretendToBeOneCountry() {
        assertFalse(CinemaQuery(CinemaRegion.WORLD).parameters(today).containsKey("with_origin_country"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPageRejected() { CinemaQuery(page = 501) }
    @Test fun languagePolicyCoversKoreanAndIndianLanguages() {
        assertTrue(CinemaTitlePolicy.preferEnglish("ko", emptySet()))
        assertTrue(CinemaTitlePolicy.preferEnglish("ta", emptySet()))
        assertTrue(CinemaTitlePolicy.preferEnglish("en", setOf("IN")))
        assertTrue(CinemaTitlePolicy.preferEnglish("en", setOf("KR")))
        assertFalse(CinemaTitlePolicy.preferEnglish("fa", setOf("IR")))
        assertEquals("English title", CinemaTitlePolicy.choose("Local", "English title", "Original", true))
        assertEquals("Local", CinemaTitlePolicy.choose("Local", "English title", "Original", false))
        assertEquals("Original", CinemaTitlePolicy.choose("", "", "Original", true))
    }
    @Test fun planningRoundsUpAndHandlesCompletedSeries() {
        assertEquals(5, CinemaPlanning.remainingNights(9, 2))
        assertEquals(0, CinemaPlanning.remainingNights(-1, 2))
        assertEquals(96, CinemaPlanning.playbackMinutes(120, 1.25f))
    }
    @Test(expected = IllegalArgumentException::class) fun zeroPaceRejected() { CinemaPlanning.remainingNights(8, 0) }
    @Test(expected = IllegalArgumentException::class) fun invalidSpeedRejected() { CinemaPlanning.playbackMinutes(90, Float.NaN) }
    @Test fun distinctCatalogOnlyTitlesAreNotCollapsedIntoIdZero() {
        val a = MediaItem(0, MediaType.MOVIE, "A", backendId = "a")
        val b = MediaItem(0, MediaType.MOVIE, "B", backendId = "b")
        assertNotEquals(cinemaMediaKey(a), cinemaMediaKey(b))
    }
}
