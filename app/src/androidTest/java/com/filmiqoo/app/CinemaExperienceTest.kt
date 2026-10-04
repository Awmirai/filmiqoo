package com.filmiqoo.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CinemaExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()

    @After fun screenshot() {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("cinema-" + testName.methodName + ".png").use { output ->
            assertTrue("Screenshot must be written to test storage", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    @Test fun moviePlaybackUsesRealVersionAndMetadataStaysReadable() {
        var played: String? = null
        compose.setContent { FilmiqooTheme { CinemaDetailContent(movie(), actions = CinemaDetailActions(play = { played = it })) } }
        compose.onNodeWithTag("cinema-detail").assertExists()
        compose.onNodeWithTag("detail-primary").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("movie-version", played) }
        compose.onNodeWithText("جدایی نادر از سیمین").assertExists()
    }

    @Test fun metadataOnlyTitleCannotPretendToPlay() {
        compose.setContent { FilmiqooTheme { CinemaDetailContent(movie().copy(platform = null)) } }
        compose.onNodeWithTag("detail-primary").assertIsNotEnabled()
    }

    @Test fun episodeBrowserCanReachLastRowWithoutClipping() {
        compose.setContent { FilmiqooTheme { CinemaDetailContent(series()) } }
        compose.onNodeWithTag("detail-episodes-shortcut").performClick()
        compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("episode-e30"))
        compose.onNodeWithTag("episode-e30").assertIsDisplayed()
    }

    @Test fun largeFontsKeepPlaybackAndEpisodeControlsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                FilmiqooTheme { CinemaDetailContent(series()) }
            }
        }
        compose.onNodeWithTag("detail-primary").assertIsDisplayed()
        compose.onNodeWithTag("detail-episodes-shortcut").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("episode-e30"))
        compose.onNodeWithTag("episode-e30").assertExists()
    }

    @Test fun navigationLabelsMatchDestinationIds() {
        compose.setContent {
            var selected by remember { mutableIntStateOf(0) }
            FilmiqooTheme { CinemaBottomBar(selected, false) { selected = it } }
        }
        compose.onNodeWithTag("navigation-3").performClick().assertIsSelected()
        compose.onNodeWithTag("navigation-3").assert(hasText("کتابخانه"))
        compose.onNodeWithTag("navigation-2").performClick().assertIsSelected()
        compose.onNodeWithTag("navigation-2").assert(hasText("کلاب"))
    }

    @Test fun kidsNavigationDoesNotExposeSocialAndDiscoveryRoutes() {
        compose.setContent { FilmiqooTheme { CinemaBottomBar(0, true) {} } }
        compose.onNodeWithTag("navigation-1").assertDoesNotExist()
        compose.onNodeWithTag("navigation-2").assertDoesNotExist()
        compose.onNodeWithTag("navigation-0").assertExists()
    }

    @Test fun spoilerShieldStartsEnabledAndCanBeChanged() {
        compose.setContent {
            var hidden by remember { mutableStateOf(true) }
            FilmiqooTheme { CinemaDetailContent(movie(), hideSpoilers = hidden, actions = CinemaDetailActions(spoiler = { hidden = it })) }
        }
        compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("spoiler-switch"))
        compose.onNodeWithTag("spoiler-switch").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("خلاصهٔ آزمایشی داستان، صرفاً برای تست رابط کاربری.").assertExists()
    }

    @Test fun iranGateExplainsActualConnectionPolicyWithLargeText() {
        var retried = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FilmiqooTheme { IranAccessScreen(IranAccessViewState(false, false, "اتصال خارج ایران است.")) { retried = true } }
            }
        }
        compose.onNodeWithText("بررسی دوباره").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test fun versionTracksUseOnlyMetadataProvidedByTheServer() {
        val tracks = org.json.JSONArray("[\"fa\",{\"language\":\"en\"},{\"label\":\"دوبله فارسی\"},null,{},\"fa\"]")
        assertEquals(listOf("فارسی", "English", "دوبله فارسی"), cinemaTrackLabels(tracks))
        assertEquals(emptyList<String>(), cinemaTrackLabels(null))
        compose.setContent { FilmiqooTheme { CinemaDetailContent(movie()) } }
    }

    private fun movie(): CinemaTitleData {
        val media = MediaItem(60243, MediaType.MOVIE, "جدایی نادر از سیمین", "جدایی نادر از سیمین",
            overview = "خلاصهٔ آزمایشی داستان، صرفاً برای تست رابط کاربری.", vote = 8.1, date = "2011-03-15", backendId = "fixture-movie")
        val detail = MediaDetail(media, "", listOf("درام", "خانوادگی"), 123, "Released",
            listOf(CastMember(1, "بازیگر نمونه", "نقش نمونه", null)), null, emptyList(), emptyList())
        val platform = PlatformDetail("fixture-movie", media.id, "movie", media.title, media.originalTitle, media.overview,
            2011, "", "", 8.1, listOf(PlatformVersion("movie-version", "1080p", "H.264", "SDR", 1_500_000_000, 7_380_000, true, true)), emptyList())
        return CinemaTitleData(detail, platform, listOf("IR"), listOf("فارسی"), "fa", "A Separation")
    }
    private fun series(): CinemaTitleData {
        val media = MediaItem(100, MediaType.TV, "My Liberation Notes", "나의 해방일지", overview = "خلاصهٔ آزمایشی سریال.", vote = 8.0, date = "2022-04-09", backendId = "fixture-series")
        val episodes = (1..30).map { n -> PlatformEpisode("e$n", n, "Episode $n", "داستان آزمایشی قسمت $n", "", 60, "v$n", "1080p", true) }
        val platform = PlatformDetail("fixture-series", 100, "series", media.title, media.originalTitle, media.overview, 2022, "", "", 8.0,
            emptyList(), listOf(PlatformSeason("season-1", 1, "فصل اول", "", episodes)))
        return CinemaTitleData(MediaDetail(media, "", listOf("درام"), 60, "Ended", emptyList(), null, emptyList(), listOf(SeasonInfo(1, "فصل اول", 30, null, "2022-04-09"))),
            platform, listOf("KR"), listOf("Korean"), "ko", media.title)
    }
}
