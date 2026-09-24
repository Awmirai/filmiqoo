package com.filmiqoo.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun VoiceRecordButton(
    enabled:Boolean,
    onRecorded:(File,Long)->Unit,
    onError:(String)->Unit
) {
    val context=LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val recording=recorder!=null

    fun startRecording() {
        if(!enabled || recorder!=null) return
        runCatching {
            val dir=File(context.cacheDir,"voice_messages").apply { mkdirs() }
            val file=File(dir,"voice_"+System.currentTimeMillis()+".m4a")
            val next=MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            outputFile=file
            startedAt=SystemClock.elapsedRealtime()
            elapsedMs=0L
            recorder=next
        }.onFailure {
            recorder?.release()
            recorder=null
            outputFile?.delete()
            outputFile=null
            onError(it.message ?: "شروع ضبط صدا ناموفق بود")
        }
    }

    fun stopRecording(send:Boolean) {
        val current=recorder ?: return
        val file=outputFile
        val duration=(SystemClock.elapsedRealtime()-startedAt).coerceAtLeast(0L)
        recorder=null
        outputFile=null
        runCatching { current.stop() }
            .onFailure { file?.delete() }
        runCatching { current.release() }
        elapsedMs=0L

        if(send && file!=null && file.isFile && file.length()>0L && duration>=350L) {
            onRecorded(file,duration)
        } else {
            file?.delete()
            if(send && duration<350L) onError("پیام صوتی خیلی کوتاه بود")
        }
    }

    val permissionLauncher=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted) startRecording()
        else onError("برای ضبط پیام صوتی اجازه میکروفون لازم است")
    }

    LaunchedEffect(recording) {
        while(recording) {
            elapsedMs=(SystemClock.elapsedRealtime()-startedAt).coerceAtLeast(0L)
            delay(200)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder?.let { r ->
                runCatching { r.stop() }
                runCatching { r.release() }
            }
            outputFile?.delete()
        }
    }

    if(recording) {
        Row(
            verticalAlignment=Alignment.CenterVertically,
            modifier=Modifier
                .background(FqDanger.copy(alpha=.12f),CircleShape)
                .padding(start=8.dp)
        ) {
            Text(
                formatVoiceTime(elapsedMs),
                color=FqDanger,
                fontSize=8.sp
            )
            IconButton(onClick={stopRecording(true)}) {
                Icon(Icons.Default.Stop,null,tint=FqDanger)
            }
        }
    } else {
        IconButton(
            enabled=enabled,
            onClick={
                if(ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )==PackageManager.PERMISSION_GRANTED
                ) {
                    startRecording()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        ) {
            Icon(
                Icons.Default.Mic,
                null,
                tint=if(enabled)FqGold else FqMuted
            )
        }
    }
}

@Composable
fun VoiceMessagePlayer(
    url:String,
    declaredDurationMs:Long=0L
) {
    val context=LocalContext.current
    val player=remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableLongStateOf(0L) }
    var duration by remember(url) {
        mutableLongStateOf(declaredDurationMs.coerceAtLeast(0L))
    }

    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(value:Boolean) {
                isPlaying=value
            }
            override fun onPlaybackStateChanged(state:Int) {
                if(state==Player.STATE_READY && player.duration>0L) {
                    duration=player.duration
                }
                if(state==Player.STATE_ENDED) {
                    position=0L
                    player.seekTo(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player,isPlaying) {
        while(isPlaying) {
            position=player.currentPosition.coerceAtLeast(0L)
            if(player.duration>0L) duration=player.duration
            delay(250)
        }
    }

    val total=duration.coerceAtLeast(declaredDurationMs).coerceAtLeast(1L)
    val progress=(position.toFloat()/total.toFloat()).coerceIn(0f,1f)

    Surface(
        color=FqSurface2,
        shape=CircleShape,
        modifier=Modifier.fillMaxWidth().padding(top=7.dp)
    ) {
        Row(
            Modifier.padding(horizontal=8.dp,vertical=4.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(
                onClick={
                    if(isPlaying) player.pause()
                    else {
                        if(player.playbackState==Player.STATE_ENDED) player.seekTo(0L)
                        player.play()
                    }
                }
            ) {
                Icon(
                    if(isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint=FqGold
                )
            }
            Slider(
                value=progress,
                onValueChange={value->
                    player.seekTo((value*total).toLong())
                    position=(value*total).toLong()
                },
                modifier=Modifier.weight(1f)
            )
            Text(
                formatVoiceTime(
                    if(position>0L) position else total
                ),
                color=Color.White.copy(alpha=.74f),
                fontSize=7.sp,
                modifier=Modifier.padding(horizontal=7.dp)
            )
        }
    }
}

private fun formatVoiceTime(ms:Long):String {
    val total=(ms/1000L).coerceAtLeast(0L)
    val min=total/60L
    val sec=total%60L
    return min.toString().padStart(2,'0')+":"+sec.toString().padStart(2,'0')
}
