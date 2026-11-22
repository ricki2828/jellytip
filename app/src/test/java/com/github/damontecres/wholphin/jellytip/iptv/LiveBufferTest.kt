package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live TV buffer modes (web 76e062ea liveBuffer.ts Auto policy) mapped to ExoPlayer LoadControl profiles. */
class LiveBufferTest {
    private val t0 = 1_000_000_000L

    @Test
    fun tvAutoKeepsTheOldBuffers() {
        assertEquals(LiveBuffer.Profile.LOW, LiveBuffer.TV_AUTO)
        assertFalse(LiveBuffer.AUTO_STEPS)
    }

    @Test
    fun lowIsTheOldBuffers() {
        val p = LiveBuffer.Profile.LOW
        assertEquals(listOf(2_000, 20_000, 700, 1_500), listOf(p.minBufferMs, p.maxBufferMs, p.startMs, p.afterRebufferMs))
    }

    @Test
    fun everyProfileIsAValidLoadControl() {
        for (p in LiveBuffer.Profile.entries) {
            assertTrue(p.name, p.startMs <= p.minBufferMs && p.afterRebufferMs <= p.minBufferMs && p.minBufferMs <= p.maxBufferMs)
        }
    }

    @Test
    fun autoStepsUpToHighOnlyDebouncedWithGrace() {
        var s = LiveBuffer.initAuto(null, t0)
        assertEquals(LiveBuffer.Profile.NORMAL, s.level)
        val (s1, up1) = LiveBuffer.onStall(s, t0 + 1_000)
        assertTrue(up1)
        assertEquals(LiveBuffer.Profile.HIGH, s1.level)
        // inside the debounce / grace windows: no step
        assertFalse(LiveBuffer.onStall(s1, t0 + 2_000).second)
        assertFalse(LiveBuffer.onStall(s1, t0 + 15_000).second)
        // never above High by itself
        s = s1
        assertFalse(LiveBuffer.onStall(s, t0 + 60_000).second)
        assertEquals(LiveBuffer.Profile.HIGH, LiveBuffer.onStall(s, t0 + 60_000).first.level)
    }

    @Test
    fun autoStepsDownAfterQuietNeverBelowNormal() {
        val s = LiveBuffer.AutoState(LiveBuffer.Profile.HIGH, lastStall = t0, lastChange = t0, startedAt = t0)
        assertEquals(LiveBuffer.Profile.HIGH, LiveBuffer.onTick(s, t0 + 60_000).level)
        assertEquals(LiveBuffer.Profile.NORMAL, LiveBuffer.onTick(s, t0 + LiveBuffer.STEP_DOWN_MS + 1).level)
        val n = LiveBuffer.AutoState(LiveBuffer.Profile.NORMAL, startedAt = t0)
        assertEquals(LiveBuffer.Profile.NORMAL, LiveBuffer.onTick(n, t0 + 10 * LiveBuffer.STEP_DOWN_MS).level)
        assertEquals(LiveBuffer.Profile.NORMAL, LiveBuffer.initAuto(LiveBuffer.Profile.LOW, t0).level)
        assertEquals(LiveBuffer.Profile.HIGH, LiveBuffer.initAuto(LiveBuffer.Profile.MAX, t0).level)
    }

    @Test
    fun modesCycle() {
        assertEquals("low", LiveBuffer.nextMode("auto"))
        assertEquals("auto", LiveBuffer.nextMode("max"))
        assertEquals("Auto (Low)", LiveBuffer.modeLabel("auto"))
        assertEquals("High (~15 s)", LiveBuffer.modeLabel("high"))
    }

    @Test
    fun filmProfileIsDeepAndNotALiveMode() {
        val p = LiveBuffer.Profile.FILM
        assertTrue(p.maxBufferMs >= 180_000 && p.afterRebufferMs >= 30_000 && p.startMs <= 3_000)
        assertTrue("memory capped below ExoPlayer's ~144 MB default", p.targetBytes in 1..(100 * 1024 * 1024))
        assertTrue("never offered as a live buffer mode", "film" !in LiveBuffer.MODES)
        assertTrue("auto stepping never reaches it", LiveBuffer.AUTO_MAX.ordinal < p.ordinal)
    }
}
