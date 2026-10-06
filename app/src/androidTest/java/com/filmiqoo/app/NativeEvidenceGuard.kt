package com.filmiqoo.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNull
import java.util.regex.Pattern

/** Semantics underneath a system error dialog are not usable native UI evidence. */
internal fun ensureNoSystemErrorDialog() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    assertNull("A system crash or ANR dialog covers the application",
        device.findObject(By.res(Pattern.compile("android:id/aerr_(close|wait|restart)"))))
}
