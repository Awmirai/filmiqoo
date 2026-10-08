package com.filmiqoo.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

class ProductJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val server=MockWebServer()
    private var original:Triple<String,String?,String?>?=null
    private var originalViewer:ViewerProfile?=null
    private var restoreViewer=false
    private fun backend(logged:Boolean=false):BackendRepository {
        server.start();return BackendRepository(compose.activity).also {
            original=Triple(it.session.baseUrl,it.session.accessToken,it.session.refreshToken)
            it.session.baseUrl=server.url("/").toString();it.session.accessToken=if(logged)"test"else null;it.session.refreshToken=if(logged)"test"else null
        }
    }
    @After fun cleanup(){
        compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))}
        original?.let{v->SessionStore(compose.activity).apply{baseUrl=v.first;accessToken=v.second;refreshToken=v.third}}
        if(restoreViewer)ViewerProfileStore(compose.activity).apply { originalViewer?.let(::activate) ?: clear() }
        server.shutdown()
    }
    @Test fun searchNormalizesRequestAndLateResponseCannotReplaceLatest(){
        val backend=backend();val queries=CopyOnWriteArrayList<String>()
        val releaseOld=java.util.concurrent.CountDownLatch(1);val oldReturning=java.util.concurrent.CountDownLatch(1)
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            if(r.requestUrl?.encodedPath!="/v1/search")return MockResponse().setBody("""{"page":1,"total_pages":1,"results":[],"genres":[]}""")
            val q=r.requestUrl?.queryParameter("q").orEmpty();queries+=q
            if(q=="قدیمی"){check(releaseOld.await(20,java.util.concurrent.TimeUnit.SECONDS));oldReturning.countDown()}
            return MockResponse().setHeader("Content-Type","application/json").setBody("""{"media":[{"id":"id-$q","tmdbId":77,"kind":"movie","title":"نتیجه $q"}]}""")
        }}
        compose.setContent{FilmiqooTheme{PremiumSearchScreen(TmdbRepository(compose.activity),backend,{},{},{},{},{})}}
        try{
            compose.onNodeWithTag("search-input").performTextInput("قدیمی")
            compose.waitUntil(5000){queries.contains("قدیمی")}
            compose.onNodeWithTag("search-input").performTextReplacement("كيان‌علي")
            compose.onNodeWithTag("search-input").performImeAction()
            compose.waitUntil(10000){queries.contains("کیان علی")&&compose.onAllNodesWithTag("search-loading").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("search-results").performScrollToNode(hasText("نتیجه کیان علی"))
            compose.onNodeWithText("نتیجه کیان علی").assertIsDisplayed()
            releaseOld.countDown();compose.waitUntil(5000){oldReturning.count==0L}
            Thread.sleep(1200);compose.waitForIdle()
            compose.onNodeWithText("نتیجه قدیمی").assertDoesNotExist();compose.onNodeWithText("نتیجه کیان علی").assertIsDisplayed()
            compose.onNodeWithTag("search-input").assertTextContains("كيان‌علي")
            assertTrue(queries.contains("کیان علی"))
        }finally{releaseOld.countDown()}
    }
    @Test fun failedCommentKeepsDraftAndRetryIdentity(){
        val backend=backend(true);val sent=CopyOnWriteArrayList<JSONObject>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            if(r.method=="POST") {sent+=JSONObject(r.body.readUtf8());return MockResponse().setResponseCode(if(sent.size==1)500 else 201).setBody("""{"id":"created"}""")}
            return MockResponse().setBody("""{"items":[],"nextCursor":null}""")
        }}
        compose.setContent{FilmiqooTheme{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){TitleDiscussion(MediaItem(77,MediaType.MOVIE,"اثر آزمایشی"),backend,{})}}}
        compose.onNodeWithTag("discussion-draft").performScrollTo().performTextInput("متن نباید گم شود")
        compose.onNodeWithText("ارسال").performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("عملیات انجام نشد؛ متن و پیوستت محفوظ است. دوباره تلاش کن.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("discussion-draft").assertTextContains("متن نباید گم شود")
        compose.onNodeWithText("ارسال").performScrollTo().performClick()
        compose.waitUntil(10000){sent.size==2}
        assertEquals(sent[0].getString("clientId"),sent[1].getString("clientId"))
        assertEquals("اثر آزمایشی",sent[1].getString("title"))
    }
    @Test fun episodeDiscussionIsSeparateFromWholeSeries(){
        val backend=backend();val paths=CopyOnWriteArrayList<String>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{paths+=r.requestUrl!!.encodedPath;return MockResponse().setBody("""{"items":[]}""")}}
        compose.setContent{FilmiqooTheme{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){TitleDiscussion(MediaItem(100,MediaType.TV,"سریال"),backend,{},listOf(DiscussionEpisode(1,2)))}}}
        compose.onNodeWithText("فصل 1 · قسمت 2").performClick()
        compose.waitUntil(10000){paths.any{android.net.Uri.decode(it).contains("series:100:s1:e2")}}
        compose.onNodeWithText("کل سریال").performClick()
        compose.waitUntil(10000){android.net.Uri.decode(paths.lastOrNull().orEmpty()).endsWith("series:100")}
    }
    @Test fun tabSwitchAndRestorationKeepSearchDraft(){
        val backend=backend()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setBody("""{"media":[{"id":"fixture","tmdbId":77,"kind":"movie","title":"پاسخ آزمایشی"}]}""")}
        val restoration=StateRestorationTester(compose)
        restoration.setContent { FilmiqooTheme {
            var tab by rememberSaveable{mutableIntStateOf(1)}
            val states=rememberSaveableStateHolder()
            CinemaAppShell(tab,false,{tab=it}){states.SaveableStateProvider(tab){
                if(tab==1)PremiumSearchScreen(TmdbRepository(compose.activity),backend,{},{},{},{},{}) else Text("کتابخانه")
            }}
        }}
        compose.onNodeWithTag("search-input").performTextInput("سریال من")
        compose.onNodeWithTag("navigation-3").performClick()
        compose.onNodeWithTag("navigation-1").performClick()
        compose.onNodeWithTag("search-input").assertTextContains("سریال من")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("search-input").assertTextContains("سریال من")
    }
    @Test fun personalListSurvivesStoreRecreationWithoutDeletingOtherProfiles(){
        val media=MediaItem(777701,MediaType.MOVIE,"فهرست آزمایشی")
        val first=CinemaPersonalStore(compose.activity,"test-profile-a")
        val second=CinemaPersonalStore(compose.activity,"test-profile-b")
        try {
            first.setSaved("watchlist",media,true);first.markSeen(media,true)
            assertTrue(CinemaPersonalStore(compose.activity,"test-profile-a").saved().any{it.id==media.id})
            assertTrue(CinemaPersonalStore(compose.activity,"test-profile-a").seenItems().any{it.id==media.id})
            assertFalse(second.saved().any{it.id==media.id})
        } finally {first.setSaved("watchlist",media,false);first.markSeen(media,false)}
        compose.setContent{FilmiqooTheme{Text("فهرست بازیابی شد")}}
    }
    @Test fun failedSettingsSaveRestoresPreviousValue(){
        val backend=backend(true)
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=
            if(r.method=="POST")MockResponse().setResponseCode(503).setBody("{}")
            else MockResponse().setBody("""{"autoplayNext":false}""")}
        compose.setContent{FilmiqooTheme{SettingsScreen(backend,{})}}
        compose.waitUntil(10000){runCatching { compose.onAllNodes(isToggleable()).onFirst().assertIsOff();true }.getOrDefault(false)}
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("ذخیره نشد؛ تنظیم قبلی حفظ شد.",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
    }
    @Test fun expiredSessionClearsActiveViewerWithoutDeletingPersonalLists() = kotlinx.coroutines.runBlocking {
        val backend=backend(true)
        originalViewer=backend.viewerProfiles.active();restoreViewer=true
        val profile=ViewerProfile("expired-profile","آزمایش","",false,"all","fa","fa",true,false)
        val media=MediaItem(777702,MediaType.MOVIE,"فهرست محفوظ")
        val personal=CinemaPersonalStore(compose.activity,profile.id)
        backend.viewerProfiles.activate(profile);personal.setSaved("watchlist",media,true)
        val requests=CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
            requests+=r
            return if(requests.size<=2)MockResponse().setResponseCode(401).setBody("{}") else MockResponse().setBody("{}")
        }}
        try {
            assertTrue(runCatching { backend.getJson("/v1/library/watchlist",true) }.isFailure)
            assertFalse(backend.session.isLoggedIn)
            assertNull(backend.viewerProfiles.activeId())
            assertTrue(personal.saved().any { it.id==media.id })
            backend.session.accessToken="new-account";backend.session.refreshToken="new-refresh"
            backend.getJson("/v1/library/watchlist",true)
            assertEquals("/v1/auth/refresh",requests[1].requestUrl!!.encodedPath)
            assertNull(requests.last().getHeader("X-Filmiqoo-Viewer-Profile"))
        } finally { personal.setSaved("watchlist",media,false) }
    }
    @Test fun temporaryRefreshFailureKeepsSessionForRetry() = kotlinx.coroutines.runBlocking {
        val backend=backend(true)
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse()
            .setResponseCode(if(r.requestUrl!!.encodedPath=="/v1/auth/refresh")503 else 401).setBody("{}")}
        assertTrue(runCatching { backend.getJson("/v1/library/watchlist",true) }.isFailure)
        assertTrue(backend.session.isLoggedIn)
        assertEquals("test",backend.session.refreshToken)
    }
}
