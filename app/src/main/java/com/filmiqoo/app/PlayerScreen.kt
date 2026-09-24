package com.filmiqoo.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

private enum class PlayerSettingsTab { QUALITY, AUDIO, SUBTITLE, DISPLAY, SPEED, TIMER, ADVANCED }

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
    val settingsRepository=remember { SettingsRepository(context.applicationContext,backend) }
    val activeViewer=remember { backend.viewerProfiles.active() }
    val preferredAudioLanguage=activeViewer?.preferredAudioLanguage
        ?.takeIf(String::isNotBlank)
        ?: initialSettings.defaultAudioLanguage
    val preferredSubtitleLanguage=activeViewer?.preferredSubtitleLanguage
        ?.takeIf(String::isNotBlank)
        ?: initialSettings.defaultSubtitleLanguage

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
    var momentsOpen by remember { mutableStateOf(false) }
    var dialogueSearchOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var settingsTab by remember { mutableStateOf(PlayerSettingsTab.QUALITY) }
    var playbackSpeed by remember { mutableFloatStateOf(initialSettings.defaultPlaybackSpeed) }
    var subtitleScale by remember { mutableFloatStateOf(initialSettings.subtitleScale) }
    var subtitleBottomPadding by remember { mutableFloatStateOf(initialSettings.subtitleBottomPadding) }
    var subtitleTextColor by remember { mutableStateOf(initialSettings.subtitleTextColor) }
    var subtitleBackgroundOpacity by remember { mutableFloatStateOf(initialSettings.subtitleBackgroundOpacity) }
    var subtitleEdgeStyle by remember { mutableStateOf(initialSettings.subtitleEdgeStyle) }
    var playerResizeMode by remember { mutableStateOf(initialSettings.playerResizeMode) }
    var trackRevision by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }
    var downloadQueued by remember { mutableStateOf(false) }
    var autoPlayNext by remember {
        mutableStateOf(activeViewer?.autoplayNext ?: initialSettings.autoplayNext)
    }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var seekGestureActive by remember { mutableStateOf(false) }
    var seekGestureStartMs by remember { mutableLongStateOf(0L) }
    var seekGestureTargetMs by remember { mutableLongStateOf(0L) }
    var sleepTimerEndsAt by remember { mutableStateOf<Long?>(null) }
    var sleepAtEpisodeEnd by remember { mutableStateOf(false) }
    var sleepTimerMessage by remember { mutableStateOf<String?>(null) }
    var dataSaverApplied by remember { mutableStateOf(false) }
    var resumePromptPositionMs by remember { mutableStateOf<Long?>(null) }
    var resumePromptDurationMs by remember { mutableLongStateOf(0L) }
    var pendingResumeVersionId by remember { mutableStateOf<String?>(null) }
    var abStartMs by remember { mutableStateOf<Long?>(null) }
    var abEndMs by remember { mutableStateOf<Long?>(null) }
    var diagnosticsEnabled by remember { mutableStateOf(false) }
    var orientationMode by remember { mutableStateOf("auto") }
    var playerSettingsMessage by remember { mutableStateOf<String?>(null) }
    var playbackSessionId by remember { mutableStateOf<String?>(null) }
    var telemetryBufferStartedAt by remember { mutableLongStateOf(0L) }
    var telemetryBufferCountPending by remember { mutableIntStateOf(0) }
    var telemetryBufferMsPending by remember { mutableLongStateOf(0L) }
    var telemetryQualitySwitchPending by remember { mutableIntStateOf(0) }
    var telemetryLastHeartbeatAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var recoveryAttempts by remember { mutableIntStateOf(0) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }

    val player=remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                playWhenReady=true
                repeatMode=Player.REPEAT_MODE_OFF
                trackSelectionParameters=trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(preferredAudioLanguage)
                    .setPreferredTextLanguage(preferredSubtitleLanguage)
                    .setTrackTypeDisabled(
                        C.TRACK_TYPE_TEXT,
                        !initialSettings.subtitlesEnabled
                    )
                    .build()
            }
    }

    val mediaSession=remember(player) {
        MediaSession.Builder(context,player)
            .setId("filmiqoo-player")
            .build()
    }

    DisposableEffect(mediaSession) {
        onDispose { mediaSession.release() }
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
                player.setMediaItem(playerMediaItem(url,currentTarget))
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

    fun playPrevious() {
        val previousId=currentTarget.previousMediaVersionId ?: return
        scope.launch {
            val fallback=PlaybackTarget(
                mediaVersionId=previousId,
                title=currentTarget.previousTitle ?: "قسمت قبلی",
                subtitle=currentTarget.previousSubtitle.orEmpty(),
                posterUrl=currentTarget.posterUrl
            )
            currentTarget=runCatching {
                backend.playbackContext(previousId)
            }.getOrDefault(fallback)
        }
    }

    fun playQueueItem(item:PlaybackQueueItem) {
        scope.launch {
            val fallback=PlaybackTarget(
                mediaVersionId=item.mediaVersionId,
                title=item.title,
                subtitle=item.subtitle,
                posterUrl=item.posterUrl ?: currentTarget.posterUrl
            )
            currentTarget=runCatching {
                backend.playbackContext(item.mediaVersionId)
            }.getOrDefault(fallback)
            queueOpen=false
        }
    }

    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying=value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val now=SystemClock.elapsedRealtime()
                if(playbackState==Player.STATE_BUFFERING) {
                    if(telemetryBufferStartedAt==0L) {
                        telemetryBufferStartedAt=now
                        telemetryBufferCountPending++
                    }
                } else if(telemetryBufferStartedAt>0L) {
                    telemetryBufferMsPending += (now-telemetryBufferStartedAt).coerceAtLeast(0L)
                    telemetryBufferStartedAt=0L
                }

                buffering=playbackState==Player.STATE_BUFFERING
                ended=playbackState==Player.STATE_ENDED
                if(
                    playbackState==Player.STATE_ENDED &&
                    !currentTarget.localUri.isNullOrBlank()
                ) {
                    scope.launch {
                        OfflineDownloadManager.consumeCompleted(
                            context,
                            currentVersionId
                        )
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackRevision++
            }

            override fun onPlayerError(playerError: PlaybackException) {
                buffering=false
                telemetryBufferStartedAt=0L

                val canRecover=currentTarget.localUri.isNullOrBlank() && recoveryAttempts<2
                if(canRecover) {
                    val position=player.currentPosition.coerceAtLeast(0L)
                    val attempt=recoveryAttempts+1
                    recoveryAttempts=attempt
                    error=null

                    scope.launch {
                        if(attempt==1) {
                            recoveryMessage="اتصال پخش قطع شد • تلاش دوباره از "+formatPlayerTime(position)
                            delay(900)
                            loadVersion(currentVersionId,position)
                        } else {
                            val fallback=lowerQualityVariant(
                                currentTarget.variants,
                                currentVersionId
                            )
                            if(fallback!=null) {
                                telemetryQualitySwitchPending++
                                recoveryMessage="Recovery • تغییر خودکار به "+fallback.label
                                delay(1200)
                                loadVersion(fallback.mediaVersionId,position)
                            } else {
                                recoveryMessage="Recovery • تلاش دوباره با منبع فعلی"
                                delay(1200)
                                loadVersion(currentVersionId,position)
                            }
                        }
                    }
                    return
                }

                error=playerError.localizedMessage ?: "خطای پخش"
                val sid=playbackSessionId
                if(sid!=null) {
                    val now=SystemClock.elapsedRealtime()
                    val watched=if(player.isPlaying)
                        (now-telemetryLastHeartbeatAt).coerceIn(0L,30_000L)
                    else 0L
                    val bufferCount=telemetryBufferCountPending
                    val bufferMs=telemetryBufferMsPending
                    val switches=telemetryQualitySwitchPending
                    playbackSessionId=null
                    scope.launch {
                        backend.endPlaybackSession(
                            sessionId=sid,
                            currentMediaVersionId=currentVersionId,
                            positionMs=player.currentPosition.coerceAtLeast(0L),
                            durationMs=player.duration.coerceAtLeast(0L),
                            watchedDeltaMs=watched,
                            bufferCountDelta=bufferCount,
                            bufferMsDelta=bufferMs,
                            qualitySwitchDelta=switches,
                            networkType=playerNetworkLabel(context),
                            completed=false,
                            exitReason="error"
                        )
                    }
                }
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

            val sid=playbackSessionId
            if(sid!=null) {
                val now=SystemClock.elapsedRealtime()
                val activeBufferMs=if(telemetryBufferStartedAt>0L)
                    (now-telemetryBufferStartedAt).coerceAtLeast(0L)
                else 0L
                val watched=if(player.isPlaying)
                    (now-telemetryLastHeartbeatAt).coerceIn(0L,30_000L)
                else 0L
                val bufferCount=telemetryBufferCountPending
                val bufferMs=telemetryBufferMsPending+activeBufferMs
                val switches=telemetryQualitySwitchPending
                CoroutineScope(Dispatchers.IO).launch {
                    backend.endPlaybackSession(
                        sessionId=sid,
                        currentMediaVersionId=currentVersionId,
                        positionMs=position,
                        durationMs=duration,
                        watchedDeltaMs=watched,
                        bufferCountDelta=bufferCount,
                        bufferMsDelta=bufferMs,
                        qualitySwitchDelta=switches,
                        networkType=playerNetworkLabel(context),
                        completed=false,
                        exitReason="back"
                    )
                }
            }
            activity?.requestedOrientation=
                oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if(oldUi!=null) activity?.window?.decorView?.systemUiVisibility=oldUi
            player.release()
        }
    }

    LaunchedEffect(currentTarget.mediaVersionId,currentTarget.localUri) {
        val previousSession=playbackSessionId
        if(previousSession!=null) {
            val now=SystemClock.elapsedRealtime()
            val watched=if(player.isPlaying)
                (now-telemetryLastHeartbeatAt).coerceIn(0L,30_000L)
            else 0L
            runCatching {
                backend.endPlaybackSession(
                    sessionId=previousSession,
                    currentMediaVersionId=currentVersionId,
                    positionMs=player.currentPosition.coerceAtLeast(0L),
                    durationMs=player.duration.coerceAtLeast(0L),
                    watchedDeltaMs=watched,
                    bufferCountDelta=telemetryBufferCountPending,
                    bufferMsDelta=telemetryBufferMsPending,
                    qualitySwitchDelta=telemetryQualitySwitchPending,
                    networkType=playerNetworkLabel(context),
                    completed=false,
                    exitReason="content_change"
                )
            }
            playbackSessionId=null
        }

        telemetryBufferStartedAt=0L
        telemetryBufferCountPending=0
        telemetryBufferMsPending=0L
        telemetryQualitySwitchPending=0
        telemetryLastHeartbeatAt=SystemClock.elapsedRealtime()

        selectedVariantId=currentTarget.mediaVersionId
        resumePromptPositionMs=null
        pendingResumeVersionId=null

        val local=currentTarget.localUri
        if(!local.isNullOrBlank()) {
            currentVersionId=currentTarget.mediaVersionId
            loading=true
            error=null
            ended=false
            playUrl=local
            player.setMediaItem(playerMediaItem(local,currentTarget))
            if(currentTarget.startPositionMs>0) player.seekTo(currentTarget.startPositionMs)
            player.prepare()
            player.playbackParameters=player.playbackParameters.withSpeed(playbackSpeed)
            player.playWhenReady=true
            loading=false
            bumpControls()
        } else {
            val requestedStart=currentTarget.startPositionMs
            if(backend.session.isLoggedIn) {
                playbackSessionId=runCatching {
                    backend.startPlaybackSession(
                        mediaVersionId=currentTarget.mediaVersionId,
                        positionMs=requestedStart.coerceAtLeast(0L),
                        networkType=playerNetworkLabel(context),
                        deviceName=(Build.MANUFACTURER+" "+Build.MODEL).trim(),
                        appVersion=BuildConfig.VERSION_NAME
                    )
                }.getOrNull()
                telemetryLastHeartbeatAt=SystemClock.elapsedRealtime()
            }

            val enriched=runCatching {
                backend.playbackContext(currentTarget.mediaVersionId)
            }.getOrNull()
            if(enriched!=null) {
                currentTarget=enriched.copy(startPositionMs=requestedStart)
            }

            val saverVariant=if(
                initialSettings.dataSaver &&
                isMeteredConnection(context)
            ) {
                chooseDataSaverVariant(currentTarget.variants)
            } else null
            val initialVersion=saverVariant?.mediaVersionId ?: currentTarget.mediaVersionId
            dataSaverApplied=saverVariant!=null && initialVersion!=currentTarget.mediaVersionId

            val resumablePosition=currentTarget.resumePositionMs
            val resumableDuration=currentTarget.resumeDurationMs
            val shouldPrompt=
                requestedStart<=0L &&
                !currentTarget.resumeCompleted &&
                resumablePosition>=60_000L &&
                (
                    resumableDuration<=0L ||
                    resumablePosition<resumableDuration-60_000L
                )

            if(shouldPrompt) {
                player.pause()
                playUrl=null
                loading=false
                resumePromptPositionMs=resumablePosition
                resumePromptDurationMs=resumableDuration
                pendingResumeVersionId=initialVersion
                bumpControls()
            } else {
                loadVersion(
                    versionId=initialVersion,
                    startPosition=requestedStart.coerceAtLeast(0L)
                )
            }
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

            val loopStart=abStartMs
            val loopEnd=abEndMs
            if(
                loopStart!=null &&
                loopEnd!=null &&
                loopEnd>loopStart+500L &&
                positionMs>=loopEnd
            ) {
                player.seekTo(loopStart)
                positionMs=loopStart
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

    LaunchedEffect(playbackSessionId,currentVersionId) {
        while(true) {
            delay(15_000)
            val sid=playbackSessionId ?: continue
            val now=SystemClock.elapsedRealtime()
            val activeBufferMs=if(telemetryBufferStartedAt>0L)
                (now-telemetryBufferStartedAt).coerceAtLeast(0L)
            else 0L
            val watched=if(player.isPlaying)
                (now-telemetryLastHeartbeatAt).coerceIn(0L,30_000L)
            else 0L
            val bufferCount=telemetryBufferCountPending
            val bufferMs=telemetryBufferMsPending+activeBufferMs
            val switches=telemetryQualitySwitchPending

            runCatching {
                backend.heartbeatPlaybackSession(
                    sessionId=sid,
                    currentMediaVersionId=currentVersionId,
                    positionMs=player.currentPosition.coerceAtLeast(0L),
                    durationMs=player.duration.coerceAtLeast(0L),
                    watchedDeltaMs=watched,
                    bufferCountDelta=bufferCount,
                    bufferMsDelta=bufferMs,
                    qualitySwitchDelta=switches,
                    networkType=playerNetworkLabel(context)
                )
            }.onSuccess {
                telemetryLastHeartbeatAt=now
                telemetryBufferCountPending=0
                telemetryBufferMsPending=0L
                telemetryQualitySwitchPending=0
                if(telemetryBufferStartedAt>0L) {
                    telemetryBufferStartedAt=now
                }
            }
        }
    }

    LaunchedEffect(ended,playbackSessionId) {
        if(ended) {
            val sid=playbackSessionId ?: return@LaunchedEffect
            val now=SystemClock.elapsedRealtime()
            val activeBufferMs=if(telemetryBufferStartedAt>0L)
                (now-telemetryBufferStartedAt).coerceAtLeast(0L)
            else 0L
            val watched=if(player.isPlaying)
                (now-telemetryLastHeartbeatAt).coerceIn(0L,30_000L)
            else 0L
            runCatching {
                backend.endPlaybackSession(
                    sessionId=sid,
                    currentMediaVersionId=currentVersionId,
                    positionMs=player.currentPosition.coerceAtLeast(0L),
                    durationMs=player.duration.coerceAtLeast(0L),
                    watchedDeltaMs=watched,
                    bufferCountDelta=telemetryBufferCountPending,
                    bufferMsDelta=telemetryBufferMsPending+activeBufferMs,
                    qualitySwitchDelta=telemetryQualitySwitchPending,
                    networkType=playerNetworkLabel(context),
                    completed=true,
                    exitReason="completed"
                )
            }
            playbackSessionId=null
            telemetryBufferStartedAt=0L
            telemetryBufferCountPending=0
            telemetryBufferMsPending=0L
            telemetryQualitySwitchPending=0
        }
    }

    LaunchedEffect(isPlaying,currentVersionId) {
        if(isPlaying) {
            delay(12_000)
            if(player.isPlaying) {
                recoveryAttempts=0
                recoveryMessage=null
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

    LaunchedEffect(sleepTimerEndsAt) {
        while(sleepTimerEndsAt!=null) {
            delay(1_000)
            val end=sleepTimerEndsAt ?: break
            if(System.currentTimeMillis()>=end) {
                player.pause()
                sleepTimerEndsAt=null
                sleepTimerMessage="Sleep Timer • پخش متوقف شد"
                bumpControls()
                break
            }
        }
    }

    LaunchedEffect(ended,sleepAtEpisodeEnd) {
        if(ended && sleepAtEpisodeEnd) {
            player.pause()
            sleepAtEpisodeEnd=false
            sleepTimerMessage="Sleep Timer • پایان قسمت"
            bumpControls()
        }
    }

    LaunchedEffect(ended,creditsReached,currentTarget.nextMediaVersionId,autoPlayNext,initialSettings.skipCredits,sleepAtEpisodeEnd) {
        val canAdvance=!sleepAtEpisodeEnd &&
            currentTarget.nextMediaVersionId!=null &&
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
            resumePromptPositionMs!=null -> onBack()
            dialogueSearchOpen -> dialogueSearchOpen=false
            momentsOpen -> momentsOpen=false
            queueOpen -> queueOpen=false
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
            .pointerInput(locked,currentVersionId) {
                if(!locked) {
                    var dragMode=0
                    var horizontalPx=0f
                    var seekStart=0L
                    var seekTarget=0L
                    detectDragGestures(
                        onDragStart={
                            dragMode=0
                            horizontalPx=0f
                            seekStart=player.currentPosition.coerceAtLeast(0L)
                            seekTarget=seekStart
                        },
                        onDragEnd={
                            if(dragMode==1 && seekGestureActive) {
                                player.seekTo(seekTarget)
                                positionMs=seekTarget
                                bumpControls()
                            }
                            seekGestureActive=false
                            gestureLabel=null
                        },
                        onDragCancel={
                            seekGestureActive=false
                            gestureLabel=null
                        },
                        onDrag={ change,dragAmount ->
                            if(dragMode==0) {
                                val ax=kotlin.math.abs(dragAmount.x)
                                val ay=kotlin.math.abs(dragAmount.y)
                                if(ax+ay<2f) return@detectDragGestures
                                dragMode=if(ax>ay)1 else 2
                            }

                            if(dragMode==1) {
                                horizontalPx+=dragAmount.x
                                val duration=player.duration.coerceAtLeast(0L)
                                val seekWindow=when {
                                    duration<=0L -> 120_000L
                                    duration<30*60_000L -> 90_000L
                                    else -> minOf(300_000L,(duration*.18f).toLong())
                                }
                                val ratio=(horizontalPx/size.width.toFloat()).coerceIn(-1f,1f)
                                seekTarget=(seekStart+(seekWindow*ratio).toLong())
                                    .coerceIn(0L,if(duration>0L)duration else Long.MAX_VALUE)
                                seekGestureStartMs=seekStart
                                seekGestureTargetMs=seekTarget
                                seekGestureActive=true
                                controlsVisible=true
                                controlsEpoch++
                            } else {
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
                        applySubtitleAppearance(
                            subtitleView,
                            subtitleTextColor,
                            subtitleBackgroundOpacity,
                            subtitleEdgeStyle
                        )
                    }
                },
                update={
                    it.player=player
                    it.resizeMode=playerResizeModeValue(playerResizeMode)
                    it.subtitleView?.setFractionalTextSize(.0533f*subtitleScale)
                    it.subtitleView?.setBottomPaddingFraction(subtitleBottomPadding)
                    applySubtitleAppearance(
                        it.subtitleView,
                        subtitleTextColor,
                        subtitleBackgroundOpacity,
                        subtitleEdgeStyle
                    )
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

        val resumeAt=resumePromptPositionMs
        val resumeVersion=pendingResumeVersionId
        if(resumeAt!=null && resumeVersion!=null) {
            ResumePromptOverlay(
                target=currentTarget,
                positionMs=resumeAt,
                durationMs=resumePromptDurationMs,
                onResume={
                    resumePromptPositionMs=null
                    pendingResumeVersionId=null
                    scope.launch {
                        loadVersion(resumeVersion,resumeAt)
                    }
                },
                onRestart={
                    resumePromptPositionMs=null
                    pendingResumeVersionId=null
                    scope.launch {
                        loadVersion(resumeVersion,0L)
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

        if(seekGestureActive) {
            SeekGestureOverlay(
                startMs=seekGestureStartMs,
                targetMs=seekGestureTargetMs,
                modifier=Modifier.align(Alignment.Center)
            )
        }

        if(diagnosticsEnabled && playUrl!=null && error==null) {
            PlayerDiagnosticsOverlay(
                player=player,
                variant=currentTarget.variants.firstOrNull {
                    it.mediaVersionId==selectedVariantId
                },
                currentVersionId=currentVersionId,
                positionMs=positionMs,
                durationMs=durationMs,
                context=context,
                modifier=Modifier.align(Alignment.TopStart).padding(start=14.dp,top=74.dp)
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
                onMoments={momentsOpen=true},
                onDialogueSearch={dialogueSearchOpen=true},
                onQueue={queueOpen=true},
                onShare={
                    sharePlayerMoment(
                        context=context,
                        target=currentTarget,
                        mediaVersionId=currentVersionId,
                        positionMs=player.currentPosition.coerceAtLeast(0L)
                    )
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

        sleepTimerMessage?.let { message ->
            Snackbar(
                modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action={
                    TextButton(onClick={sleepTimerMessage=null}) { Text("باشه") }
                }
            ) { Text(message) }
        }

        playerSettingsMessage?.let { message ->
            Snackbar(
                modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action={
                    TextButton(onClick={playerSettingsMessage=null}) { Text("باشه") }
                }
            ) { Text(message) }
        }

        recoveryMessage?.let { message ->
            Snackbar(
                modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action={
                    TextButton(onClick={recoveryMessage=null}) { Text("باشه") }
                }
            ) { Text(message) }
        }
    }

    if(dialogueSearchOpen) {
        DialogueSearchSheet(
            backend=backend,
            mediaVersionId=currentVersionId,
            onSeekTo={
                player.seekTo(it)
                positionMs=it
                dialogueSearchOpen=false
                bumpControls()
            },
            onDismiss={dialogueSearchOpen=false}
        )
    }

    if(momentsOpen) {
        PlaybackMomentsSheet(
            backend=backend,
            mediaVersionId=currentVersionId,
            positionMs=positionMs,
            onSeekTo={
                player.seekTo(it)
                positionMs=it
                bumpControls()
            },
            onDismiss={momentsOpen=false}
        )
    }

    if(queueOpen) {
        EpisodeQueueSheet(
            target=currentTarget,
            onPrevious={
                queueOpen=false
                playPrevious()
            },
            onPlayItem={playQueueItem(it)},
            onDismiss={queueOpen=false}
        )
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
            subtitleTextColor=subtitleTextColor,
            subtitleBackgroundOpacity=subtitleBackgroundOpacity,
            subtitleEdgeStyle=subtitleEdgeStyle,
            resizeMode=playerResizeMode,
            autoPlayNext=autoPlayNext,
            dataSaverApplied=dataSaverApplied,
            sleepTimerEndsAt=sleepTimerEndsAt,
            sleepAtEpisodeEnd=sleepAtEpisodeEnd,
            abStartMs=abStartMs,
            abEndMs=abEndMs,
            diagnosticsEnabled=diagnosticsEnabled,
            orientationMode=orientationMode,
            currentPositionMs=positionMs,
            onDismiss={settingsOpen=false},
            onVariant={ variant ->
                val position=player.currentPosition.coerceAtLeast(0L)
                if(variant.mediaVersionId!=currentVersionId) {
                    telemetryQualitySwitchPending++
                }
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
            onSubtitleTextColor={subtitleTextColor=it},
            onSubtitleBackgroundOpacity={subtitleBackgroundOpacity=it},
            onSubtitleEdgeStyle={subtitleEdgeStyle=it},
            onResizeMode={playerResizeMode=it},
            onAutoPlayNext={autoPlayNext=it},
            onSleepTimer={minutes->
                sleepAtEpisodeEnd=false
                sleepTimerEndsAt=minutes?.let {
                    System.currentTimeMillis()+it*60_000L
                }
                sleepTimerMessage=when(minutes) {
                    null -> "Sleep Timer خاموش شد"
                    else -> "Sleep Timer برای "+minutes+" دقیقه فعال شد"
                }
            },
            onSleepAtEpisodeEnd={
                sleepTimerEndsAt=null
                sleepAtEpisodeEnd=it
                sleepTimerMessage=if(it)"بعد از پایان قسمت پخش متوقف می‌شه" else "Sleep Timer خاموش شد"
            },
            onSetAbStart={
                abStartMs=positionMs
                if(abEndMs!=null && abEndMs!!<=positionMs+500L) abEndMs=null
                playerSettingsMessage="نقطه A روی "+formatPlayerTime(positionMs)+" ثبت شد"
            },
            onSetAbEnd={
                val start=abStartMs
                if(start==null) {
                    playerSettingsMessage="اول نقطه A رو ثبت کن"
                } else if(positionMs<=start+500L) {
                    playerSettingsMessage="نقطه B باید بعد از A باشه"
                } else {
                    abEndMs=positionMs
                    playerSettingsMessage="A‑B Repeat فعال شد"
                }
            },
            onClearAb={
                abStartMs=null
                abEndMs=null
                playerSettingsMessage="A‑B Repeat خاموش شد"
            },
            onDiagnostics={diagnosticsEnabled=it},
            onOrientation={mode->
                orientationMode=mode
                activity?.requestedOrientation=when(mode) {
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
            },
            onSaveDefaults={
                val next=AppPreferences(context.applicationContext).read().copy(
                    defaultPlaybackSpeed=playbackSpeed,
                    subtitleScale=subtitleScale,
                    subtitleBottomPadding=subtitleBottomPadding,
                    subtitleTextColor=subtitleTextColor,
                    subtitleBackgroundOpacity=subtitleBackgroundOpacity,
                    subtitleEdgeStyle=subtitleEdgeStyle,
                    playerResizeMode=playerResizeMode,
                    autoplayNext=autoPlayNext
                )
                AppPreferences(context.applicationContext).write(next)
                playerSettingsMessage="تنظیمات فعلی به‌عنوان پیش‌فرض ذخیره شد"
                if(backend.session.isLoggedIn) {
                    scope.launch {
                        runCatching { settingsRepository.save(next) }
                            .onFailure {
                                playerSettingsMessage="روی دستگاه ذخیره شد؛ Sync حساب ناموفق بود"
                            }
                    }
                }
            }
        )
    }
}

@Composable
private fun ResumePromptOverlay(
    target:PlaybackTarget,
    positionMs:Long,
    durationMs:Long,
    onResume:()->Unit,
    onRestart:()->Unit,
    onBack:()->Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha=.78f)),
        contentAlignment=Alignment.Center
    ) {
        Surface(
            color=Color(0xF0191C23),
            shape=RoundedCornerShape(24.dp),
            modifier=Modifier.widthIn(max=430.dp).padding(22.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(16.dp))
                            .background(FqGold.copy(alpha=.14f)),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.History,null,tint=FqGold,modifier=Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ادامه تماشا؟",color=FqGold,fontSize=9.sp)
                        Text(
                            target.title,
                            color=Color.White,
                            fontSize=16.sp,
                            fontWeight=FontWeight.Bold,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis
                        )
                        if(target.subtitle.isNotBlank()) {
                            Text(target.subtitle,color=FqMuted,fontSize=8.sp)
                        }
                    }
                }

                Text(
                    "آخرین بار تا "+formatPlayerTime(positionMs)+" دیدی.",
                    color=Color.White.copy(alpha=.78f),
                    fontSize=10.sp,
                    modifier=Modifier.padding(top=16.dp)
                )

                if(durationMs>0L) {
                    LinearProgressIndicator(
                        progress={(positionMs.toFloat()/durationMs.toFloat()).coerceIn(0f,1f)},
                        color=FqGold,
                        trackColor=FqSurface3,
                        modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                    )
                }

                Button(
                    onClick=onResume,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow,null,tint=Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ادامه از "+formatPlayerTime(positionMs),
                        color=Color.Black,
                        fontWeight=FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick=onRestart,
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                ) {
                    Icon(Icons.Default.Replay,null)
                    Spacer(Modifier.width(6.dp))
                    Text("از اول پخش کن")
                }

                TextButton(
                    onClick=onBack,
                    modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=4.dp)
                ) {
                    Text("فعلاً نه",color=FqMuted)
                }
            }
        }
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
    onMoments: () -> Unit,
    onDialogueSearch: () -> Unit,
    onQueue: () -> Unit,
    onShare: () -> Unit,
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
        PlayerGlassIcon(Icons.Default.Forum,onMoments)
        Spacer(Modifier.width(5.dp))
        PlayerGlassIcon(Icons.Default.ManageSearch,onDialogueSearch)
        Spacer(Modifier.width(5.dp))
        if(target.previousMediaVersionId!=null || target.upNext.isNotEmpty()) {
            PlayerGlassIcon(Icons.Default.QueuePlayNext,onQueue)
            Spacer(Modifier.width(5.dp))
        }
        PlayerGlassIcon(Icons.Default.Share,onShare)
        Spacer(Modifier.width(5.dp))
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
private fun SeekGestureOverlay(
    startMs:Long,
    targetMs:Long,
    modifier:Modifier=Modifier
) {
    val delta=targetMs-startMs
    Surface(
        color=Color.Black.copy(alpha=.78f),
        shape=RoundedCornerShape(18.dp),
        modifier=modifier.widthIn(min=210.dp,max=320.dp)
    ) {
        Column(
            Modifier.padding(horizontal=18.dp,vertical=14.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Icon(
                if(delta>=0)Icons.Default.FastForward else Icons.Default.FastRewind,
                null,
                tint=FqGold,
                modifier=Modifier.size(28.dp)
            )
            Text(
                formatPlayerTime(targetMs),
                color=Color.White,
                fontSize=18.sp,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.padding(top=5.dp)
            )
            Text(
                (if(delta>=0)"+ " else "− ")+formatPlayerTime(kotlin.math.abs(delta)),
                color=FqMuted,
                fontSize=9.sp,
                modifier=Modifier.padding(top=3.dp)
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
private fun EpisodeQueueSheet(
    target:PlaybackTarget,
    onPrevious:()->Unit,
    onPlayItem:(PlaybackQueueItem)->Unit,
    onDismiss:()->Unit
) {
    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(bottom=26.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=18.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("صف قسمت‌ها",fontSize=19.sp,fontWeight=FontWeight.Bold)
                    Text(
                        target.subtitle.ifBlank{"پخش فعلی"},
                        color=FqMuted,
                        fontSize=8.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
                IconButton(onClick=onDismiss) {
                    Icon(Icons.Default.Close,null)
                }
            }

            target.previousMediaVersionId?.let {
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth()
                        .padding(horizontal=18.dp,vertical=10.dp)
                        .clickable { onPrevious() }
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SkipPrevious,null,tint=FqGold)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("قسمت قبلی",color=FqGold,fontSize=8.sp)
                            Text(
                                target.previousTitle ?: "قسمت قبلی",
                                fontSize=10.sp,
                                fontWeight=FontWeight.Bold
                            )
                            target.previousSubtitle?.let { sub ->
                                Text(sub,color=FqMuted,fontSize=7.sp)
                            }
                        }
                        Icon(Icons.Default.PlayArrow,null)
                    }
                }
            }

            Surface(
                color=FqGold.copy(alpha=.08f),
                shape=RoundedCornerShape(16.dp),
                modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=4.dp)
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayCircle,null,tint=FqGold)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("در حال پخش",color=FqGold,fontSize=8.sp)
                        Text(target.title,fontSize=10.sp,fontWeight=FontWeight.Bold)
                        if(target.subtitle.isNotBlank()) {
                            Text(target.subtitle,color=FqMuted,fontSize=7.sp)
                        }
                    }
                }
            }

            if(target.upNext.isEmpty()) {
                PlayerSettingsEmpty("قسمت بعدی دیگری در Catalog آماده پخش نیست.")
            } else {
                Text(
                    "بعدی‌ها",
                    color=FqMuted,
                    fontSize=9.sp,
                    modifier=Modifier.padding(horizontal=18.dp,vertical=10.dp)
                )
                LazyColumn(
                    modifier=Modifier.heightIn(max=420.dp),
                    contentPadding=PaddingValues(horizontal=18.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    items(target.upNext,key={it.mediaVersionId}) { item ->
                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(15.dp),
                            modifier=Modifier.fillMaxWidth()
                                .clickable { onPlayItem(item) }
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                RemoteImage(
                                    item.posterUrl ?: target.posterUrl,
                                    Modifier.width(72.dp).height(44.dp)
                                        .clip(RoundedCornerShape(9.dp)),
                                    ContentScale.Crop
                                )
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        fontSize=9.sp,
                                        fontWeight=FontWeight.Bold,
                                        maxLines=1,
                                        overflow=TextOverflow.Ellipsis
                                    )
                                    if(item.subtitle.isNotBlank()) {
                                        Text(
                                            item.subtitle,
                                            color=FqMuted,
                                            fontSize=7.sp,
                                            maxLines=1,
                                            overflow=TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Icon(Icons.Default.PlayArrow,null,tint=FqGold)
                            }
                        }
                    }
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
    subtitleTextColor: String,
    subtitleBackgroundOpacity: Float,
    subtitleEdgeStyle: String,
    resizeMode: String,
    autoPlayNext: Boolean,
    dataSaverApplied: Boolean,
    sleepTimerEndsAt: Long?,
    sleepAtEpisodeEnd: Boolean,
    abStartMs: Long?,
    abEndMs: Long?,
    diagnosticsEnabled: Boolean,
    orientationMode: String,
    currentPositionMs: Long,
    onDismiss: () -> Unit,
    onVariant: (PlaybackVariant) -> Unit,
    onAudio: (PlayerTrackChoice) -> Unit,
    onSubtitle: (PlayerTrackChoice?) -> Unit,
    onSpeed: (Float) -> Unit,
    onSubtitleScale: (Float) -> Unit,
    onSubtitleBottomPadding: (Float) -> Unit,
    onSubtitleTextColor: (String) -> Unit,
    onSubtitleBackgroundOpacity: (Float) -> Unit,
    onSubtitleEdgeStyle: (String) -> Unit,
    onResizeMode: (String) -> Unit,
    onAutoPlayNext: (Boolean) -> Unit,
    onSleepTimer: (Int?) -> Unit,
    onSleepAtEpisodeEnd: (Boolean) -> Unit,
    onSetAbStart: () -> Unit,
    onSetAbEnd: () -> Unit,
    onClearAb: () -> Unit,
    onDiagnostics: (Boolean) -> Unit,
    onOrientation: (String) -> Unit,
    onSaveDefaults: () -> Unit
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
                    PlayerSettingsTab.SPEED to "سرعت",
                    PlayerSettingsTab.TIMER to "تایمر",
                    PlayerSettingsTab.ADVANCED to "پیشرفته"
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
                    if(dataSaverApplied) {
                        Surface(
                            color=FqGold.copy(alpha=.09f),
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp)
                        ) {
                            Row(
                                Modifier.padding(11.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.DataSaverOn,null,tint=FqGold)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Data Saver فعال",fontSize=9.sp,fontWeight=FontWeight.Bold)
                                    Text(
                                        "روی شبکه Metered کیفیت سبک‌تر به‌صورت خودکار انتخاب شده؛ هر زمان خواستی دستی عوضش کن.",
                                        color=FqMuted,
                                        fontSize=7.sp,
                                        lineHeight=13.sp
                                    )
                                }
                            }
                        }
                    }
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

                    HorizontalDivider(
                        color=FqSurface3,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=8.dp)
                    )

                    Text(
                        "رنگ زیرنویس",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=5.dp)
                    )
                    LazyRow(
                        contentPadding=PaddingValues(horizontal=18.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            listOf(
                                "white" to "سفید",
                                "yellow" to "زرد",
                                "cyan" to "فیروزه‌ای"
                            )
                        ) { item ->
                            PremiumChip(
                                label=item.second,
                                active=subtitleTextColor==item.first,
                                onClick={onSubtitleTextColor(item.first)}
                            )
                        }
                    }

                    Text(
                        "پس‌زمینه • "+(subtitleBackgroundOpacity*100).roundToInt()+"٪",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=8.dp)
                    )
                    Slider(
                        value=subtitleBackgroundOpacity.coerceIn(0f,.85f),
                        onValueChange=onSubtitleBackgroundOpacity,
                        valueRange=0f..0.85f,
                        colors=SliderDefaults.colors(
                            thumbColor=FqGold,
                            activeTrackColor=FqGold
                        ),
                        modifier=Modifier.padding(horizontal=18.dp)
                    )

                    Text(
                        "حاشیه متن",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=5.dp)
                    )
                    LazyRow(
                        contentPadding=PaddingValues(horizontal=18.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            listOf(
                                "none" to "بدون حاشیه",
                                "outline" to "Outline",
                                "shadow" to "Shadow"
                            )
                        ) { item ->
                            PremiumChip(
                                label=item.second,
                                active=subtitleEdgeStyle==item.first,
                                onClick={onSubtitleEdgeStyle(item.first)}
                            )
                        }
                    }
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

                PlayerSettingsTab.TIMER -> {
                    val remainingMinutes=sleepTimerEndsAt?.let {
                        kotlin.math.ceil(
                            ((it-System.currentTimeMillis()).coerceAtLeast(0L))/60_000.0
                        ).toInt()
                    }

                    PlayerSettingsRow(
                        icon=Icons.Default.TimerOff,
                        title="خاموش",
                        subtitle="پخش بدون محدودیت زمانی",
                        selected=sleepTimerEndsAt==null && !sleepAtEpisodeEnd,
                        onClick={
                            onSleepTimer(null)
                            onSleepAtEpisodeEnd(false)
                        }
                    )
                    listOf(15,30,45,60).forEach { minutes ->
                        PlayerSettingsRow(
                            icon=Icons.Default.Timer,
                            title=minutes.toString()+" دقیقه",
                            subtitle=if(remainingMinutes!=null && kotlin.math.abs(remainingMinutes-minutes)<=1)
                                "فعال • حدود "+remainingMinutes+" دقیقه باقی‌مانده"
                            else "",
                            selected=remainingMinutes!=null && kotlin.math.abs(remainingMinutes-minutes)<=1 && !sleepAtEpisodeEnd,
                            onClick={onSleepTimer(minutes)}
                        )
                    }
                    PlayerSettingsRow(
                        icon=Icons.Default.NightsStay,
                        title="پایان همین قسمت",
                        subtitle="بعد از پایان فیلم یا قسمت، پخش متوقف می‌شود.",
                        selected=sleepAtEpisodeEnd,
                        onClick={onSleepAtEpisodeEnd(!sleepAtEpisodeEnd)}
                    )
                }

                PlayerSettingsTab.ADVANCED -> {
                    Text(
                        "A‑B Repeat",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=10.dp)
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=18.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick=onSetAbStart,
                            modifier=Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FirstPage,null,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("A • "+(abStartMs?.let(::formatPlayerTime) ?: formatPlayerTime(currentPositionMs)),fontSize=8.sp)
                        }
                        OutlinedButton(
                            onClick=onSetAbEnd,
                            modifier=Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.LastPage,null,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("B • "+(abEndMs?.let(::formatPlayerTime) ?: "ثبت"),fontSize=8.sp)
                        }
                    }

                    if(abStartMs!=null || abEndMs!=null) {
                        PlayerSettingsRow(
                            icon=Icons.Default.Repeat,
                            title=if(abStartMs!=null && abEndMs!=null)"A‑B Repeat فعال" else "A‑B Repeat آماده",
                            subtitle=listOfNotNull(
                                abStartMs?.let { "A "+formatPlayerTime(it) },
                                abEndMs?.let { "B "+formatPlayerTime(it) }
                            ).joinToString(" • "),
                            selected=abStartMs!=null && abEndMs!=null,
                            onClick=onClearAb
                        )
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
                            Text("Playback Diagnostics",fontSize=11.sp)
                            Text(
                                "Resolution، Codec، Bitrate، Buffer و Network را روی تصویر نشان بده.",
                                color=FqMuted,
                                fontSize=8.sp,
                                modifier=Modifier.padding(top=3.dp)
                            )
                        }
                        Switch(
                            checked=diagnosticsEnabled,
                            onCheckedChange=onDiagnostics
                        )
                    }

                    Text(
                        "جهت تصویر",
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=8.dp)
                    )
                    LazyRow(
                        contentPadding=PaddingValues(horizontal=18.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            listOf(
                                "auto" to "Auto",
                                "landscape" to "Landscape",
                                "portrait" to "Portrait"
                            )
                        ) { item ->
                            PremiumChip(
                                label=item.second,
                                active=orientationMode==item.first,
                                onClick={onOrientation(item.first)}
                            )
                        }
                    }

                    HorizontalDivider(
                        color=FqSurface3,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=12.dp)
                    )

                    Button(
                        onClick=onSaveDefaults,
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp)
                    ) {
                        Icon(Icons.Default.Save,null,tint=Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("ذخیره تنظیمات فعلی به‌عنوان Default",color=Color.Black)
                    }

                    Text(
                        "سرعت، اندازه و جای زیرنویس، نسبت تصویر و Auto‑next برای دفعات بعد ذخیره می‌شن.",
                        color=FqMuted,
                        fontSize=7.sp,
                        lineHeight=13.sp,
                        modifier=Modifier.padding(horizontal=18.dp,vertical=9.dp)
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogueSearchSheet(
    backend: BackendRepository,
    mediaVersionId: String,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val repo=remember { DialogueSearchRepository(backend) }
    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DialogueCue>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query,language,mediaVersionId) {
        val q=query.trim()
        if(q.length<2) {
            results=emptyList()
            error=null
            loading=false
            return@LaunchedEffect
        }
        delay(320)
        loading=true
        error=null
        runCatching {
            repo.search(
                mediaVersionId=mediaVersionId,
                query=q,
                language=language.takeIf(String::isNotBlank)
            )
        }.onSuccess {
            results=it
        }.onFailure {
            error=it.message ?: "جستجو ناموفق بود"
        }
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start=16.dp,end=16.dp,bottom=18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "جستجو در دیالوگ",
                        fontSize=19.sp,
                        fontWeight=FontWeight.Black
                    )
                    Text(
                        "یک جمله یا کلمه رو پیدا کن و مستقیم همون لحظه پخش رو باز کن.",
                        color=FqMuted,
                        fontSize=8.sp
                    )
                }
                IconButton(onClick=onDismiss) {
                    Icon(Icons.Default.Close,null)
                }
            }

            OutlinedTextField(
                value=query,
                onValueChange={query=it.take(160)},
                placeholder={Text("مثلاً: I know what I have to do")},
                leadingIcon={Icon(Icons.Default.Search,null)},
                trailingIcon={
                    if(query.isNotBlank()) {
                        IconButton(onClick={query=""}) {
                            Icon(Icons.Default.Close,null)
                        }
                    }
                },
                singleLine=true,
                shape=RoundedCornerShape(16.dp),
                modifier=Modifier.fillMaxWidth().padding(top=10.dp)
            )

            LazyRow(
                contentPadding=PaddingValues(top=8.dp,bottom=4.dp),
                horizontalArrangement=Arrangement.spacedBy(6.dp)
            ) {
                items(
                    listOf(
                        "" to "همه",
                        "fa" to "فارسی",
                        "en" to "English",
                        "de" to "Deutsch",
                        "ar" to "العربية",
                        "tr" to "Türkçe",
                        "ko" to "한국어",
                        "ja" to "日本語"
                    )
                ) { item ->
                    FilterChip(
                        selected=language==item.first,
                        onClick={language=item.first},
                        label={Text(item.second,fontSize=8.sp)}
                    )
                }
            }

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                )
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=7.dp)
                )
            }

            when {
                query.trim().length<2 -> {
                    PlayerSearchHint(
                        icon=Icons.Default.Subtitles,
                        title="دیالوگ رو پیدا کن",
                        body="حداقل دو کاراکتر بنویس. نتیجه‌ها با زمان دقیق زیرنویس نمایش داده می‌شن."
                    )
                }
                !loading && results.isEmpty() && error==null -> {
                    PlayerSearchHint(
                        icon=Icons.Default.SearchOff,
                        title="چیزی پیدا نشد",
                        body="عبارت کوتاه‌تر یا زبان دیگه رو امتحان کن."
                    )
                }
                else -> {
                    LazyColumn(
                        modifier=Modifier.heightIn(max=440.dp).padding(top=7.dp),
                        verticalArrangement=Arrangement.spacedBy(7.dp)
                    ) {
                        items(
                            results,
                            key={it.language+":"+it.startMs+":"+it.endMs+":"+it.text.hashCode()}
                        ) { cue ->
                            Surface(
                                color=FqSurface2,
                                shape=RoundedCornerShape(15.dp),
                                modifier=Modifier.fillMaxWidth().clickable {
                                    onSeekTo(cue.startMs)
                                }
                            ) {
                                Row(
                                    Modifier.padding(11.dp),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color=FqGold.copy(alpha=.12f),
                                        shape=RoundedCornerShape(9.dp)
                                    ) {
                                        Text(
                                            formatPlayerTime(cue.startMs),
                                            color=FqGold,
                                            fontSize=8.sp,
                                            fontWeight=FontWeight.Bold,
                                            modifier=Modifier.padding(horizontal=7.dp,vertical=5.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            cue.text,
                                            fontSize=9.sp,
                                            lineHeight=15.sp,
                                            maxLines=3,
                                            overflow=TextOverflow.Ellipsis
                                        )
                                        Text(
                                            cue.language.uppercase(),
                                            color=FqMuted,
                                            fontSize=6.sp,
                                            modifier=Modifier.padding(top=3.dp)
                                        )
                                    }
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        null,
                                        tint=FqGold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSearchHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    body:String
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical=34.dp),
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Icon(icon,null,tint=FqGold,modifier=Modifier.size(36.dp))
        Text(
            title,
            fontSize=11.sp,
            fontWeight=FontWeight.Bold,
            modifier=Modifier.padding(top=8.dp)
        )
        Text(
            body,
            color=FqMuted,
            fontSize=8.sp,
            lineHeight=14.sp,
            modifier=Modifier.padding(start=18.dp,end=18.dp,top=4.dp)
        )
    }
}

private fun sharePlayerMoment(
    context:Context,
    target:PlaybackTarget,
    mediaVersionId:String,
    positionMs:Long
) {
    val link="filmiqoo://play/"+mediaVersionId+"?t="+positionMs
    val message=buildString {
        append(target.title)
        if(target.subtitle.isNotBlank()) append(" • ").append(target.subtitle)
        append("\n")
        append("از ").append(formatPlayerTime(positionMs))
        append("\n").append(link)
    }
    val intent=Intent(Intent.ACTION_SEND).apply {
        type="text/plain"
        putExtra(Intent.EXTRA_TEXT,message)
    }
    context.startActivity(
        Intent.createChooser(intent,"اشتراک این لحظه")
    )
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


private fun lowerQualityVariant(
    variants:List<PlaybackVariant>,
    currentVersionId:String
):PlaybackVariant? {
    if(variants.isEmpty()) return null
    val sorted=variants.sortedByDescending { variantQualityRank(it.label) }
    val currentIndex=sorted.indexOfFirst { it.mediaVersionId==currentVersionId }
    return when {
        currentIndex>=0 && currentIndex<sorted.lastIndex -> sorted[currentIndex+1]
        currentIndex<0 -> sorted.lastOrNull()
        else -> null
    }
}

private fun variantQualityRank(label:String):Int {
    val normalized=label.lowercase(Locale.US)
    return when {
        "4320" in normalized || "8k" in normalized -> 4320
        "2160" in normalized || "4k" in normalized -> 2160
        "1440" in normalized || "2k" in normalized -> 1440
        "1080" in normalized -> 1080
        "720" in normalized -> 720
        "576" in normalized -> 576
        "480" in normalized -> 480
        "360" in normalized -> 360
        "240" in normalized -> 240
        else -> normalized.filter(Char::isDigit).toIntOrNull() ?: 0
    }
}

private fun playerMediaItem(
    uri:String,
    target:PlaybackTarget
):ExoMediaItem {
    val metadata=MediaMetadata.Builder()
        .setTitle(target.title)
        .setSubtitle(target.subtitle)
        .apply {
            target.posterUrl
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                ?.let(::setArtworkUri)
        }
        .build()

    return ExoMediaItem.Builder()
        .setUri(uri)
        .setMediaId(target.mediaVersionId)
        .setMediaMetadata(metadata)
        .build()
}

private fun applySubtitleAppearance(
    view:androidx.media3.ui.SubtitleView?,
    textColor:String,
    backgroundOpacity:Float,
    edgeStyle:String
) {
    if(view==null) return
    val foreground=when(textColor) {
        "yellow" -> android.graphics.Color.rgb(255,232,88)
        "cyan" -> android.graphics.Color.rgb(102,224,255)
        else -> android.graphics.Color.WHITE
    }
    val alpha=(backgroundOpacity.coerceIn(0f,1f)*255f).roundToInt()
    val background=android.graphics.Color.argb(alpha,0,0,0)
    val edge=when(edgeStyle) {
        "none" -> CaptionStyleCompat.EDGE_TYPE_NONE
        "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }
    view.setStyle(
        CaptionStyleCompat(
            foreground,
            background,
            android.graphics.Color.TRANSPARENT,
            edge,
            android.graphics.Color.BLACK,
            null
        )
    )
}

@Composable
private fun PlayerDiagnosticsOverlay(
    player:ExoPlayer,
    variant:PlaybackVariant?,
    currentVersionId:String,
    positionMs:Long,
    durationMs:Long,
    context:Context,
    modifier:Modifier=Modifier
) {
    val video=player.videoFormat
    val audio=player.audioFormat
    val bufferedMs=(player.bufferedPosition-player.currentPosition).coerceAtLeast(0L)
    val resolution=if(video!=null && video.width>0 && video.height>0)
        video.width.toString()+"×"+video.height
    else "—"
    val frameRate=video?.frameRate?.takeIf { it>0f }?.let {
        String.format(Locale.US,"%.1f fps",it)
    } ?: "—"
    val bitrate=video?.bitrate?.takeIf { it>0 }?.let {
        String.format(Locale.US,"%.1f Mbps",it/1_000_000.0)
    } ?: "—"
    val codec=video?.codecs
        ?.takeIf(String::isNotBlank)
        ?: video?.sampleMimeType
        ?: variant?.codec
        ?: "—"
    val audioInfo=buildList {
        audio?.language?.takeIf(String::isNotBlank)?.let(::add)
        audio?.channelCount?.takeIf { it>0 }?.let { add(it.toString()+"ch") }
        audio?.sampleRate?.takeIf { it>0 }?.let { add((it/1000).toString()+"kHz") }
    }.joinToString(" • ").ifBlank { "—" }

    Surface(
        color=Color.Black.copy(alpha=.72f),
        shape=RoundedCornerShape(12.dp),
        modifier=modifier.widthIn(max=300.dp)
    ) {
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MonitorHeart,
                    null,
                    tint=FqGold,
                    modifier=Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "Playback Diagnostics",
                    color=FqGold,
                    fontSize=8.sp,
                    fontWeight=FontWeight.Bold
                )
            }
            DiagnosticLine("Quality",variant?.label ?: "Auto")
            DiagnosticLine("Video",resolution+" • "+frameRate)
            DiagnosticLine("Codec",codec)
            DiagnosticLine("Bitrate",bitrate)
            DiagnosticLine("Audio",audioInfo)
            DiagnosticLine("Buffer",String.format(Locale.US,"%.1fs",bufferedMs/1000.0))
            DiagnosticLine("Network",playerNetworkLabel(context))
            DiagnosticLine("Position",formatPlayerTime(positionMs)+" / "+formatPlayerTime(durationMs))
            DiagnosticLine("Version",currentVersionId.take(8))
        }
    }
}

@Composable
private fun DiagnosticLine(label:String,value:String) {
    Row(
        Modifier.fillMaxWidth().padding(top=2.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Text(label,color=Color.White.copy(alpha=.55f),fontSize=6.sp,modifier=Modifier.width(54.dp))
        Text(
            value,
            color=Color.White.copy(alpha=.9f),
            fontSize=6.sp,
            maxLines=1,
            overflow=TextOverflow.Ellipsis,
            modifier=Modifier.weight(1f)
        )
    }
}

private fun playerNetworkLabel(context:Context):String {
    val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network=cm.activeNetwork ?: return "Offline"
    val caps=cm.getNetworkCapabilities(network) ?: return "Unknown"
    return when {
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Other"
    }
}

private fun playerResizeModeValue(mode:String):Int=when(mode) {
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}


private fun isMeteredConnection(context:Context):Boolean {
    val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    return runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
}

private fun chooseDataSaverVariant(
    variants:List<PlaybackVariant>
):PlaybackVariant? {
    if(variants.size<2) return null

    fun resolution(label:String):Int? =
        Regex("""(\d{3,4})""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

    val numbered=variants.mapNotNull { variant ->
        resolution(variant.label)?.let { it to variant }
    }
    if(numbered.isEmpty()) return variants.lastOrNull()

    val under720=numbered.filter { it.first<=720 }
    return (under720.maxByOrNull { it.first } ?: numbered.minByOrNull { it.first })?.second
}
