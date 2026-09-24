package com.filmiqoo.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.Rational
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private data class PlayerTrackChoice(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
)

private enum class PlayerSettingsTab { QUALITY, AUDIO, SUBTITLE, DISPLAY, SPEED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmiqooPlayerScreen(
    target: PlaybackTarget,
    backend: BackendRepository,
    onBack: () -> Unit
) {
    val context=LocalContext.current
    val activity=context as? Activity
    val audioManager=remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val scope=rememberCoroutineScope()
    val initialSettings=remember { AppPreferences(context.applicationContext).read() }

    var currentTarget by remember(target.mediaVersionId) { mutableStateOf(target) }
    var currentVersionId by remember(target.mediaVersionId) { mutableStateOf(target.mediaVersionId) }
    var selectedVariantId by remember(target.mediaVersionId) { mutableStateOf(target.mediaVersionId) }

    var playUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsEpoch by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsTab by remember { mutableStateOf(PlayerSettingsTab.QUALITY) }
    var playbackSpeed by remember { mutableFloatStateOf(initialSettings.defaultPlaybackSpeed) }
    var subtitleScale by remember { mutableFloatStateOf(initialSettings.subtitleScale) }
    var subtitleBottomPadding by remember { mutableFloatStateOf(initialSettings.subtitleBottomPadding) }
    var playerResizeMode by remember { mutableStateOf(initialSettings.playerResizeMode) }
    var trackRevision by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }
    var downloadQueued by remember { mutableStateOf(false) }
    var autoPlayNext by remember { mutableStateOf(initialSettings.autoplayNext) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureValue by remember { mutableFloatStateOf(0f) }

    val player=remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                playWhenReady=true
                repeatMode=Player.REPEAT_MODE_OFF
            }
    }

    fun bumpControls() {
        controlsVisible=true
        controlsEpoch++
    }

    suspend fun loadVersion(
        versionId: String,
        startPosition: Long,
        preserveTarget: Boolean = true
    ) {
        loading=true
        error=null
        ended=false

        val oldDuration=player.duration.coerceAtLeast(0L)
        val oldPosition=player.currentPosition.coerceAtLeast(0L)
        if(currentVersionId.isNotBlank() && oldPosition>0L) {
            runCatching {
                backend.saveProgress(currentVersionId,oldPosition,oldDuration)
            }
        }

        runCatching { backend.playbackUrl(versionId) }
            .onSuccess { url ->
                playUrl=url
                currentVersionId=versionId
                selectedVariantId=versionId
                player.setMediaItem(ExoMediaItem.fromUri(url))
                if(startPosition>0) player.seekTo(startPosition)
                player.prepare()
                player.playbackParameters=player.playbackParameters.withSpeed(playbackSpeed)
                player.playWhenReady=true
                if(!preserveTarget) {
                    currentTarget=currentTarget.copy(mediaVersionId=versionId)
                }
            }
            .onFailure {
                error=it.message ?: "خطا در دریافت لینک پخش"
            }
        loading=false
        bumpControls()
    }

    fun playNext() {
        val nextId=currentTarget.nextMediaVersionId ?: return
        scope.launch {
            val fallback=PlaybackTarget(
                mediaVersionId=nextId,
                title=currentTarget.nextTitle ?: "قسمت بعدی",
                subtitle=currentTarget.nextSubtitle.orEmpty(),
                posterUrl=currentTarget.posterUrl
            )
            currentTarget=runCatching {
                backend.playbackContext(nextId)
            }.getOrDefault(fallback)
        }
    }

    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying=value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering=playbackState==Player.STATE_BUFFERING
                ended=playbackState==Player.STATE_ENDED
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackRevision++
            }

            override fun onPlayerError(playerError: PlaybackException) {
                error=playerError.localizedMessage ?: "خطای پخش"
                buffering=false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(Unit) {
        val oldOrientation=activity?.requestedOrientation
        val oldUi=activity?.window?.decorView?.systemUiVisibility
        activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR
        activity?.window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        onDispose {
            val position=player.currentPosition.coerceAtLeast(0L)
            val duration=player.duration.coerceAtLeast(0L)
            scope.launch {
                runCatching {
                    backend.saveProgress(currentVersionId,position,duration)
                }
            }
            activity?.requestedOrientation=
                oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if(oldUi!=null) activity?.window?.decorView?.systemUiVisibility=oldUi
            player.release()
        }
    }

    LaunchedEffect(currentTarget.mediaVersionId,currentTarget.localUri) {
        currentVersionId=currentTarget.mediaVersionId
        selectedVariantId=currentTarget.mediaVersionId

        val local=currentTarget.localUri
        if(!local.isNullOrBlank()) {
            loading=true
            error=null
            ended=false
            playUrl=local
            player.setMediaItem(ExoMediaItem.fromUri(local))
            if(currentTarget.startPositionMs>0) player.seekTo(currentTarget.startPositionMs)
            player.prepare()
            player.playbackParameters=player.playbackParameters.withSpeed(playbackSpeed)
            player.playWhenReady=true
            loading=false
            bumpControls()
        } else {
            val requestedStart=currentTarget.startPositionMs
            val enriched=runCatching {
                backend.playbackContext(currentTarget.mediaVersionId)
            }.getOrNull()
            if(enriched!=null) {
                currentTarget=enriched.copy(startPositionMs=requestedStart)
            }
            loadVersion(
                versionId=currentTarget.mediaVersionId,
                startPosition=requestedStart
            )
        }
    }

    LaunchedEffect(player) {
        while(true) {
            delay(500)
            positionMs=player.currentPosition.coerceAtLeast(0L)
            durationMs=player.duration.coerceAtLeast(0L)
            if(!isScrubbing && durationMs>0) {
                seekFraction=(positionMs.toFloat()/durationMs.toFloat()).coerceIn(0f,1f)
            }
            if(initialSettings.skipRecap) {
                currentTarget.recapEndMs?.let { end ->
                    if(positionMs in 1 until end) player.seekTo(end)
                }
            }
            if(initialSettings.skipIntro) {
                currentTarget.introEndMs?.let { end ->
                    if(positionMs in 1 until end) player.seekTo(end)
                }
            }
        }
    }

    LaunchedEffect(currentVersionId) {
        while(true) {
            delay(10_000)
            val duration=player.duration.coerceAtLeast(0L)
            if(duration>0) {
                runCatching {
                    backend.saveProgress(
                        currentVersionId,
                        player.currentPosition.coerceAtLeast(0L),
                        duration
                    )
                }
            }
        }
    }

    LaunchedEffect(controlsVisible,isPlaying,controlsEpoch,locked) {
        if(controlsVisible && isPlaying && !locked) {
            delay(3_500)
            controlsVisible=false
        }
    }

    val creditsReached=currentTarget.creditsStartMs?.let { positionMs>=it } == true

    LaunchedEffect(ended,creditsReached,currentTarget.nextMediaVersionId,autoPlayNext,initialSettings.skipCredits) {
        val canAdvance=currentTarget.nextMediaVersionId!=null &&
            (ended || (creditsReached && initialSettings.skipCredits))
        if(canAdvance && autoPlayNext) {
            delay(if(ended)7_000 else 4_000)
            val stillEligible=ended ||
                (currentTarget.creditsStartMs?.let { player.currentPosition>=it } == true)
            if(stillEligible) playNext()
        }
    }

    BackHandler {
        when {
            settingsOpen -> settingsOpen=false
            locked -> {
                locked=false
                bumpControls()
            }
            else -> onBack()
        }
    }

    val audioTracks=remember(trackRevision) {
        playerTrackChoices(player,C.TRACK_TYPE_AUDIO)
    }
    val subtitleTracks=remember(trackRevision) {
        playerTrackChoices(player,C.TRACK_TYPE_TEXT)
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .pointerInput(locked,currentVersionId) {
                detectTapGestures(
                    onTap={
                        if(locked) {
                            controlsVisible=true
                            controlsEpoch++
                        } else {
                            controlsVisible=!controlsVisible
                            controlsEpoch++
                        }
                    },
                    onDoubleTap={ offset ->
                        if(locked) return@detectTapGestures
                        val duration=player.duration.coerceAtLeast(0L)
                        val delta=if(offset.x < size.width/2f) -10_000L else 10_000L
                        val next=(player.currentPosition+delta)
                            .coerceIn(0L,if(duration>0)duration else Long.MAX_VALUE)
                        player.seekTo(next)
                        positionMs=next
                        bumpControls()
                    }
                )
            }
            .pointerInput(locked) {
                if(!locked) {
                    detectDragGestures(
                        onDragStart={},
                        onDragEnd={ gestureLabel=null },
                        onDragCancel={ gestureLabel=null },
                        onDrag={ change,dragAmount ->
                            if(kotlin.math.abs(dragAmount.y) <= kotlin.math.abs(dragAmount.x)) {
                                return@detectDragGestures
                            }
                            val delta=(-dragAmount.y/size.height.toFloat())*1.45f
                            if(change.position.x < size.width/2f) {
                                val window=activity?.window ?: return@detectDragGestures
                                val attrs=window.attributes
                                val current=if(attrs.screenBrightness<0f) .5f else attrs.screenBrightness
                                val next=(current+delta).coerceIn(.03f,1f)
                                attrs.screenBrightness=next
                                window.attributes=attrs
                                gestureLabel="روشنایی"
                                gestureValue=next
                            } else {
                                val max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    .coerceAtLeast(1)
                                val current=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    .toFloat()/max.toFloat()
                                val next=(current+delta).coerceIn(0f,1f)
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (next*max).roundToInt(),
                                    0
                                )
                                gestureLabel="صدا"
                                gestureValue=next
                            }
                        }
                    )
                }
            }
    ) {
        if(playUrl!=null) {
            AndroidView(
                factory={ ctx ->
                    PlayerView(ctx).apply {
                        this.player=player
                        useController=false
                        keepScreenOn=true
                        resizeMode=playerResizeModeValue(playerResizeMode)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        subtitleView?.setFractionalTextSize(.0533f*subtitleScale)
                        subtitleView?.setBottomPaddingFraction(subtitleBottomPadding)
                    }
                },
                update={
                    it.player=player
                    it.resizeMode=playerResizeModeValue(playerResizeMode)
                    it.subtitleView?.setFractionalTextSize(.0533f*subtitleScale)
                    it.subtitleView?.setBottomPaddingFraction(subtitleBottomPadding)
                },
                modifier=Modifier.fillMaxSize()
            )
        }

        if(loading || buffering) {
            PlayerBufferingOverlay(
                title=if(loading)"در حال آماده‌سازی پخش امن..." else "در حال بافر..."
            )
        }

        error?.let {
            PlayerErrorOverlay(
                message=it,
                onRetry={
                    scope.launch {
                        loadVersion(currentVersionId,player.currentPosition.coerceAtLeast(0L))
                    }
                },
                onBack=onBack
            )
        }

        gestureLabel?.let {
            GestureValueOverlay(
                label=it,
                value=gestureValue,
                modifier=Modifier.align(Alignment.Center)
            )
        }

        if(locked) {
            FilledTonalIconButton(
                onClick={
                    locked=false
                    bumpControls()
                },
                colors=IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor=Color.Black.copy(alpha=.56f)
                ),
                modifier=Modifier.align(Alignment.CenterStart).padding(16.dp)
            ) {
                Icon(Icons.Default.Lock,null,tint=Color.White)
            }
        } else if(controlsVisible && error==null) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha=.7f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha=.82f)
                        )
                    )
                )
            )

            PlayerTopControls(
                target=currentTarget,
                currentVariant=currentTarget.variants.firstOrNull {
                    it.mediaVersionId==selectedVariantId
                },
                downloadQueued=downloadQueued,
                onBack=onBack,
                onDownload={
                    scope.launch {
                        if(currentTarget.localUri!=null) {
                            downloadQueued=true
                            return@launch
                        }
                        runCatching {
                            backend.enqueueDownload(
                                context,
                                currentTarget.copy(mediaVersionId=currentVersionId)
                            )
                        }.onSuccess {
                            downloadQueued=true
                        }.onFailure {
                            error=it.message
                        }
                    }
                },
                onPip={
                    activity?.enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16,9))
                            .build()
                    )
                },
                onSettings={
                    settingsOpen=true
                    settingsTab=PlayerSettingsTab.QUALITY
                },
                onLock={
                    locked=true
                    controlsVisible=false
                }
            )

            PlayerCenterControls(
                isPlaying=isPlaying,
                onBack10={
                    val next=(player.currentPosition-10_000L).coerceAtLeast(0L)
                    player.seekTo(next)
                    bumpControls()
                },
                onPlayPause={
                    if(player.isPlaying) player.pause() else player.play()
                    bumpControls()
                },
                onForward10={
                    val duration=player.duration.coerceAtLeast(0L)
                    val next=(player.currentPosition+10_000L)
                        .coerceAtMost(if(duration>0)duration else Long.MAX_VALUE)
                    player.seekTo(next)
                    bumpControls()
                },
                modifier=Modifier.align(Alignment.Center)
            )

            PlayerBottomControls(
                positionMs=positionMs,
                durationMs=durationMs,
                fraction=seekFraction,
                speed=playbackSpeed,
                onSeekStart={
                    isScrubbing=true
                },
                onFractionChanged={
                    isScrubbing=true
                    seekFraction=it
                },
                onSeekFinished={
                    if(durationMs>0) {
                        player.seekTo((durationMs*seekFraction).toLong())
                    }
                    isScrubbing=false
                    bumpControls()
                },
                onSettings={
                    settingsOpen=true
                    settingsTab=PlayerSettingsTab.SPEED
                },
                modifier=Modifier.align(Alignment.BottomCenter)
            )

            currentTarget.recapEndMs?.let { end ->
                if(positionMs in 1 until end) {
                    PlayerSkipButton(
                        label="رد کردن مرور قبلی",
                        onClick={player.seekTo(end)},
                        modifier=Modifier.align(Alignment.BottomEnd).padding(end=18.dp,bottom=102.dp)
                    )
                }
            }

            currentTarget.introEndMs?.let { end ->
                if(positionMs in 1 until end) {
                    PlayerSkipButton(
                        label="رد کردن تیتراژ",
                        onClick={player.seekTo(end)},
                        modifier=Modifier.align(Alignment.BottomEnd).padding(end=18.dp,bottom=102.dp)
                    )
                }
            }
        }

        if((ended || creditsReached) && currentTarget.nextMediaVersionId!=null) {
            NextEpisodeOverlay(
                title=currentTarget.nextTitle ?: "قسمت بعدی",
                subtitle=currentTarget.nextSubtitle.orEmpty(),
                autoPlay=autoPlayNext,
                onAutoPlayChange={autoPlayNext=it},
                onPlayNow={playNext()},
                onDismiss={ended=false},
                modifier=Modifier.align(Alignment.BottomEnd).padding(18.dp)
            )
        }

        if(downloadQueued) {
            Snackbar(
                modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action={
                    TextButton(onClick={downloadQueued=false}) {
                        Text("باشه")
                    }
                }
            ) {
                Text("دانلود به صف اضافه شد")
            }
        }
    }

    if(settingsOpen) {
        PlayerSettingsSheet(
            tab=settingsTab,
            onTab={settingsTab=it},
            variants=currentTarget.variants.ifEmpty {
                listOf(
                    PlaybackVariant(
                        mediaVersionId=currentVersionId,
                        label=currentTarget.subtitle.substringAfterLast(" • ","Source")
                    )
                )
            },
            selectedVariantId=selectedVariantId,
            audioTracks=audioTracks,
            subtitleTracks=subtitleTracks,
            speed=playbackSpeed,
            subtitleScale=subtitleScale,
            subtitleBottomPadding=subtitleBottomPadding,
            resizeMode=playerResizeMode,
            autoPlayNext=autoPlayNext,
            onDismiss={settingsOpen=false},
            onVariant={ variant ->
                val position=player.currentPosition.coerceAtLeast(0L)
                scope.launch {
                    loadVersion(variant.mediaVersionId,position)
                }
            },
            onAudio={ choice ->
                applyTrackChoice(player,C.TRACK_TYPE_AUDIO,choice)
                trackRevision++
            },
            onSubtitle={ choice ->
                if(choice==null) {
                    player.trackSelectionParameters=
                        player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true)
                            .build()
                } else {
                    applyTrackChoice(player,C.TRACK_TYPE_TEXT,choice)
                }
                trackRevision++
            },
            onSpeed={ speed ->
                playbackSpeed=speed
                player.setPlaybackSpeed(speed)
            },
            onSubtitleScale={subtitleScale=it},
            onSubtitleBottomPadding={subtitleBottomPadding=it},
            onResizeMode={playerResizeMode=it},
            onAutoPlayNext={autoPlayNext=it}
        )
    }
}

