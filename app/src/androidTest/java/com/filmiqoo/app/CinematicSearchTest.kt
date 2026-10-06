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
        compose.onNodeWithTag("search-type-1").performClick()
        compose.onNodeWithText("2 عنوان · جدیدترین سال").assertExists()
        assertEquals(1, searchCalls.get())
    }

    @Test fun sortAvailabilityAndTypedQuerySurviveRestoration() {
        val backend = backend()
        val repository = TmdbRepository(compose.activity)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FilmiqooTheme { PremiumSearchScreen(repository, backend, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("search-input").performTextInput("عنوان من")
        compose.waitUntil(10000) { compose.onAllNodesWithText("3 عنوان · مرتبط‌ترین").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-options").performClick()
        compose.onNodeWithTag("search-order-RATING").performScrollTo().performClick()
        compose.onNodeWithTag("search-playable-switch").performScrollTo().performClick()
        compose.onNodeWithText("اعمال").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("search-input").assertTextContains("عنوان من")
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

    @Test fun emptyStarterRetryLoadsServiceArtworkAndOpensTheReceivedTitle() {
        val backend = backend()
        val metadataCalls = AtomicInteger()
        val metadataPaths = CopyOnWriteArrayList<String>()
        val imageCalls = AtomicInteger()
        // Test-only artwork is returned by the same HTTP service as the title, never a runtime asset.
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(37, 82, 88)) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val image = server.url("/received-poster.png").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/received-poster.png") {
                    imageCalls.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                }
                if (path == "/v1/tmdb") {
                    metadataPaths += request.requestUrl!!.queryParameter("path").orEmpty()
                    val results = if (metadataCalls.incrementAndGet() == 1) "[]" else """[{"id":77,"media_type":"movie","title":"جدایی نادر از سیمین","original_title":"A Separation","poster_path":"$image","vote_average":8.1,"release_date":"2011-03-15"}]"""
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"results":$results}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[],"results":[]}""")
            }
        }
        val repository = TmdbRepository(compose.activity)
        compose.setContent { FilmiqooTheme { PremiumSearchScreen(repository, backend, {}, { opened += it }, {}, {}, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("پیشنهادها هنوز آماده نیستند").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-poster-empty", useUnmergedTree = true).assertExists()
        assertEquals(1, metadataCalls.get())
        assertEquals(0, imageCalls.get())
        compose.onNodeWithText("دریافت پیشنهادها").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("poster-MOVIE:77").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-poster-atmosphere", useUnmergedTree = true).assertExists()
        compose.waitUntil(10000) { imageCalls.get() > 0 }
        compose.onNodeWithText("پیشنهادها هنوز آماده نیستند").assertDoesNotExist()
        compose.onNodeWithTag("poster-MOVIE:77").performScrollTo().performClick()
        val received = opened.single()
        assertEquals(77, received.id)
        assertEquals(image, received.posterPath)
        assertEquals("A Separation", received.originalTitle)
        assertFalse(received.streamReady)
        assertNull(received.mediaVersionId)
        assertEquals(2, metadataCalls.get())
        assertEquals(listOf("trending/all/week", "trending/all/week"), metadataPaths.toList())
    }
}
