package com.filmiqoo.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CinematicSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private var original: Triple<String, String?, String?>? = null
    private var originalHistory: String? = null
    private var originalTmdbCredential: String? = null
    private var historyTouched = false
    private val searchCalls = AtomicInteger()
    private val opened = CopyOnWriteArrayList<MediaItem>()
    private val media = """{"media":[
        {"id":"movie-ready","tmdbId":101,"kind":"movie","title":"فیلم قابل پخش","year":2022,"rating":7.2,"streamReady":true,"mediaVersionId":"real-version"},
        {"id":"movie-metadata","tmdbId":102,"kind":"movie","title":"فیلم اطلاعاتی","year":2025,"rating":6.5,"streamReady":true},
        {"id":"series","tmdbId":103,"kind":"series","title":"سریال","year":2024,"rating":9.0}
    ]}"""

    private fun backend(failFirst: Boolean = false): BackendRepository {
        originalHistory = compose.activity.getSharedPreferences("filmiqoo_search_history", android.content.Context.MODE_PRIVATE).getString("items", null)
        historyTouched = true
        val tmdbPrefs=compose.activity.getSharedPreferences("filmiqoo_tmdb", android.content.Context.MODE_PRIVATE)
        originalTmdbCredential=tmdbPrefs.getString("credential",null);tmdbPrefs.edit().remove("credential").commit()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/v1/search") {
                    val count = searchCalls.incrementAndGet()
                    if (failFirst && count == 1) return MockResponse().setResponseCode(503).setBody("{}")
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(media)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[],"results":[]}""")
            }
        }
        return BackendRepository(compose.activity).also {
            original = Triple(it.session.baseUrl, it.session.accessToken, it.session.refreshToken)
            it.session.baseUrl = server.url("/").toString(); it.session.accessToken = null; it.session.refreshToken = null
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        original?.let { saved -> SessionStore(compose.activity).apply { baseUrl = saved.first; accessToken = saved.second; refreshToken = saved.third } }
        if (historyTouched) compose.activity.getSharedPreferences("filmiqoo_search_history", android.content.Context.MODE_PRIVATE)
            .edit().apply { originalHistory?.let { putString("items", it) } ?: remove("items") }.commit()
        if(historyTouched) compose.activity.getSharedPreferences("filmiqoo_tmdb",android.content.Context.MODE_PRIVATE).edit().apply{originalTmdbCredential?.let{putString("credential",it)}?:remove("credential")}.commit()
        server.shutdown()
    }

    @Test fun realPlaybackFilterSortAndDetailCallbackAreConnected() {
        val backend = backend()
        val repository = TmdbRepository(compose.activity)
        compose.setContent { FilmiqooTheme { PremiumSearchScreen(repository, backend, {}, { opened += it }, {}, {}, {}) } }
        compose.onNodeWithTag("search-input").performTextInput("سینما")
        compose.waitUntil(10000) { compose.onAllNodesWithText("3 عنوان · مرتبط‌ترین").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-options").performClick()
        compose.onNodeWithTag("search-order-NEWEST").performScrollTo().performClick()
        compose.onNodeWithTag("search-playable-switch").performScrollTo().performClick()
        compose.onNodeWithText("اعمال").performScrollTo().performClick()
        compose.onNodeWithText("1 عنوان · جدیدترین سال").assertExists()
        compose.onNodeWithTag("poster-MOVIE:102").assertDoesNotExist()
        compose.onNodeWithTag("poster-MOVIE:101").performScrollTo().performClick()
        assertEquals("real-version", opened.single().mediaVersionId)
        assertEquals("movie-ready", opened.single().backendId)
        // Clearing availability returns metadata-only titles; this does not call them playable.
        compose.onNodeWithTag("search-type-filters").performScrollToNode(hasTestTag("search-playable-active"))
        compose.onNodeWithTag("search-playable-active").performClick()
        compose.onNodeWithText("3 عنوان · جدیدترین سال").assertExists()
        compose.onNodeWithTag("search-type-filters").performScrollToNode(hasTestTag("search-type-1"));compose.onNodeWithTag("search-type-1").performClick()
        compose.onNodeWithText("2 عنوان · جدیدترین سال").assertExists()
        assertEquals(1, searchCalls.get())
    }

    @Test fun sortAvailabilityAndTypedQuerySurviveRestoration() {
        val backend = backend()
        val repository = TmdbRepository(compose.activity)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FilmiqooTheme { PremiumSearchScreen(repository, backend, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("search-input").performTextInput("عنوان من");compose.onNodeWithTag("search-input").performImeAction()
        compose.waitUntil(10000) { compose.onAllNodesWithText("3 عنوان · مرتبط‌ترین").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-options").performClick()
        compose.onNodeWithTag("search-order-RATING").performScrollTo().performClick()
        compose.onNodeWithTag("search-playable-switch").performScrollTo().performClick()
        compose.onNodeWithText("اعمال").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("search-input").assertTextContains("عنوان من")
        compose.waitUntil(10_000){searchCalls.get()==2}
        compose.onNodeWithTag("search-results").performScrollToIndex(0)
        compose.waitUntil(10000) { compose.onAllNodesWithText("1 عنوان · بالاترین امتیاز").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-options").performClick()
        compose.onNodeWithTag("search-order-RATING").assertIsSelected()
        compose.onNodeWithTag("search-playable-switch").performScrollTo().assertIsOn()
    }

    @Test fun retryPreservesQueryAndSubmittedHistoryWorks() {
        val backend = backend(failFirst = true)
        val repository = TmdbRepository(compose.activity)
        val historyPrefs = compose.activity.getSharedPreferences("filmiqoo_search_history", android.content.Context.MODE_PRIVATE)
        originalHistory = historyPrefs.getString("items", null); historyTouched = true
        historyPrefs.edit().remove("items").commit()
        compose.setContent { FilmiqooTheme { PremiumSearchScreen(repository, backend, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("search-input").performTextInput("نام محفوظ")
        compose.waitUntil(10000) { compose.onAllNodesWithText("ارتباط برقرار نشد").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("تلاش دوباره").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("3 عنوان · مرتبط‌ترین").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-input").assertTextContains("نام محفوظ").performImeAction()
        assertTrue(UniversalSearchRepository(compose.activity, backend).history().contains("نام محفوظ"))
        compose.onNodeWithTag("search-clear").performClick()
        compose.onNodeWithTag("search-results").performScrollToNode(hasText("جست‌وجوهای اخیر"))
        compose.onNodeWithText("نام محفوظ").performClick()
        compose.onNodeWithTag("search-input").assertTextContains("نام محفوظ")
    }


    @Test fun emptyStarterRetryLoadsServiceArtworkAndOpensTheReceivedTitle(){
        val backend=backend();val empty=java.util.concurrent.atomic.AtomicBoolean(true)
        val paths=CopyOnWriteArrayList<String>();val imageCalls=AtomicInteger()
        val bitmap=Bitmap.createBitmap(64,96,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(37,82,88))}
        val png=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val image=server.url("/received-poster.png").toString()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            if(r.requestUrl!!.encodedPath=="/received-poster.png"){imageCalls.incrementAndGet();return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(png))}
            if(r.requestUrl!!.encodedPath=="/v1/tmdb"){
                val metadataPath=r.requestUrl!!.queryParameter("path").orEmpty();paths+=metadataPath
                val result=if(empty.get()||metadataPath!="discover/movie")"[]"else"""[{"id":77,"media_type":"movie","title":"جدایی نادر از سیمین","original_title":"A Separation","original_language":"fa","poster_path":"$image","vote_average":8.1,"release_date":"2011-03-15"}]"""
                return MockResponse().setBody("""{"results":$result,"genres":[],"page":1,"total_pages":1}""")
            };return MockResponse().setBody("""{"items":[],"results":[]}""")
        }}
        compose.setContent{FilmiqooTheme{PremiumSearchScreen(TmdbRepository(compose.activity),backend,{}, {opened+=it},{},{},{})}}
        compose.waitUntil(10_000){compose.onAllNodesWithText("پیشنهادها هنوز آماده نیستند").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("search-poster-empty",useUnmergedTree=true).assertExists();assertEquals(0,imageCalls.get())
        empty.set(false);compose.onNodeWithText("دریافت پیشنهادها").performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("poster-MOVIE:77").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("search-poster-atmosphere",useUnmergedTree=true).assertExists();compose.waitUntil(10_000){imageCalls.get()>0}
        compose.onNodeWithTag("poster-MOVIE:77").performScrollTo().performClick();val received=opened.single();assertEquals(77,received.id);assertEquals(image,received.posterPath);assertEquals("A Separation",received.originalTitle);assertFalse(received.streamReady);assertNull(received.mediaVersionId)
        assertEquals(2,paths.count{it=="discover/movie"});assertEquals(2,paths.count{it=="discover/tv"})
    }
    @Test fun metadataRetryPreservesCatalogAndSeparatesMoviesSeriesAndPeople(){
        val backend=backend();val calls=AtomicInteger();val people=CopyOnWriteArrayList<Pair<Int,String>>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            if(r.requestUrl!!.encodedPath=="/v1/search"){searchCalls.incrementAndGet();return MockResponse().setBody(media)}
            if(r.requestUrl!!.queryParameter("path")=="search/multi"){
                if(calls.incrementAndGet()==1)return MockResponse().setResponseCode(503).setBody("{}")
                return MockResponse().setBody("""{"page":1,"total_pages":1,"results":[{"id":101,"media_type":"movie","title":"Ready metadata title","original_language":"en","release_date":"2022-01-01","genre_ids":[35]},{"id":104,"media_type":"movie","title":"New movie","original_language":"ko","release_date":"2024-01-01","vote_average":8.7,"genre_ids":[18]},{"id":103,"media_type":"tv","name":"Existing series","first_air_date":"2024-01-01"},{"id":500,"media_type":"person","name":"Park Actor"}]}""")
            };return MockResponse().setBody("""{"items":[],"results":[],"genres":[]}""")
        }}
        compose.setContent{FilmiqooTheme{PremiumSearchScreen(TmdbRepository(compose.activity),backend,{}, {opened+=it},{},{},{},onPerson={id,name->people+=id to name})}}
        compose.onNodeWithTag("search-input").performTextInput("کیان");compose.onNodeWithTag("search-input").performImeAction();compose.waitUntil(10_000){compose.onAllNodesWithTag("search-metadata-error").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("3 عنوان · مرتبط‌ترین").assertExists()
        compose.onNodeWithText("تلاش دوباره برای اطلاعات").performScrollTo().performClick();compose.waitUntil(10_000){calls.get()==2};compose.onNodeWithTag("search-results").performScrollToIndex(0);compose.waitUntil(10_000){compose.onAllNodesWithText("4 عنوان · مرتبط‌ترین").fetchSemanticsNodes().isNotEmpty()};assertEquals(1,searchCalls.get())
        compose.onNodeWithTag("poster-MOVIE:101").performScrollTo().performClick();assertEquals("real-version",opened.single().mediaVersionId);assertEquals("movie-ready",opened.single().backendId)
        compose.onNodeWithTag("search-type-filters").performScrollToNode(hasTestTag("search-type-3"));compose.onNodeWithTag("search-type-3").performClick();compose.onNodeWithTag("search-results").performScrollToNode(hasTestTag("search-person-500"));compose.onNodeWithTag("search-person-500").performClick();assertEquals(listOf(500 to "Park Actor"),people.toList())
    }

    @Test fun returnedMetadataFacetsRestoreAndNeverTreatUnknownCatalogDataAsMatching(){
        val backend=backend()
        val catalog="""{"media":[{"id":"ready-drama","tmdbId":101,"kind":"movie","title":"درام آماده","year":2024,"rating":8.5,"streamReady":true,"mediaVersionId":"drama-version","genreIds":[18],"originalLanguage":"ko","hasPersianDub":true,"hasPersianSubtitle":true},{"id":"unknown-facets","tmdbId":102,"kind":"movie","title":"اطلاعات نامشخص","year":2024,"rating":9.0},{"id":"known-series","tmdbId":103,"kind":"series","title":"سریال درام","year":2024,"rating":9.0,"genreIds":[18],"originalLanguage":"ko","hasPersianSubtitle":true}]}"""
        val upstream="""{"page":1,"total_pages":1,"results":[{"id":104,"media_type":"movie","title":"Metadata drama","original_language":"ko","release_date":"2024-01-01","vote_average":8.7,"genre_ids":[18]},{"id":105,"media_type":"movie","title":"Metadata comedy","original_language":"en","release_date":"2024-01-01","vote_average":9.0,"genre_ids":[35]}]}"""
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            val u=r.requestUrl!!;val body=when{
                u.encodedPath=="/v1/search"->{searchCalls.incrementAndGet();catalog}
                u.encodedPath=="/v1/tmdb"&&u.queryParameter("path")=="search/multi"->upstream
                u.encodedPath=="/v1/tmdb"&&u.queryParameter("path") in listOf("genre/movie/list","genre/tv/list")->"""{"genres":[{"id":18,"name":"درام"},{"id":35,"name":"کمدی"}]}"""
                else->"""{"items":[],"results":[],"page":1,"total_pages":1}"""
            };return MockResponse().setHeader("Content-Type","application/json").setBody(body)
        }}
        val restoration=StateRestorationTester(compose)
        restoration.setContent{FilmiqooTheme{PremiumSearchScreen(TmdbRepository(compose.activity),backend,{}, {opened+=it},{},{},{})}}
        fun awaitCount(count:Int){compose.waitUntil(10_000){compose.onAllNodesWithText("$count عنوان · مرتبط‌ترین").fetchSemanticsNodes().isNotEmpty()&&compose.onAllNodesWithTag("search-loading").fetchSemanticsNodes().isEmpty()}}
        fun scrollFacet(label:String,tag:String){
            compose.waitUntil(5_000){compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText(label).performScrollTo();compose.onNodeWithTag(tag).performScrollTo()
            repeat(3){val node=compose.onNodeWithTag(tag).fetchSemanticsNode();val viewport=compose.onNodeWithTag("search-options-sheet").fetchSemanticsNode().boundsInRoot;val top=node.positionInRoot.y;val bottom=top+node.size.height
                val dy=when{bottom>viewport.bottom->bottom-viewport.bottom;top<viewport.top->top-viewport.top;else->0f}
                if(kotlin.math.abs(dy)>.5f)compose.onNodeWithTag("search-options-sheet").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,dy)}
            };compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        fun choose(label:String,tag:String){scrollFacet(label,tag);compose.onNodeWithTag(tag).performClick().assertIsSelected()}
        compose.onNodeWithTag("search-input").performTextInput("درام");compose.onNodeWithTag("search-input").performImeAction();awaitCount(5)
        compose.onNodeWithTag("search-type-1").performClick();awaitCount(4)
        compose.onNodeWithTag("search-options").performClick();choose("ژانر موجود در نتیجه‌ها","search-facet-genre-18");compose.onNodeWithText("اعمال").performScrollTo().performClick();awaitCount(2)
        compose.onNodeWithTag("poster-MOVIE:102").assertDoesNotExist();compose.onNodeWithTag("poster-MOVIE:105").assertDoesNotExist()
        compose.onNodeWithTag("search-options").performClick();choose("سال انتشار","search-facet-year-2020");choose("حداقل امتیاز ثبت‌شده","search-facet-rating-8");choose("زبان اصلیِ ثبت‌شده","search-facet-language-ko")
        compose.onNodeWithTag("search-dubbed-switch").performScrollTo().performClick();compose.onNodeWithTag("search-subtitle-switch").performScrollTo().performClick();compose.onNodeWithText("اعمال").performScrollTo().performClick();awaitCount(1)
        compose.onNodeWithTag("poster-MOVIE:104").assertDoesNotExist();compose.onNodeWithTag("poster-MOVIE:101").performScrollTo().performClick()
        assertEquals("ready-drama",opened.single().backendId);assertEquals("drama-version",opened.single().mediaVersionId);assertTrue(opened.single().hasPersianDub);assertTrue(opened.single().hasPersianSubtitle)
        restoration.emulateSavedInstanceStateRestore();compose.onNodeWithTag("search-input").assertTextContains("درام");compose.waitUntil(10_000){searchCalls.get()==2};compose.onNodeWithTag("search-results").performScrollToIndex(0);awaitCount(1);compose.onNodeWithTag("search-type-1").assertIsSelected()
        compose.onNodeWithTag("search-options").performClick()
        listOf("سال انتشار" to "search-facet-year-2020","حداقل امتیاز ثبت‌شده" to "search-facet-rating-8","ژانر موجود در نتیجه‌ها" to "search-facet-genre-18","زبان اصلیِ ثبت‌شده" to "search-facet-language-ko").forEach{(label,tag)->compose.waitUntil(5_000){compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()};scrollFacet(label,tag);compose.onNodeWithTag(tag).assertIsSelected()}
        compose.onNodeWithTag("search-dubbed-switch").performScrollTo().assertIsOn();compose.onNodeWithTag("search-subtitle-switch").performScrollTo().assertIsOn()
        compose.onNodeWithText("بازنشانی").performScrollTo().performClick();compose.onNodeWithText("اعمال").performScrollTo().performClick();awaitCount(5);compose.onNodeWithTag("search-type-0").assertIsSelected();assertEquals(2,searchCalls.get())
    }

}
