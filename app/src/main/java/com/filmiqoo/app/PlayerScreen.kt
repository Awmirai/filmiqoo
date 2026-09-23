package com.filmiqoo.app

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FilmiqooPlayerScreen(
    target: PlaybackTarget,
    backend: BackendRepository,
    onBack: () -> Unit
) {
    val context=LocalContext.current
    val activity=context as? Activity
    val scope=rememberCoroutineScope()
    var playUrl by remember(target.mediaVersionId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var downloadQueued by remember { mutableStateOf(false) }

    val player=remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady=true
            repeatMode=Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(Unit) {
        val oldOrientation=activity?.requestedOrientation
        activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation=oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val position=player.currentPosition.coerceAtLeast(0)
            val duration=player.duration.coerceAtLeast(0)
            scope.launch { backend.saveProgress(target.mediaVersionId,position,duration) }
            player.release()
        }
    }

    LaunchedEffect(target.mediaVersionId) {
        loading=true
        runCatching { backend.playbackUrl(target.mediaVersionId) }
            .onSuccess { url ->
                playUrl=url
                player.setMediaItem(ExoMediaItem.fromUri(url))
                player.prepare()
                player.playWhenReady=true
            }
            .onFailure { error=it.message }
        loading=false
    }

    LaunchedEffect(player) {
        while(true) {
            delay(10_000)
            if(player.duration>0) {
                backend.saveProgress(target.mediaVersionId,player.currentPosition,player.duration)
            }
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if(playUrl!=null) {
            AndroidView(
                factory={ ctx ->
                    PlayerView(ctx).apply {
                        this.player=player
                        useController=true
                        controllerShowTimeoutMs=3500
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShowSubtitleButton(true)
                    }
                },
                update={it.player=player},
                modifier=Modifier.fillMaxSize()
            )
        }

        if(loading) {
            Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                CircularProgressIndicator(color=FqGold)
                Text("در حال آماده‌سازی پخش امن...",color=Color.White,fontSize=11.sp,modifier=Modifier.padding(top=10.dp))
            }
        }

        error?.let {
            Surface(
                color=Color(0xEE15171D),
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.align(Alignment.Center).padding(24.dp)
            ) {
                Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline,null,tint=FqDanger,modifier=Modifier.size(36.dp))
                    Text("پخش شروع نشد",fontSize=17.sp,modifier=Modifier.padding(top=8.dp))
                    Text(it,color=FqMuted,fontSize=10.sp,modifier=Modifier.padding(top=7.dp))
                    Button(onClick=onBack,colors=ButtonDefaults.buttonColors(containerColor=FqGold),modifier=Modifier.padding(top=12.dp)) {
                        Text("بازگشت")
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            FilledTonalIconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(target.title,color=Color.White,fontSize=13.sp)
                if(target.subtitle.isNotBlank()) Text(target.subtitle,color=Color.White.copy(alpha=.65f),fontSize=9.sp)
            }
            FilledTonalIconButton(
                onClick={
                    scope.launch {
                        runCatching { backend.enqueueDownload(context,target) }
                            .onSuccess { downloadQueued=true }
                            .onFailure { error=it.message }
                    }
                }
            ) {
                Icon(if(downloadQueued)Icons.Default.DownloadDone else Icons.Default.Download,null)
            }
        }

        if(downloadQueued) {
            Snackbar(
                modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) { Text("دانلود به صف دستگاه اضافه شد") }
        }
    }
}
