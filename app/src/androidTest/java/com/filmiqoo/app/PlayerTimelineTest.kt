package com.filmiqoo.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerTimelineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun persianPlaybackKeepsPhysicalTimeDirectionLeftToRight() {
        val fraction = mutableFloatStateOf(.5f)
        var jump = 0
        var seekStarts = 0
        var seekFinishes = 0
        compose.setContent {
            FilmiqooTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        PlayerCenterControlsV2(false, { jump = -10 }, {}, { jump = 10 },
                            Modifier.fillMaxWidth().height(180.dp))
                        PlayerBottomControlsV2(
                            positionMs=(fraction.floatValue * 100_000).toLong(),
                            durationMs=100_000, fraction=fraction.floatValue, speed=1f,
                            hotMoments=emptyList(), onHotMoment={},
                            onSeekStart={ seekStarts++ },
                            onFractionChanged={ fraction.floatValue=it },
                            onSeekFinished={ seekFinishes++ },
                            onSpeed={}, onCaptions={}, onMore={}
                        )
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("۱۰ ثانیه عقب")
        val forward = compose.onNodeWithContentDescription("۱۰ ثانیه جلو")
        assertTrue("Back must be physically left of forward in Persian UI",
            back.fetchSemanticsNode().boundsInRoot.center.x < forward.fetchSemanticsNode().boundsInRoot.center.x)
        back.performClick()
        compose.runOnIdle { assertEquals(-10, jump) }
        forward.performClick()
        compose.runOnIdle { assertEquals(10, jump) }
        val timeline = compose.onNodeWithContentDescription("زمان پخش")
        timeline.performTouchInput { click(Offset(width * .2f, height / 2f)) }
        compose.runOnIdle { assertTrue("Left side must seek toward the start", fraction.floatValue < .3f) }
        timeline.performTouchInput { click(Offset(width * .8f, height / 2f)) }
        compose.runOnIdle {
            assertTrue("Right side must seek toward the end", fraction.floatValue > .7f)
            assertTrue(seekStarts >= 2)
            assertEquals(2, seekFinishes)
        }
        ensureNoSystemErrorDialog()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("player-rtl-timeline.png").use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
