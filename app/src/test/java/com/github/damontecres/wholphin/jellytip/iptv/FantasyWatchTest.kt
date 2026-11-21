package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FantasyWatchTest {
    private fun fx(
        fid: String,
        home: String,
        away: String,
        status: String,
        matchId: String? = null,
    ) = FxItem(fid, "NFL", "nfl", null, home, away, null, null, home, away, null, null, 0, status, null, null, 0.0, null, false, false, listOf(FxChannel("p:$fid", "Ch $fid", null, null, null, 0.9, false, false, null, null, false, null)), null, null, null, matchId)

    @Test
    fun noPlayableChannelNoWatch() {
        val bare = fx("z", "DET", "GB", "in").copy(channels = emptyList())
        assertNull(FantasyWatch.fixtureFor(FantasyStarter("me", "X", null, "DET", 1.0, null, "in"), listOf(bare)))
    }

    @Test
    fun byMatchIdThenTeam() {
        val items = listOf(fx("a", "DET", "GB", "in", matchId = "m1"), fx("b", "BUF", "MIA", "pre"), fx("c", "KC", "LV", "post"))
        assertEquals("a", FantasyWatch.fixtureFor(FantasyStarter("me", "X", null, "BUF", 1.0, "m1", "in"), items)?.fid)
        assertEquals("b", FantasyWatch.fixtureFor(FantasyStarter("me", "Y", null, "buf", 1.0, null, "pre"), items)?.fid)
        assertNull(FantasyWatch.fixtureFor(FantasyStarter("me", "Z", null, "KC", 1.0, null, "in"), items)) // its game is over
        assertNull(FantasyWatch.fixtureFor(FantasyStarter("me", "W", null, "DET", 1.0, null, "post"), items))
        assertEquals("Pregame", FantasyWatch.stateText(FantasyStarter("me", "W", null, null, null, null, "pre")))
    }

    @Test
    fun sleeperCodeFindsEspnFixture() {
        // Sleeper/server say WAS, ESPN fixtures say WSH
        val items = listOf(fx("w", "WSH", "DAL", "in"), fx("j", "JAX", "TEN", "pre"))
        assertEquals("w", FantasyWatch.fixtureFor(FantasyStarter("me", "T", null, "WAS", 1.0, null, "in"), items)?.fid)
        assertEquals("j", FantasyWatch.fixtureFor(FantasyStarter("me", "U", null, "JAC", 1.0, null, "pre"), items)?.fid)
    }
}