@Composable
private fun PlayerBufferingOverlay(title: String) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha=.18f)),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.Center
    ) {
        CircularProgressIndicator(color=FqGold,strokeWidth=3.dp)
        Text(
            title,
            color=Color.White,
            fontSize=10.sp,
            modifier=Modifier.padding(top=10.dp)
        )
    }
}

@Composable
private fun PlayerErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        Surface(
            color=Color(0xF0181B22),
            shape=RoundedCornerShape(22.dp),
            modifier=Modifier.widthIn(max=420.dp).padding(24.dp)
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    null,
                    tint=FqDanger,
                    modifier=Modifier.size(42.dp)
                )
                Text(
                    "پخش متوقف شد",
                    fontSize=18.sp,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(top=9.dp)
                )
                Text(
                    message,
                    color=FqMuted,
                    fontSize=9.sp,
                    lineHeight=15.sp,
                    modifier=Modifier.padding(top=6.dp)
                )
                Row(Modifier.padding(top=14.dp)) {
                    OutlinedButton(onClick=onBack) { Text("بازگشت") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick=onRetry,
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                    ) {
                        Icon(Icons.Default.Refresh,null)
                        Spacer(Modifier.width(5.dp))
                        Text("تلاش دوباره")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTopControls(
    target: PlaybackTarget,
    currentVariant: PlaybackVariant?,
    downloadQueued: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onPip: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        PlayerGlassIcon(Icons.Default.ArrowBack,onBack)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                target.title,
                color=Color.White,
                fontSize=13.sp,
                fontWeight=FontWeight.Bold,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
            Text(
                listOf(
                    target.subtitle,
                    currentVariant?.label.orEmpty()
                ).filter(String::isNotBlank).distinct().joinToString(" • "),
                color=Color.White.copy(alpha=.62f),
                fontSize=8.sp,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
        }
        PlayerGlassIcon(
            if(downloadQueued)Icons.Default.DownloadDone else Icons.Default.Download,
            onDownload
        )
        Spacer(Modifier.width(5.dp))
        PlayerGlassIcon(Icons.Default.PictureInPictureAlt,onPip)
        Spacer(Modifier.width(5.dp))
        PlayerGlassIcon(Icons.Default.Settings,onSettings)
        Spacer(Modifier.width(5.dp))
        PlayerGlassIcon(Icons.Default.LockOpen,onLock)
    }
}

@Composable
private fun PlayerGlassIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha=.5f))
            .clickable { onClick() },
        contentAlignment=Alignment.Center
    ) {
        Icon(icon,null,tint=Color.White,modifier=Modifier.size(20.dp))
    }
}

