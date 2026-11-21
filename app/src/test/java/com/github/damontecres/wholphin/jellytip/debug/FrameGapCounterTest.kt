package com.github.damontecres.wholphin.jellytip.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGapCounterTest {
    private val p = FrameGapCounter.periodNs(60f)

    @Test
    fun steadyFramesMissNothing() {
        val c = FrameGapCounter(p)
        repeat(120) { c.onFrame(it * p + (if (it % 2 == 0) 300_000L else -300_000L)) } // ±0.3 ms jitter
        assertEquals(120, c.frames)
        assertEquals(0L, c.missed)
    }

    @Test
    fun gapsCountMissedRefreshes() {
        val c = FrameGapCounter(p)
        c.onFrame(0)
        c.onFrame(p)
        c.onFrame(3 * p) // 1 missed
        c.onFrame(8 * p) // 4 missed
        assertEquals(5L, c.missed)
        assertEquals(5 * p, c.longestGapNs)
        assertEquals("uiFrames=4 missedVsyncs=5 longestGap=83ms", c.summary())
    }

    @Test
    fun repeatedOrBackwardTimesAreIgnoredForGaps() {
        val c = FrameGapCounter(p)
        c.onFrame(10 * p)
        c.onFrame(10 * p)
        c.onFrame(9 * p)
        c.onFrame(11 * p)
        assertEquals(0L, c.missed)
    }

    @Test
    fun periodFallsBackTo60Hz() {
        assertEquals(16_666_667L, FrameGapCounter.periodNs(0f))
        assertEquals(20_000_000L, FrameGapCounter.periodNs(50f))
    }
}
