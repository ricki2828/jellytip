package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** /live `fullCard` (cricket full scorecard, Ricki 27-Nov): parse, absent = nothing, release rule, tab stepping. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class CricketFullCardTest {
    private val raw = javaClass.classLoader!!.getResourceAsStream("jellytip/cricket-fullcard-live.json")!!.bufferedReader().readText()

    private val live get() = LiveParse.live(raw)

    @Test
    fun parsesInningsNewestFirst() {
        val fc = live.fullCard!!
        assertEquals(listOf(3, 2, 1), fc.innings.map { it.n })
        val cur = fc.innings[0]
        assertEquals("Home XI 2nd innings", cur.title)
        assertEquals("198/2", cur.score)
        assertEquals("60.3", cur.overs)
        assertTrue(cur.current)
        assertNull(cur.result)
        assertEquals("all out", fc.innings[1].result)
        assertEquals("417", fc.innings[1].score)
        assertEquals("HOM 198/2", fullTabLabel(cur))
    }

    @Test
    fun battingBowlingExtrasFow() {
        val cur = live.fullCard!!.innings[0]
        assertEquals(4, cur.batting.size)
        val b = cur.batting[1]
        assertEquals("Player H2", b.name)
        assertEquals("not out", b.out)
        assertTrue(b.atCrease)
        assertEquals(listOf("112", "171", "15", "0", "65.5"), listOf(b.runs, b.balls, b.fours, b.sixes, b.sr))
        assertFalse(cur.batting[0].atCrease)
        assertEquals("b A7", cur.batting[0].out)
        assertEquals("10 (b 5, lb 3, w 1, nb 1)", cur.extras)
        assertEquals(7, cur.didNotBat.size)
        assertEquals(listOf(1, 2), cur.fow.map { it.wicket })
        assertEquals("128", cur.fow[0].runs)
        assertEquals("40.2", cur.fow[0].overs)
        val o = cur.bowling.first { it.name == "Player A8" }
        assertEquals(listOf("10.0", "1", "37", "0", "3.70"), listOf(o.overs, o.maidens, o.runs, o.wickets, o.econ))
        assertEquals(11, live.fullCard!!.innings[1].batting.size)
        assertEquals(10, live.fullCard!!.innings[1].fow.size)
    }

    @Test
    fun absentOrEmptyMeansNoCard() {
        val j = JSONObject(raw)
        j.remove("fullCard")
        assertNull(LiveParse.live(j.toString()).fullCard)
        assertNotNull(LiveParse.live(j.toString()).scorecard)
        j.put("fullCard", JSONObject("""{"innings":[]}"""))
        assertNull(LiveParse.live(j.toString()).fullCard)
        j.put("fullCard", JSONObject.NULL)
        assertNull(LiveParse.live(j.toString()).fullCard)
        assertNull(SportParse.fullCard(null))
    }

    @Test
    fun partialInningsParseWithoutCrashing() {
        val fc = SportParse.fullCard(JSONObject("""{"innings":[{"n":1,"runs":20,"wickets":1,"batting":[{"name":"A","out":"not out"},{"out":"no name"}]}]}"""))!!
        val i = fc.innings[0]
        assertEquals("Innings 1", i.title)
        assertEquals("20/1", i.score)
        assertEquals(1, i.batting.size)
        assertNull(i.extras)
        assertTrue(i.bowling.isEmpty() && i.fow.isEmpty() && i.didNotBat.isEmpty())
    }

    @Test
    fun releasedOnlyWithTheSummaryScorecard() {
        val l = live
        assertSame(l.fullCard, GameStateLogic.releasedFullCard(l.fullCard, l.scorecard))
        assertNull(GameStateLogic.releasedFullCard(l.fullCard, null))
        assertNull(GameStateLogic.releasedFullCard(null, l.scorecard))
        // held overs do not hide the card: the same rule as the scorecard's innings / batters
        val held = GameStateLogic.releasedCard(l.scorecard) { false }
        assertNotNull(held)
        assertSame(l.fullCard, GameStateLogic.releasedFullCard(l.fullCard, held))
    }

    @Test
    fun fullTimeCardWaitsForTheSheet() {
        // OK on the open sheet must not reach the full-time card's accept (channel switch)
        assertTrue(GameStateLogic.fullTimeCardDue(true, false, false))
        assertFalse(GameStateLogic.fullTimeCardDue(true, false, true))
        assertFalse(GameStateLogic.fullTimeCardDue(true, true, false))
        assertFalse(GameStateLogic.fullTimeCardDue(false, false, false))
    }

    @Test
    fun selectedTabFollowsTheInningsNumber() {
        val fc = live.fullCard!!
        assertEquals(1, fullTabIndex(fc, 2))
        assertEquals(0, fullTabIndex(fc, null))
        assertEquals(0, fullTabIndex(fc, 99))
        // a new innings arriving at the front shifts the indexes; the selection stays on innings 2
        val grown = fc.copy(innings = listOf(fc.innings[0].copy(n = 4)) + fc.innings)
        assertEquals(2, fullTabIndex(grown, 2))
    }

    @Test
    fun ratesFormattedToFixedPlaces() {
        val i = SportParse.fullCard(JSONObject("{\"innings\":[{\"n\":1,\"batting\":[{\"name\":\"A\",\"out\":\"not out\",\"sr\":50},{\"name\":\"B\",\"out\":\"b X\",\"sr\":33.333}],\"bowling\":[{\"name\":\"C\",\"econ\":4.1},{\"name\":\"D\",\"econ\":\"x\"}]}]}"))!!.innings[0]
        assertEquals(listOf("50.0", "33.3"), i.batting.map { it.sr })
        assertEquals(listOf("4.10", null), i.bowling.map { it.econ })
    }

    @Test
    fun tabStepStopsAtTheEnds() {
        assertEquals(1, stepFullTab(0, 1, 3))
        assertEquals(0, stepFullTab(0, -1, 3))
        assertEquals(2, stepFullTab(2, 1, 3))
        assertEquals(0, stepFullTab(0, 1, 0))
    }

    @Test
    fun reuseKeepsTheUnchangedCardInstance() {
        val a = live
        val b = LiveParse.live(raw).reuse(a)
        assertSame(a.fullCard, b.fullCard)
    }
}
