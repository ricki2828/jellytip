package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportDelayTest {
    @Test
    fun clampKeepsRange() {
        assertEquals(30, DelayMath.clampDelay(null))
        assertEquals(0, DelayMath.clampDelay(-4.0))
        assertEquals(300, DelayMath.clampDelay(999.0))
        assertEquals(42, DelayMath.clampDelay(41.6))
        assertEquals(300, DelayMath.stepDelay(295, 10))
        assertEquals(0, DelayMath.stepDelay(3, -5))
    }

    @Test
    fun revealHoldsByDelayPlusLag() {
        // event at 1000, delay 30, playhead 10 s behind the edge: revealed from 1040
        assertFalse(DelayMath.isRevealed(1000.0, 1039.0, 30, 10.0, false))
        assertTrue(DelayMath.isRevealed(1000.0, 1040.0, 30, 10.0, false))
        // no event time = live reveal on; replay reveals nothing
        assertTrue(DelayMath.isRevealed(null, 0.0, 30, 0.0, false))
        assertFalse(DelayMath.isRevealed(null, 0.0, 30, 0.0, true))
        assertFalse(DelayMath.isRevealed(0.0, 99999.0, 30, 0.0, true))
    }

    @Test
    fun replayHysteresis() {
        assertFalse(DelayMath.replayState(false, 120.0))
        assertTrue(DelayMath.replayState(false, 121.0))
        assertTrue(DelayMath.replayState(true, 105.0))
        assertFalse(DelayMath.replayState(true, 99.0))
        assertTrue(DelayMath.replayState(false, 0.0, forced = true))
    }

    @Test
    fun calibrateAnchorsOnLatestMoment() {
        assertEquals(25, DelayMath.calibratedDelay(1000.0, 1030.0, 5.0))
        assertNull(DelayMath.calibratedDelay(null, 1030.0, 0.0))
        assertNull(DelayMath.calibratedDelay(1000.0, 1400.0, 0.0)) // stale moment
        assertNull(DelayMath.calibratedDelay(1000.0, 990.0, 0.0)) // tap before the event
        // future moments never become the anchor
        assertEquals(10.0, DelayMath.noteEvent(10.0, 500.0, 100.0))
        assertEquals(50.0, DelayMath.noteEvent(10.0, 50.0, 100.0))
        assertEquals(50.0, DelayMath.noteEvent(50.0, 20.0, 100.0))
    }

    @Test
    fun channelKey() {
        assertEquals("p1" to "123", DelayMath.parseChannelKey("p1:123"))
        assertEquals("_" to "abc", DelayMath.parseChannelKey("abc"))
    }

    @Test
    fun revealClockFlow() {
        var now = 1000.0
        var lag = 0.0
        var forced = false
        val c = RevealClock({ lag }, { forced }, { now })
        c.reset(30)
        assertFalse(c.reveal(990.0))
        assertTrue(c.reveal(970.0))
        // server value applies until the viewer changes it
        c.serverDelay(10)
        assertTrue(c.reveal(990.0))
        c.setDelay(60)
        c.serverDelay(5)
        assertEquals(60, c.delaySec)
        // calibrate: nothing seen yet
        assertEquals(DelayMath.Calibrate.None, c.calibrate())
        c.noteMoment(980.0)
        assertTrue(c.canCalibrate())
        assertEquals(DelayMath.Calibrate.Set(20), c.calibrate())
        // catch-up forces replay
        forced = true
        assertFalse(c.reveal())
        assertEquals(DelayMath.Calibrate.Replay, c.calibrate())
        forced = false
        lag = 200.0
        assertFalse(c.reveal())
        lag = 0.0
        now = 1000.0
        assertTrue(c.reveal())
    }

    @Test
    fun holdSecsCountsDown() {
        val c = RevealClock({ 0.0 }, { false }, { 1000.0 })
        c.reset(30)
        assertEquals(0, c.holdSecs(960.0))
        assertEquals(20, c.holdSecs(990.0))
        assertNull(c.holdSecs(null))
        val r = RevealClock({ 0.0 }, { true }, { 1000.0 })
        assertNull(r.holdSecs(990.0))
    }

    private fun cand(
        id: String,
        kind: String,
        at: Double,
        fav: Boolean = false,
        late: Boolean = false,
    ) = AlertPolicy.Candidate(id, kind, at, favourite = fav, late = late, payload = id)

    @Test
    fun keyEvents() {
        val m = AlertPolicy.Mode.KEY
        assertTrue(AlertPolicy.isKeyEvent(cand("a", "try", 0.0), m))
        assertFalse(AlertPolicy.isKeyEvent(cand("a", "lead_change", 0.0), m))
        assertTrue(AlertPolicy.isKeyEvent(cand("a", "lead_change", 0.0, late = true), m))
        assertFalse(AlertPolicy.isKeyEvent(cand("a", "score", 0.0), m))
        assertTrue(AlertPolicy.isKeyEvent(cand("a", "score", 0.0, fav = true), m))
        assertFalse(AlertPolicy.isKeyEvent(cand("a", "try", 0.0), AlertPolicy.Mode.OFF))
        assertFalse(AlertPolicy.isKeyEvent(cand("a", "try", 0.0), m, blowout = true))
        assertTrue(AlertPolicy.isKeyEvent(cand("a", "score", 0.0), AlertPolicy.Mode.ALL))
    }

    @Test
    fun gateHoldsUntilRevealAndSpacesAlerts() {
        val reveal = { at: Double -> at <= 970.0 } // delay 30 at now=1000
        val q = listOf(cand("a", "try", 980.0), cand("b", "lead_change", 960.0, late = true), cand("c", "goal", 965.0))
        val p = AlertPolicy.pick(q, null, 1000.0, reveal, AlertPolicy.Mode.KEY)
        // c (goal, prio 5) beats b; a is still held
        assertEquals("c", p.show?.id)
        assertEquals(listOf("a", "b"), p.pending.map { it.id }.sorted())
        // within 30 s of the last alert nothing shows
        val p2 = AlertPolicy.pick(p.pending, 1000.0, 1010.0, { true }, AlertPolicy.Mode.KEY)
        assertNull(p2.show)
        val p3 = AlertPolicy.pick(p2.pending, 1000.0, 1031.0, { true }, AlertPolicy.Mode.KEY)
        assertEquals("a", p3.show?.id)
    }

    @Test
    fun gateDropsStaleAndDuplicates() {
        val q = listOf(cand("a", "try", 100.0), cand("a", "try", 100.0), cand("z", "try", 999.0).copy(revealedAt = 800.0))
        val p = AlertPolicy.pick(q, null, 1000.0, { false }, AlertPolicy.Mode.KEY)
        assertNull(p.show)
        assertTrue(p.pending.isEmpty())
    }

    @Test
    fun lateAndBlowout() {
        assertTrue(AlertPolicy.isLateGame("nfl", "4", "12:00"))
        assertFalse(AlertPolicy.isLateGame("nfl", "3", "1:00"))
        assertTrue(AlertPolicy.isLateGame("rugby", "2", "66:10"))
        assertFalse(AlertPolicy.isLateGame(null, "2", "80"))
        assertTrue(AlertPolicy.isBlowout("rugby", 40, 6, true))
        assertFalse(AlertPolicy.isBlowout("rugby", 40, 6, false))
        assertFalse(AlertPolicy.isBlowout("cricket", 400, 6, true))
    }

    @Test
    fun scorebugAndClock() {
        assertTrue(CompanionPolicy.scorebugVisible(1000, 0, false, false))
        assertFalse(CompanionPolicy.scorebugVisible(9000, 0, false, false))
        assertTrue(CompanionPolicy.scorebugVisible(9000, 0, true, false))
        assertFalse(CompanionPolicy.scorebugVisible(1000, 0, true, true))
        assertEquals("12:20", CompanionPolicy.tickClock("12:30", "nfl", true, 10.0))
        assertEquals("62:30", CompanionPolicy.tickClock("62:10", "nrl", true, 20.0))
        assertEquals("62:30", CompanionPolicy.tickClock("62:10", "nrl", true, 90.0)) // capped run-on
        assertEquals("0:00", CompanionPolicy.tickClock("0:05", "nba", true, 10.0))
        assertEquals("63'", CompanionPolicy.tickClock("63'", "football", true, 10.0))
        assertEquals("12:30", CompanionPolicy.tickClock("12:30", "nfl", false, 10.0))
        assertEquals("12:30", CompanionPolicy.tickClock("12:30", null, true, 10.0))
        assertFalse(CompanionPolicy.clockRunning("post", null, Triple("1", "2", 0)))
        assertFalse(CompanionPolicy.clockRunning("in", Triple("5:00", "2", 0), Triple("5:00", "2", 10_000)))
        assertTrue(CompanionPolicy.clockRunning("in", Triple("5:10", "2", 0), Triple("5:00", "2", 10_000)))
        assertEquals(1, CompanionPolicy.newestRevealedIndex(listOf(10L, 20L, 30L)) { (it ?: 0.0) <= 25.0 })
        assertEquals(-1, CompanionPolicy.newestRevealedIndex(listOf(30L)) { false })
    }
}
