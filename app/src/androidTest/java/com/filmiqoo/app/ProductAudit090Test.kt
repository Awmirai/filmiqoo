package com.filmiqoo.app
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native AFTER evidence for 0.9; synthetic fixtures are clearly marked. */
class ProductAudit090Test {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private val fixture=ProductAudit090Fixture();private var fixtureStarted=false
 private var original:Triple<String,String?,String?>?=null;private var history:String?=null;private var historyTouched=false
 private var label="uninitialized";private val observations=JSONArray();private var phasePage="setup"
 private var auditFocusManager:androidx.compose.ui.focus.FocusManager?=null
 private fun phase(stage:String){android.util.Log.i("FilmiqooProductAudit","label=$label page=$phasePage phase=$stage uptimeMs=${android.os.SystemClock.uptimeMillis()}")}
 private fun present(tag:String)=compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
 private fun ready(tag:String){compose.waitUntil(12_000){check(!present("audit090-detail-error"));present(tag)};compose.waitForIdle()}
 @After fun cleanup(){
  fixture.slowRelease.countDown();compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))}
  original?.let{SessionStore(compose.activity).apply{baseUrl=it.first;accessToken=it.second;refreshToken=it.third}}
  if(historyTouched)compose.activity.getSharedPreferences("filmiqoo_search_history",android.content.Context.MODE_PRIVATE).edit().apply{history?.let{putString("items",it)}?:remove("items")}.commit()
  if(fixtureStarted)fixture.close()
 }
 @Test fun captureTwelveEssentialPages(){
  val args=InstrumentationRegistry.getArguments();label=(args.getString("auditLabel")?:"default").take(120);check(args.getString("auditStage","after")=="after")
  fixture.start();fixtureStarted=true;val account=BackendRepository(compose.activity);original=Triple(account.session.baseUrl,account.session.accessToken,account.session.refreshToken)
  val prefs=compose.activity.getSharedPreferences("filmiqoo_search_history",android.content.Context.MODE_PRIVATE);history=prefs.getString("items",null);historyTouched=true;prefs.edit().remove("items").commit()
  account.session.baseUrl=fixture.base();account.session.accessToken="qa."+android.util.Base64.encodeToString("{\"sub\":\"qa-audit-090\"}".toByteArray(Charsets.UTF_8),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)+".fixture";account.session.refreshToken="audit-refresh"
  val repository=TmdbRepository(compose.activity);var page by mutableStateOf("movies");var generation by mutableIntStateOf(0)
  val movie=MediaItem((('I'.code*100+'R'.code)*10+1)+500000,MediaType.MOVIE,"داستان شهر 1","داستان شهر 1")
  val series=MediaItem(('K'.code*100+'R'.code)*10+1,MediaType.TV,"KR Series 1","KR Series 1")
  phase("set-content:begin")
  compose.setContent{val focusManager=androidx.compose.ui.platform.LocalFocusManager.current;SideEffect{auditFocusManager=focusManager};FilmiqooTheme{key(page,generation){when(page){
   "movie-detail"->Audit090Detail(movie,account);"series-episodes"->Audit090Detail(series,account)
   else->CinemaAppShell(selected=when{page.startsWith("series")||page.endsWith("-tv")->4;page.startsWith("search")->1;page=="profile"->3;else->2},kids=false,onSelected={}){when(page){
    "movies","movies-slow"->MoviesDiscoveryScreen(repository,account,{},{})
    "series","series-offline"->SeriesDiscoveryScreen(repository,account,{},{},{},{})
    "search-default","search-results"->PremiumSearchScreen(repository,account,{},{},{},{},{},{})
    "profile"->Audit090Profile(account,repository)
    "world-registry"->Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())){DiscoveryDestinationHeader("سینمای جهان","کشورها از رجیستری سرویس",{},null);WorldDiscoveryHub(repository,account,MediaType.MOVIE,{})}
    else->CountryDiscoveryPage(repository,account,country=if(page.startsWith("tr-"))"TR"else"KR",type=if(page.endsWith("-tv"))MediaType.TV else MediaType.MOVIE,onBack={},onMedia={},onSearch={})
   }}
  }}}};phase("set-content:end")
  fun select(value:String){phasePage=value;phase("page-change:begin");compose.runOnIdle{page=value;generation++};compose.waitForIdle();phase("page-change:end")}
  val pages=listOf("movies","series","search-default","search-results","profile","world-registry","tr-movie","tr-tv","kr-movie","kr-tv","movie-detail","series-episodes")
  for(name in pages){select(name);when(name){
   "movies"->ready("movies-discovery-hero");"series"->ready("series-discovery-hero");"search-default"->ready("search-poster-atmosphere")
   "search-results"->{ready("search-input");compose.onNodeWithTag("search-input").performTextInput("cinema");if(!label.endsWith("keyboard"))hideIme();compose.waitUntil(12_000){fixture.requests.any{it.requestUrl?.encodedPath?.endsWith("/v1/search")==true&&it.requestUrl?.queryParameter("q")=="cinema"}};compose.waitUntil(12_000){!present("search-loading")};phase("search-results:scroll-target");val target="poster-"+cinemaMediaKey(movie);assertSearchTarget(target)}
   "profile"->{ready("cinema-profile-hub");compose.waitUntil(12_000){compose.onAllNodesWithText("حساب آزمایشی سینما",substring=true).fetchSemanticsNodes().isNotEmpty()};ready("profile-stats-ready")}
   "world-registry"->{ready("world-country-TR");compose.onNodeWithText("همهٔ کشورها").performScrollTo().performClick();ready("world-country-picker");ready("world-registry-ZA")}
   "movie-detail","series-episodes"->{ready("detail-scroll");if(name=="series-episodes")compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("episode-qa-episode-1"))}
   else->ready(if(name.endsWith("-tv"))"series-discovery-hero"else"movies-discovery-hero")
  }
   if(name.startsWith("tr-")||name.startsWith("kr-")){val c=if(name.startsWith("tr-"))"TR"else"KR";val type=if(name.endsWith("-tv"))"tv"else"movie";assertTrue(fixture.requests.any{it.requestUrl?.queryParameter("path")=="discover/$type"&&it.requestUrl?.queryParameter("with_origin_country")==c})}
   if(name.startsWith("search")){if(label.endsWith("keyboard")){compose.onNodeWithTag("search-input").performClick();compose.waitUntil(5_000){imeState().first&&imeState().second>0}}else hideIme();if(name=="search-results"){assertSearchTarget("poster-"+cinemaMediaKey(movie))}}
   if(name !in listOf("movie-detail","series-episodes")){listOf(0,2,4,1,3).forEach{compose.onNodeWithTag("navigation-$it").assertExists()};val selected=when{name.startsWith("series")||name.endsWith("-tv")->4;name.startsWith("search")->1;name=="profile"->3;else->2};compose.onNodeWithTag("navigation-$selected").assertIsSelected();compose.onNodeWithTag("party-lobby").assertDoesNotExist();compose.onNodeWithTag("cinema-social").assertDoesNotExist()}
   capture(name);if(name.startsWith("search"))hideIme()
  }
  if(label=="393x852-font1.0-gesture"){
   select("profile");ready("profile-stats-ready");compose.onNodeWithTag("profile-hub-tab-2").performClick()
   compose.onNodeWithTag("profile-scroll").performScrollToNode(hasText("سلیقهٔ سینمایی تو"));compose.onNodeWithTag("profile-taste-insufficient").assertDoesNotExist();capture("profile-real-taste")
   compose.onNodeWithTag("profile-scroll").performScrollToNode(hasTestTag("profile-hub-tabs"));compose.onNodeWithTag("profile-hub-tab-1").performScrollTo().performClick();compose.waitUntil(12_000){present("profile-history-fixture-version-movie-IR")};compose.onNodeWithTag("profile-scroll").performScrollToNode(hasTestTag("profile-history-fixture-version-movie-IR"));capture("profile-real-history")
   account.session.baseUrl=fixture.base("slow");select("movies-slow");assertTrue(fixture.slowEntered.await(8,TimeUnit.SECONDS));ready("movies-discovery");compose.onNodeWithTag("movies-discovery-hero").assertDoesNotExist();capture("movies-slow-loading",JSONObject().put("metadataRequestPending",true));fixture.slowRelease.countDown();ready("movies-discovery-hero")
   account.session.baseUrl=fixture.base("offline");select("series-offline");ready("series-discovery");compose.waitUntil(12_000){compose.onAllNodesWithText("تلاش دوباره").fetchSemanticsNodes().isNotEmpty()};capture("series-service-error",JSONObject().put("fixtureHttpStatus",503));fixture.offlineFailure=false;compose.onAllNodesWithText("تلاش دوباره").onFirst().performClick();ready("series-discovery-hero");assertTrue(fixture.requests.count{it.requestUrl?.encodedPath?.contains("/offline/")==true&&it.requestUrl?.queryParameter("path")=="trending/tv/week"}>=2);capture("series-service-recovered",JSONObject().put("retryReachedServer",true))
  }
  assertFalse(fixture.requests.any{it.requestUrl?.encodedPath?.endsWith("/v1/watch-parties")==true})
  PlatformTestStorageRegistry.getInstance().openOutputFile("audit-090-$label-metrics.json").use{it.write(observations.toString(2).toByteArray(Charsets.UTF_8))}
 }
 private fun assertSearchTarget(target:String){
  try{compose.onNodeWithTag("search-results").performScrollToNode(hasTestTag(target));compose.onNodeWithTag(target).assertIsDisplayed()}
  catch(failure:Throwable){runCatching{android.util.Log.e("FilmiqooProductAudit","search grid="+compose.onNodeWithTag("search-results").fetchSemanticsNode().boundsInRoot+" target="+compose.onNodeWithTag(target).fetchSemanticsNode().boundsInRoot+" ime="+imeState());capture("search-results-failure")};throw failure}
 }
 private fun imeState():Pair<Boolean,Int>{var value=false to 0;compose.runOnUiThread{val i=ViewCompat.getRootWindowInsets(compose.activity.window.decorView);val t=WindowInsetsCompat.Type.ime();value=(i?.isVisible(t)==true)to(i?.getInsets(t)?.bottom?:0)};return value}
 private fun hideIme(){
  compose.runOnIdle{auditFocusManager?.clearFocus(force=true)}
  compose.runOnUiThread{val a=compose.activity;WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
  compose.waitUntil(5_000){val ime=imeState();!ime.first&&ime.second==0};compose.waitForIdle()
 }
 private fun completeNativeFrame(){compose.waitForIdle();val latch=CountDownLatch(1);compose.runOnUiThread{val v=compose.activity.window.decorView;check(v.isHardwareAccelerated);v.viewTreeObserver.registerFrameCommitCallback{latch.countDown()};v.invalidate()};assertTrue("Committed frame required",latch.await(4,TimeUnit.SECONDS))}
 private fun capture(name:String,extra:JSONObject=JSONObject()){
  phasePage=name;phase("frame-commit:begin");completeNativeFrame();phase("frame-commit:end");ensureNoSystemErrorDialog()
  val ime=imeState();val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
  try{PlatformTestStorageRegistry.getInstance().openOutputFile("audit-090-$label-$name.png").use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
   val c=compose.activity.resources.configuration;val memory=android.os.Debug.MemoryInfo().also{android.os.Debug.getMemoryInfo(it)};val paths=JSONArray()
   fixture.requests.takeLast(20).forEach{r->val u=r.requestUrl;paths.put(JSONObject().put("path",u?.encodedPath).put("metadataPath",u?.queryParameter("path")).put("country",u?.queryParameter("with_origin_country")?:u?.queryParameter("country")).put("language",u?.queryParameter("with_original_language")?:u?.queryParameter("language")))}
   val observation=JSONObject().put("stage","after").put("version","0.9").put("fixture",true).put("page",name).put("widthDp",c.screenWidthDp).put("heightDp",c.screenHeightDp).put("fontScale",c.fontScale).put("screenshotWidthPx",bitmap.width).put("screenshotHeightPx",bitmap.height).put("totalPssKb",memory.totalPss).put("imeVisible",ime.first).put("imeBottomPx",ime.second).put("recentRequests",paths)
   extra.keys().forEach{observation.put(it,extra.get(it))};observations.put(observation);PlatformTestStorageRegistry.getInstance().openOutputFile("audit-090-$label-$name-observation.json").use{it.write(observation.toString(2).toByteArray(Charsets.UTF_8))};phase("captured")
  }finally{bitmap.recycle()}
 }
}
@Composable private fun Audit090Detail(media:MediaItem,backend:BackendRepository){val context=LocalContext.current;var data by remember(media.key){mutableStateOf<CinemaTitleData?>(null)};var failure by remember(media.key){mutableStateOf<Exception?>(null)}
 LaunchedEffect(media.key,backend){try{data=CinemaDataRepository(context,backend).title(media)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){failure=e}}
 data?.let{CinemaDetailContent(it)}?:Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){if(failure!=null)Text(failure?.message.orEmpty(),Modifier.testTag("audit090-detail-error"))else CircularProgressIndicator(Modifier.testTag("audit090-detail-loading"))}
}
@Composable internal fun Audit090Profile(backend:BackendRepository,repository:TmdbRepository){ConnectedProfileScreen(backend,repository,onMedia={},onPlay={},onCommunity={},onDownloads={},onLibrary={},onSocialSaves={},onHistory={},onCreatorStudio={},onInbox={},onSettings={},onViewerProfiles={},onParentalControls={},onSecurity={},onSafety={},onFollowRequests={},onCloseFriends={},onEditProfile={},onFilmDna={},onReputation={},onSeriesCalendar={},onSocialCollections={},onLoggedOut={},loggedIn=true)}
