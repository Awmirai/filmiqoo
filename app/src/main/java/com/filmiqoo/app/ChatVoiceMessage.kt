package com.filmiqoo.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

@Composable
fun VoiceRecordButton(
    enabled:Boolean,
    onRecorded:(File,Long,List<Int>)->Unit,
    onError:(String)->Unit
) {
    val context=LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val amplitudeSamples=remember { mutableStateListOf<Int>() }
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
            amplitudeSamples.clear()
            outputFile=file
            startedAt=SystemClock.elapsedRealtime()
            elapsedMs=0L
            recorder=next
        }.onFailure {
            recorder?.release()
            recorder=null
            outputFile?.delete()
            outputFile=null
            amplitudeSamples.clear()
            onError(it.message ?: "شروع ضبط صدا ناموفق بود")
        }
    }

    fun stopRecording(send:Boolean) {
        val current=recorder ?: return
        val file=outputFile
        val duration=(SystemClock.elapsedRealtime()-startedAt).coerceAtLeast(0L)
        val waveform=compressVoiceWaveform(amplitudeSamples.toList())
        recorder=null
        outputFile=null

        val stopped=runCatching {
            current.stop()
            true
        }.getOrDefault(false)
        runCatching { current.release() }
        elapsedMs=0L
        amplitudeSamples.clear()

        if(send && stopped && file!=null && file.isFile && file.length()>0L && duration>=350L) {
            onRecorded(file,duration,waveform)
        } else {
            file?.delete()
            if(send && duration<350L) onError("پیام صوتی خیلی کوتاه بود")
            else if(send && !stopped) onError("ضبط پیام صوتی کامل نشد")
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
            val amplitude=runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            val normalized=((amplitude/32767f)*100f)
                .roundToInt()
                .coerceIn(3,100)
            amplitudeSamples.add(normalized)
            if(amplitudeSamples.size>600) {
                val compact=compressVoiceWaveform(amplitudeSamples.toList(),300)
                amplitudeSamples.clear()
                amplitudeSamples.addAll(compact)
            }
            delay(120)
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
                .padding(start=6.dp)
        ) {
            Box(Modifier.size(8.dp).background(FqDanger,CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(formatVoiceTime(elapsedMs),color=FqDanger,fontSize=8.sp)

            MiniWaveform(
                waveform=compressVoiceWaveform(amplitudeSamples.toList(),24),
                progress=1f,
                modifier=Modifier.width(70.dp).height(24.dp).padding(horizontal=5.dp)
            )

            IconButton(onClick={stopRecording(false)}) {
                Icon(Icons.Default.Close,null,tint=FqMuted)
            }
            IconButton(onClick={stopRecording(true)}) {
                Icon(Icons.Default.Send,null,tint=FqGold)
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
    declaredDurationMs:Long=0L,
    waveform:List<Int> = emptyList()
) {
    val context=LocalContext.current
    val player=remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var isBuffering by remember(url) { mutableStateOf(true) }
    var position by remember(url) { mutableLongStateOf(0L) }
    var duration by remember(url) {
        mutableLongStateOf(declaredDurationMs.coerceAtLeast(0L))
    }
    var speed by remember(url) { mutableFloatStateOf(1f) }
    var showRemaining by remember(url) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(value:Boolean) {
                isPlaying=value
            }

            override fun onPlaybackStateChanged(state:Int) {
                isBuffering=state==Player.STATE_BUFFERING || state==Player.STATE_IDLE
                if(state==Player.STATE_READY && player.duration>0L) {
                    duration=player.duration
                    isBuffering=false
                }
                if(state==Player.STATE_ENDED) {
                    position=0L
                    player.seekTo(0L)
                    isPlaying=false
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
            delay(160)
        }
    }

    val total=duration.coerceAtLeast(declaredDurationMs).coerceAtLeast(1L)
    val progress=(position.toFloat()/total.toFloat()).coerceIn(0f,1f)
    val shownTime=if(showRemaining) {
        "-"+formatVoiceTime((total-position).coerceAtLeast(0L))
    } else {
        formatVoiceTime(position.coerceAtLeast(0L))
    }
    val displayWave=remember(waveform,url) {
        if(waveform.isNotEmpty()) {
            compressVoiceWaveform(waveform,44)
        } else {
            List(44) { index ->
                18 + ((index*37 + url.hashCode().absoluteValue)%73)
            }
        }
    }

    Surface(
        color=FqSurface2,
        shape=CircleShape,
        modifier=Modifier.fillMaxWidth().padding(top=7.dp)
    ) {
        Column(Modifier.padding(horizontal=8.dp,vertical=5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(
                    onClick={
                        if(isPlaying) {
                            player.pause()
                        } else {
                            if(player.playbackState==Player.STATE_ENDED) player.seekTo(0L)
                            player.play()
                        }
                    }
                ) {
                    if(isBuffering) {
                        CircularProgressIndicator(
                            color=FqGold,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            if(isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null,
                            tint=FqGold
                        )
                    }
                }

                Column(Modifier.weight(1f)) {
                    MiniWaveform(
                        waveform=displayWave,
                        progress=progress,
                        modifier=Modifier.fillMaxWidth().height(28.dp)
                    )
                    Slider(
                        value=progress,
                        onValueChange={value->
                            val next=(value*total).toLong().coerceIn(0L,total)
                            player.seekTo(next)
                            position=next
                        },
                        modifier=Modifier.fillMaxWidth().height(20.dp)
                    )
                }

                Text(
                    shownTime,
                    color=Color.White.copy(alpha=.74f),
                    fontSize=7.sp,
                    modifier=Modifier
                        .padding(horizontal=7.dp)
                        .clickable { showRemaining=!showRemaining }
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal=4.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Text(formatVoiceTime(total),color=FqMuted,fontSize=6.sp)
                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick={
                        val next=(player.currentPosition-10_000L).coerceAtLeast(0L)
                        player.seekTo(next)
                        position=next
                    },
                    modifier=Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.Replay10,
                        null,
                        tint=FqMuted,
                        modifier=Modifier.size(18.dp)
                    )
                }

                TextButton(
                    onClick={
                        speed=when(speed) {
                            1f -> 1.5f
                            1.5f -> 2f
                            else -> 1f
                        }
                        player.setPlaybackParameters(PlaybackParameters(speed))
                    },
                    contentPadding=PaddingValues(horizontal=7.dp,vertical=0.dp)
                ) {
                    Text(
                        when(speed) {
                            1f -> "1×"
                            1.5f -> "1.5×"
                            else -> "2×"
                        },
                        color=FqGold,
                        fontSize=7.sp
                    )
                }

                IconButton(
                    onClick={
                        val next=(player.currentPosition+10_000L).coerceAtMost(total)
                        player.seekTo(next)
                        position=next
                    },
                    modifier=Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.Forward10,
                        null,
                        tint=FqMuted,
                        modifier=Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniWaveform(
    waveform:List<Int>,
    progress:Float,
    modifier:Modifier=Modifier
) {
    val values=if(waveform.isEmpty()) listOf(20,40,65,35,75,48,30,58) else waveform
    Canvas(modifier) {
        if(values.isEmpty()) return@Canvas
        val step=size.width/values.size.toFloat()
        val playedUntil=(progress.coerceIn(0f,1f)*values.size).toInt()
        values.forEachIndexed { index,value ->
            val x=step*index+step/2f
            val barHeight=size.height*(0.18f+0.82f*(value.coerceIn(0,100)/100f))
            drawLine(
                color=if(index<=playedUntil)FqGold else Color.White.copy(alpha=.22f),
                start=Offset(x,(size.height-barHeight)/2f),
                end=Offset(x,(size.height+barHeight)/2f),
                strokeWidth=(step*0.42f).coerceAtLeast(1.5f),
                cap=StrokeCap.Round
            )
        }
    }
}

private fun compressVoiceWaveform(values:List<Int>,target:Int=48):List<Int> {
    if(values.isEmpty()) return emptyList()
    if(values.size<=target) return values.map { it.coerceIn(0,100) }
    val out=ArrayList<Int>(target)
    for(i in 0 until target) {
        val from=(i*values.size)/target
        val to=(((i+1)*values.size)/target).coerceAtMost(values.size)
        val slice=values.subList(from,to.coerceAtLeast(from+1))
        out.add((slice.sum().toFloat()/slice.size).roundToInt().coerceIn(0,100))
    }
    return out
}

private fun formatVoiceTime(ms:Long):String {
    val total=(ms/1000L).coerceAtLeast(0L)
    val min=total/60L
    val sec=total%60L
    return min.toString().padStart(2,'0')+":"+sec.toString().padStart(2,'0')
}

private val Int.absoluteValue:Int
    get()=if(this==Int.MIN_VALUE)Int.MAX_VALUE else kotlin.math.abs(this)
