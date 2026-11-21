package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SportFixturesTest {
    private val utc = ZoneId.of("UTC")
    private val now = 1_763_700_000L // 2025-11-21 04:40 UTC

    private val body =
        """
        {"items":[
          {"fid":"f1","league":"NRL","leagueKey":"nrl","home":{"name":"Warriors","short":"WAR","logo":"v1/img/w.png","teamId":"1"},
           "away":"Panthers","kickoff":${now - 600},"status":"in","favourite":true,"matchId":"m1",
           "channels":[{"id":"p:2","name":"Sky Sport 2","confidence":0.6},{"id":"p:1","name":"Sky Sport 1","confidence":0.95,"quality":{"bucket":"fhd"}}],
           "best":"p:1","reminderId":7},
          {"fid":"f2","league":"NBA","home":"Lakers","away":"Suns","kickoff":${now + 3600},"status":"pre","rank":2,
           "channels":[{"id":"p:9","name":"ESPN","confidence":0.9,"lastFail":{"at":${now - 100}}},{"id":"p:8","name":"ESPN 2","confidence":0.9}]},
          {"fid":"f3","home":"A","away":"B","kickoff":${now + 7200},"status":"pre","favourite":true,"channels":[]},
          {"fid":"f4","home":"C","away":"D","kickoff":${now - 90000},"status":"post","score":{"home":3,"away":1}},
          {"fid":"f1","home":"dup","away":"dup","kickoff":1,"status":"pre"},
          {"fid":"bad","home":"X","away":"Y","status":"pre"},
          {"fid":"ms","home":"E","away":"F","kickoff":${(now + 60) * 1000},"status":"pre"}
        ],"degraded":true,
         "more":[{"league":"NBA","leagueKey":"nba","day":"2025-11-21","hidden":3},{"league":"NBA","leagueKey":"nba","day":"2025-11-21","hidden":2},
                 {"league":"NFL","day":"2025-11-22","hidden":4},{"league":"MLB","day":"2025-11-21","hidden":0}]}
        """.trimIndent()

    @Test
    fun parsesAndDropsUnusable() {
        val r = SportFixtures.parse(body) { "https://x/$it" }
        assertTrue(r.degraded)
        assertEquals(listOf("f1", "f2", "f3", "f4", "ms"), r.items.map { it.fid })
        val f1 = r.items[0]
        assertEquals("Warriors v Panthers", f1.title)
        assertEquals("https://x/v1/img/w.png", f1.homeLogo)
        assertEquals("7", f1.reminderId)
        assertTrue(f1.channels[0].maybe)
        assertFalse(f1.channels[1].maybe)
        assertEquals(now + 60, r.items[4].kickoff) // milliseconds tolerated
        assertEquals(3, r.items[3].score?.home)
    }

    @Test
    fun groupsLiveFavouritesFinished() {
        val g = SportFixtures.group(SportFixtures.parse(body).items)
        assertEquals(listOf("live", "yours", "big", "finished"), g.map { it.id })
        assertEquals(listOf("f3"), g[1].items.map { it.fid })
        assertEquals(listOf("f2", "ms"), g[2].items.map { it.fid })
    }

    @Test
    fun moreRowsForTodayOnly() {
        val r = SportFixtures.parse(body)
        val rows = SportFixtures.todayMore(r.more, now, emptySet(), utc)
        assertEquals(1, rows.size)
        assertEquals(5, rows[0].hidden)
        assertEquals("nba", rows[0].key)
        assertTrue(SportFixtures.todayMore(r.more, now, setOf("nba"), utc).isEmpty())
    }

    @Test
    fun mergeSwapsExpandedLeague() {
        val items = SportFixtures.parse(body).items
        val full = listOf(items[1].copy(fid = "f2b"))
        val merged = SportFixtures.merge(items, full, setOf("NBA"))
        assertTrue(merged.any { it.fid == "f2b" })
        assertFalse(merged.any { it.fid == "f2" })
    }

    @Test
    fun picksSolidBestAndSkipsRecentFailure() {
        val items = SportFixtures.parse(body).items
        val p1 = SportFixtures.pickChannel(items[0], now)
        assertEquals("p:1", p1.channel?.id)
        assertEquals(listOf("p:1", "p:2"), p1.order.map { it.id })
        val p2 = SportFixtures.pickChannel(items[1], now)
        assertEquals("p:8", p2.channel?.id)
        assertEquals("ESPN", p2.skipped)
        assertNull(SportFixtures.pickChannel(items[2], now).channel)
    }

    @Test
    fun liveMatchIdOnlyForLiveLaunch() {
        val items = SportFixtures.parse(body).items
        val f1 = items[0]
        assertEquals("m1", SportFixtures.liveMatchId(f1, f1.channels[1]))
        assertEquals("m1", SportFixtures.liveMatchId(f1, f1.channels[0]))
        assertNull(SportFixtures.liveMatchId(f1, null))
        assertNull(SportFixtures.liveMatchId(items[1], items[1].channels[1]))
    }

    @Test
    fun notesNeverCarryScores() {
        val items = SportFixtures.parse(body).items
        assertEquals("Live on Sky Sport 1", SportFixtures.note(items[0], now, utc))
        assertEquals("Starts 05:40 · ESPN 2", SportFixtures.note(items[1], now, utc))
        assertEquals("Starts 06:40 · No stream found yet", SportFixtures.note(items[2], now, utc))
        assertEquals("Finished — no replay available", SportFixtures.note(items[3], now, utc))
    }

    @Test
    fun resolvesDirectChannel() {
        val items = SportFixtures.parse(body).items
        val m = SportFixtures.resolveChannelMatch(items, "p:1", now)
        assertEquals("m1", m?.matchId)
        assertFalse(m!!.maybe)
        assertTrue(SportFixtures.resolveChannelMatch(items, "p:2", now)!!.maybe)
        assertNull(SportFixtures.resolveChannelMatch(items, "p:9", now)) // starts in an hour
        assertNull(SportFixtures.resolveChannelMatch(items, null, now))
    }
}
