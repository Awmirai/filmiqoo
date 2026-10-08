package com.filmiqoo.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OnlinePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private var originalBase: String? = null
    private var originalAccess: String? = null
    private var originalRefresh: String? = null

    private fun backend(): BackendRepository {
        server.start()
        return BackendRepository(compose.activity).also {
            originalBase = it.session.baseUrl
            originalAccess = it.session.accessToken
            originalRefresh = it.session.refreshToken
            it.session.baseUrl = server.url("/").toString()
            it.session.accessToken = "instrumentation-only"
            it.session.refreshToken = "instrumentation-only"
        }
    }

    @After fun cleanUp() {
        // Cleanup evidence must not obscure a primary failure or retain a system error overlay.
        if (runCatching { ensureNoSystemErrorDialog() }.isSuccess) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    PlatformTestStorageRegistry.getInstance().openOutputFile("player-final-state.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
        // Dispose the player and polling effects before restoring the real endpoint.
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        SessionStore(compose.activity).also {
            originalBase?.let { url -> it.baseUrl = url }
            it.accessToken = originalAccess
            it.refreshToken = originalRefresh
        }
        server.shutdown()
    }

    @Test fun realOnlineFlowDecodesVideoAdvancesAndReturnsSafely() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("player-fixture.mp4").use { it.readBytes() }
        val tokens = AtomicInteger()
        val mediaRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v1/playback/token" -> {
                    assertEquals("network-regression", JSONObject(request.body.readUtf8()).getString("mediaVersionId"))
                    tokens.incrementAndGet()
                    json(JSONObject().put("url", server.url("/media.mp4").toString()).toString())
                }
                "/media.mp4" -> {
                    mediaRequests.incrementAndGet()
                    val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (start >= bytes.size) MockResponse().setResponseCode(416)
                    else MockResponse().setResponseCode(if (request.getHeader("Range") == null) 200 else 206)
                        .setHeader("Content-Type", "video/mp4")
                        .setHeader("Accept-Ranges", "bytes")
                        .apply { if (request.getHeader("Range") != null) setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}") }
                        .setBody(Buffer().write(bytes, start, bytes.size - start))
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        compose.activity.setTheme(R.style.Theme_Filmiqoo)
        // Verify the theme supports the native Cast widget, even on a device without Cast services.
        compose.runOnUiThread { MediaRouteButton(compose.activity) }
        val repository = backend()
        val launchAt=android.os.SystemClock.elapsedRealtime()
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            FilmiqooTheme {
                if (open) FilmiqooPlayerScreen(PlaybackTarget("network-regression", "Network playback"), repository, onBack = { open = false })
                else Text("Online player closed safely")
            }
        }
        compose.waitUntil(30_000) {
            var playing = false
            compose.runOnUiThread {
                val player = findPlayer(compose.activity.window.decorView)?.player
                playing = player?.isPlaying == true && player.currentPosition >= 2_000 && player.videoSize.width == 320
            }
            playing
        }
        assertTrue("Online token endpoint was used", tokens.get() > 0)
        assertTrue("Video was actually fetched over HTTP", mediaRequests.get() > 0)
        // Includes the required 2 s of advancing playback and polling; this is not first-frame latency.
        PlatformTestStorageRegistry.getInstance().openOutputFile("online-playback-observation.json").use {
            it.write(JSONObject().put("setupToPlayingAt2SecondsMs",android.os.SystemClock.elapsedRealtime()-launchAt)
                .put("videoWidth",320).put("httpMediaRequests",mediaRequests.get()).put("tokenRequests",tokens.get()).toString(2).toByteArray())
        }
        // Controls auto-hide during real decoding, and video taps still reach Compose
        // above the native PlayerView. Tools must dismiss before leaving playback.
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("پنهان‌کردن کنترل‌ها").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("player-video-gestures").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .2f, height * .4f)) }
        compose.waitUntil(2_500) { compose.onAllNodesWithContentDescription("پنهان‌کردن کنترل‌ها").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("پنهان‌کردن کنترل‌ها").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("پنهان‌کردن کنترل‌ها").assertDoesNotExist()
        compose.onNodeWithTag("player-video-gestures").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .2f, height * .4f)) }
        compose.waitUntil(2_500) { compose.onAllNodesWithContentDescription("پنهان‌کردن کنترل‌ها").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("ابزارهای پخش").onFirst().performClick()
        compose.onNodeWithContentDescription("بستن").assertIsDisplayed()
        ensureNoSystemErrorDialog()
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        // Native Back injection returns before the modal's hide animation and composition removal.
        compose.waitUntil(2_500) { compose.onAllNodesWithContentDescription("بستن").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("بستن").assertDoesNotExist()
        compose.onNodeWithContentDescription("پنهان‌کردن کنترل‌ها").performClick()
        ensureNoSystemErrorDialog()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("online-video-playing.png").use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Online player closed safely").assertIsDisplayed()
    }

    @Test fun newCatalogPostAppearsWithoutReopeningScreenAndSurvivesRefreshFailure() {
        val requests = AtomicInteger()
        val available = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath != "/v1/catalog/home") return MockResponse().setResponseCode(404)
                requests.incrementAndGet()
                return when (available.get()) {
                    0 -> json("""{"items":[]}""")
                    1 -> json("""{"items":[{"id":"post21","tmdbId":1482938,"kind":"movie","title":"Union County","year":2026}]}""")
                    else -> MockResponse().setResponseCode(503)
                }
            }
        }
        val repository = backend()
        compose.setContent { FilmiqooTheme { RecentCatalogShelf(rememberRecentCatalog(repository, 150), {}) } }
        compose.waitUntil(10_000) { requests.get() > 0 }
        compose.onNodeWithText("Union County").assertDoesNotExist()
        available.set(1)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Union County").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Union County").assertIsDisplayed()
        available.set(2)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("به‌روزرسانی انجام نشد؛ دوباره تلاش کن.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Union County").assertIsDisplayed()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPlayer(view.getChildAt(i))?.let { return it }
        return null
    }
}
