package com.filmiqoo.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualPlayerCanOpenReportBadMediaAndCloseWithoutKillingApp() {
        compose.activity.setTheme(R.style.Theme_Filmiqoo)
        val backend = BackendRepository(compose.activity)
        val target = PlaybackTarget(mediaVersionId = "player-regression", title = "Player regression",
            localUri = "file:///this-file-does-not-exist.mp4")
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            FilmiqooTheme {
                if (open) FilmiqooPlayerScreen(target, backend, onBack = { open = false })
                else Text("Player closed safely")
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("تلاش دوباره").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("تلاش دوباره").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3000)
        ensureNoSystemErrorDialog()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("player-error-recovery.png").use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithText("بازگشت").performClick()
        compose.onNodeWithText("Player closed safely").assertIsDisplayed()
    }
}