@Composable
private fun PlayerCenterControls(
    isPlaying: Boolean,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    modifier: Modifier=Modifier
) {
    Row(
        modifier,
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(30.dp)
    ) {
        Column(
            horizontalAlignment=Alignment.CenterHorizontally,
            modifier=Modifier.clickable { onBack10() }
        ) {
            Icon(Icons.Default.Replay10,null,tint=Color.White,modifier=Modifier.size(34.dp))
            Text("10",color=Color.White.copy(alpha=.7f),fontSize=7.sp)
        }

        Box(
            Modifier.size(68.dp).clip(CircleShape).background(Color.White)
                .clickable { onPlayPause() },
            contentAlignment=Alignment.Center
        ) {
            Icon(
                if(isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,
                null,
                tint=Color.Black,
                modifier=Modifier.size(38.dp)
            )
        }

        Column(
            horizontalAlignment=Alignment.CenterHorizontally,
            modifier=Modifier.clickable { onForward10() }
        ) {
            Icon(Icons.Default.Forward10,null,tint=Color.White,modifier=Modifier.size(34.dp))
            Text("10",color=Color.White.copy(alpha=.7f),fontSize=7.sp)
        }
    }
}

@Composable
private fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    fraction: Float,
    speed: Float,
    onSeekStart: () -> Unit,
    onFractionChanged: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier=Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp)
    ) {
        Slider(
            value=fraction.coerceIn(0f,1f),
            onValueChange={
                onSeekStart()
                onFractionChanged(it)
            },
            onValueChangeFinished=onSeekFinished,
            colors=SliderDefaults.colors(
                thumbColor=FqGold,
                activeTrackColor=FqGold,
                inactiveTrackColor=Color.White.copy(alpha=.25f)
            ),
            modifier=Modifier.fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Text(
                formatPlayerTime(positionMs),
                color=Color.White,
                fontSize=8.sp
            )
            Text(
                " / "+formatPlayerTime(durationMs),
                color=Color.White.copy(alpha=.55f),
                fontSize=8.sp
            )
            Spacer(Modifier.weight(1f))
            Surface(
                color=Color.Black.copy(alpha=.5f),
                shape=RoundedCornerShape(9.dp),
                modifier=Modifier.clickable { onSettings() }
            ) {
                Text(
                    formatSpeed(speed),
                    color=Color.White,
                    fontSize=8.sp,
                    modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp)
                )
            }
        }
    }
}

