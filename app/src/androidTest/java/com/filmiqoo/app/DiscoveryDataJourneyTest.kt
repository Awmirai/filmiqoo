package com.filmiqoo.app
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
class DiscoveryDataJourneyTest {
 private val context=InstrumentationRegistry.getInstrumentation().targetContext
 private val server=MockWebServer();private lateinit var backend:BackendRepository
 private var original:Triple<String,String?,String?>?=null
 @Before fun setup(){server.start();backend=BackendRepository(context);original=Triple(backend.session.baseUrl,backend.session.accessToken,backend.session.refreshToken);backend.session.baseUrl=server.url("/").toString();backend.session.accessToken="test";backend.session.refreshToken="test"}
 @After fun cleanup(){original?.let{backend.session.baseUrl=it.first;backend.session.accessToken=it.second;backend.session.refreshToken=it.third};server.shutdown()}
 @Test fun oldProxyCannotPretendItAppliedSeriesStatusAndRuntime()=runBlocking{
  server.enqueue(MockResponse().setBody("""{"results":[],"page":1,"total_pages":0}"""))
  try{DiscoveryRepository(context,backend).page(MediaType.TV,DiscoverySection.COMPLETED,DiscoveryFilters(runtimeMax=45));fail("silently ignored filter")}catch(expected:DiscoveryCapabilityException){}
  val request=server.takeRequest();assertEquals("3|4",request.requestUrl!!.queryParameter("with_status"));assertEquals("45",request.requestUrl!!.queryParameter("with_runtime.lte"))
 }
 @Test fun missingLocalEndpointReportsUpgradeRatherThanEmpty()=runBlocking{
  server.enqueue(MockResponse().setResponseCode(404).setBody("404 page not found"))
  try{DiscoveryRepository(context,backend).page(MediaType.MOVIE,DiscoverySection.DUBBED);fail("unsupported endpoint presented as empty")}catch(expected:DiscoveryCapabilityException){}
  assertEquals("/v1/catalog/discovery",server.takeRequest().requestUrl!!.encodedPath)
 }
 @Test fun typedSearchAndMergePreserveKnownCatalogFacets()=runBlocking{
  server.enqueue(MockResponse().setBody("""{"page":1,"total_pages":1,"results":[{"id":17,"media_type":"movie","title":"English Korean Title","original_title":"한국 제목","original_language":"ko","genre_ids":[18],"vote_count":300,"vote_average":8.1,"release_date":"2025-02-03"},{"id":18,"media_type":"tv","name":"English Indian Series","original_name":"मूल नाम","original_language":"ta","origin_country":["IN"],"genre_ids":[35]},{"id":19,"media_type":"person","name":"Actual Person","profile_path":null,"known_for":[{"id":17,"media_type":"movie","title":"English Korean Title"}]}]}"""))
  val result=DiscoveryRepository(context,backend).searchMetadata("cinema");assertEquals(2,result.titles.size);assertEquals(MediaType.MOVIE,result.titles[0].media.type);assertEquals("English Korean Title",result.titles[0].media.title);assertEquals("ta",result.titles[1].originalLanguage);assertEquals(listOf("IN"),result.titles[1].originCountries);assertNull(result.people.single().profilePath)
  val local=result.titles[0].media.copy(title="نام محلی",backendId="local-title",mediaVersionId="local-version",streamReady=true,quality="1080p",hasPersianDub=true,originCountries=listOf("KR","US"),runtimeMinutes=125)
  val merged=mergeDiscoveryTitles(listOf(local),result.titles);assertEquals("English Korean Title",merged[0].media.title);assertEquals("local-title",merged[0].media.backendId);assertEquals("local-version",merged[0].media.mediaVersionId);assertTrue(merged[0].media.streamReady);assertTrue(merged[0].isPersianDubbed);assertEquals(listOf("KR","US"),merged[0].originCountries);assertEquals(125,merged[0].runtimeMinutes)
  val request=server.takeRequest();assertEquals("search/multi",request.requestUrl!!.queryParameter("path"));assertEquals("en-US",request.requestUrl!!.queryParameter("language"))
 }
 @Test fun localFiltersComposeAndUnknownFactsRemainUnknown()=runBlocking{
  server.enqueue(MockResponse().setBody("""{"discoveryVersion":1,"page":2,"totalPages":3,"items":[{"id":"local-title","tmdbId":null,"kind":"series","title":"Actual Local Series","originalTitle":null,"posterUrl":null,"rating":null,"year":null,"mediaVersionId":"real-version","streamReady":true,"hasPersianDub":true,"hasPersianSubtitle":true,"dubbedEpisodeCount":1,"availableEpisodeCount":2,"episodeCount":8,"genreIds":[18],"originCountries":["TR","US"],"originalLanguage":"tr","runtimeMinutes":42,"seriesStatus":"Ended"}]}"""))
  val page=DiscoveryRepository(context,backend).page(MediaType.TV,DiscoverySection.DUBBED,DiscoveryFilters(country="TR",language="tr",genreId=18,minRating=7.0,status="3",persianSubtitleOnly=true),2)
  assertTrue(page.hasMore);val item=page.items.single();assertEquals(1,item.dubbedEpisodeCount);assertEquals(2,item.availableEpisodeCount);assertEquals(8,item.episodeCount);assertNull(item.media.posterPath);assertEquals("",item.media.originalTitle);assertEquals(0.0,item.media.vote,0.0);assertEquals("",item.media.date)
  val url=server.takeRequest().requestUrl!!;assertEquals("TR",url.queryParameter("country"));assertEquals("tr",url.queryParameter("language"));assertEquals("18",url.queryParameter("genreId"));assertEquals("true",url.queryParameter("persianDubbedOnly"));assertEquals("true",url.queryParameter("persianSubtitleOnly"));assertEquals("2",url.queryParameter("page"))
 }
 @Test fun episodeVersionsAndLocalRatingsPersistPresentationFacts()=runBlocking{
  server.enqueue(MockResponse().setBody("""{"id":"series-id","tmdbId":77,"kind":"series","title":"Actual Series","year":2025,"hasPersianDub":true,"dubbedEpisodeCount":1,"availableEpisodeCount":2,"episodeCount":8,"genreIds":[18],"originalLanguage":"ko","originCountries":["KR","US"],"seriesStatus":"Ended","versions":[],"seasons":[{"id":"season-id","number":1,"episodes":[{"id":"episode-id","number":1,"name":"Episode One","streamReady":true,"mediaVersionId":"dub-version","isPersianDubbed":true,"hasPersianDub":true,"versions":[{"id":"dub-version","quality":"1080p","streamReady":true,"isPersianDubbed":true,"detectionSource":"caption","detectionConfidence":"HIGH","detectionEvidence":["caption:explicit_persian_audio"],"audioTracks":[],"subtitleTracks":[]},{"id":"original-version","streamReady":true,"isPersianDubbed":false,"hasPersianSubtitle":true}]}]}]}"""))
  val detail=backend.detail("series-id");val ep=detail.seasons.single().episodes.single();assertEquals(2,ep.versions.size);assertTrue(ep.versions[0].isPersianDubbed);assertFalse(ep.versions[1].isPersianDubbed);assertTrue(ep.versions[1].hasPersianSubtitle);assertEquals("HIGH",ep.versions[0].detectionConfidence);assertTrue(ep.versions[0].audioTracks.isEmpty())
  val media=detail.asMediaItem();assertTrue(media.streamReady)
  val store=CinemaPersonalStore(context,"discovery-test-"+System.nanoTime());store.setSaved("watchlist",media,true);store.setRating(media,9)
  val saved=store.saved().single();assertTrue(saved.hasPersianDub);assertEquals(1,saved.dubbedEpisodeCount);assertEquals(8,saved.episodeCount);assertEquals(listOf(18),saved.genreIds);assertEquals(listOf("KR","US"),saved.originCountries);assertEquals(9,store.ratings().single().value)
  store.setSaved("watchlist",media,false);store.setRating(media,null)
 }
 private fun savedLibraryPage(page:Int,more:Boolean,vararg ids:Int):MockResponse {
  val items=JSONArray();ids.forEach{id->items.put(JSONObject().put("id","catalog-"+id).put("tmdbId",id).put("kind",if(id==99)"series"else"movie").put("title","Saved title "+id))}
  return MockResponse().setBody(JSONObject().put("libraryVersion",1).put("page",page).put("hasMore",more).put("items",items).toString())
 }
 @Test fun completeSavedListsFetchEveryPageAndRetainCanonicalIdentity()=runBlocking{
  val repo=LibraryRepository(backend)
  server.enqueue(savedLibraryPage(1,true,77,88));server.enqueue(savedLibraryPage(2,false,77,99))
  val saved=repo.watchlist();assertEquals(listOf(77,88,99),saved.map{it.id});assertEquals(MediaType.TV,saved.last().type);assertEquals("catalog-99",saved.last().backendId);assertFalse(repo.watchlistLimited)
  for(page in 1..2){val r=server.takeRequest();assertEquals("/v1/library/watchlist",r.requestUrl!!.encodedPath);assertEquals(page.toString(),r.requestUrl!!.queryParameter("page"));assertEquals("Bearer test",r.getHeader("Authorization"))}
  server.enqueue(savedLibraryPage(1,true,11));server.enqueue(savedLibraryPage(2,false,12));assertEquals(listOf(11,12),repo.favorites().map{it.id});assertFalse(repo.favoritesLimited)
  for(page in 1..2){val r=server.takeRequest();assertEquals("/v1/library/favorites",r.requestUrl!!.encodedPath);assertEquals(page.toString(),r.requestUrl!!.queryParameter("page"))}
 }
 @Test fun savedListFailureAndLegacyCapabilityNeverReturnPartialAsComplete()=runBlocking{
  val repo=LibraryRepository(backend)
  server.enqueue(savedLibraryPage(1,true,77));server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
  try{repo.watchlist();fail("partial list was returned after page-two failure")}catch(expected:BackendHttpException){assertEquals(503,expected.status)}
  server.enqueue(MockResponse().setBody("""{"items":[{"id":"legacy-77","tmdbId":77,"kind":"movie","title":"Legacy saved title"}]}"""))
  assertEquals(listOf(77),repo.watchlist().map{it.id});assertTrue(repo.watchlistLimited)
  server.enqueue(savedLibraryPage(1,true,77));server.enqueue(savedLibraryPage(1,false,88))
  try{repo.watchlist();fail("wrong page accepted as complete")}catch(expected:IllegalStateException){}
  server.enqueue(MockResponse().setBody("""{"libraryVersion":1,"page":1,"hasMore":false,"items":[{}, {"id":"ok","tmdbId":77,"kind":"movie","title":"Actual saved title"}]}"""))
  try{repo.watchlist();fail("malformed row silently discarded")}catch(expected:IllegalStateException){}
 }
 @Test fun savedListAccountSwitchCancelsBeforeReturningOrFetchingOtherScope()=runBlocking{
  fun token(id:String)="test."+android.util.Base64.encodeToString(JSONObject().put("sub",id).toString().toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)+".fixture"
  backend.session.accessToken=token("first")
  server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{backend.session.accessToken=token("second");return savedLibraryPage(1,true,77)}}
  try{LibraryRepository(backend).watchlist();fail("old account list leaked")}catch(expected:kotlinx.coroutines.CancellationException){}
  assertEquals(1,server.requestCount);assertEquals("1",server.takeRequest().requestUrl!!.queryParameter("page"))
 }
 @Test fun savedListValidCatalogAliasesDeduplicateAfterEachRowIsValidated()=runBlocking{
  val repo=LibraryRepository(backend)
  fun aliasPage(page:Int,more:Boolean,id:String,kind:String)=MockResponse().setBody(JSONObject().put("libraryVersion",1).put("page",page).put("hasMore",more).put("items",JSONArray().put(JSONObject().put("id",id).put("tmdbId",99).put("kind",kind).put("title","Actual series"))).toString())
  server.enqueue(aliasPage(1,true,"series-catalog","series"));server.enqueue(aliasPage(2,true,"anime-catalog","anime"));server.enqueue(savedLibraryPage(3,false,77))
  assertEquals(listOf(99,77),repo.watchlist().map{it.id});assertFalse(repo.watchlistLimited);assertEquals(3,server.requestCount)
  val items=JSONArray().put(JSONObject().put("id","series-catalog").put("tmdbId",99).put("kind","series").put("title","Actual series")).put(JSONObject().put("id","anime-catalog").put("tmdbId",99).put("kind","anime").put("title","Actual series"))
  server.enqueue(MockResponse().setBody(JSONObject().put("libraryVersion",1).put("page",1).put("hasMore",false).put("items",items).toString()))
  assertEquals(1,repo.favorites().size);assertFalse(repo.favoritesLimited)
 }
 @Test fun savedListViewerAndMaturityChangesCancelWithoutCrossProfileResults()=runBlocking{
  val originalViewer=backend.viewerProfiles.active()
  try{
   val first=ViewerProfile("00000000-0000-4000-8000-000000000001","Pagination adult","",false,"all","fa","fa",true,false)
   for((index,changeViewer)in listOf(true,false).withIndex()){
    backend.viewerProfiles.activate(first)
    server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{backend.viewerProfiles.activate(if(changeViewer)first.copy(id="00000000-0000-4000-8000-000000000002")else first.copy(kidsMode=true,maturityLevel="kids"));return savedLibraryPage(1,true,77)}}
    try{LibraryRepository(backend).favorites();fail("changed viewer/maturity leaked")}catch(expected:kotlinx.coroutines.CancellationException){}
    val r=server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS)?:error("library request missing");assertEquals(first.id,r.getHeader("X-Filmiqoo-Viewer-Profile"));assertEquals(index+1,server.requestCount)
   }
  }finally{if(originalViewer==null)backend.viewerProfiles.clear()else backend.viewerProfiles.activate(originalViewer)}
 }
}
