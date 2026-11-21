package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SportAmbientTest {
    private val now = 1_763_700_000L

    private fun fx(
        fid: String,
        status: String,
        kickoff: Long,
        favourite: Boolean = true,
        rank: Double? = null,
        withChannel: Boolean = true,
        score: Boolean = false,
    ) = FxItem(
        fid = fid,
        league = "NRL",
        leagueKey = "nrl",
        sport = "rugby league",
        home = "Warriors",
        away = "Panthers",
        homeId = null,
        awayId = null,
        homeShort = "WAR",
        awayShort = null,
        homeLogo = null,
        awayLogo = null,
        kickoff = kickoff,
        status = status,
        stage = null,
        score = if (score) FxScore(12, 6, "55'", "2H") else null,
        significance = 0.0,
        rank = rank,
        favourite = favourite,
        autoRecord = false,
        channels =
            if (withChannel) {
                listOf(FxChannel("p:1", "Sky Sport 1", 1, null, null, 0.95, false, false, null, null, false, null))
            } else {
                emptyList()
            },
        best = if (withChannel) "p:1" else null,
        reminderId = null,
        recordingId = null,
        matchId = "m-$fid",
    )

    @Test
    fun liveFavouriteBeatsSoonAndNonFavourites() {
        val p =
            SportAmbient.pick(
                listOf(
                    fx("big", "in", now - 600, favourite = false, rank = 1.0),
                    fx("soon", "pre", now + 300),
                    fx("live", "in", now - 1200),
                ),
                now,
            )!!
        assertEquals("live", p.f.fid)
        assertTrue(p.live)
        assertEquals("ON NOW", p.badge)
        assertEquals("NRL · OK TO WATCH", p.line)
    }

    @Test
    fun soonCountsDownAndFarDoesNot() {
        val p = SportAmbient.pick(listOf(fx("soon", "pre", now + 8 * 60 - 30)), now)!!
        assertEquals("KICK-OFF IN 8 MIN", p.badge)
        assertNull(SportAmbient.pick(listOf(fx("later", "pre", now + 3600)), now))
        assertNull(SportAmbient.pick(listOf(fx("done", "post", now - 600)), now))
        assertEquals("KICK-OFF NOW", SportAmbient.pick(listOf(fx("late", "pre", now - 60)), now)!!.badge)
    }

    @Test
    fun soonestThenRank() {
        val p = SportAmbient.pick(listOf(fx("b", "pre", now + 600, rank = 1.0), fx("a", "pre", now + 300, rank = 5.0)), now)!!
        assertEquals("a", p.f.fid)
    }

    @Test
    fun neverAScoreAndNoStreamSaysSo() {
        val p = SportAmbient.pick(listOf(fx("s", "in", now - 60, score = true, withChannel = false)), now)!!
        assertFalse(p.badge.contains("12") || p.line.contains("12"))
        assertEquals("NRL · NO STREAM YET", p.line)
    }

    @Test
    fun shortNamesAndDrift() {
        assertEquals("WAR", SportAmbient.short("Warriors", "war"))
        assertEquals("PAN", SportAmbient.short("Panthers", null))
        for (s in 0L until 20L) assertNotEquals(SportAmbient.drift(s), SportAmbient.drift(s + 1))
        assertEquals(SportAmbient.drift(-1), SportAmbient.drift(8))
    }
}