@Composable
private fun GestureValueOverlay(
    label: String,
    value: Float,
    modifier: Modifier=Modifier
) {
    Surface(
        color=Color.Black.copy(alpha=.72f),
        shape=RoundedCornerShape(18.dp),
        modifier=modifier.width(190.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Icon(
                if(label=="صدا")Icons.Default.VolumeUp else Icons.Default.BrightnessHigh,
                null,
                tint=FqGold,
                modifier=Modifier.size(28.dp)
            )
            Text(
                label,
                color=Color.White,
                fontSize=10.sp,
                modifier=Modifier.padding(top=5.dp)
            )
            LinearProgressIndicator(
                progress={value.coerceIn(0f,1f)},
                color=FqGold,
                trackColor=Color.White.copy(alpha=.18f),
                modifier=Modifier.fillMaxWidth().padding(top=9.dp)
            )
            Text(
                (value*100).roundToInt().toString()+"٪",
                color=FqMuted,
                fontSize=8.sp,
                modifier=Modifier.padding(top=5.dp)
            )
        }
    }
}

@Composable
private fun PlayerSkipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier=Modifier
) {
    Button(
        onClick=onClick,
        colors=ButtonDefaults.buttonColors(
            containerColor=Color.Black.copy(alpha=.7f),
            contentColor=Color.White
        ),
        shape=RoundedCornerShape(12.dp),
        modifier=modifier
    ) {
        Text(label,fontSize=9.sp)
        Spacer(Modifier.width(5.dp))
        Icon(Icons.Default.FastForward,null,modifier=Modifier.size(17.dp))
    }
}

