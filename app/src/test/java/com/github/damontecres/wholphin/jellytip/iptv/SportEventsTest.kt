package com.github.damontecres.wholphin.jellytip.iptv

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
class SportEventsTest {
    private val t = 1_800_000_000L

    private fun ev(
        type: String = SportEventRules.SHOWN,
        fid: String = "espn:rugby~1:2",
        surface: String = SportEventRules.GAMECENTRE,
        at: Long = t,
        listId: String? = "5b8d-1a2b3c4d",
        section: String? = "live",
        pos: Int? = 1,
    ) = SportEvent(type, fid, surface, at, section, pos, listId)

    @Test
    fun shownOncePerFixtureSurfaceAndList_evenAfterASend() {
        val q = SportEventQueue()
        assertTrue(q.add(ev()))
        assertFalse(q.add(ev(at = t + 600)))
        q.done(q.take())
        // Scrolled back into view after the flush: still the same list
        assertFalse(q.add(ev(at = t + 900)))
        // A new list (re-served answer) or another surface: shown again
        assertTrue(q.add(ev(listId = "5b8e-00000000")))
        assertTrue(q.add(ev(surface = SportEventRules.HOME_HERO)))
    }

    @Test
    fun otherTypesOncePerMinute() {
        val q = SportEventQueue()
        assertTrue(q.add(ev(type = SportEventRules.OPENED, at = t)))
        assertFalse(q.add(ev(type = SportEventRules.OPENED, at = t - (t % 60) + 59)))
        assertTrue(q.add(ev(type = SportEventRules.OPENED, at = t - (t % 60) + 60)))
        assertTrue(q.add(ev(type = SportEventRules.WATCH, at = t)))
    }

    @Test
    fun unknownTypeSurfaceOrBadFidIsDropped() {
        val q = SportEventQueue()
        assertFalse(q.add(ev(type = "liked")))
        assertFalse(q.add(ev(surface = "web_fixtures"))) // a web surface, never sent from the TV
        assertFalse(q.add(ev(fid = "")))
        assertFalse(q.add(ev(fid = "a\nb")))
        assertEquals(0, q.size())
    }

    @Test
    fun batchesOfFiftyOldestFirstAndCapped() {
        val q = SportEventQueue(cap = 120)
        repeat(130) { q.add(ev(fid = "f$it")) }
        assertEquals(120, q.size())
        val b = q.take()
        assertEquals(50, b.size)
        assertEquals("f10", b.first().fid)
        q.done(b)
        assertEquals(70, q.size())
    }

    @Test
    fun seenKeysAreBounded() {
        val q = SportEventQueue(cap = 10, seenCap = 5)
        repeat(6) { q.add(ev(fid = "f$it")) }
        // f0's key fell out of the seen set
        assertTrue(q.add(ev(fid = "f0")))
    }

    @Test
    fun bodyMatchesTheContract() {
        val o =
            JSONObject(SportEventRules.body(listOf(ev(), ev(type = SportEventRules.WATCH, section = "Your teams", pos = 0, listId = null).copy(ms = 90_000))))
        val a = o.getJSONArray("events")
        assertEquals(2, a.length())
        val e0 = a.getJSONObject(0)
        assertEquals("shown", e0.getString("type"))
        assertEquals("espn:rugby~1:2", e0.getString("fid"))
        assertEquals("gamecentre", e0.getString("surface"))
        assertEquals(t, e0.getLong("at")) // seconds, not ms
        assertEquals("live", e0.getString("section"))
        assertEquals(1, e0.getInt("pos"))
        assertEquals("5b8d-1a2b3c4d", e0.getString("listId"))
        assertFalse(e0.has("ms"))
        val e1 = a.getJSONObject(1)
        // The server only takes [A-Za-z0-9_.:-]{1,40} sections and 1-based places: left out, not sent wrong
        assertFalse(e1.has("section"))
        assertFalse(e1.has("pos"))
        assertFalse(e1.has("listId"))
        assertEquals(90_000L, e1.getLong("ms"))
    }

    @Test
    fun answers() {
        assertEquals(SportEventAnswer.SENT, SportEventPolicy.answer(204))
        assertEquals(SportEventAnswer.KEEP, SportEventPolicy.answer(null))
        assertEquals(SportEventAnswer.KEEP, SportEventPolicy.answer(429))
        assertEquals(SportEventAnswer.OFF, SportEventPolicy.answer(404))
        assertEquals(SportEventAnswer.OFF, SportEventPolicy.answer(403))
        assertEquals(SportEventAnswer.OFF, SportEventPolicy.answer(501))
        assertEquals(SportEventAnswer.DROP, SportEventPolicy.answer(401))
        assertEquals(SportEventAnswer.DROP, SportEventPolicy.answer(400))
        assertEquals(SportEventAnswer.DROP, SportEventPolicy.answer(500))
    }

    @Test
    fun fixturesListIdIsStampedOnEveryItem() {
        val body =
            """{"listId":"5b8d-1a2b3c4d","ranker":"v1.2","items":[
              {"fid":"a","kickoff":1800000000,"status":"in","home":{"name":"New Zealand"},"away":{"name":"England"}},
              {"fid":"b","kickoff":1800000000,"status":"pre","home":{"name":"Blues"},"away":{"name":"Chiefs"}}
            ]}"""
        val r = SportFixtures.parse(body)
        assertEquals("5b8d-1a2b3c4d", r.listId)
        assertEquals("v1.2", r.ranker)
        assertTrue(r.items.all { it.listId == "5b8d-1a2b3c4d" })
        // Older server: none
        val old = SportFixtures.parse("""{"items":[{"fid":"a","kickoff":1800000000,"status":"in","home":{"name":"A"},"away":{"name":"B"}}]}""")
        assertNull(old.listId)
        assertNull(old.items.single().listId)
    }

    @Test
    fun channelMatchCarriesItsFixture() {
        val body =
            """{"items":[{"fid":"nzeng","kickoff":${t - 600},"status":"in","home":{"name":"New Zealand"},"away":{"name":"England"},
              "channels":[{"id":"p1:10","name":"Stan Sports 10","confidence":0.9}]}]}"""
        val items = SportFixtures.parse(body).items
        val cm = SportFixtures.resolveChannelMatch(items, "p1:10", t)
        assertEquals("nzeng", cm?.fixture?.fid)
        assertFalse(cm!!.maybe)
    }
}
