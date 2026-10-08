package com.filmiqoo.app
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import okhttp3.mockwebserver.MockResponse
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
class PersonalCinemaHubTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private val fixture=ProductAudit090Fixture();private var started=false;private var original:Triple<String,String?,String?>?=null
 private var librarySnapshot:Map<String,*>?=null
 private val statsJson="""{"schemaVersion":1,"totalWatchMs":7500000,"moviesWatched":2,"seriesWatched":0,"seriesStarted":1,"episodesWatched":3,"completedTitles":2,"currentlyWatching":1,"historyTitles":4,"watchlistCount":0,"favoriteCount":0,"tasteSampleSize":4,"genres":[],"countries":[],"legacyHistoryWithoutTime":true}"""
 private fun backend():BackendRepository{
  librarySnapshot=compose.activity.getSharedPreferences("filmiqoo_cinema_library_v1",Context.MODE_PRIVATE).all
  fixture.start();started=true;return BackendRepository(compose.activity).also{original=Triple(it.session.baseUrl,it.session.accessToken,it.session.refreshToken);it.session.baseUrl=fixture.base();it.session.accessToken="qa."+android.util.Base64.encodeToString("{\"sub\":\"qa-profile-test\"}".toByteArray(),android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)+".fixture";it.session.refreshToken="qa-refresh"}
 }
 @After fun cleanup(){
  compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))};original?.let{SessionStore(compose.activity).apply{baseUrl=it.first;accessToken=it.second;refreshToken=it.third}}
  librarySnapshot?.let{snapshot->val edit=compose.activity.getSharedPreferences("filmiqoo_cinema_library_v1",Context.MODE_PRIVATE).edit().clear();snapshot.forEach{(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)}};edit.commit()};if(started)fixture.close()
 }
 private fun ready(tag:String){compose.waitUntil(12_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
 @Test fun actualRecordedTimeAndInsufficientTasteStayDistinct(){
  val backend=backend();fixture.intercept={r->if(r.requestUrl?.encodedPath=="/v1/library/viewing-stats")MockResponse().setBody(statsJson)else null}
  compose.setContent{FilmiqooTheme{Audit090Profile(backend,TmdbRepository(compose.activity))}};ready("profile-watch-time");compose.onNodeWithTag("profile-watch-time").assertTextEquals("۲ ساعت و ۵ دقیقه")
  compose.onNodeWithTag("profile-scroll").performScrollToNode(hasTestTag("profile-taste-insufficient"));compose.onNodeWithTag("profile-taste-insufficient").assertExists()
  compose.onNodeWithTag("profile-scroll").performScrollToNode(hasTestTag("profile-hub-tabs"));compose.onNodeWithTag("profile-hub-tabs").performScrollToNode(hasTestTag("profile-hub-tab-2"));compose.onNodeWithTag("profile-hub-tab-2").performClick()
  compose.onNodeWithTag("profile-scroll").performScrollToNode(hasText("بخشی از تاریخچهٔ قدیمی زمان پخش ثبت‌شده ندارد و به ساعت تماشا اضافه نشده است."));compose.onNodeWithText("بخشی از تاریخچهٔ قدیمی زمان پخش ثبت‌شده ندارد و به ساعت تماشا اضافه نشده است.").assertExists()
 }
 @Test fun unavailableStatsDoNotBecomeFakeZerosAndRetryRecovers(){
  val backend=backend();val calls=AtomicInteger();fixture.intercept={r->if(r.requestUrl?.encodedPath=="/v1/library/viewing-stats")if(calls.incrementAndGet()==1)MockResponse().setResponseCode(503).setBody("{}")else MockResponse().setBody(statsJson)else null}
  compose.setContent{FilmiqooTheme{Audit090Profile(backend,TmdbRepository(compose.activity))}};ready("profile-stats-error");compose.onNodeWithTag("profile-watch-time").assertDoesNotExist();compose.onNodeWithText("دریافت آمار").performScrollTo().performClick();ready("profile-watch-time");compose.onNodeWithTag("profile-watch-time").assertTextEquals("۲ ساعت و ۵ دقیقه");assertEquals(2,calls.get())
 }
 @Test fun zeroHistoryUsesDiscoveryOnboardingWithoutEmptyMetrics(){
  val backend=backend();fixture.intercept={r->when(r.requestUrl?.encodedPath){"/v1/library/viewing-stats"->MockResponse().setBody("""{"schemaVersion":1,"totalWatchMs":0,"moviesWatched":0,"seriesWatched":0,"episodesWatched":0,"historyTitles":0,"genres":[],"countries":[]}""");"/v1/watch/history","/v1/watch/continue"->MockResponse().setBody("{\"items\":[]}");else->null}}
  compose.setContent{FilmiqooTheme{Audit090Profile(backend,TmdbRepository(compose.activity))}};ready("profile-new-viewer");compose.onNodeWithTag("profile-watch-time").assertDoesNotExist();compose.onNodeWithTag("profile-real-metrics").assertDoesNotExist();compose.onNodeWithTag("profile-scroll").performScrollToNode(hasTestTag("profile-movies"));compose.onNodeWithTag("profile-movies").assertIsDisplayed()
 }
 @Test fun recordedTimeWithoutProgressIsVisibleRatherThanZeroActivity(){
  val backend=backend();fixture.intercept={r->when(r.requestUrl?.encodedPath){"/v1/library/viewing-stats"->MockResponse().setBody("""{"schemaVersion":1,"totalWatchMs":60000,"moviesWatched":0,"seriesWatched":0,"episodesWatched":0,"historyTitles":0,"genres":[],"countries":[]}""");"/v1/watch/history","/v1/watch/continue"->MockResponse().setBody("{\"items\":[]}");else->null}}
  compose.setContent{FilmiqooTheme{Audit090Profile(backend,TmdbRepository(compose.activity))}};ready("profile-watch-time");compose.onNodeWithTag("profile-watch-time").assertTextEquals("۱ دقیقه");compose.onNodeWithTag("profile-new-viewer").assertDoesNotExist()
 }
 @Test fun completedMovieExclusionsAreTypedScopedAndDistinguishOldCapability(){
  val backend=backend();val calls=AtomicInteger()
  fixture.intercept={request->if(request.requestUrl?.encodedPath=="/v1/library/viewing-stats"){
   val completion=when(calls.incrementAndGet()){1->"\"completedMovieTmdbIds\":[77,77,null,0,-1,\"null\",78.5,2147483648,99],";2->"\"completedMovieTmdbIds\":[],";else->""}
   MockResponse().setBody("{\"schemaVersion\":1,"+completion+"\"genres\":[],\"countries\":[]}")
  }else null}
  val repository=UserViewingStatsRepository(backend)
  val completed=kotlinx.coroutines.runBlocking{repository.load()};assertEquals(setOf(77,99),completed.completedMovieTmdbIds)
  val empty=kotlinx.coroutines.runBlocking{repository.load()};assertEquals(emptySet<Int>(),empty.completedMovieTmdbIds)
  val old=kotlinx.coroutines.runBlocking{repository.load()};assertNull(old.completedMovieTmdbIds)
  val requests=fixture.requests.filter{it.requestUrl?.encodedPath=="/v1/library/viewing-stats"};assertEquals(3,requests.size);assertTrue(requests.all{it.getHeader("Authorization")?.startsWith("Bearer qa.")==true})
 }
 @Test fun localRatingsRemainUsableWhenCloudListsFail(){
  val backend=backend();fixture.intercept={r->if(r.requestUrl?.encodedPath in listOf("/v1/library/watchlist","/v1/library/favorites"))MockResponse().setResponseCode(503).setBody("{}")else null}
  val personal=CinemaPersonalStore(compose.activity,backend.viewerProfiles.activeId());val media=MediaItem(77,MediaType.MOVIE,"Local rated title");personal.setRating(media,7)
  compose.setContent{FilmiqooTheme{Audit090Profile(backend,TmdbRepository(compose.activity))}};compose.onNodeWithTag("profile-hub-tabs").performScrollToNode(hasTestTag("profile-hub-tab-3"));compose.onNodeWithTag("profile-hub-tab-3").performClick();compose.onNodeWithTag("profile-list-tab-2").performClick();compose.onNodeWithTag("profile-scroll").performScrollToNode(hasTestTag("profile-saved-MOVIE:77"));compose.onNodeWithText("امتیاز شخصی: ۷ از ۱۰ · محلی").assertExists();compose.onNodeWithTag("profile-list-remove-MOVIE:77").performClick();compose.waitUntil(5_000){personal.rating(media)==null};assertFalse(fixture.requests.any{it.requestUrl?.encodedPath?.contains("/ratings")==true})
 }
}