@Composable
private fun NextEpisodeOverlay(
    title: String,
    subtitle: String,
    autoPlay: Boolean,
    onAutoPlayChange: (Boolean) -> Unit,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier=Modifier
) {
    Surface(
        color=Color(0xF0161920),
        shape=RoundedCornerShape(20.dp),
        modifier=modifier.widthIn(max=360.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("قسمت بعدی",color=FqGold,fontSize=9.sp)
            Text(
                title,
                color=Color.White,
                fontSize=15.sp,
                fontWeight=FontWeight.Bold,
                maxLines=1,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=4.dp)
            )
            if(subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color=FqMuted,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=3.dp)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top=10.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Switch(
                    checked=autoPlay,
                    onCheckedChange=onAutoPlayChange
                )
                Spacer(Modifier.width(6.dp))
                Text("پخش خودکار",fontSize=8.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick=onDismiss) { Text("بستن") }
                Button(
                    onClick=onPlayNow,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                ) {
                    Text("الان پخش کن",color=Color.Black,fontSize=8.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    tab: PlayerSettingsTab,
    onTab: (PlayerSettingsTab) -> Unit,
    variants: List<PlaybackVariant>,
    selectedVariantId: String,
    audioTracks: List<PlayerTrackChoice>,
    subtitleTracks: List<PlayerTrackChoice>,
    speed: Float,
    subtitleScale: Float,
    subtitleBottomPadding: Float,
    resizeMode: String,
    autoPlayNext: Boolean,
    onDismiss: () -> Unit,
    onVariant: (PlaybackVariant) -> Unit,
    onAudio: (PlayerTrackChoice) -> Unit,
    onSubtitle: (PlayerTrackChoice?) -> Unit,
    onSpeed: (Float) -> Unit,
    onSubtitleScale: (Float) -> Unit,
    onSubtitleBottomPadding: (Float) -> Unit,
    onResizeMode: (String) -> Unit,
    onAutoPlayNext: (Boolean) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(bottom=26.dp)
        ) {
            Text(
                "تنظیمات پخش",
                fontSize=19.sp,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.padding(horizontal=18.dp)
            )

            ScrollableTabRow(
                selectedTabIndex=tab.ordinal,
                containerColor=FqSurface,
                contentColor=FqGold,
                edgePadding=10.dp,
                divider={},
                modifier=Modifier.padding(top=8.dp)
            ) {
                listOf(
                    PlayerSettingsTab.QUALITY to "کیفیت",
                    PlayerSettingsTab.AUDIO to "صدا",
                    PlayerSettingsTab.SUBTITLE to "زیرنویس",
                    PlayerSettingsTab.DISPLAY to "تصویر",
                    PlayerSettingsTab.SPEED to "سرعت"
                ).forEach { item ->
                    Tab(
                        selected=tab==item.first,
                        onClick={onTab(item.first)},
                        text={Text(item.second,fontSize=9.sp)}
                    )
                }
            }

            when(tab) {
                PlayerSettingsTab.QUALITY -> {
                    if(variants.isEmpty()) {
                        PlayerSettingsEmpty("نسخه دیگری برای این فایل موجود نیست.")
                    } else {
                        variants.forEach { variant ->
                            PlayerSettingsRow(
                                icon=Icons.Default.HighQuality,
                                title=variant.label,
                                subtitle=listOf(variant.codec,variant.hdr)
                                    .filter(String::isNotBlank).joinToString(" • "),
                                selected=variant.mediaVersionId==selectedVariantId,
                                onClick={onVariant(variant)}
                            )
                        }
                    }
                }

                PlayerSettingsTab.AUDIO -> {
                    if(audioTracks.isEmpty()) {
                        PlayerSettingsEmpty("Track صوتی قابل انتخاب دیگری شناسایی نشد.")
                    } else {
                        audioTracks.forEach { choice ->
                            PlayerSettingsRow(
                                icon=Icons.Default.SurroundSound,
                                title=choice.label,
                                selected=choice.selected,
                                onClick={onAudio(choice)}
                            )
                        }
                    }
                }

                PlayerSettingsTab.SUBTITLE -> {
                    PlayerSettingsRow(
                        icon=Icons.Default.SubtitlesOff,
                        title="خاموش",
                        selected=subtitleTracks.none { it.selected },
                        onClick={onSubtitle(null)}
                    )
                    subtitleTracks.forEach { choice ->
                        PlayerSettingsRow(
                            icon=Icons.Default.Subtitles,
                            title=choice.label,
                            selected=choice.selected,
                            onClick={onSubtitle(choice)}
                        )
                    }

                    HorizontalDivider(
                        color=FqSurface3,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=8.dp)
                    )

                    Text(
                        "اندازه زیرنویس • "+((subtitleScale*100).roundToInt()).toString()+"٪",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=4.dp)
                    )
                    Slider(
                        value=subtitleScale.coerceIn(.7f,1.6f),
                        onValueChange=onSubtitleScale,
                        valueRange=.7f..1.6f,
                        colors=SliderDefaults.colors(
                            thumbColor=FqGold,
                            activeTrackColor=FqGold
                        ),
                        modifier=Modifier.padding(horizontal=18.dp)
                    )

                    Text(
                        "موقعیت زیرنویس",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=4.dp)
                    )
                    Slider(
                        value=subtitleBottomPadding.coerceIn(.02f,.28f),
                        onValueChange=onSubtitleBottomPadding,
                        valueRange=.02f..0.28f,
                        colors=SliderDefaults.colors(
                            thumbColor=FqGold,
                            activeTrackColor=FqGold
                        ),
                        modifier=Modifier.padding(horizontal=18.dp)
                    )
                }

                PlayerSettingsTab.DISPLAY -> {
                    Text(
                        "نسبت تصویر",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=10.dp)
                    )
                    LazyRow(
                        contentPadding=PaddingValues(horizontal=18.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            listOf(
                                "fit" to "Fit",
                                "fill" to "Fill",
                                "zoom" to "Zoom"
                            )
                        ) { item ->
                            PremiumChip(
                                label=item.second,
                                active=resizeMode==item.first,
                                onClick={onResizeMode(item.first)}
                            )
                        }
                    }
                    Text(
                        when(resizeMode) {
                            "fill" -> "تصویر قاب را پر می‌کند و ممکن است نسبت اصلی تغییر کند."
                            "zoom" -> "تصویر بدون کشیدگی زوم می‌شود و ممکن است لبه‌ها برش بخورند."
                            else -> "کل تصویر با نسبت اصلی داخل قاب نمایش داده می‌شود."
                        },
                        color=FqMuted,
                        fontSize=8.sp,
                        lineHeight=14.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=14.dp)
                    )
                }

                PlayerSettingsTab.SPEED -> {
                    Text(
                        "سرعت پخش",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(start=18.dp,end=18.dp,top=14.dp)
                    )
                    LazyRow(
                        contentPadding=PaddingValues(horizontal=18.dp,vertical=10.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f)) { value ->
                            PremiumChip(
                                label=formatSpeed(value),
                                active=kotlin.math.abs(speed-value)<.01f,
                                onClick={onSpeed(value)}
                            )
                        }
                    }

                    HorizontalDivider(
                        color=FqSurface3,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=8.dp)
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("پخش خودکار قسمت بعد",fontSize=11.sp)
                            Text(
                                "بعد از پایان قسمت، قسمت بعدی خودکار شروع شود.",
                                color=FqMuted,
                                fontSize=8.sp,
                                modifier=Modifier.padding(top=3.dp)
                            )
                        }
                        Switch(
                            checked=autoPlayNext,
                            onCheckedChange=onAutoPlayNext
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String="",
    selected: Boolean=false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal=18.dp,vertical=11.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                .background(if(selected)FqGold.copy(alpha=.15f) else FqSurface2),
            contentAlignment=Alignment.Center
        ) {
            Icon(icon,null,tint=if(selected)FqGold else Color.White)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=11.sp,fontWeight=FontWeight.Bold)
            if(subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color=FqMuted,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
        }
        if(selected) {
            Icon(Icons.Default.CheckCircle,null,tint=FqGold)
        }
    }
}

