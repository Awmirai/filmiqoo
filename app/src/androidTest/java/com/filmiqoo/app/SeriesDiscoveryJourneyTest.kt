package com.filmiqoo.app
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SeriesDiscoveryJourneyTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private val fixture=ProductAudit090Fixture();private var started=false;private var original:Triple<String,String?,String?>?=null
 private val opened=CopyOnWriteArrayList<MediaItem>()
 private fun setup(loggedIn:Boolean=false):BackendRepository{fixture.start();started=true;return BackendRepository(compose.activity).also{original=Triple(it.session.baseUrl,it.session.accessToken,it.session.refreshToken);it.session.baseUrl=fixture.base();it.session.accessToken=if(loggedIn)"qa."+android.util.Base64.encodeToString("{\"sub\":\"qa-series-test\"}".toByteArray(),android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)+".fixture"else null;it.session.refreshToken=if(loggedIn)"qa-refresh"else null}}
 @After fun cleanup(){compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))};original?.let{SessionStore(compose.activity).apply{baseUrl=it.first;accessToken=it.second;refreshToken=it.third}};if(started)fixture.close()}
 private fun present(tag:String)=compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
 private fun ready(tag:String){compose.waitUntil(12_000){present(tag)};compose.waitForIdle()}
 private fun awaitRequest(path:String,vararg params:Pair<String,String>){compose.waitUntil(12_000){fixture.requests.any{r->r.requestUrl?.encodedPath==path&&params.all{(k,v)->r.requestUrl?.queryParameter(k)==v}}}}
 @Test fun majorCountryDestinationsUseActualOriginTypeAndTitle(){
  val backend=setup();val repo=TmdbRepository(compose.activity);var country by mutableStateOf("TR");var type by mutableStateOf(MediaType.TV)
  compose.setContent{FilmiqooTheme{key(country,type){CountryDiscoveryPage(repo,backend,country,type,{}, {opened+=it},{})}}}
  for(c in listOf("TR","KR","IN","IR","US","JP"))for(kind in listOf(MediaType.TV,MediaType.MOVIE)){
   compose.runOnIdle{country=c;type=kind};val ns=if(kind==MediaType.TV)"tv"else"movie"
   awaitRequest("/v1/tmdb","path" to "discover/$ns","with_origin_country" to c)
   val tag=if(kind==MediaType.TV)"series-hero-details"else"movies-hero-details";ready(tag);compose.onNodeWithTag(tag).performScrollTo().performClick()
   compose.waitUntil(5_000){opened.isNotEmpty()&&opened.last().type==kind&&c in opened.last().originCountries};assertTrue(c in opened.last().originCountries)
  };assertFalse(fixture.requests.any{it.requestUrl?.encodedPath=="/v1/watch-parties"})
 }
 @Test fun indianLanguageGenreAndCountrySurviveRestoration(){
  val backend=setup();val repo=TmdbRepository(compose.activity);val restoration=StateRestorationTester(compose)
  restoration.setContent{FilmiqooTheme{CountryDiscoveryPage(repo,backend,"IN",MediaType.TV,{}, {opened+=it},{})}}
  compose.onNodeWithTag("country-discovery-tv-IN").performScrollToNode(hasTestTag("country-language-ta"));compose.onNodeWithTag("country-language-ta").performClick()
  awaitRequest("/v1/tmdb","path" to "discover/tv","with_origin_country" to "IN","with_original_language" to "ta")
  restoration.emulateSavedInstanceStateRestore();compose.onNodeWithTag("country-discovery-tv-IN").performScrollToNode(hasTestTag("country-language-ta"));compose.onNodeWithTag("country-language-ta").assertIsSelected()
  compose.onNodeWithTag("country-discovery-tv-IN").performScrollToNode(hasTestTag("discovery-genre-18"));compose.onNodeWithTag("discovery-genre-18").performClick();ready("discovery-full-list")
  awaitRequest("/v1/tmdb","path" to "discover/tv","with_origin_country" to "IN","with_original_language" to "ta","with_genres" to "18")
 }
 @Test fun confirmedDubSubtitleAndCountryFiltersCompose(){
  val backend=setup();val repo=TmdbRepository(compose.activity)
  compose.setContent{FilmiqooTheme{CountryDiscoveryPage(repo,backend,"KR",MediaType.TV,{}, {opened+=it},{})}}
  compose.onNodeWithTag("country-discovery-tv-KR").performScrollToNode(hasTestTag("country-filters"));compose.onNodeWithTag("country-filters").performClick()
  compose.onNodeWithTag("discovery-dub-filter").performScrollTo().performClick();compose.onNodeWithTag("discovery-subtitle-filter").performScrollTo().performClick();compose.onNodeWithTag("discovery-apply-filters").performScrollTo().performClick()
  awaitRequest("/v1/catalog/discovery","type" to "series","country" to "KR","persianDubbedOnly" to "true","persianSubtitleOnly" to "true")
  compose.onNodeWithTag("country-discovery-tv-KR").performScrollToIndex(0);ready("series-hero-details");compose.onNodeWithTag("series-hero-details").performScrollTo().performClick();compose.waitUntil(5_000){opened.isNotEmpty()}
  val actual=opened.last();assertTrue(actual.hasPersianDub);assertTrue(actual.hasPersianSubtitle);assertEquals(3,actual.dubbedEpisodeCount);assertEquals(4,actual.availableEpisodeCount);assertEquals(MediaType.TV,actual.type)
 }
 @Test fun endedFullListPaginatesWithoutLosingCountryOrType(){
  val backend=setup();val repo=TmdbRepository(compose.activity);compose.setContent{FilmiqooTheme{CountryDiscoveryPage(repo,backend,"TR",MediaType.TV,{}, {opened+=it},{})}}
  compose.onNodeWithTag("country-discovery-tv-TR").performScrollToNode(hasTestTag("country-completed"));compose.onNodeWithTag("country-completed").performClick();ready("discovery-full-list")
  awaitRequest("/v1/tmdb","path" to "discover/tv","with_origin_country" to "TR","with_status" to "3|4","page" to "1")
  compose.onNodeWithTag("discovery-list-grid").performScrollToNode(hasTestTag("discovery-load-more"));compose.onNodeWithTag("discovery-load-more").performClick();awaitRequest("/v1/tmdb","path" to "discover/tv","with_origin_country" to "TR","with_status" to "3|4","page" to "2")
  val id=('T'.code*100+'R'.code)*10+5;ready("poster-TV:$id");compose.onNodeWithTag("discovery-list-grid").performScrollToNode(hasTestTag("poster-TV:$id"));compose.onNodeWithTag("poster-TV:$id").performClick();compose.waitUntil(5_000){opened.isNotEmpty()};assertEquals("Ended",opened.last().seriesStatus);assertTrue("TR" in opened.last().originCountries)
 }
 @Test fun failedCountryMetadataRetriesWithoutObsoleteNavigation(){
  val backend=setup();val failed=AtomicBoolean(false);fixture.intercept={r->if(r.requestUrl?.queryParameter("path")=="discover/tv"&&failed.compareAndSet(false,true))MockResponse().setResponseCode(503).setBody("{}")else null}
  compose.setContent{FilmiqooTheme{CountryDiscoveryPage(TmdbRepository(compose.activity),backend,"TR",MediaType.TV,{}, {opened+=it},{})}}
  compose.waitUntil(12_000){compose.onAllNodesWithText("تلاش دوباره").fetchSemanticsNodes().isNotEmpty()};compose.onAllNodesWithText("تلاش دوباره").onFirst().performScrollTo().performClick();ready("series-hero-details")
  compose.onNodeWithTag("party-lobby").assertDoesNotExist();compose.onNodeWithTag("cinema-social").assertDoesNotExist()
 }
 @Test fun calendarUsesActualKnownDatesAndExactTitle(){
  val backend=setup(true);fixture.intercept={r->if(r.requestUrl?.encodedPath=="/v1/series/calendar")MockResponse().setBody("""{"items":[{"media":{"id":"late-series","kind":"series","title":"late"},"episode":{"id":"late","seasonNumber":1,"episodeNumber":2,"airDate":"2026-10-11","streamReady":false}},{"media":{"id":"early-series","kind":"series","title":"early"},"episode":{"id":"early","seasonNumber":1,"episodeNumber":1,"airDate":"2026-10-08","streamReady":false}},{"media":{"id":"unknown-series","kind":"series","title":"unknown"},"episode":{"id":"unknown","airDate":""}}]}""")else null}
  compose.setContent{FilmiqooTheme{SeriesDiscoveryScreen(TmdbRepository(compose.activity),backend,{opened+=it},{},{},{})}}
  compose.onNodeWithTag("series-discovery").performScrollToNode(hasTestTag("series-following-calendar"));awaitRequest("/v1/series/calendar","days" to "60");ready("series-calendar-early");ready("series-calendar-late")
  compose.onNodeWithTag("series-discovery").performScrollToNode(hasTestTag("series-calendar-late"));assertTrue(compose.onNodeWithTag("series-calendar-early").fetchSemanticsNode().positionInRoot.y<compose.onNodeWithTag("series-calendar-late").fetchSemanticsNode().positionInRoot.y);compose.onNodeWithTag("series-calendar-unknown").assertDoesNotExist()
  compose.onNodeWithTag("series-discovery").performScrollToNode(hasTestTag("series-calendar-early"));compose.onNodeWithTag("series-calendar-early").assertIsDisplayed().performClick();compose.waitUntil(5_000){opened.isNotEmpty()};assertEquals("early-series",opened.single().backendId);assertFalse(opened.single().streamReady)
 }
 @Test fun guestCalendarRequiresAuthAndDoesNotInventActivity(){
  val backend=setup();val auth=AtomicInteger();compose.setContent{FilmiqooTheme{SeriesDiscoveryScreen(TmdbRepository(compose.activity),backend,{},{},{},{auth.incrementAndGet()})}}
  compose.onNodeWithTag("series-discovery").performScrollToNode(hasTestTag("series-calendar-login"));compose.onNodeWithTag("series-calendar-login").performClick();assertEquals(1,auth.get());assertFalse(fixture.requests.any{it.requestUrl?.encodedPath in listOf("/v1/series/calendar","/v1/watch/continue")})
 }
 @Test fun worldRegistryLoadsOnDemandAndSelectsNonShortcutCountry(){
  val backend=setup();val selected=CopyOnWriteArrayList<String>();compose.setContent{FilmiqooTheme{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){WorldDiscoveryHub(TmdbRepository(compose.activity),backend,MediaType.TV,{selected+=it})}}}
  ready("world-country-TR");assertFalse(fixture.requests.any{it.requestUrl?.queryParameter("path")=="configuration/countries"});compose.onNodeWithText("همهٔ کشورها").performScrollTo().performClick();ready("world-registry-ZA");compose.onNodeWithTag("world-country-query").performTextInput("ZA");compose.onNodeWithTag("world-registry-TR").assertDoesNotExist();compose.onNodeWithTag("world-registry-ZA").performClick();assertEquals(listOf("ZA"),selected.toList())
 }
 @Test fun episodeSelectionPlaysTheChosenDubFile()=runBlocking{
  val backend=setup();val items=backend.catalogHome();val media=items.first{it.type==MediaType.TV&&"KR" in it.originCountries}
  val data=CinemaDataRepository(compose.activity,backend).title(media);val versions=CopyOnWriteArrayList<String>()
  compose.setContent{FilmiqooTheme{CinemaDetailContent(data,actions=CinemaDetailActions(play={versions+=it}))}}
  compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("episode-versions-qa-episode-1"));compose.onNodeWithTag("episode-versions-qa-episode-1").performClick();compose.onNodeWithTag("episode-version-play-qa-episode-dub-1").performScrollTo().performClick();assertEquals(listOf("qa-episode-dub-1"),versions.toList())
 }
}
