package com.filmiqoo.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

val FqBg = Color(0xFF06070A)
val FqSurface = Color(0xFF0F1218)
val FqSurface2 = Color(0xFF171C25)
val FqSurface3 = Color(0xFF222A36)
val FqGold = Color(0xFFFFBE1A)
val FqGoldSoft = Color(0xFFFFD76D)
val FqText = Color(0xFFF7F7F8)
val FqMuted = Color(0xFFA7ADB8)
val FqDanger = Color(0xFFFF5263)
val FqGreen = Color(0xFF4CD57B)

@Composable
fun FilmiqooTheme(content: @Composable () -> Unit) {
    val family = FontFamily.SansSerif

    val typography = remember {
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
            bodyLarge = TextStyle(fontFamily=family,fontSize=16.sp,fontWeight=FontWeight.Medium),
            bodyMedium = TextStyle(fontFamily=family,fontSize=14.sp,fontWeight=FontWeight.Medium),
            bodySmall = TextStyle(fontFamily=family,fontSize=12.sp,fontWeight=FontWeight.Medium),
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

    val shapes = remember {
        Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
