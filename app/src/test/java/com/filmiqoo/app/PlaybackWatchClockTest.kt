package com.filmiqoo.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackWatchClockTest {
    @Test fun pauseAndBufferingDoNotBecomeWatchedTime(){
        val clock=PlaybackWatchClock()
        clock.observe(0,false);clock.observe(8_000,true);clock.observe(18_000,false)
        clock.observe(40_000,false);clock.observe(50_000,true);clock.observe(55_000,false)
        assertEquals(15_000,clock.pendingMs)
    }
    @Test fun failedHeartbeatRetainsTimeAndAcknowledgesOnlySentChunk(){
        val clock=PlaybackWatchClock()
        clock.reset(0,true);clock.observe(45_000,true)
        clock.acknowledged(30_000);clock.observe(48_000,false)
        assertEquals(18_000,clock.pendingMs)
        clock.acknowledged(18_000);assertEquals(0,clock.pendingMs)
    }
    @Test fun resetSeparatesSessionsAndClockRollbackAddsNoTime(){
        val clock=PlaybackWatchClock()
        clock.reset(10_000,true);clock.observe(9_000,false)
        assertEquals(0,clock.pendingMs)
        clock.reset(20_000,true);clock.observe(25_000,false)
        assertEquals(5_000,clock.pendingMs)
        clock.reset(30_000);assertEquals(0,clock.pendingMs)
    }
}
