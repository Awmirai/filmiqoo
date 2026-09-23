package com.filmiqoo.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

val FqBg = Color(0xFF07080B)
val FqSurface = Color(0xFF101319)
val FqSurface2 = Color(0xFF171B23)
val FqSurface3 = Color(0xFF202630)
val FqGold = Color(0xFFFFB800)
val FqGoldSoft = Color(0xFFFFCD57)
val FqText = Color(0xFFF7F7F8)
val FqMuted = Color(0xFFA7ADB8)
val FqDanger = Color(0xFFFF5263)
val FqGreen = Color(0xFF4CD57B)

@OptIn(ExperimentalTextApi::class)
@Composable
fun FilmiqooTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val family = remember {
        runCatching {
            FontFamily(
                Font(
                    path = "iransans.ttf",
                    assetManager = context.assets,
                    weight = FontWeight.Bold
                )
            )
        }.getOrElse { FontFamily.SansSerif }
    }

    val typography = remember(family) {
        Typography(
            displayLarge = TextStyle(fontFamily=family,fontSize=44.sp,fontWeight=FontWeight.Bold),
            displayMedium = TextStyle(fontFamily=family,fontSize=36.sp,fontWeight=FontWeight.Bold),
            displaySmall = TextStyle(fontFamily=family,fontSize=30.sp,fontWeight=FontWeight.Bold),
            headlineLarge = TextStyle(fontFamily=family,fontSize=28.sp,fontWeight=FontWeight.Bold),
            headlineMedium = TextStyle(fontFamily=family,fontSize=24.sp,fontWeight=FontWeight.Bold),
            headlineSmall = TextStyle(fontFamily=family,fontSize=21.sp,fontWeight=FontWeight.Bold),
            titleLarge = TextStyle(fontFamily=family,fontSize=20.sp,fontWeight=FontWeight.Bold),
            titleMedium = TextStyle(fontFamily=family,fontSize=16.sp,fontWeight=FontWeight.Bold),
            titleSmall = TextStyle(fontFamily=family,fontSize=14.sp,fontWeight=FontWeight.Bold),
            bodyLarge = TextStyle(fontFamily=family,fontSize=16.sp,fontWeight=FontWeight.Bold),
            bodyMedium = TextStyle(fontFamily=family,fontSize=14.sp,fontWeight=FontWeight.Bold),
            bodySmall = TextStyle(fontFamily=family,fontSize=12.sp,fontWeight=FontWeight.Bold),
            labelLarge = TextStyle(fontFamily=family,fontSize=14.sp,fontWeight=FontWeight.Bold),
            labelMedium = TextStyle(fontFamily=family,fontSize=12.sp,fontWeight=FontWeight.Bold),
            labelSmall = TextStyle(fontFamily=family,fontSize=10.sp,fontWeight=FontWeight.Bold)
        )
    }

    val colors = darkColorScheme(
        primary = FqGold,
        onPrimary = Color.Black,
        background = FqBg,
        onBackground = FqText,
        surface = FqSurface,
        onSurface = FqText,
        surfaceVariant = FqSurface2,
        onSurfaceVariant = FqMuted,
        secondary = FqGoldSoft,
        error = FqDanger
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            content = content
        )
    }
}
