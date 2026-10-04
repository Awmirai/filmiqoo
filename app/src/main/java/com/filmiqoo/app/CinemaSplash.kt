package com.filmiqoo.app

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** One short brand introduction per activity session, retained across rotation. */
@Composable
internal fun CinemaSplash(content: @Composable () -> Unit) {
    var finished by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val reduced = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val entrance = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!finished) {
            if (!reduced) entrance.animateTo(1f, tween(850))
            delay(if (reduced) 250 else 850)
            finished = true
        }
    }
    if (finished) content() else Box(Modifier.fillMaxSize().testTag("cinema-splash")
        .background(Brush.radialGradient(listOf(CinemaAccent.copy(alpha = .16f), CinemaInk))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
            alpha = entrance.value; scaleX = .8f + .2f * entrance.value; scaleY = scaleX
            translationY = (1f - entrance.value) * 30f
        }) {
            FilmiqooBrandMark(104.dp)
            Text("FILMIQOO", fontSize = 34.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Black, color = CinemaPaper, modifier = Modifier.padding(top = 24.dp))
            Text("قصه‌ها ما را به هم می‌رسانند", fontSize = 14.sp, color = CinemaSoft, modifier = Modifier.padding(top = 12.dp))
        }
        Text("سینمای تو. آدم‌های تو.", color = CinemaSoft, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp))
    }
}
