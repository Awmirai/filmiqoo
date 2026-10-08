package com.filmiqoo.app

/** Monotonic playing time, independent from position/seek and playback speed. Main-thread owned. */
internal class PlaybackWatchClock {
    private var lastAt:Long?=null
    private var wasPlaying=false
    var pendingMs:Long=0L
        private set
    var totalMs:Long=0L
        private set
    fun observe(now:Long,playing:Boolean){
        lastAt?.let{before->if(wasPlaying){val delta=(now-before).coerceAtLeast(0L);pendingMs+=delta;totalMs+=delta}}
        lastAt=now;wasPlaying=playing
    }
    fun acknowledged(milliseconds:Long){pendingMs=(pendingMs-milliseconds.coerceAtLeast(0L)).coerceAtLeast(0L)}
    fun reset(now:Long,playing:Boolean=false){pendingMs=0L;totalMs=0L;lastAt=now;wasPlaying=playing}
}