@Composable
private fun PlayerSettingsEmpty(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Info,null,tint=FqMuted,modifier=Modifier.size(34.dp))
        Text(
            message,
            color=FqMuted,
            fontSize=9.sp,
            modifier=Modifier.padding(top=8.dp)
        )
    }
}

private fun playerTrackChoices(
    player: ExoPlayer,
    type: Int
): List<PlayerTrackChoice> {
    val groups=player.currentTracks.groups
    return buildList {
        groups.forEachIndexed { groupIndex,group ->
            if(group.type!=type) return@forEachIndexed
            for(trackIndex in 0 until group.length) {
                val format=group.getTrackFormat(trackIndex)
                val label=when(type) {
                    C.TRACK_TYPE_AUDIO -> {
                        listOf(
                            format.label.orEmpty(),
                            languageName(format.language),
                            if(format.channelCount>0) format.channelCount.toString()+"ch" else ""
                        ).filter(String::isNotBlank).distinct().joinToString(" • ")
                            .ifBlank { "صدای "+(trackIndex+1) }
                    }
                    C.TRACK_TYPE_TEXT -> {
                        listOf(
                            format.label.orEmpty(),
                            languageName(format.language)
                        ).filter(String::isNotBlank).distinct().joinToString(" • ")
                            .ifBlank { "زیرنویس "+(trackIndex+1) }
                    }
                    else -> "Track "+(trackIndex+1)
                }
                add(
                    PlayerTrackChoice(
                        groupIndex=groupIndex,
                        trackIndex=trackIndex,
                        label=label,
                        selected=group.isTrackSelected(trackIndex)
                    )
                )
            }
        }
    }
}

