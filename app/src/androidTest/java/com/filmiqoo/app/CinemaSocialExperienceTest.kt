package com.filmiqoo.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CinemaSocialExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val server = MockWebServer()
    private var original: Triple<String,String?,String?>? = null
    @After fun cleanup() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            PlatformTestStorageRegistry.getInstance().openOutputFile("social-${testName.methodName}.png").use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        original?.let { values -> SessionStore(compose.activity).apply { baseUrl=values.first;accessToken=values.second;refreshToken=values.third } }
        server.shutdown()
    }
    @Test fun inlineCommentsProtectSpoilersAndPersistStickerWithStableIdentity() {
        server.start()
        val sent = AtomicReference<JSONObject?>()
        server.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.method=="POST" && request.requestUrl?.encodedPath?.replace("%3A", ":", ignoreCase=true)=="/v1/discussions/movie:77") {
                    sent.set(JSONObject(request.body.readUtf8()))
                    return MockResponse().setHeader("Content-Type","application/json").setBody("""{"id":"new-comment"}""")
                }
                return MockResponse().setHeader("Content-Type","application/json").setBody("""{"items":[{"id":"spoiler","body":"متن پنهان داستان","spoiler":true,"authorName":"دوست سینمایی","createdAt":"2026-10-04","replies":0}],"nextCursor":null}""")
            }
        }
        val backend = BackendRepository(compose.activity)
        original=Triple(backend.session.baseUrl,backend.session.accessToken,backend.session.refreshToken)
        backend.session.baseUrl=server.url("/").toString();backend.session.accessToken="test";backend.session.refreshToken="test"
        compose.setContent { FilmiqooTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TitleDiscussion(MediaItem(77,MediaType.MOVIE,"عنوان آزمایشی"),backend,{error("unexpected auth")})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("هشدار اسپویل · نمایش دیدگاه").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("متن پنهان داستان").assertDoesNotExist()
        compose.onNodeWithText("هشدار اسپویل · نمایش دیدگاه").performScrollTo().performClick()
        compose.onNodeWithText("متن پنهان داستان").assertExists()
        compose.onNodeWithTag("discussion-draft").performScrollTo().performTextInput("پیشنهاد می‌کنم")
        compose.onNodeWithContentDescription("انتخاب استیکر").performScrollTo().performClick()
        compose.onNodeWithText("وقتِ سینما").performClick()
        compose.onNodeWithText("ارسال").performScrollTo().performClick()
        compose.waitUntil(10_000) { sent.get()!=null }
        assertEquals("popcorn",sent.get()!!.getString("sticker"))
        assertEquals("پیشنهاد می‌کنم",sent.get()!!.getString("body"))
        assertTrue(sent.get()!!.getString("clientId").matches(Regex("[a-f0-9-]{36}")))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("دیدگاهت منتشر شد.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("discussion-draft").assertTextContains("")
    }
    @Test fun splashFinishesAndDoesNotReplayOnStateRestore() {
        compose.mainClock.autoAdvance=false
        val restoration=StateRestorationTester(compose)
        restoration.setContent { FilmiqooTheme { CinemaSplash { Text("صفحهٔ ورود آماده است") } } }
        compose.onNodeWithTag("cinema-splash").assertExists()
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance=true
        compose.waitUntil(5_000) { compose.onAllNodesWithText("صفحهٔ ورود آماده است").fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("cinema-splash").assertDoesNotExist()
        compose.onNodeWithText("صفحهٔ ورود آماده است").assertIsDisplayed()
    }

    @Test fun homeSpotlightOpensRealTitleAndKeepsNavigationReachable() {
        val title=MediaItem(259731,MediaType.TV,"HIS & HERS",posterPath="https://image.tmdb.org/t/p/w500/cDSXLVQLkCSBIpBx3UW04TsfZ5c.jpg",
            backdropPath="https://image.tmdb.org/t/p/w1280/n4hJLZmBG8kZccNn7bNgBDsVQ6a.jpg",vote=7.3,date="2026")
        var opened:MediaItem?=null
        compose.setContent { FilmiqooTheme {
            androidx.compose.material3.Scaffold(containerColor=CinemaInk,bottomBar={CinemaBottomBar(0,false,{})}) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                    CinemaHeading("FILMIQOO","خانهٔ فیلم‌بازها")
                    CinemaSpotlight(HomeBundle(trending=listOf(title),popularTv=listOf(title)),{opened=it},{_,_->})
                }
            }
        } }
        compose.onNodeWithText("کشف این عنوان").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(259731,opened?.id) }
        compose.onNodeWithTag("navigation-2").assertIsDisplayed()
    }
}
