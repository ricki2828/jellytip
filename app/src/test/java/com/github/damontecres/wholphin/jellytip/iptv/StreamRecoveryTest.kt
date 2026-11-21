package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.iptv.StreamRecovery.Conn
import com.github.damontecres.wholphin.jellytip.iptv.StreamRecovery.Denied
import com.github.damontecres.wholphin.jellytip.iptv.StreamRecovery.Effect
import com.github.damontecres.wholphin.jellytip.iptv.StreamRecovery.Event
import com.github.damontecres.wholphin.jellytip.iptv.StreamRecovery.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecoveryTest {
    private fun run(
        s: StreamRecovery.State,
        vararg es: Event,
    ): Pair<StreamRecovery.State, List<Effect>> {
        var st = s
        val all = ArrayList<Effect>()
        for (e in es) {
            val r = StreamRecovery.step(st, e)
            st = r.state
            all += r.effects
        }
        return st to all
    }

    private val live = StreamRecovery.State(live = true)

    @Test
    fun faultReleasesBeforeOpening() {
        val (s, fx) = run(live, Event.Fault(0, canHls = true))
        assertEquals(Phase.RECONNECTING, s.phase)
        assertEquals(1, s.attempt)
        assertEquals(Conn.RELEASING, s.conn)
        assertEquals(listOf(Effect.Release), fx)
        // the open only comes once the release is confirmed, after the backoff (never under the grace gap)
        val (s2, fx2) = run(s, Event.Released)
        assertEquals(Conn.OPENING, s2.conn)
        assertEquals(listOf(Effect.Open(StreamRecovery.backoffMs(1), null, "ts")), fx2)
    }

    @Test
    fun backoffThenHlsSwitchThenFail() {
        assertEquals(1_000, StreamRecovery.backoffMs(1))
        assertEquals(8_000, StreamRecovery.backoffMs(4))
        assertEquals(8_000, StreamRecovery.backoffMs(9))
        var s = run(live, Event.Fault(0, true), Event.Released, Event.Opened).first
        repeat(3) { s = run(s, Event.Fault(0, true), Event.Released, Event.Opened).first }
        assertEquals(4, s.attempt)
        s = run(s, Event.Fault(0, true)).first
        assertEquals(Phase.SWITCHED, s.phase)
        assertEquals("m3u8", s.format)
        val (s2, fx) = run(s, Event.Released)
        assertEquals(Effect.Open(StreamRecovery.GRACE_MS, null, "m3u8"), fx.single())
        s = run(s2, Event.Opened, Event.Fault(0, true), Event.Released, Event.Opened, Event.Fault(0, true)).first
        assertEquals(Phase.FAILED, s.phase)
        assertEquals("This channel isn't responding. Try another channel or retry.", StreamRecovery.copy(s))
    }

    @Test
    fun vodResumesAtPositionAndNeverSwitches() {
        val vod = StreamRecovery.State(live = false)
        val (s, _) = run(vod, Event.Fault(1234, canHls = true), Event.Released)
        assertEquals(1234L, s.resumeAt)
        var st = s
        repeat(4) { st = run(st, Event.Opened, Event.Fault(1300, true), Event.Released).first }
        assertEquals(Phase.FAILED, st.phase)
        assertFalse(st.switched)
    }

    @Test
    fun recoveredIsForgivenOnlyWhenStable() {
        var s = run(live, Event.Fault(0, true), Event.Released, Event.Opened, Event.Recovered).first
        assertEquals(Phase.PLAYING, s.phase)
        assertTrue(s.unstable)
        // a new fault before stable keeps counting
        s = run(s, Event.Fault(0, true)).first
        assertEquals(2, s.attempt)
        s = run(s, Event.Released, Event.Opened, Event.Recovered, Event.Stable).first
        assertEquals(0, s.attempt)
        assertFalse(s.unstable)
    }

    @Test
    fun busyOnceThenBlocked() {
        var s = run(live, Event.Fault(0, true), Event.Released).first
        val (s2, fx) = run(s, Event.Busy)
        assertTrue(s2.busyRetried)
        assertTrue(fx.contains(Effect.Release))
        s = run(s2, Event.Released, Event.Busy).first
        assertEquals(Phase.BLOCKED, s.phase)
    }

    @Test
    fun fatalDeniesAndRetryNowDebounces() {
        val (s, fx) = run(live, Event.Fatal(Denied.AUTH))
        assertEquals(Phase.DENIED, s.phase)
        assertTrue(fx.contains(Effect.Release))
        assertTrue(StreamRecovery.copy(s)!!.contains("login"))
        val (r, rfx) = run(s, Event.Released, Event.RetryNow(0, 10_000))
        assertEquals(Phase.RECONNECTING, r.phase)
        assertTrue(rfx.any { it is Effect.Open })
        // mashing Retry while it opens does nothing
        assertEquals(r, StreamRecovery.step(r, Event.RetryNow(0, 10_500)).state)
    }

    @Test
    fun offlineWaitsWithoutSpendingAttempts() {
        var s = run(live, Event.Offline, Event.Fault(0, true)).first
        assertEquals(1, s.attempt)
        val (s2, fx) = run(s, Event.Released)
        assertTrue(fx.isEmpty()) // no open while offline
        s = s2
        val (s3, fx3) = run(s, Event.Online)
        assertEquals(Effect.Open(StreamRecovery.GRACE_MS, null, "ts"), fx3.single())
        assertEquals(1, s3.attempt)
    }

    @Test
    fun staleReleaseNeverClosesAHeldStream() {
        val (s, fx) = run(live, Event.Released)
        assertEquals(Conn.HELD, s.conn)
        assertTrue(fx.isEmpty())
    }

    @Test
    fun cancelEnds() {
        val (s, fx) = run(live, Event.Cancel)
        assertEquals(Phase.ENDED, s.phase)
        assertTrue(fx.contains(Effect.Exit))
        assertEquals(s, StreamRecovery.step(s, Event.Fault(0, true)).state)
    }

    @Test
    fun permanentReasons() {
        assertEquals(Denied.AUTH, StreamRecovery.permanentReason(403, "provider_auth"))
        assertEquals(Denied.LINK, StreamRecovery.permanentReason(410, null))
        assertEquals(Denied.RATE, StreamRecovery.permanentReason(429, null))
        assertEquals(Denied.MISSING, StreamRecovery.permanentReason(404, null))
        assertNull(StreamRecovery.permanentReason(500, null))
        assertNull(StreamRecovery.permanentReason(0, "network"))
        assertEquals("refused", StreamRecovery.deniedFailReason(Denied.DENIED))
        assertNull(StreamRecovery.deniedFailReason(Denied.AUTH))
    }
}
