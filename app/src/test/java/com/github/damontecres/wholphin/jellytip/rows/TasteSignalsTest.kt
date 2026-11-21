package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.signals.PlayTracker
import com.github.damontecres.wholphin.jellytip.signals.TasteQueue
import com.github.damontecres.wholphin.jellytip.signals.TasteRules
import com.github.damontecres.wholphin.jellytip.signals.TasteSignal
import com.github.damontecres.wholphin.jellytip.signals.TasteTarget
import com.github.damontecres.wholphin.jellytip.signals.WatchMeta
import com.github.damontecres.wholphin.jellytip.signals.WatchSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TV taste signals (web lib/signals.ts rules): every play-type event carries ms. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TasteSignalsTest {
    private val film = WatchMeta(TasteTarget("movie", 603), isFilm = true)
    private val at = 1_800_000_000_000L

    @Test
    fun watchRules() {
        assertNull(TasteRules.watchSignal(film, 30.0, 7200.0, false, at))
        assertEquals("skip", TasteRules.watchSignal(film, 60.0, 7200.0, false, at)?.type)
        assertEquals("play", TasteRules.watchSignal(film, 600.0, 7200.0, false, at)?.type)
        assertEquals("finish", TasteRules.watchSignal(film, 6500.0, 7200.0, false, at)?.type)
        assertEquals("finish", TasteRules.watchSignal(film, 120.0, 0.0, true, at)?.type)
        // an episode leaving early is never a skip: a minute is a play
        assertEquals("play", TasteRules.watchSignal(film.copy(isFilm = false), 60.0, 7200.0, false, at)?.type)
        assertNull(TasteRules.watchSignal(film.copy(isFilm = false), 59.0, 7200.0, false, at))
        val chan = film.copy(channel = true)
        assertEquals("play", TasteRules.watchSignal(chan, 31.0, 7200.0, true, at)?.type)
    }

    @Test
    fun playEventsCarryMs() {
        val s = TasteRules.watchSignal(film, 600.4, 7200.0, false, at)!!
        assertEquals(600_400L, s.ms)
        val body = JSONObject(TasteRules.body(listOf(s))).getJSONArray("events").getJSONObject(0)
        assertEquals(600_400L, body.getLong("ms"))
        assertEquals(603, body.getInt("tmdbId"))
        assertFalse(body.has("itemId"))
    }

    @Test
    fun detailDwell() {
        assertNull(TasteRules.detailSignal(TasteTarget("series", itemId = "x"), 5_000, at))
        assertEquals(TasteRules.DETAIL_MAX_MS, TasteRules.detailSignal(TasteTarget("series", itemId = "x"), 60 * 60_000, at)?.ms)
    }

    @Test
    fun queueDedupesAndCaps() {
        val q = TasteQueue(cap = 3)
        val s = TasteSignal("play", at, "movie", tmdbId = 1, ms = 1)
        assertTrue(q.add(s))
        assertFalse(q.add(s.copy(at = at + 1_000))) // same minute
        assertFalse(q.add(TasteSignal("play", at, "movie"))) // no id
        (2..5).forEach { q.add(s.copy(tmdbId = it)) }
        assertEquals(3, q.size())
        q.done(q.take(2))
        assertEquals(1, q.size())
    }

    @Test
    fun trackerIgnoresSeeks() {
        val t = PlayTracker()
        listOf(0.0, 1.0, 2.0, 100.0, 101.0).forEach { t.tick(it) }
        assertEquals(3.0, t.playedS, 0.001)
        val w = WatchSession()
        w.begin(film, 7200.0)
        w.tick(0.0)
        w.tick(1.0)
        assertNull(w.end(false, at)) // 1 s: says nothing
        assertNull(w.end(false, at)) // closed once
    }
}
