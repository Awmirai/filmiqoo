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
    private fun backend(logged:Boolean=false):BackendRepository {
        server.start();return BackendRepository(compose.activity).also {
            original=Triple(it.session.baseUrl,it.session.accessToken,it.session.refreshToken)
            it.session.baseUrl=server.url("/").toString();it.session.accessToken=if(logged)"test"else null;it.session.refreshToken=if(logged)"test"else null
        }
    }
    @After fun cleanup(){
        compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))}
        original?.let{v->SessionStore(compose.activity).apply{baseUrl=v.first;accessToken=v.second;refreshToken=v.third}}
        server.shutdown()
    }
    @Test fun searchNormalizesRequestAndLateResponseCannotReplaceLatest(){
        val backend=backend();val queries=CopyOnWriteArrayList<String>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            val q=r.requestUrl?.queryParameter("q").orEmpty();queries+=q
            if(q=="قدیمی")Thread.sleep(900)
            return MockResponse().setHeader("Content-Type","application/json").setBody("""{"media":[{"id":"id-$q","tmdbId":77,"kind":"movie","title":"نتیجه $q"}]}""")
        }}
        compose.setContent{FilmiqooTheme{PremiumSearchScreen(TmdbRepository(compose.activity),backend,{},{},{},{},{})}}
        compose.onNodeWithTag("search-input").performTextInput("قدیمی")
        compose.waitUntil(5000){queries.contains("قدیمی")}
        compose.onNodeWithTag("search-input").performTextReplacement("كيان‌علي")
        compose.waitUntil(10000){compose.onAllNodesWithText("نتیجه کیان علی").fetchSemanticsNodes().isNotEmpty()}
        Thread.sleep(1200);compose.waitForIdle()
        compose.onNodeWithText("نتیجه قدیمی").assertDoesNotExist()
        compose.onNodeWithTag("search-input").assertTextContains("كيان‌علي")
        assertTrue(queries.contains("کیان علی"))
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
}
