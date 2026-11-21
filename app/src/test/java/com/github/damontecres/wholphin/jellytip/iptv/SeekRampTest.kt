package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remote seeking ramp (web seekRamp.ts). */
class SeekRampTest {
    @Test
    fun pressesRampAndClamp() {
        var s = SeekRamp.advance(null, 1, 0, false, 100.0, 5000.0)
        assertEquals(110.0, s.target, 0.0)
        s = SeekRamp.advance(s, 1, 200, false, 0.0, 5000.0)
        assertEquals(120.0, s.target, 0.0)
        s = SeekRamp.advance(s, 1, 400, false, 0.0, 5000.0)
        assertEquals(150.0, s.target, 0.0) // third press: 30 s
        s = SeekRamp.advance(s, -1, 600, false, 0.0, 5000.0)
        assertEquals(140.0, s.target, 0.0) // reverse keeps target, ramp restarts
        val near = SeekRamp.advance(null, 1, 0, false, 4995.0, 5000.0)
        assertEquals(4999.0, near.target, 0.0)
        assertEquals(0.0, SeekRamp.advance(null, -1, 0, false, 3.0, 5000.0).target, 0.0)
    }

    @Test
    fun holdRampsAndThrottles() {
        val s = SeekRamp.advance(null, 1, 0, false, 0.0, 9999.0)
        assertSame(s, SeekRamp.advance(s, 1, 100, true, 0.0, 9999.0))
        assertEquals(60.0, SeekRamp.stepFor(1, 2000, true), 0.0)
        assertEquals(30.0, SeekRamp.stepFor(1, 800, true), 0.0)
    }

    @Test
    fun commitOnlyWhenReleasedAndSettled() {
        val s = SeekRamp.advance(null, 1, 0, false, 0.0, 9999.0)
        assertFalse(SeekRamp.commitDue(s, 500, false))
        assertFalse(SeekRamp.commitDue(s, 800, true))
        assertTrue(SeekRamp.commitDue(s, 800, false))
        // the release was missed (a dialog took the key up): commit once the "hold" stopped repeating
        assertTrue(SeekRamp.commitDue(s, 1600, true))
    }
}
