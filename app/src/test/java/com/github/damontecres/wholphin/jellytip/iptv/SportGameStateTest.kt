package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SportGameStateTest {
    private fun live(
        home: String?,
        away: String?,
        clock: String?,
        period: String?,
        status: String? = "in",
        layout: String? = null,
        plays: List<LivePlay> = emptyList(),
    ) = MatchLive(status, clock, period, home, away, emptyList(), emptyList(), plays, null, null, null, emptyList(), updatedAt = null, layout = layout)

    @Test
    fun sportFromLayoutAndLeague() {
        assertEquals("nrl", GameStateLogic.sportOf("rugby", "nrl"))
        assertEquals("rugby", GameStateLogic.sportOf("rugby", "super-rugby"))
        assertEquals("nba", GameStateLogic.sportOf("basketball", null))
        assertEquals("nfl", GameStateLogic.sportOf(null, "nfl"))
        assertEquals("cricket", GameStateLogic.sportOf(null, "bbl"))
        assertEquals("default", GameStateLogic.sportOf("f1", null))
    }

    @Test
    fun periodsAndClocks() {
        assertEquals(4, GameStateLogic.periodNo("Q4"))
        assertEquals(5, GameStateLogic.periodNo("OT"))
        assertEquals(6, GameStateLogic.periodNo("2OT"))
        assertEquals(2, GameStateLogic.periodNo("2nd Half"))
        assertEquals(480, GameStateLogic.secsLeft("8:00"))
        assertNull(GameStateLogic.secsLeft("63'"))
        assertEquals(63, GameStateLogic.matchMinute("63'", null))
        assertEquals(47, GameStateLogic.matchMinute("45+2'", null))
        assertEquals(41, GameStateLogic.matchMinute(null, "2nd Half"))
    }

    @Test
    fun classifyStates() {
        val c = GameStateLogic::classify
        assertEquals(GameKind.FINAL, c("nrl", "post", 20, 10, null, null, null, null, null, null).kind)
        assertEquals(GameKind.PRE, c("nrl", "pre", null, null, null, null, null, null, null, null).kind)
        assertEquals(GameKind.BREAK, c("nrl", "in", 6, 0, "40:00", "Half Time", null, null, null, null).kind)
        assertEquals(GameKind.BLOWOUT, c("nrl", "in", 36, 6, "56:00", "2", null, null, null, null).kind)
        assertEquals(GameKind.TIGHT, c("nrl", "in", 12, 8, "66:00", "2", null, null, null, null).kind)
        assertEquals(GameKind.LIVE, c("nrl", "in", 12, 8, "20:00", "1", null, null, null, null).kind)
        assertEquals(GameKind.BLOWOUT, c("nfl", "in", 35, 7, "10:00", "Q3", null, null, null, null).kind)
        assertEquals(GameKind.TIGHT, c("nfl", "in", 21, 17, "5:00", "Q4", null, null, null, null).kind)
        assertEquals(GameKind.TIGHT, c("nba", "in", 100, 100, "2:00", "OT", null, null, null, null).kind)
        assertEquals(GameKind.TIGHT, c("cricket", "in", 100, 95, "", "", null, 8.0, 8.5, "bbl").kind)
        assertEquals(GameKind.LIVE, c("cricket", "in", 100, 50, "", "Day 2", null, 3.0, 9.0, "test").kind)
        assertEquals(GameKind.BLOWOUT, c("cricket", "in", 100, 50, "", "", null, 6.0, 13.0, "bbl").kind)
    }

    @Test
    fun deciderAndBlowoutWording() {
        val d = live("18", "12", "68:00", "2", layout = "rugby", plays = listOf(LivePlay("65:10", "2", "Try Warriors", true, "home", 4)))
        val st = GameStateLogic.of(d, "nrl", null, false)
        assertEquals(GameKind.TIGHT, st.kind)
        val rows = GameStateLogic.deciderRows(d, st, "WAR", "PAN", false)
        assertEquals("Score" to "WAR by 6 · one score", rows[0])
        assertEquals("Time left" to "12'", rows[1])
        assertEquals("Last score" to "65:10 Try Warriors", rows[2])
        val b = live("40", "6", "60:00", "2", layout = "rugby")
        val bs = GameStateLogic.of(b, "nrl", null, false)
        assertEquals(GameKind.BLOWOUT, bs.kind)
        assertEquals("Your team leads 40–6 · 2 60:00", GameStateLogic.blowoutLine(b, bs, "WAR", "PAN", "home"))
        assertEquals("WAR lead 40–6 · 2 60:00", GameStateLogic.blowoutLine(b, bs, "WAR", "PAN", "away"))
        val recap = GameStateLogic.recapPlays(d, "home")
        assertTrue(recap.single().mine)
    }

    @Test
    fun favouriteSideByIdThenName() {
        val h = TeamAware.Side("New Zealand Warriors", "Warriors", "3")
        val a = TeamAware.Side("Penrith Panthers", "Panthers", "9")
        assertEquals("home", TeamAware.favouriteSide(h, a, "nrl", emptyList(), listOf("3" to "nrl")))
        // league must agree when both say
        assertNull(TeamAware.favouriteSide(h, a, "nba", emptyList(), listOf("3" to "nrl")))
        assertEquals("away", TeamAware.favouriteSide(h, a, "nrl", listOf("panthers"), emptyList()))
        assertNull(TeamAware.favouriteSide(h, a, "nrl", listOf("Warriors", "Panthers"), emptyList()))
        assertNull(TeamAware.favouriteSide(h, null, "nrl", listOf("Warriors"), emptyList()))
        assertTrue(TeamAware.isFavouriteMoment("try", "home", "home"))
        assertFalse(TeamAware.isFavouriteMoment("try", "away", "home"))
        assertFalse(TeamAware.isFavouriteMoment("red_card", "home", "home"))
    }

    @Test
    fun favouriteScoresAlertInKeyModeAndSurviveBlowout() {
        val fav = AlertPolicy.Candidate("a", "score", 0.0, favourite = TeamAware.isFavouriteMoment("score", "home", "home"), payload = Unit)
        val other = AlertPolicy.Candidate("b", "score", 0.0, favourite = TeamAware.isFavouriteMoment("score", "away", "home"), payload = Unit)
        assertTrue(AlertPolicy.isKeyEvent(fav, AlertPolicy.Mode.KEY))
        assertFalse(AlertPolicy.isKeyEvent(other, AlertPolicy.Mode.KEY))
        assertTrue(AlertPolicy.isKeyEvent(fav, AlertPolicy.Mode.KEY, blowout = true))
        assertFalse(AlertPolicy.isKeyEvent(other.copy(kind = "try"), AlertPolicy.Mode.KEY, blowout = true))
    }

    @Test
    fun prefsTeamsParse() {
        val sp = JSONObject("""{"teams":["Warriors",{"name":"Lakers","teamId":"13","leagueKey":"nba"}],"teamRefs":[{"teamId":"3","leagueKey":"nrl","name":"New Zealand Warriors"},{"teamId":"13"}]}""")
        assertEquals(listOf("Warriors", "Lakers", "New Zealand Warriors"), TeamAware.teamNames(sp))
        assertEquals(listOf("13" to "nba", "3" to "nrl"), TeamAware.teamRefs(sp))
    }

    @Test
    fun scorecardAndTimelineParse() {
        val sc =
            SportParse.scorecard(
                JSONObject(
                    """{"innings":[{"team":"home","runs":180,"wickets":6,"overs":"20.0"},{"team":"away","runs":95,"wickets":3,"overs":"11.2","current":true}],
                    "batters":[{"name":"Williamson","runs":40,"balls":30,"fours":4,"sixes":1,"onStrike":true}],
                    "bowler":{"name":"Starc","figures":"2/25","overs":"3.2"},"partnership":{"runs":51,"balls":33},"target":181,"equation":"Need 86 from 52","lead":-85,
                    "overs":[{"over":11,"runs":9,"balls":["1","4","W","4"],"at":1000}]}""",
                ),
            )!!
        assertEquals(2, sc.innings.size)
        assertTrue(sc.innings[1].current)
        assertEquals("Starc 2/25 (3.2 ov)", sc.bowling)
        assertEquals("51 (33)", sc.partnership)
        assertEquals("86 from 52", sc.need)
        assertEquals(-85, sc.lead)
        assertEquals(listOf("1", "4", "W", "4"), sc.overs[0].balls)
        assertEquals(0, GameStateLogic.releasedCard(sc) { (it ?: 0.0) < 500 }!!.overs.size)
        val tl = SportParse.timeline(JSONArray("""[{"id":"1","kind":"try","team":"home","text":"Try: Tuivasa-Sheck","clock":"12'","at":900},{"id":"2","label":"Sin bin","at":1100},{"text":"no id"}]"""))
        assertEquals(2, tl.size)
        assertEquals(1, GameStateLogic.releasedTimeline(tl) { (it ?: 0.0) <= 1000 }.size)
        assertEquals(7, SportParse.wicketsInHand(JSONObject("""{"wickets":3}""")))
    }
}
