package com.filmiqoo.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real launch path against the existing server, without creating or using an account. */
@RunWith(AndroidJUnit4::class)
class PreviewLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun existingServerReachesWorkingLoginScreen() {
        assumeTrue(BuildConfig.LEGACY_SERVER_PREVIEW && BuildConfig.FILMIQOO_API_BASE_URL == "https://api.filmiqo.com")
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("خوش برگشتی").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("iran-access-screen").assertDoesNotExist()
        compose.onNodeWithTag("legacy-preview-notice").assertIsDisplayed()
        compose.onNodeWithText("ایمیل یا نام کاربری").assertIsDisplayed()
        compose.onNodeWithText("ساخت حساب").performClick()
        compose.onNodeWithText("حسابت رو بساز").assertIsDisplayed()
        compose.onNodeWithText("ورود").performClick()
        compose.onNodeWithText("خوش برگشتی").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3000)
        ensureNoSystemErrorDialog()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("preview-real-launch-login.png").use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
