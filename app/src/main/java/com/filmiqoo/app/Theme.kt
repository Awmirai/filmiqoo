package com.filmiqoo.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val FqBg = Color(0xFF070708)
val FqSurface = Color(0xFF111113)
val FqSurface2 = Color(0xFF18181B)
val FqSurface3 = Color(0xFF202024)
val FqSurface4 = Color(0xFF29292E)
val FqGold = Color(0xFFE50914)
val FqGoldSoft = Color(0xFFFF5660)
val FqText = Color(0xFFF7F8FA)
val FqMuted = Color(0xFFADB5C2)
val FqMutedStrong = Color(0xFFC8CDD5)
val FqDanger = Color(0xFFFF4757)
val FqGreen = Color(0xFF47D18C)
val FqBlue = Color(0xFF5CA8FF)
val FqPurple = Color(0xFFA98BFF)

@Composable
fun FilmiqooTheme(content:@Composable ()->Unit) {
    val family=FontFamily.SansSerif

    val typography=remember {
        Typography(
            displayLarge=TextStyle(
                fontFamily=family,
                fontSize=42.sp,
                lineHeight=48.sp,
                fontWeight=FontWeight.Black
            ),
            displayMedium=TextStyle(
                fontFamily=family,
                fontSize=34.sp,
                lineHeight=40.sp,
                fontWeight=FontWeight.Black
            ),
            displaySmall=TextStyle(
                fontFamily=family,
                fontSize=28.sp,
                lineHeight=34.sp,
                fontWeight=FontWeight.Black
            ),
            headlineLarge=TextStyle(
                fontFamily=family,
                fontSize=26.sp,
                lineHeight=32.sp,
                fontWeight=FontWeight.Bold
            ),
            headlineMedium=TextStyle(
                fontFamily=family,
                fontSize=22.sp,
                lineHeight=28.sp,
                fontWeight=FontWeight.Bold
            ),
            headlineSmall=TextStyle(
                fontFamily=family,
                fontSize=19.sp,
                lineHeight=25.sp,
                fontWeight=FontWeight.Bold
            ),
            titleLarge=TextStyle(
                fontFamily=family,
                fontSize=18.sp,
                lineHeight=24.sp,
                fontWeight=FontWeight.Bold
            ),
            titleMedium=TextStyle(
                fontFamily=family,
                fontSize=16.sp,
                lineHeight=22.sp,
                fontWeight=FontWeight.SemiBold
            ),
            titleSmall=TextStyle(
                fontFamily=family,
                fontSize=14.sp,
                lineHeight=20.sp,
                fontWeight=FontWeight.SemiBold
            ),
            bodyLarge=TextStyle(
                fontFamily=family,
                fontSize=16.sp,
                lineHeight=24.sp,
                fontWeight=FontWeight.Normal
            ),
            bodyMedium=TextStyle(
                fontFamily=family,
                fontSize=14.sp,
                lineHeight=21.sp,
                fontWeight=FontWeight.Normal
            ),
            bodySmall=TextStyle(
                fontFamily=family,
                fontSize=12.sp,
                lineHeight=18.sp,
                fontWeight=FontWeight.Normal
            ),
            labelLarge=TextStyle(
                fontFamily=family,
                fontSize=14.sp,
                lineHeight=20.sp,
                fontWeight=FontWeight.Bold
            ),
            labelMedium=TextStyle(
                fontFamily=family,
                fontSize=12.sp,
                lineHeight=18.sp,
                fontWeight=FontWeight.SemiBold
            ),
            labelSmall=TextStyle(
                fontFamily=family,
                fontSize=11.sp,
                lineHeight=16.sp,
                fontWeight=FontWeight.SemiBold
            )
        )
    }

    val colors=darkColorScheme(
        primary=FqGold,
        onPrimary=Color.White,
        primaryContainer=Color(0xFF4A070B),
        onPrimaryContainer=FqGoldSoft,
        secondary=FqBlue,
        onSecondary=Color(0xFF001D35),
        tertiary=FqPurple,
        background=FqBg,
        onBackground=FqText,
        surface=FqSurface,
        onSurface=FqText,
        surfaceVariant=FqSurface2,
        onSurfaceVariant=FqMutedStrong,
        outline=Color.White.copy(alpha=.12f),
        outlineVariant=Color.White.copy(alpha=.06f),
        error=FqDanger,
        onError=Color.White
    )

    val shapes=remember {
        Shapes(
            extraSmall=androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium=androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge=androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme=colors,
            typography=typography,
            shapes=shapes,
            content=content
        )
    }
}
