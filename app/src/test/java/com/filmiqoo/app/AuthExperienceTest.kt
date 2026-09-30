package com.filmiqoo.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="fa-rIR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthExperienceTest {
    @get:Rule val compose=createComposeRule()

    private fun render(state:AuthUiState,scale:Float=1f) {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                FilmiqooTheme { AuthExperience(state,{}, {}, {}, {}) }
            }
        }
    }
    private fun capture(name:String) {
        compose.onRoot().captureRoboImage("build/outputs/ui-review/$name.png")
    }
    @Test fun loginPhone() {
        render(AuthUiState())
        compose.onNodeWithText("رمز عبور را فراموش کرده‌ام").assertIsDisplayed()
        compose.onNodeWithText("ورود به فیلمیکو").assertIsNotEnabled()
        capture("login-phone")
    }
    @Test fun recoveryFlowCanBeReachedWithoutSigningIn() {
        compose.setContent {
            var state by remember { mutableStateOf(AuthUiState()) }
            FilmiqooTheme { AuthExperience(state,{state=it},{state=state.copy(step=it)}, {}, {}) }
        }
        compose.onNodeWithText("رمز عبور را فراموش کرده‌ام").performClick()
        compose.onNodeWithText("ایمیل حساب").performTextInput("viewer@example.com")
        compose.onNodeWithText("دریافت کد بازیابی").assertIsEnabled()
        capture("recovery-phone")
    }
    @Test fun registrationScrollsToSubmit() {
        render(AuthUiState(step=AuthStep.REGISTER))
        compose.onNodeWithText("تکرار رمز عبور").performScrollTo().assertIsDisplayed()
        capture("register-phone")
    }
    @Test fun resetPhone() {
        render(AuthUiState(step=AuthStep.RESET,email="viewer@example.com",resendSeconds=48,notice="کد تا ۱۵ دقیقه معتبر است."))
        compose.onNodeWithText("تغییر ایمیل").performScrollTo().assertIsDisplayed()
        capture("reset-phone")
    }
    @Test @Config(qualifiers="fa-rIR-w320dp-h640dp-xhdpi") fun smallPhoneLargeText() {
        render(AuthUiState(step=AuthStep.RESET,email="viewer@example.com",error="کد بازیابی نامعتبر یا منقضی شده است."),1.5f)
        compose.onNodeWithText("ثبت رمز جدید").performScrollTo().assertIsDisplayed()
        capture("reset-large-text")
    }
    @Test @Config(qualifiers="fa-rIR-w1000dp-h800dp-mdpi") fun loginTablet() {
        render(AuthUiState());capture("login-tablet")
    }
    @Test fun successRequiresFreshLogin() {
        render(AuthUiState(step=AuthStep.COMPLETE))
        compose.onNodeWithText("ورود با رمز جدید").assertIsEnabled();capture("recovery-complete")
    }
    @Test fun validationAcceptsPersianDigitsAndRejectsMismatchedPasswords() {
        assertEquals("01234567",normalizeAuthCode("۰١۲٣۴٥۶٧89"))
        assertFalse(AuthUiState(step=AuthStep.RESET,email="viewer@example.com",code="12345678",password="secure-password",confirmation="different-password").canSubmit)
        assertFalse(validAuthPassword("ک".repeat(65)))
        assertFalse(validAuthEmail("bad\n@example.com"))
        assertTrue(AuthUiState(step=AuthStep.RESET,email="viewer@example.com",code="12345678",password="secure-password",confirmation="secure-password").canSubmit)
    }
}
