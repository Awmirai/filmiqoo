package com.filmiqoo.app

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

class FilmiqooCastController(context: Context) {
    private val appContext=context.applicationContext
    private val castContext: CastContext? =
        runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()

    private fun remote(): RemoteMediaClient? =
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    fun isConnected(): Boolean =
        castContext?.sessionManager?.currentCastSession?.isConnected == true

    fun deviceName(): String =
        castContext?.sessionManager?.currentCastSession
            ?.castDevice
            ?.friendlyName
            .orEmpty()

    fun positionMs(): Long =
        remote()?.approximateStreamPosition?.coerceAtLeast(0L) ?: 0L

    fun durationMs(): Long =
        remote()?.streamDuration?.coerceAtLeast(0L) ?: 0L

    fun isPlaying(): Boolean = remote()?.isPlaying == true

    fun load(
        url:String,
        title:String,
        subtitle:String,
        artworkUrl:String?,
        positionMs:Long,
        autoplay:Boolean
    ): Boolean {
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

        val info=MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(castContentType(url))
            .setMetadata(metadata)
            .build()

        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(autoplay)
                .setCurrentTime(positionMs.coerceAtLeast(0L))
                .build()
        )
        return true
    }

    fun togglePlayback() {
        val client=remote() ?: return
        if(client.isPlaying) client.pause() else client.play()
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

    private fun castContentType(url:String):String {
        val clean=url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "application/x-mpegURL"
            clean.endsWith(".mpd") -> "application/dash+xml"
            clean.endsWith(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }
}
