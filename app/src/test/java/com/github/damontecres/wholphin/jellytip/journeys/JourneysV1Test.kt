package com.github.damontecres.wholphin.jellytip.journeys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Journeys v1 (spec ~/ai/state/fleet/jellytip-journeys-v1-spec.md) against resources/jellytip/journeys-v1-fixture.json. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class JourneysV1Test {
    private val base = "https://api.example"

    private val fixture by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/journeys-v1-fixture.json")!!.bufferedReader().readText())
    }

    private fun home() = JourneysV1.home(fixture.getJSONObject("home").toString(), base)!!

    private fun journey() = JourneysV1.journey(fixture.getJSONObject("journey").toString(), base)!!

    @Test
    fun parsesHomeRowsInOrderAndDropsBadEntries() {
        val h = home()
        assertEquals("cur:road-to-heat", h.active)
        assertEquals(8, h.journeys.size) // the entry without an id is dropped
        assertEquals(listOf("cur:road-to-heat", "l:sight-and-sound", "d:240"), h.yours) // unknown id dropped
        // An id already in "yours" is not drawn again in "start"
        assertEquals(listOf("cur:neo-noir-6", "cur:kurosawa-to-leone"), h.start)
        assertEquals(listOf("f:87359", "d:1223"), h.finish)
        assertEquals(1763900000L, h.builtAt)
        val s = h.journeys.getValue("cur:road-to-heat")
        assertTrue(s.curated)
        assertEquals(2, s.nowIndex)
        assertEquals("Thief", s.nowTitle)
        assertEquals("https://image.tmdb.org/t/p/w1280/thief.jpg", s.backdrop)
        assertNull(h.journeys.getValue("cur:all-done").nowIndex)
        assertNull(h.journeys.getValue("d:240").poster)
    }

    @Test
    fun activeMustBeAKnownJourney() {
        val o = fixture.getJSONObject("home")
        o.put("active", "cur:gone")
        assertNull(JourneysV1.home(o.toString(), base)!!.active)
    }

    @Test
    fun unreadableBodiesAreNull() {
        assertNull(JourneysV1.home("not json", base))
        assertNull(JourneysV1.home("{}", base))
        assertNull(JourneysV1.journey("{\"steps\":[]}", base))
    }

    @Test
    fun parsesStepsAndSources() {
        val j = journey()
        assertEquals(6, j.steps.size) // a step without a title is dropped
        assertEquals("R", j.maxRating)
        assertEquals(JvSource.LIBRARY, j.steps[0].source)
        assertEquals(JvSource.IPTV, j.steps[1].source)
        assertEquals("v:p1:m:9001", j.steps[1].iptv?.id)
        // Library ids compare dashless, lower case
        assertEquals("0000000000000000000000000000000b", j.steps[2].itemId)
        assertEquals(JvSource.GETTABLE, j.steps[3].source)
        assertTrue(j.steps[4].rewatch)
        assertNull(j.steps[5].why)
        assertEquals(JvSource.MISSING, j.steps[5].source)
    }

    @Test
    fun playableSourceWithoutItsIdIsNotPlayable() {
        val lib = JourneysV1.step(JSONObject("""{"n":1,"title":"A","source":"library","itemId":null}"""), base)!!
        assertEquals(JvSource.MISSING, lib.source)
        val iptv = JourneysV1.step(JSONObject("""{"n":1,"title":"A","source":"iptv","iptv":null}"""), base)!!
        assertEquals(JvSource.MISSING, iptv.source)
    }

    @Test
    fun tonightIsFirstUnseenPlayableAndGetItIsTheGap() {
        val steps = journey().steps
        assertEquals(2, JourneysV1.nowIndex(steps))
        assertEquals(3, JourneysV1.getIndex(steps))
        assertEquals(
            listOf(JvDot.DONE, JvDot.DONE, JvDot.NOW, JvDot.GAP, JvDot.NEXT, JvDot.GAP),
            steps.indices.map { JourneysV1.dot(steps, it) },
        )
    }

    @Test
    fun missingEarlierStepOffersTheNextPlayable() {
        val steps = journey().steps
        // Thief seen: Manhunter can't play yet, so tonight is Heat, and Get it still points at Manhunter
        val after = steps.mapIndexed { i, s -> if (i == 2) s.copy(seen = true) else s }
        assertEquals(4, JourneysV1.nowIndex(after))
        assertEquals(3, JourneysV1.getIndex(after))
        assertEquals(JvDot.GAP, JourneysV1.dot(after, 3))
    }

    @Test
    fun skippedStepIsPassedOver() {
        val steps = journey().steps.mapIndexed { i, s -> if (i == 2) s.copy(skipped = true) else s }
        assertEquals(4, JourneysV1.nowIndex(steps))
        assertEquals(JvDot.SKIPPED, JourneysV1.dot(steps, 2))
    }

    @Test
    fun allSeenHasNoTonight() {
        val steps = journey().steps.map { it.copy(seen = true) }
        assertNull(JourneysV1.nowIndex(steps))
        assertNull(JourneysV1.getIndex(steps))
    }

    @Test
    fun playingALaterStepSkipsTonights() {
        val steps = journey().steps
        assertEquals("Thief", JourneysV1.skipFor(steps, 4)?.title)
        assertNull(JourneysV1.skipFor(steps, 2))
        assertNull(JourneysV1.skipFor(steps, 0))
    }

    @Test
    fun kickerMetaAndProgress() {
        assertEquals("THE ROAD TO HEAT · STEP 3 OF 6", JourneysV1.kicker("The road to Heat", 2, 6))
        val thief = journey().steps[2]
        assertEquals("1981 · 2 h 2 m · ★ 7.4 · Library", JourneysV1.meta(thief))
        assertEquals("1970 · 2 h 20 m · ★ 7.9 · IPTV", JourneysV1.meta(journey().steps[1]))
        assertEquals("45 m", JourneysV1.runtime(45))
        assertEquals("2 h", JourneysV1.runtime(120))
        assertNull(JourneysV1.runtime(null))
        val s = home().journeys.getValue("cur:road-to-heat")
        assertEquals("2/6", JourneysV1.progressLabel(s))
        assertEquals(2f / 6, JourneysV1.progress(s), 0.001f)
        assertEquals(0f, JourneysV1.progress(s.copy(total = 0)), 0f)
    }

    @Test
    fun pathWindowFitsLongJourneys() {
        assertEquals(JourneysV1.PathWindow(0, 5, 0, 0), JourneysV1.window(6, 2, max = 6))
        // 99 steps, tonight is #42: one before it, then the next ones
        val w = JourneysV1.window(99, 41)
        assertEquals(JourneysV1.PathWindow(40, 44, 40, 54), w)
        // Near the end the window slides back to stay full
        assertEquals(JourneysV1.PathWindow(94, 98, 94, 0), JourneysV1.window(99, 98))
        // At the start
        assertEquals(JourneysV1.PathWindow(0, 4, 0, 94), JourneysV1.window(99, 0))
        // Finished: the last steps
        assertEquals(JourneysV1.PathWindow(94, 98, 94, 0), JourneysV1.window(99, null))
        assertEquals(-1, JourneysV1.window(0, null).to)
    }

    @Test
    fun refetchKeepsCardsInPlace() {
        assertEquals(listOf("a", "b", "c", "d"), JourneysV1.stable(listOf("a", "b", "c"), listOf("c", "a", "d", "b")))
        assertEquals(listOf("a", "c"), JourneysV1.stable(listOf("a", "b", "c"), listOf("c", "a")))
        val old = home()
        val fresh = old.copy(yours = old.yours.reversed(), start = listOf("cur:kurosawa-to-leone"))
        val merged = JourneysV1.stable(old, fresh)
        assertEquals(old.yours, merged.yours)
        assertEquals(listOf("cur:kurosawa-to-leone"), merged.start)
        assertEquals(fresh, JourneysV1.stable(null, fresh))
    }

    @Test
    fun onlyA404IsMissingAndOnlyFastApisOwnMeansNoRoute() {
        assertTrue(JourneysV1.isNotFound(404))
        assertFalse(JourneysV1.isNotFound(500))
        assertFalse(JourneysV1.isNotFound(null))
        assertTrue(JourneysV1.isRouteMissing(404, """{"detail":"Not Found"}"""))
        assertFalse(JourneysV1.isRouteMissing(404, """{"error":"no such journey"}"""))
        assertFalse(JourneysV1.isRouteMissing(503, """{"detail":"x"}"""))
        assertFalse(JourneysV1.isRouteMissing(401, """{"error":"user_required"}"""))
        assertEquals(2_000L, JourneysV1.backoffMs(0))
        assertEquals(60_000L, JourneysV1.backoffMs(9))
    }

    @Test
    fun kidsHomeHasOnlyWhatTheServerSent() {
        val h = JourneysV1.home(fixture.getJSONObject("kidsHome").toString(), base)!!
        assertNull(h.activeSummary)
        assertEquals(listOf("cur:ghibli-first", "f:10194"), h.start)
        assertFalse(h.isEmpty)
    }
}
