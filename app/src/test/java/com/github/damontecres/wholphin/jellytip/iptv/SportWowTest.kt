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
        assertEquals(BreakModePolicy.Reason.BREAK, BreakModePolicy.autoOpen(true, false, false, GameKind.BREAK, "HT", null, null, 0, false))
        // closed by hand during this break: stays closed for this break, opens for the next
        assertNull(BreakModePolicy.autoOpen(true, false, false, GameKind.BREAK, "HT", "HT", 0, 1, false))
        assertEquals(BreakModePolicy.Reason.BREAK, BreakModePolicy.autoOpen(true, false, false, GameKind.BREAK, "FT2", "HT", 0, 1, false))
        assertNull(BreakModePolicy.autoOpen(false, false, false, GameKind.BREAK, "HT", null, null, 0, false))
        assertNull(BreakModePolicy.autoOpen(true, true, false, GameKind.BREAK, "HT", null, null, 0, false))
        assertEquals(BreakModePolicy.Reason.MOMENT, BreakModePolicy.autoOpen(true, false, false, GameKind.LIVE, null, null, null, 0, true))
        assertNull(BreakModePolicy.autoOpen(true, false, false, GameKind.BLOWOUT, null, null, null, 0, true))
        assertNull(BreakModePolicy.autoOpen(true, false, false, GameKind.TIGHT, null, null, 10_000, 60_000, true))
        assertFalse(BreakModePolicy.autoClose(BreakModePolicy.Reason.USER, 0, 99_999, GameKind.LIVE, false, false))
        assertTrue(BreakModePolicy.autoClose(BreakModePolicy.Reason.MOMENT, 0, 6_000, GameKind.LIVE, false, false))
        assertFalse(BreakModePolicy.autoClose(BreakModePolicy.Reason.MOMENT, 0, 5_000, GameKind.LIVE, false, false))
        assertFalse(BreakModePolicy.autoClose(BreakModePolicy.Reason.BREAK, 0, 99_999, GameKind.BREAK, false, false))
        assertTrue(BreakModePolicy.autoClose(BreakModePolicy.Reason.BREAK, 0, 1, GameKind.LIVE, false, false))
        assertFalse(BreakModePolicy.autoClose(BreakModePolicy.Reason.BREAK, 0, 1, GameKind.LIVE, true, false))
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
        val cards = CatchUpPolicy.cards(null, latest, null) { (it ?: 0.0) <= 1000 }
        assertEquals(1, cards.size)
        assertEquals(4, cards[0].points)
        // no timeline: scoring plays of the released answer, oldest first
        val plays = listOf(LivePlay("40:00", "2", "Second", true, "away", 6), LivePlay("10:00", "1", "Not scoring", false), LivePlay("05:00", "1", "First", true, "home", 6))
        val rel = latest.copy(timeline = emptyList(), plays = plays)
        assertEquals(listOf("First", "Second"), CatchUpPolicy.cards(rel, rel.copy(), rel) { true }.map { it.text })
        // nothing released: no cards
        assertTrue(CatchUpPolicy.cards(null, rel, rel) { false }.isEmpty())
    }

    private fun answer(
        plays: List<LivePlay> = emptyList(),
        timeline: List<TimelineEntry> = emptyList(),
        updatedAt: Long = 2000,
    ) = MatchLive("in", null, null, "0", "0", emptyList(), emptyList(), plays, null, null, null, emptyList(), updatedAt = updatedAt, timeline = timeline)

    private fun play(
        id: String,
        wallclock: Double?,
        scoring: Boolean = true,
    ) = LivePlay("10:00", "1", "Play $id", scoring, "home", 3, id = id, wallclock = wallclock)

    // Reveal released up to t = 1000; null is "released" too, so the wallclock != null rule is what hides it
    private val upTo1000: (Double?) -> Boolean = { (it ?: 0.0) <= 1000 }

    // Plays are gated on wallclock + slack: these wallclocks sit [CatchUpPolicy.WALLCLOCK_SLACK_SEC] before the stamps
    private fun wc(t: Double) = t - CatchUpPolicy.WALLCLOCK_SLACK_SEC

    @Test
    fun catchUpCardsFromAnUnreleasedFullAnswer() {
        // Newest first, as the server sends them: c not yet released, b released by its own wallclock, n has none
        val full = answer(listOf(play("c", wc(1500.0)), play("n", null), play("b", wc(900.0)), play("x", wc(800.0), scoring = false), play("a", wc(500.0))))
        val cards = CatchUpPolicy.cards(null, full, full, upTo1000)
        assertEquals(listOf("Play a", "Play b"), cards.map { it.text })
        // Reveal off (replay): nothing
        assertTrue(CatchUpPolicy.cards(null, full, full) { false }.isEmpty())
        // Only plays with no wallclock in an unreleased answer: nothing (spoiler-safe)
        assertTrue(CatchUpPolicy.cards(null, answer(listOf(play("n", null))), answer(listOf(play("n", null))), upTo1000).isEmpty())
    }

    @Test
    fun catchUpCardsMergeReleasedAndNewestDeDuped() {
        // The released full answer (older) has a and z; the newest one has b (released stamp), a again, and c (held)
        val released = answer(listOf(play("a", null), play("z", null)), updatedAt = 900)
        val newest = answer(listOf(play("c", wc(1500.0)), play("b", wc(950.0)), play("a", wc(500.0))))
        val cards = CatchUpPolicy.cards(released, newest, newest, upTo1000)
        // a once; z (only in the released one) counts as older; oldest first
        assertEquals(listOf("Play z", "Play a", "Play b"), cards.map { it.text })
        // A play with no wallclock in the newest answer still shows when the released answer already had it
        val rel2 = answer(listOf(play("n", null)), updatedAt = 900)
        val new2 = answer(listOf(play("m", null), play("n", null)))
        assertEquals(listOf("Play n"), CatchUpPolicy.cards(rel2, new2, new2, upTo1000).map { it.text })
        // Released answer alone (no newer full one)
        assertEquals(listOf("Play z", "Play a"), CatchUpPolicy.cards(released, released, released, upTo1000).map { it.text })
    }

    @Test
    fun catchUpPlaysWaitForWallclockPlusSlack() {
        // Event at 970: its moment stamp is later, so at reveal 1000 (970 + 60 = 1030 not yet released) it is held
        val full = answer(listOf(play("late", 970.0), play("early", 900.0)))
        assertEquals(listOf("Play early"), CatchUpPolicy.cards(null, full, full, upTo1000).map { it.text })
        assertEquals(listOf("Play early", "Play late"), CatchUpPolicy.cards(null, full, full) { (it ?: 0.0) <= 1030 }.map { it.text })
    }

    @Test
    fun catchUpCardsCapKeepsTheNewest() {
        val plays = (20 downTo 1).map { play("p$it", it.toDouble()) }
        val full = answer(plays)
        val cards = CatchUpPolicy.cards(null, full, full) { true }
        assertEquals(CatchUpPolicy.MAX_CARDS, cards.size)
        assertEquals("Play p9", cards.first().text)
        assertEquals("Play p20", cards.last().text)
    }

    @Test
    fun catchUpCardsNrlTimelineUnchanged() {
        val tl = listOf(TimelineEntry("1", "try", "home", "Try Warriors", "12'", 900.0), TimelineEntry("2", "conversion", "home", "Conversion", "13'", 1100.0))
        // The timeline wins over plays; each entry gated on its own time
        val full = answer(listOf(play("a", wc(500.0))), timeline = tl)
        val cards = CatchUpPolicy.cards(null, full, full, upTo1000)
        assertEquals(listOf("Try Warriors"), cards.map { it.text })
        assertEquals(4, cards[0].points)
        // Timeline only on a light latest answer (no full one): still used
        assertEquals(listOf("Try Warriors"), CatchUpPolicy.cards(null, answer(timeline = tl), null, upTo1000).map { it.text })
    }

    @Test
    fun catchUpOptions() {
        assertEquals(listOf(0, 1, 2), CatchUpPolicy.options(true, 5))
        assertEquals(listOf(0, 2), CatchUpPolicy.options(true, 0))
        assertEquals(listOf(1, 2), CatchUpPolicy.options(false, 3))
        // Only Live left: no prompt
        assertTrue(CatchUpPolicy.options(false, 0).isEmpty())
        assertEquals("5 key plays, then live", CatchUpPolicy.catchUpLine(5))
        assertEquals("1 key play, then live", CatchUpPolicy.catchUpLine(1))
    }

    @Test
    fun catchUpWindowGivesUp() {
        // Failed (no_catchup, recovery gave up): at once, even after it played
        assertTrue(CatchUpPolicy.catchUpGaveUp(failed = true, everPlayed = true, openingMs = 0))
        // Never started within the catch-up stall window
        assertFalse(CatchUpPolicy.catchUpGaveUp(failed = false, everPlayed = false, openingMs = CatchUpPolicy.START_TIMEOUT_MS - 500))
        assertTrue(CatchUpPolicy.catchUpGaveUp(failed = false, everPlayed = false, openingMs = CatchUpPolicy.START_TIMEOUT_MS))
        // Played once: a later reconnect is the recovery machine's (it ends in Failed if it gives up)
        assertFalse(CatchUpPolicy.catchUpGaveUp(failed = false, everPlayed = true, openingMs = 60_000))
        // Honest copy: never worked vs worked then stopped
        assertEquals(CatchUpPolicy.FAILED_TOAST, CatchUpPolicy.gaveUpToast(0))
        assertEquals(CatchUpPolicy.FAILED_TOAST, CatchUpPolicy.gaveUpToast(CatchUpPolicy.PLAYED_OK_MS))
        assertEquals(CatchUpPolicy.STOPPED_TOAST, CatchUpPolicy.gaveUpToast(CatchUpPolicy.PLAYED_OK_MS + 500))
    }
}
