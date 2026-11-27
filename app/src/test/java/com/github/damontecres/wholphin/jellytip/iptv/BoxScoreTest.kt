package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** /live `boxScore` (NFL / basketball full box score, Ricki 27-Nov): parse, absent = nothing, release rule, tabs. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class BoxScoreTest {
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream("jellytip/$n")!!.bufferedReader().readText()

    private val nflRaw = res("box-nfl-live.json")
    private val nbaRaw = res("box-nba-live.json")
    private val nfl get() = LiveParse.live(nflRaw)
    private val nba get() = LiveParse.live(nbaRaw)

    @Test
    fun parsesNflCompareAndSections() {
        val b = nfl.boxScore!!
        assertEquals("nfl", b.kind)
        assertEquals(listOf("away", "home"), b.teams.map { it.side })
        val tot = b.compare.first { it.label == "Total yards" }
        assertEquals("416", tot.home)
        assertEquals("369", tot.away)
        assertEquals("35:01", b.compare.first { it.label == "Possession" }.home)
        assertEquals(listOf("Passing", "Rushing", "Receiving", "Defense", "Kicking", "Punting"), b.teams[0].sections.map { it.title })
        val pass = b.teams[0].sections[0]
        assertEquals(listOf("C/ATT", "YDS", "AVG", "TD", "INT", "SACKS", "QBR", "RTG"), pass.labels)
        assertEquals("21/31", pass.rows[0].stats[0])
        assertTrue(b.teams.all { t -> t.sections.all { s -> s.rows.all { it.stats.size == s.labels.size } } })
    }

    @Test
    fun parsesBasketballTables() {
        val b = nba.boxScore!!
        assertEquals("basketball", b.kind)
        assertTrue(b.compare.isEmpty())
        val a = b.teams[0]
        assertEquals(listOf("Starters", "Bench"), a.sections.map { it.title })
        assertEquals(listOf("MIN", "PTS", "REB", "AST", "STL", "BLK", "FG", "3PT", "FT", "+/-"), a.sections[0].labels)
        val r = a.sections[0].rows[0]
        assertEquals("F", r.pos)
        assertEquals(listOf("36", "32", "14", "4", "2", "1", "11-18", "0-1", "10-13", "+4"), r.stats)
        val tot = a.sections.last().rows.last()
        assertTrue(tot.total)
        assertEquals("104", tot.stats[1])
        assertEquals(4, a.dnp.size)
    }

    @Test
    fun tabsAreTeamStatsThenTeamsForNflAndTeamsForBasketball() {
        assertEquals(listOf("compare", "away", "home"), nfl.boxScore!!.tabs.map { it.key })
        assertEquals(listOf("Team stats", "AWY", "HOM"), nfl.boxScore!!.tabs.map { it.label })
        assertEquals(listOf("away", "home"), nba.boxScore!!.tabs.map { it.key })
    }

    @Test
    fun absentOrEmptyMeansNoBox() {
        val j = JSONObject(nbaRaw)
        j.remove("boxScore")
        assertNull(LiveParse.live(j.toString()).boxScore)
        j.put("boxScore", JSONObject("""{"kind":"basketball","teams":[]}"""))
        assertNull(LiveParse.live(j.toString()).boxScore)
        j.put("boxScore", JSONObject.NULL)
        assertNull(LiveParse.live(j.toString()).boxScore)
        j.put("boxScore", JSONObject("""{"kind":"cricket","teams":[{"team":"home","sections":[{"title":"x","labels":["A"],"rows":[{"name":"p","stats":["1"]}]}]}]}"""))
        assertNull(LiveParse.live(j.toString()).boxScore)
        assertNull(SportParse.boxScore(null))
    }

    @Test
    fun partialBoxParsesWithoutCrashing() {
        val b =
            SportParse.boxScore(
                JSONObject(
                    """{"kind":"nfl","teams":[{"team":"home","sections":[
                    {"title":"Rushing","labels":["CAR","YDS"],"rows":[{"name":"A","stats":["10"]},{"stats":["1","2"]}]},
                    {"title":"Empty","labels":["X"],"rows":[]}]},{"team":"nope","sections":[]}]}""",
                ),
            )!!
        assertEquals(1, b.teams.size)
        assertEquals(1, b.teams[0].sections.size)
        assertEquals(listOf("10"), b.teams[0].sections[0].rows[0].stats)
        assertEquals("home", b.tabs.single().label)
        assertTrue(b.compare.isEmpty())
    }

    @Test
    fun releasedWithTheSnapshotButNeverInReplayOrSpoilerNotice() {
        val b = nba.boxScore
        assertSame(b, GameStateLogic.releasedBoxScore(b, replay = false, spoilerNotice = false))
        assertNull(GameStateLogic.releasedBoxScore(b, replay = true, spoilerNotice = false))
        assertNull(GameStateLogic.releasedBoxScore(b, replay = false, spoilerNotice = true))
        assertNull(GameStateLogic.releasedBoxScore(null, replay = false, spoilerNotice = false))
    }

    @Test
    fun selectedTabFollowsTheKey() {
        val b = nfl.boxScore!!
        assertEquals(2, GameStateLogic.boxTabIndex(b, "home"))
        assertEquals(0, GameStateLogic.boxTabIndex(b, null))
        assertEquals(0, GameStateLogic.boxTabIndex(b, "gone"))
        assertEquals(0, GameStateLogic.boxTabIndex(null, "home"))
        // the server dropping the compare block shifts the indexes; the selection stays on "home"
        val noCmp = b.copy(compare = emptyList())
        assertEquals(1, GameStateLogic.boxTabIndex(noCmp, "home"))
    }

    @Test
    fun tabStepStopsAtTheEnds() {
        assertEquals(1, stepBoxTab(0, 1, 3))
        assertEquals(0, stepBoxTab(0, -1, 3))
        assertEquals(2, stepBoxTab(2, 1, 3))
        assertEquals(0, stepBoxTab(0, 1, 0))
    }

    @Test
    fun columnWidthFollowsTheLongestCell() {
        assertEquals(46, boxColWidth("PTS", listOf("32", "7")).value.toInt())
        assertEquals(62, boxColWidth("FG", listOf("11-18", "6-16")).value.toInt())
        assertEquals(62, boxColWidth("SACKS", listOf("5", "1")).value.toInt())
        assertEquals(54, boxColWidth("AVG", listOf("10.7", "4.6")).value.toInt())
        assertEquals(72, boxColWidth("TIME", listOf("24:59:00")).value.toInt())
        assertFalse(boxColWidth("C/ATT", listOf("21/31")) < boxColWidth("YDS", listOf("331")))
    }

    @Test
    fun reuseKeepsTheUnchangedBoxInstance() {
        val a = nfl
        assertSame(a.boxScore, LiveParse.live(nflRaw).reuse(a).boxScore)
    }

    @Test
    fun boxScoreDoesNotOutliveFullTime() {
        val full = nba.copy(updatedAt = 100)
        val inPlay = nba.copy(boxScore = null, status = "in", updatedAt = 200)
        val post = nba.copy(boxScore = null, status = "post", updatedAt = 200)
        val a = LiveSnapshots()
        a.add(full, true, 100)
        a.add(inPlay, false, 200)
        assertSame(full.boxScore, a.releasedFull { true }!!.boxScore)
        val b = LiveSnapshots()
        b.add(full, true, 100)
        b.add(post, false, 200)
        assertNull(b.releasedFull { true }!!.boxScore)
        // a held (not yet released) full-time answer leaves the box up
        assertSame(full.boxScore, b.releasedFull { it != null && it < 150 }!!.boxScore)
    }

    @Test
    fun sheetKindPicksTheBlockThatArrived() {
        val cricket = LiveParse.live(res("cricket-fullcard-live.json")).fullCard
        val box = nba.boxScore
        assertEquals(SheetKind.NONE, GameStateLogic.sheetKind(null, null))
        assertEquals(SheetKind.BOX, GameStateLogic.sheetKind(null, box))
        assertEquals(SheetKind.CRICKET, GameStateLogic.sheetKind(cricket, null))
        assertEquals(SheetKind.CRICKET, GameStateLogic.sheetKind(cricket, box))
    }
}
