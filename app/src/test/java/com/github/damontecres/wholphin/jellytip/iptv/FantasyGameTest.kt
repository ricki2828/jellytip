package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FantasyGameTest {
    private val gibbs = FantasyStarter("me", "Jahmyr Gibbs", "RB", "DET", 12.4, "m1", "in", id = "p9")
    private val williams = FantasyStarter("me", "Jameson Williams", "WR", "DET", 3.0, "m1", "in")
    private val lg = FantasyLeague("L1", "CCP Dynasty", "Me", 93.8, 112.4, "Them", 79.2, listOf(gibbs, williams))

    @Test
    fun lastPlayById() {
        val live =
            FantasyLive(
                12,
                listOf(lg),
                listOf(
                    FantasyEvent("100", "L1", "J. Gibbs", 6.4, "me", "TD catch", playerId = "p9"),
                    FantasyEvent("200", "L1", "Jahmyr Gibbs", 1.0, "me", "rush", playerId = "p1"),
                    FantasyEvent("150", "L1", "J. Gibbs", 2.0, "me", "rush", playerId = "p9"),
                ),
            )
        assertEquals("rush", FantasyGameLogic.lastEvent(live, lg, gibbs)?.reason)
        assertEquals(2.0, FantasyGameLogic.lastEvent(live, lg, gibbs)!!.delta, 0.0)
    }

    @Test
    fun lastPlayByFullNameAndTeamNeverSurname() {
        val live =
            FantasyLive(
                12,
                listOf(lg),
                listOf(
                    FantasyEvent("100", "L1", "Williams", 6.0, "me", "TD"),
                    FantasyEvent("110", "L1", "Jameson Williams", 2.0, "me", "catch", nflTeam = "DET"),
                    FantasyEvent("120", "L1", "Jameson Williams", 9.0, "me", "TD", nflTeam = "BUF"),
                    FantasyEvent("130", "L1", "Jameson Williams", 4.0, "opp", "catch", nflTeam = "DET"),
                ),
            )
        assertEquals("catch", FantasyGameLogic.lastEvent(live, lg, williams)?.reason)
        assertNull(FantasyGameLogic.lastEvent(FantasyLive(12, listOf(lg), emptyList()), lg, williams))
    }

    @Test
    fun summary() {
        assertEquals("You lead by 14.6 · 2 playing · projected 112.4", FantasyGameLogic.summaryLine(lg))
        assertEquals("You trail by 4.0", FantasyGameLogic.summaryLine(lg.copy(mePoints = 75.2, meProjected = null, starters = emptyList())))
        assertNull(FantasyGameLogic.summaryLine(lg.copy(oppPoints = null)))
    }
}
