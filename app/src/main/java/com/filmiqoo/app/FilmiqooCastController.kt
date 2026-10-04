package com.filmiqoo.app

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.Closeable

enum class FilmiqooCastConnection {
    UNAVAILABLE,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SUSPENDED,
    ERROR
}

data class FilmiqooCastState(
    val connection:FilmiqooCastConnection=FilmiqooCastConnection.DISCONNECTED,
    val deviceName:String="",
    val isPlaying:Boolean=false,
    val positionMs:Long=0L,
    val durationMs:Long=0L,
    val mediaVersionId:String?=null,
    val error:String?=null
) {
    val connected:Boolean
        get()=connection==FilmiqooCastConnection.CONNECTED

    val connecting:Boolean
        get()=connection==FilmiqooCastConnection.CONNECTING
}

class FilmiqooCastController(context: Context) : Closeable {
    private val appContext=context.applicationContext
    private val castContext:CastContext?=
        runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()

    private val mutableState=MutableStateFlow(
        if(castContext==null) {
            FilmiqooCastState(connection=FilmiqooCastConnection.UNAVAILABLE)
        } else {
            FilmiqooCastState(connection=FilmiqooCastConnection.DISCONNECTED)
        }
    )
    val state:StateFlow<FilmiqooCastState> = mutableState.asStateFlow()

    private var attachedRemote:RemoteMediaClient?=null

