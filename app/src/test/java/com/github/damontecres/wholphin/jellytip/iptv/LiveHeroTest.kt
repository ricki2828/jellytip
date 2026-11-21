package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2A match hero picks: Home (only his teams' / followed leagues' live games) and Game Centre (then the top-ranked live, his next game). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class LiveHeroTest {
    private val now = 1_763_700_000L

    private fun fx(
        fid: String,
        status: String,
        fav: Boolean = false,
        rank: Int? = null,
        kickoff: Long = now - 600,
        channel: Boolean = true,
    ): String {
        val ch = if (channel) """[{"id":"p:$fid","name":"Ch $fid","confidence":0.95}]""" else "[]"
        val r = rank?.let { ""","rank":$it""" } ?: ""
        return """{"fid":"$fid","home":"H$fid","away":"A$fid","kickoff":$kickoff,"status":"$status","favourite":$fav$r,"channels":$ch,"best":"p:$fid"}"""
    }

    private fun items(vararg f: String) = SportFixtures.parse("""{"items":[${f.joinToString(",")}]}""").items

    @Test
    fun homeShowsHisLiveGamesRanked() {
        val list = items(fx("a", "in", rank = 1), fx("b", "in", fav = true, rank = 5), fx("c", "in", fav = true, rank = 2), fx("d", "pre", fav = true, kickoff = now + 600))
        assertEquals(listOf("c", "b"), LiveHero.homePicks(list, now).map { it.fid })
    }

    @Test
    fun homeHasNoFallbackButGameCentreKeepsTheTopRankedLiveGame() {
        // Ricki 21-Nov: a La Liga game he doesn't follow must not top Home
        val list = items(fx("a", "in", rank = 3), fx("b", "in", rank = 1), fx("x", "in", rank = 0, channel = false))
        assertTrue(LiveHero.homePicks(list, now).isEmpty())
        assertEquals(listOf("b"), LiveHero.gameCentrePicks(list, now).map { it.fid })
    }

    @Test
    fun homeShowsFollowedLeagueGames() {
        val league = fx("l", "in", rank = 4).dropLast(1) + ""","why":["favourite league"]}"""
        val list = items(fx("a", "in", rank = 1), league, fx("b", "in", fav = true, rank = 5))
        assertEquals(listOf("l", "b"), LiveHero.homePicks(list, now).map { it.fid })
        assertTrue(items(league).single().followedLeague)
    }

    @Test
    fun noLiveGameMeansNoHomeHeroButGameCentreShowsHisNextGame() {
        val list = items(fx("a", "pre", rank = 1, kickoff = now + 1800), fx("b", "pre", fav = true, kickoff = now + 7200), fx("c", "post"))
        assertTrue(LiveHero.homePicks(list, now).isEmpty())
        assertEquals(listOf("b"), LiveHero.gameCentrePicks(list, now).map { it.fid })
        assertEquals(listOf("a"), LiveHero.gameCentrePicks(items(fx("a", "pre", rank = 1, kickoff = now + 1800)), now).map { it.fid })
        assertTrue(LiveHero.gameCentrePicks(items(fx("a", "pre", kickoff = now + 3 * 86400)), now).isEmpty())
    }

    @Test
    fun cycleWraps() {
        assertEquals(1, LiveHero.cycle(0, 3, 1))
        assertEquals(2, LiveHero.cycle(0, 3, -1))
        assertEquals(0, LiveHero.cycle(2, 3, 1))
        assertEquals(0, LiveHero.cycle(0, 0, 1))
    }

    @Test
    fun tagIsSpoilerSafe() {
        val live = items(fx("a", "in"))[0]
        assertEquals("Live", heroTag(live, now))
        assertEquals("Full time", heroTag(items(fx("b", "post"))[0], now))
    }
}
