package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Continue Watching labels and hiding for IPTV entries (web continueRow.ts). */
class ContinueRulesTest {
    private fun r(
        kind: String,
        pos: Double,
        dur: Double,
        s: Int? = 2,
        e: Int? = 5,
        at: Long = 100,
    ) = IptvResume(kind, "p:x:1", "p:s:1", "T", "Show", s, e, pos, dur, at, null, null, emptyList())

    @Test
    fun labels() {
        assertEquals("S2 E5 · 23 min left", IptvContinueRules.rowLabel(r("episode", 600.0, 600.0 + 23 * 60)))
        assertEquals("1h 5m left", IptvContinueRules.rowLabel(r("movie", 0.5, 65 * 60.0, null, null)))
        assertEquals("Next: S2 E6", IptvContinueRules.rowLabel(r("episode", 0.0, 0.0, 2, 6)))
        assertEquals("In progress", IptvContinueRules.rowLabel(r("movie", 50.0, 0.0, null, null)))
        assertEquals("1 min left", IptvContinueRules.leftText(10.0))
    }

    @Test
    fun doneAndHidden() {
        assertTrue(IptvContinueRules.nearlyDone(5800.0, 6000.0))
        assertTrue(IptvContinueRules.nearlyDone(5700.0, 6000.0))
        assertFalse(IptvContinueRules.nearlyDone(3000.0, 6000.0))
        assertFalse(IptvContinueRules.nearlyDone(3000.0, 0.0))
        assertTrue(IptvContinueRules.stillHidden(r("movie", 1.0, 10.0, at = 100), 150))
        assertFalse(IptvContinueRules.stillHidden(r("movie", 1.0, 10.0, at = 200), 150))
        assertFalse(IptvContinueRules.stillHidden(r("movie", 1.0, 10.0), null))
    }
}
