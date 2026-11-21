package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportWowTest {
    private val now = 1_000_000L

    private fun ch(id: String) = FxChannel(id, "Ch $id", null, null, null, 0.9, false, false, null, null, false, null)

    private fun fx(
        fid: String,
        kickoff: Long,
        status: String,
        fav: Boolean = true,
        reminder: String? = null,
        channels: List<FxChannel> = listOf(ch("p:$fid")),
    ) = FxItem(fid, "NRL", "nrl", null, "Warriors", "Panthers", null, null, "WAR", "PAN", null, null, kickoff, status, null, null, 0.0, null, fav, false, channels, null, reminder, null, "m$fid")

    @Test
    fun kickoffSoonThenLiveOnce() {
        val f = fx("a", now + 120, "pre")
        val d = KickoffPolicy.due(listOf(f), now, emptySet(), null)
        assertEquals("soon", d?.phase)
        assertEquals("Kick-off in 2 min", KickoffPolicy.headline(d!!, now))
        assertNull(KickoffPolicy.due(listOf(f), now, setOf("a:soon"), null))
        val live = f.copy(status = "in", kickoff = now - 30)
        assertEquals("live", KickoffPolicy.due(listOf(live), now, setOf("a:soon"), null)?.phase)
        assertNull(KickoffPolicy.due(listOf(live), now, setOf("a:live"), null))
        // too early, too late, not a favourite, has a reminder, already watching, no channel
        assertNull(KickoffPolicy.due(listOf(fx("b", now + 600, "pre")), now, emptySet(), null))
        assertNull(KickoffPolicy.due(listOf(fx("b", now - 900, "in")), now, emptySet(), null))
        assertNull(KickoffPolicy.due(listOf(fx("b", now + 60, "pre", fav = false)), now, emptySet(), null))
        assertNull(KickoffPolicy.due(listOf(fx("b", now + 60, "pre", reminder = "7")), now, emptySet(), null))
        assertNull(KickoffPolicy.due(listOf(fx("b", now + 60, "pre")), now, emptySet(), "p:b"))
        assertNull(KickoffPolicy.due(listOf(fx("b", now + 60, "pre", channels = emptyList())), now, emptySet(), null))
        // a stale "pre" past kick-off counts as live
        assertEquals("live", KickoffPolicy.due(listOf(fx("c", now - 10, "pre")), now, emptySet(), null)?.phase)
    }

    @Test
    fun fullTimeNextGame() {
        val done = fx("a", now - 6000, "post")
        val favSoon = fx("b", now + 900, "pre")
        val otherLive = fx("c", now - 600, "in", fav = false)
        val farFav = fx("d", now + 7200, "pre")
        assertEquals("b", FullTimePolicy.nextGame(listOf(done, otherLive, favSoon, farFav), setOf("a", "ma"), now)?.fid)
        assertEquals("c", FullTimePolicy.nextGame(listOf(done, otherLive, farFav), setOf("a"), now)?.fid)
        assertNull(FullTimePolicy.nextGame(listOf(done, farFav), setOf("a"), now))
        assertEquals("Starts in 15 min · OK to switch", FullTimePolicy.nextLine(favSoon, now))
        assertEquals("Live now · OK to switch", FullTimePolicy.nextLine(otherLive, now))
    }

    @Test
    fun breakModeOpensAndCloses() {
        val R = BreakModePolicy.Reason
        assertEquals(R.BREAK, BreakModePolicy.autoOpen(true, false, false, GameKind.BREAK, "HT", null, null, 0, false))
        // closed by hand during this break: stays closed for this break, opens for the next
        assertNull(BreakModePolicy.autoOpen(true, false, false, GameKind.BREAK, "HT", "HT", 0, 1, false))
        assertEquals(R.BREAK, BreakModePolicy.autoOpen(true, false, false, GameKind.BREAK, "FT2", "HT", 0, 1, false))
        assertNull(BreakModePolicy.autoOpen(false, false, false, GameKind.BREAK, "HT", null, null, 0, false))
        assertNull(BreakModePolicy.autoOpen(true, true, false, GameKind.BREAK, "HT", null, null, 0, false))
        assertEquals(R.MOMENT, BreakModePolicy.autoOpen(true, false, false, GameKind.LIVE, null, null, null, 0, true))
        assertNull(BreakModePolicy.autoOpen(true, false, false, GameKind.BLOWOUT, null, null, null, 0, true))
        assertNull(BreakModePolicy.autoOpen(true, false, false, GameKind.TIGHT, null, null, 10_000, 60_000, true))
        assertFalse(BreakModePolicy.autoClose(R.USER, 0, 99_999, GameKind.LIVE, false, false))
        assertTrue(BreakModePolicy.autoClose(R.MOMENT, 0, 6_000, GameKind.LIVE, false, false))
        assertFalse(BreakModePolicy.autoClose(R.MOMENT, 0, 5_000, GameKind.LIVE, false, false))
        assertFalse(BreakModePolicy.autoClose(R.BREAK, 0, 99_999, GameKind.BREAK, false, false))
        assertTrue(BreakModePolicy.autoClose(R.BREAK, 0, 1, GameKind.LIVE, false, false))
        assertFalse(BreakModePolicy.autoClose(R.BREAK, 0, 1, GameKind.LIVE, true, false))
    }

    @Test
    fun catchUpOfferAndCards() {
        assertTrue(CatchUpPolicy.offer(true, false, false, "in", now - 1500, now))
        assertFalse(CatchUpPolicy.offer(true, false, false, "in", now - 300, now))
        assertFalse(CatchUpPolicy.offer(true, true, false, "in", now - 1500, now))
        assertFalse(CatchUpPolicy.offer(true, false, true, "in", now - 1500, now))
        assertFalse(CatchUpPolicy.offer(false, false, false, "in", now - 1500, now))
        assertFalse(CatchUpPolicy.offer(true, false, false, "post", now - 1500, now))
        assertFalse(CatchUpPolicy.offer(true, false, false, "in", null, now))
        assertEquals("You're joining 25 minutes in", CatchUpPolicy.joiningLine(now - 1500, now))

        val tl = listOf(TimelineEntry("1", "try", "home", "Try Warriors", "12'", 900.0), TimelineEntry("2", "try", "away", "Try Panthers", "27'", 1900.0))
        val latest = MatchLive("in", null, null, "4", "4", emptyList(), emptyList(), emptyList(), null, null, null, emptyList(), updatedAt = 2000, timeline = tl)
        val cards = CatchUpPolicy.cards(null, latest) { (it ?: 0.0) <= 1000 }
        assertEquals(1, cards.size)
        assertEquals(4, cards[0].points)
        // no timeline: scoring plays of the released answer, oldest first
        val plays = listOf(LivePlay("40:00", "2", "Second", true, "away", 6), LivePlay("10:00", "1", "Not scoring", false), LivePlay("05:00", "1", "First", true, "home", 6))
        val rel = latest.copy(timeline = emptyList(), plays = plays)
        assertEquals(listOf("First", "Second"), CatchUpPolicy.cards(rel, rel.copy()) { true }.map { it.text })
        // nothing released: no cards
        assertTrue(CatchUpPolicy.cards(null, rel) { false }.isEmpty())
    }
}
