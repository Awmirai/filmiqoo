package com.filmiqoo.app
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class DiscoveryRulesTest {
 private val today=LocalDate.of(2026,10,7)
 @Test fun countryLanguageGenreRatingAndRuntimeAreActualUpstreamFilters(){
  val q=discoveryQuery(MediaType.MOVIE,DiscoverySection.POPULAR,DiscoveryFilters(country="in",language="ta",genreId=28,minRating=7.5,runtimeMax=150,yearFrom=2020,yearTo=2025),1,today)
  assertEquals("discover/movie",q.path);assertEquals("IN",q.parameters["with_origin_country"]);assertEquals("ta",q.parameters["with_original_language"]);assertEquals("en-US",q.parameters["language"]);assertEquals("28",q.parameters["with_genres"]);assertEquals("7.5",q.parameters["vote_average.gte"]);assertEquals("150",q.parameters["with_runtime.lte"]);assertEquals("2025-12-31",q.parameters["primary_release_date.lte"])
 }
 @Test fun iranUsesPersianButNeverInfersNationalityFromLanguage(){val q=discoveryQuery(MediaType.TV,DiscoverySection.POPULAR,DiscoveryFilters(country="IR"),2,today);assertEquals("fa-IR",q.parameters["language"]);assertEquals("IR",q.parameters["with_origin_country"]);assertFalse(q.parameters.containsKey("with_original_language"))}
 @Test fun completedAndMiniseriesUseActualTvStatusAndType(){
  assertEquals("3|4",discoveryQuery(MediaType.TV,DiscoverySection.COMPLETED,DiscoveryFilters(),1,today).parameters["with_status"])
  val q=discoveryQuery(MediaType.TV,DiscoverySection.MINISERIES,DiscoveryFilters(status="Ended"),1,today);assertEquals("2",q.parameters["with_type"]);assertEquals("3",q.parameters["with_status"])
  assertTrue(discoveryQuery(MediaType.TV,DiscoverySection.COMPLETED,DiscoveryFilters(status="Returning Series"),1,today).empty)
 }
 @Test fun topTitlesUseTmdbVotesAndExcludeFutureDates(){val q=discoveryQuery(MediaType.TV,DiscoverySection.TOP_RATED,DiscoveryFilters(),1,today);assertEquals("vote_average.desc",q.parameters["sort_by"]);assertEquals("200",q.parameters["vote_count.gte"]);assertEquals("2026-10-07",q.parameters["first_air_date.lte"]);assertTrue(discoveryQuery(MediaType.MOVIE,DiscoverySection.NEW,DiscoveryFilters(yearFrom=2028,yearTo=2029),1,today).empty)}
 @Test fun airingUsesActualWindowAndTimezone(){val q=discoveryQuery(MediaType.TV,DiscoverySection.AIRING,DiscoveryFilters(),1,today);assertEquals("0",q.parameters["with_status"]);assertEquals("2026-09-30",q.parameters["air_date.gte"]);assertEquals("2026-10-14",q.parameters["air_date.lte"]);assertEquals("Asia/Tehran",q.parameters["timezone"])}
 @Test fun unsupportedFiltersAreNeverSilentlyDropped(){listOf<()->Unit>({DiscoveryFilters(minRating=Double.NaN)},{DiscoveryFilters(country="Korea")},{DiscoveryFilters(yearFrom=2026,yearTo=2020)},{discoveryQuery(MediaType.MOVIE,DiscoverySection.AIRING,DiscoveryFilters(),1,today)},{discoveryQuery(MediaType.TV,DiscoverySection.TRENDING,DiscoveryFilters(country="KR"),1,today)},{discoveryQuery(MediaType.TV,DiscoverySection.POPULAR,DiscoveryFilters(persianDubbedOnly=true),1,today)}).forEach{operation->try{operation();fail("unsupported filter accepted")}catch(expected:IllegalArgumentException){}}}
}
