package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class RecPlayTest {
    private fun rec(
        state: String,
        start: Long = 1000,
        stop: Long = 4600,
        began: Long? = null,
        ended: Long? = null,
        stopPadded: Long? = null,
        dur: Double? = null,
    ) = IptvRecording("r", "c", "C", "T", null, start, stop, state, 0, null, false, true, null, dur, began = began, ended = ended, stopPadded = stopPadded)

    @Test
    fun lengthFromThePlayerFirst() {
        assertEquals(2000.0, RecPlay.lengthSec(rec("done"), 9999, 2000.0), 0.0)
    }

    @Test
    fun inProgressGrowsWithTheClock() {
        assertEquals(500.0, RecPlay.lengthSec(rec("recording", began = 1100), 1600, Double.NaN), 0.0)
        assertEquals(600.0, RecPlay.lengthSec(rec("recording"), 1600, 0.0), 0.0)
    }

    @Test
    fun finishedUsesSavedThenBeganEndedThenSlot() {
        assertEquals(3300.0, RecPlay.lengthSec(rec("done", dur = 3300.0), 0, 0.0), 0.0)
        assertEquals(3500.0, RecPlay.lengthSec(rec("done", began = 1000, ended = 4500), 0, 0.0), 0.0)
        assertEquals(4000.0, RecPlay.lengthSec(rec("done", stopPadded = 5000), 0, 0.0), 0.0)
        assertEquals(3600.0, RecPlay.lengthSec(rec("done"), 0, 0.0), 0.0)
        assertEquals(0.0, RecPlay.lengthSec(null, 0, 0.0), 0.0)
    }

    @Test
    fun seeksStayInside() {
        assertEquals(597.0, RecPlay.clampSeek(900.0, 600.0, true), 0.0)
        assertEquals(599.0, RecPlay.clampSeek(900.0, 600.0, false), 0.0)
        assertEquals(0.0, RecPlay.clampSeek(-5.0, 600.0, false), 0.0)
        assertEquals(42.0, RecPlay.clampSeek(42.0, 0.0, false), 0.0)
    }
}