    private val remoteCallback=object:RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            publishRemoteState()
        }

        override fun onMetadataUpdated() {
            publishRemoteState()
        }

        override fun onMediaError(mediaError: MediaError) {
            mutableState.value=mutableState.value.copy(
                connection=FilmiqooCastConnection.ERROR,
                error="خطای پخش روی تلویزیون"
            )
        }
    }

    private val progressListener=
        RemoteMediaClient.ProgressListener { progressMs,durationMs ->
            val current=mutableState.value
            mutableState.value=current.copy(
                positionMs=progressMs.coerceAtLeast(0L),
                durationMs=durationMs.coerceAtLeast(0L),
                isPlaying=attachedRemote?.isPlaying==true,
                error=null
            )
        }

    private val sessionListener=object:SessionManagerListener<CastSession> {
        override fun onSessionStarting(session:CastSession) {
            mutableState.value=mutableState.value.copy(
                connection=FilmiqooCastConnection.CONNECTING,
                error=null
            )
        }

        override fun onSessionStarted(session:CastSession,sessionId:String) {
            attach(session)
        }

        override fun onSessionStartFailed(session:CastSession,error:Int) {
            detachRemote()
            mutableState.value=FilmiqooCastState(
                connection=FilmiqooCastConnection.ERROR,
                error="اتصال به تلویزیون برقرار نشد."
            )
        }

        override fun onSessionEnding(session:CastSession) {
            publishRemoteState()
        }

        override fun onSessionEnded(session:CastSession,error:Int) {
            detachRemote()
            mutableState.value=FilmiqooCastState(
                connection=FilmiqooCastConnection.DISCONNECTED
            )
        }

        override fun onSessionResuming(session:CastSession,sessionId:String) {
            mutableState.value=mutableState.value.copy(
                connection=FilmiqooCastConnection.CONNECTING,
                error=null
            )
        }

        override fun onSessionResumed(session:CastSession,wasSuspended:Boolean) {
            attach(session)
        }

        override fun onSessionResumeFailed(session:CastSession,error:Int) {
            detachRemote()
            mutableState.value=FilmiqooCastState(
                connection=FilmiqooCastConnection.ERROR,
                error="بازیابی اتصال تلویزیون ناموفق بود."
            )
        }

        override fun onSessionSuspended(session:CastSession,reason:Int) {
            mutableState.value=mutableState.value.copy(
                connection=FilmiqooCastConnection.SUSPENDED,
                error="ارتباط با دستگاه موقتاً قطع شده؛ در حال بازیابی…"
            )
        }
    }

    init {
        castContext?.sessionManager?.let { manager ->
            runCatching {
                manager.addSessionManagerListener(
                    sessionListener,
                    CastSession::class.java
                )
            }
            manager.currentCastSession?.let(::attach)
        }
    }

    private fun currentSession():CastSession?=
        castContext?.sessionManager?.currentCastSession

    private fun remote():RemoteMediaClient?=
        currentSession()?.remoteMediaClient

    private fun attach(session:CastSession) {
        detachRemote()
        attachedRemote=session.remoteMediaClient
        attachedRemote?.let { client ->
            runCatching { client.registerCallback(remoteCallback) }
            runCatching { client.addProgressListener(progressListener,500L) }
            runCatching { client.requestStatus() }
        }
        publishRemoteState()
    }

    private fun detachRemote() {
        attachedRemote?.let { client ->
            runCatching { client.unregisterCallback(remoteCallback) }
            runCatching { client.removeProgressListener(progressListener) }
        }
        attachedRemote=null
    }

    private fun publishRemoteState() {
        val session=currentSession()
        val client=session?.remoteMediaClient
        if(session==null || session.isConnected!=true) {
            mutableState.value=FilmiqooCastState(
                connection=if(castContext==null)
                    FilmiqooCastConnection.UNAVAILABLE
                else FilmiqooCastConnection.DISCONNECTED
            )
            return
        }

        val custom=client?.mediaInfo?.customData
        mutableState.value=FilmiqooCastState(
            connection=FilmiqooCastConnection.CONNECTED,
            deviceName=session.castDevice?.friendlyName.orEmpty(),
            isPlaying=client?.isPlaying==true,
            positionMs=client?.approximateStreamPosition
                ?.coerceAtLeast(0L) ?: 0L,
            durationMs=client?.streamDuration
                ?.coerceAtLeast(0L) ?: 0L,
            mediaVersionId=custom
                ?.optString("filmiqooMediaVersionId")
                ?.takeIf(String::isNotBlank),
            error=null
        )
    }

    fun isConnected():Boolean = mutableState.value.connected

    fun deviceName():String = mutableState.value.deviceName

    fun positionMs():Long =
        remote()?.approximateStreamPosition?.coerceAtLeast(0L)
            ?: mutableState.value.positionMs

    fun durationMs():Long =
        remote()?.streamDuration?.coerceAtLeast(0L)
            ?: mutableState.value.durationMs

    fun isPlaying():Boolean = remote()?.isPlaying==true

    fun load(
        url:String,
        contentType:String?,
        mediaVersionId:String,
        title:String,
        subtitle:String,
        artworkUrl:String?,
        positionMs:Long,
        autoplay:Boolean,
        externalSubtitleUrl:String?=null,
        externalSubtitleMime:String?=null,
        externalSubtitleLabel:String?=null
    ):Boolean {
        val client=remote() ?: return false

        val metadata=MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE,title)
            if(subtitle.isNotBlank()) {
                putString(MediaMetadata.KEY_SUBTITLE,subtitle)
            }
            artworkUrl
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.let { addImage(WebImage(it)) }
        }

        val customData=JSONObject()
            .put("filmiqooMediaVersionId",mediaVersionId)
            .put("filmiqooSource","android")

        val tracks=buildList {
            val subtitleUrl=externalSubtitleUrl?.trim().orEmpty()
            val mime=externalSubtitleMime?.trim().orEmpty().lowercase()
            val castCompatible=
                subtitleUrl.startsWith("https://") &&
                (
                    mime=="text/vtt" ||
                    mime=="application/ttml+xml" ||
                    subtitleUrl.substringBefore('?').lowercase().endsWith(".vtt")
                )
            if(castCompatible) {
                add(
                    MediaTrack.Builder(9001L,MediaTrack.TYPE_TEXT)
                        .setName(
                            externalSubtitleLabel
                                ?.takeIf(String::isNotBlank)
                                ?: "فارسی"
                        )
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(subtitleUrl)
                        .setContentType(
                            if(mime=="application/ttml+xml")
                                "application/ttml+xml"
                            else
                                "text/vtt"
                        )
                        .setLanguage("fa")
                        .build()
                )
            }
        }

        val builder=MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(
                contentType
                    ?.takeIf(String::isNotBlank)
                    ?: castContentType(url)
            )
            .setMetadata(metadata)
            .setCustomData(customData)

        if(tracks.isNotEmpty()) {
            builder.setMediaTracks(tracks)
        }

        val request=MediaLoadRequestData.Builder()
            .setMediaInfo(builder.build())
            .setAutoplay(autoplay)
            .setCurrentTime(positionMs.coerceAtLeast(0L))
            .build()

        val pending=runCatching { client.load(request) }.getOrNull()
            ?: return false

        pending.setResultCallback { result ->
            if(result.status.isSuccess) {
                if(tracks.isNotEmpty()) {
                    client.setActiveMediaTracks(longArrayOf(9001L))
                }
                publishRemoteState()
            } else {
                mutableState.value=mutableState.value.copy(
                    connection=FilmiqooCastConnection.ERROR,
                    error="تلویزیون نتونست این ویدیو رو باز کنه."
                )
            }
        }
        return true
    }

    fun togglePlayback() {
        remote()?.togglePlayback()
    }

    fun pause() {
        remote()?.pause()
    }

    fun play() {
        remote()?.play()
    }

    fun seekTo(positionMs:Long) {
        remote()?.seek(
            MediaSeekOptions.Builder()
                .setPosition(positionMs.coerceAtLeast(0L))
                .build()
        )
    }

    fun seekBy(deltaMs:Long) {
        seekTo((positionMs()+deltaMs).coerceAtLeast(0L))
    }

    fun disconnect(stopReceiver:Boolean=true) {
        runCatching {
            castContext?.sessionManager?.endCurrentSession(stopReceiver)
        }
    }

    override fun close() {
        detachRemote()
        castContext?.sessionManager?.let { manager ->
            runCatching {
                manager.removeSessionManagerListener(
                    sessionListener,
                    CastSession::class.java
                )
            }
        }
    }

    private fun castContentType(url:String):String {
        val clean=url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "application/x-mpegURL"
            clean.endsWith(".mpd") -> "application/dash+xml"
            clean.endsWith(".webm") -> "video/webm"
            clean.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
    }
}
