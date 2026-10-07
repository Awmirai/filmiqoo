package com.filmiqoo.app
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

class SeriesManualStateTest {
 private val context=InstrumentationRegistry.getInstrumentation().targetContext
 private val server=MockWebServer();private lateinit var backend:BackendRepository
 private var original:Triple<String,String?,String?>?=null;private var originalViewer:ViewerProfile?=null
 private val requests=CopyOnWriteArrayList<RecordedRequest>()
 private val title="00000000-0000-4000-8000-000000000010";private val season="00000000-0000-4000-8000-000000000020"
 private val ep1="00000000-0000-4000-8000-000000000031";private val ep2="00000000-0000-4000-8000-000000000032"
 private val viewer=ViewerProfile("00000000-0000-4000-8000-000000000041","Manual state QA","",false,"all","fa","fa",true,false)
 private fun token(sub:String,rev:String="initial")="fixture."+Base64.encodeToString(JSONObject().put("sub",sub).put("jti",rev).toString().toByteArray(Charsets.UTF_8),Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)+".signature"
 @Before fun setup(){server.start();backend=BackendRepository(context);original=Triple(backend.session.baseUrl,backend.session.accessToken,backend.session.refreshToken);originalViewer=backend.viewerProfiles.active();backend.session.baseUrl=server.url("/").toString();backend.session.accessToken=token("account-a");backend.session.refreshToken="manual-qa";backend.viewerProfiles.activate(viewer)}
 @After fun cleanup(){try{original?.let{backend.session.baseUrl=it.first;backend.session.accessToken=it.second;backend.session.refreshToken=it.third};originalViewer?.let(backend.viewerProfiles::activate)?:backend.viewerProfiles.clear()}finally{server.shutdown()}}
 private fun fixture(supported:Boolean){
  server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{requests+=r
   val manual=JSONObject().put("seasonId",season).put("seasonNumber",1).put("episodeId",ep1).put("episodeNumber",1).put("positionMs",0).put("durationMs",0).put("completed",false).put("progress",0.0)
   val actual=JSONObject().put("seasonId",season).put("seasonNumber",1).put("episodeId",ep2).put("episodeNumber",2).put("positionMs",96000).put("durationMs",100000).put("completed",true).put("progress",0.96)
   val body=JSONObject().put("mediaTitleId",title).put("watchedCount",1).put("totalCount",2).put("progress",0.5).put("items",JSONArray().put(manual).put(actual))
   if(supported){body.put("manualMarksVersion",1).put("manualSeenCount",1);manual.put("manualSeen",true);actual.put("manualSeen",false)}
   return MockResponse().setBody(if(r.method=="GET")body.toString()else """{"manualMarksVersion":1,"watched":true}""")
  }}
 }
 private suspend fun reject(action:suspend()->Unit){val n=server.requestCount;val e=runCatching{action()}.exceptionOrNull();assertTrue("must fail through scope/capability guard",e is IllegalStateException||e is CancellationException);assertEquals("guard emitted HTTP",n,server.requestCount)}
 @Test fun oldServerWithoutManualCapabilityCannotReceiveAnyManualWrites()=runBlocking{
  fixture(false);val repo=SeriesProgressRepository(backend)
  reject{repo.setEpisodeWatched(ep1,true)};reject{repo.setSeasonWatched(season,true)}
  assertNotNull(runCatching{repo.load(title)}.exceptionOrNull());assertEquals(1,server.requestCount)
  val r=requests.single();assertEquals("GET",r.method);assertEquals("/v1/watch/series/$title/progress",r.requestUrl!!.encodedPath)
  assertEquals(viewer.id,r.getHeader("X-Filmiqoo-Viewer-Profile"));assertEquals("Bearer "+token("account-a"),r.getHeader("Authorization"))
  reject{repo.setEpisodeWatched(ep1,true)};reject{repo.setSeasonWatched(season,false)};assertFalse(requests.any{it.method=="POST"})
 }
 @Test fun supportedManualStateUsesScopedCapabilityWithoutChangingActualCompletion()=runBlocking{
  fixture(true);val repo=SeriesProgressRepository(backend);val p=repo.load(title)
  assertEquals(1L,p.watchedCount);assertEquals(1L,p.manualSeenCount);assertEquals(2L,p.totalCount);assertEquals(.5f,p.progress,.001f)
  val manual=p.episodes.getValue(ep1);assertTrue(manual.manualSeen);assertFalse(manual.completed);assertEquals(0L,manual.positionMs);assertEquals(0L,manual.durationMs);assertEquals(0f,manual.progress,0f)
  val actual=p.episodes.getValue(ep2);assertFalse(actual.manualSeen);assertTrue(actual.completed);assertEquals(96000L,actual.positionMs);assertEquals(100000L,actual.durationMs);assertEquals(.96f,actual.progress,.001f)
  reject{repo.setEpisodeWatched("unknown-episode",true)};reject{repo.setSeasonWatched("unknown-season",true)}
  val refreshed=token("account-a","refresh");backend.session.accessToken=refreshed;repo.setEpisodeWatched(ep1,false);repo.setSeasonWatched(season,true)
  val writes=requests.filter{it.method=="POST"};assertEquals(2,writes.size)
  assertEquals("/v1/watch/episodes/$ep1/status",writes[0].requestUrl!!.encodedPath);assertEquals("/v1/watch/seasons/$season/status",writes[1].requestUrl!!.encodedPath)
  writes.forEachIndexed{i,r->assertEquals(viewer.id,r.getHeader("X-Filmiqoo-Viewer-Profile"));assertEquals("Bearer $refreshed",r.getHeader("Authorization"));val b=JSONObject(r.body.readUtf8());assertEquals(1,b.getInt("manualMarksVersion"));assertEquals(i==1,b.getBoolean("watched"))}
  suspend fun rejectBoth(){reject{repo.setEpisodeWatched(ep1,true)};reject{repo.setSeasonWatched(season,true)}}
  suspend fun reload(){backend.session.accessToken=token("account-a","reload");backend.viewerProfiles.activate(viewer);repo.load(title)}
  backend.session.accessToken=token("account-b");rejectBoth();reload()
  backend.viewerProfiles.activate(viewer.copy(id="00000000-0000-4000-8000-000000000042"));rejectBoth();reload()
  backend.viewerProfiles.activate(viewer.copy(maturityLevel="teen"));rejectBoth();reload()
  val oldBase=backend.session.baseUrl;try{backend.session.baseUrl=server.url("/different-origin").toString();rejectBoth()}finally{backend.session.baseUrl=oldBase}
  assertEquals(2,requests.count{it.method=="POST"})
 }
}