private fun applyTrackChoice(
    player: ExoPlayer,
    type: Int,
    choice: PlayerTrackChoice
) {
    val group=player.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
    player.trackSelectionParameters=
        player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type,false)
            .setOverrideForType(
                TrackSelectionOverride(
                    group.mediaTrackGroup,
                    choice.trackIndex
                )
            )
            .build()
}

private fun languageName(code: String?): String {
    if(code.isNullOrBlank() || code=="und") return ""
    return when(code.lowercase(Locale.ROOT)) {
        "fa","fas","per" -> "فارسی"
        "en","eng" -> "English"
        "de","deu","ger" -> "Deutsch"
        "ar","ara" -> "العربية"
        "tr","tur" -> "Türkçe"
        "ko","kor" -> "한국어"
        "ja","jpn" -> "日本語"
        "hi","hin" -> "हिन्दी"
        else -> code.uppercase(Locale.ROOT)
    }
}

private fun formatPlayerTime(ms: Long): String {
    if(ms<=0) return "00:00"
    val total=ms/1000
    val hours=total/3600
    val minutes=(total%3600)/60
    val seconds=total%60
    return if(hours>0) {
        String.format(Locale.US,"%02d:%02d:%02d",hours,minutes,seconds)
    } else {
        String.format(Locale.US,"%02d:%02d",minutes,seconds)
    }
}

private fun formatSpeed(value: Float): String =
    if(kotlin.math.abs(value-value.toInt())<.01f) {
        value.toInt().toString()+"x"
    } else {
        String.format(Locale.US,"%.2gx",value)
    }


private fun playerResizeModeValue(mode:String):Int=when(mode) {
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}
