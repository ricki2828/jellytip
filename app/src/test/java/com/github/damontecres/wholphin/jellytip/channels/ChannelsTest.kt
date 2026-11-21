package com.github.damontecres.wholphin.jellytip.channels

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

/**
 * The TV schedule must match the web's bit for bit (same item at the same offset on every device). The golden vectors in
 * resources/jellytip/channels-vectors.json were made by running the web's own lib/channels.ts, personalChannels.ts and
 * tasteProfile.ts (origin/jellytip-web 0e7e7787) under node 24; the generator is channels-vectors.gen.mts.txt next to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class ChannelsTest {
    private val v: JSONObject by lazy {
        JSONObject(
            javaClass.classLoader!!
                .getResourceAsStream("jellytip/channels-vectors.json")!!
                .bufferedReader()
                .readText(),
        )
    }

    private val t = TICKS_PER_SECOND

    private fun film(
        i: Int,
        min: Int,
        aff: Double? = null,
    ) = ChanItem(id = i.toString(16).padStart(32, '0'), name = "F$i", type = "Movie", runTimeTicks = min * 60 * t, aff = aff)

    private fun ep(
        s: String,
        se: Int,
        e: Int,
        min: Int,
        aff: Double? = null,
    ) = ChanItem(
        id = "${s}e${se}x$e".padEnd(32, '0'),
        name = "$s ${se}x$e",
        type = "Episode",
        seriesId = s,
        seriesName = s,
        season = se,
        episode = e,
        runTimeTicks = min * 60 * t,
        aff = aff,
    )

    private val films = (0 until 9).map { film(it + 1, 80 + it * 7) }
    private val filmsAff = (0 until 9).map { film(it + 1, 80 + it * 7, (it % 4) / 3.0) }
    private val eps =
        listOf(1, 2, 3, 4, 5, 6, 7).map { ep("aaa", 1, it, 22) } +
            listOf(1, 2, 3).map { ep("bbb", 2, it, 25) } +
            listOf(1, 2, 3, 4, 5).map { ep("ccc", 1, it, 30, 0.8) } + film(50, 95)

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    @Test
    fun epochAndHash() {
        assertEquals(v.getLong("epoch"), EPOCH_MS)
        val h = v.getJSONArray("hash")
        for (i in 0 until h.length()) {
            val p = h.getJSONArray(i)
            assertEquals(p.getString(0), p.getLong(1), Channels.hashString(p.getString(0)))
        }
    }

    @Test
    fun rngAndWeights() {
        val r = Channels.rng(Channels.seedFor("comedy", 7))
        val want = v.getJSONArray("rng")
        for (i in 0 until want.length()) assertEquals(want.getDouble(i), r(), 0.0)
        val w = v.getJSONArray("weight")
        listOf(null, 0.0, 0.25, 0.5, 0.75, 1.0).forEachIndexed { i, a -> assertEquals(w.getDouble(i), Channels.affinityWeight(a), 1e-12) }
    }

    @Test
    fun lineups() {
        val l = v.getJSONObject("lineups")
        assertEquals(l.getJSONArray("films0").strings(), Channels.lineup(Channels.poolOf(films), "comedy", 0).map { it.id })
        assertEquals(l.getJSONArray("films3").strings(), Channels.lineup(Channels.poolOf(films), "comedy", 3).map { it.id })
        assertEquals(l.getJSONArray("filmsAff").strings(), Channels.lineup(Channels.poolOf(filmsAff), "for-you", 2).map { it.id })
        assertEquals(l.getJSONArray("eps").strings(), Channels.lineup(Channels.poolOf(eps), "sitcom-classics", 0).map { it.id })
        assertEquals(l.getJSONArray("eps5").strings(), Channels.lineup(Channels.poolOf(eps), "sitcom-classics", 5).map { it.id })
    }

    @Test
    fun samples() {
        assertEquals(v.getJSONArray("sample").strings(), Channels.sample(filmsAff, 4, "comedy").map { it.id })
        assertEquals(v.getJSONArray("sampleNoAff").strings(), Channels.sample(films, 4, "comedy").map { it.id })
    }

    @Test
    fun schedules() {
        val s = v.getJSONArray("schedules")
        for (i in 0 until s.length()) {
            val o = s.getJSONObject(i)
            val got = Channels.schedule(Channels.poolOf(eps), "sitcom-classics", o.getLong("now"), 4)
            assertEquals(o.getJSONArray("ids").strings(), got.slots.map { it.item.id })
            val starts = o.getJSONArray("starts")
            assertEquals((0 until starts.length()).map { starts.getLong(it) }, got.slots.map { it.startMs })
            assertEquals(o.getLong("offsetS"), got.offsetS)
            val tune = o.getJSONObject("tune")
            val tt = Channels.tuneTarget(got.slots, got.offsetS)!!
            assertEquals(tune.getString("id"), tt.id)
            assertEquals(tune.getLong("startS"), tt.startS)
            assertEquals(tune.optString("thenId"), tt.thenId ?: "")
            assertEquals(tune.getString("title"), tt.title)
        }
        val f = v.getJSONArray("schedulesFilms")
        for (i in 0 until f.length()) {
            val o = f.getJSONObject(i)
            val got = Channels.schedule(Channels.poolOf(filmsAff), "for-you", o.getLong("now"), 3)
            assertEquals(o.getJSONArray("ids").strings(), got.slots.map { it.item.id })
            assertEquals(o.getLong("offsetS"), got.offsetS)
        }
    }

    @Test
    fun tasteAndPersonal() {
        val prof =
            Taste.parse(
                """{"profile":{"genres":{"Comedy":0.8,"Sci-Fi & Fantasy":0.4,"Horror":-0.6},"decades":{"1990":0.5},"media":{"movie":0.3,"series":-0.2}}}""",
            )!!
        val aff = v.getJSONArray("aff")
        assertEquals(aff.getDouble(0), Taste.affinity(prof, listOf("Comedy"), 1994, true), 1e-12)
        assertEquals(aff.getDouble(1), Taste.affinity(prof, listOf("Horror", "Comedy"), 2010, false), 1e-12)
        assertEquals(aff.getDouble(2), Taste.affinity(prof, emptyList(), null, true), 1e-12)
        assertEquals(aff.getDouble(3), Taste.affinity(prof, listOf("Science Fiction"), 1999, false), 1e-12)
        assertEquals(v.getJSONArray("topGenres").strings(), Taste.topGenres(prof, 5))

        fun mk(
            id: String,
            g: String,
            a: Double,
            sid: String? = null,
        ) = ChanItem(
            id = id.padEnd(32, '0'),
            name = id,
            type = if (sid != null) "Episode" else "Movie",
            seriesId = sid,
            season = if (sid != null) 1 else null,
            episode = if (sid != null) id.last().digitToInt() else null,
            runTimeTicks = 50 * 60 * t,
            genres = listOf(g),
            aff = a,
        )
        val base =
            mapOf(
                "comedy" to listOf(mk("c1", "Comedy", 0.9), mk("c2", "Comedy", 0.6), mk("c3", "Comedy", 0.3), mk("c4", "Comedy", 0.7)),
                "sci-fi-nights" to listOf(mk("s1", "Science Fiction", 0.56), mk("s2", "Science Fiction", 0.5)),
                "sitcom-classics" to
                    listOf(mk("e1", "Comedy", 0.8, "show1"), mk("e2", "Comedy", 0.8, "show1"), mk("e3", "Comedy", 0.8, "show1"), mk("f1", "Comedy", 0.4, "show2")),
            )
        val defs = PersonalChannels.personalDefs(prof, base)
        val want = v.getJSONArray("personalDefs")
        assertEquals((0 until want.length()).map { want.getJSONArray(it).getString(0) to want.getJSONArray(it).getString(1) }, defs.map { it.id to it.name })
        val p = v.getJSONObject("personal")
        assertEquals(p.getJSONArray("foryou").strings(), PersonalChannels.personalItems(defs[0], base).map { it.id })
        assertEquals(p.getJSONArray("genre").strings(), PersonalChannels.personalItems(defs[1], base, "comedy").map { it.id })
        assertEquals(p.getJSONArray("shows").strings(), PersonalChannels.personalItems(defs[2], base).map { it.id })
        assertNull(Taste.parse("""{"profile":{}}"""))
    }

    @Test
    fun chainRules() {
        val (ok1, s1) = Channels.chainBudget(emptyList(), 1_000_000)
        assertTrue(ok1)
        val (ok2, s2) = Channels.chainBudget(s1 + 1_000_100 + 1_000_200, 1_000_300)
        assertFalse(ok2)
        assertEquals(3, s2.size)
        assertTrue(Channels.chainBudget(s2, 1_000_000 + Channels.CHAIN_WINDOW_MS + 1).first)
        assertTrue(Channels.playedEnough(60.0, 5000.0))
        assertFalse(Channels.playedEnough(5.0, 5000.0))
        assertTrue(Channels.playedEnough(8.0, 10.0))
    }

    @Test
    fun iptvPool() {
        val (films, pending) =
            Channels.iptvPoolItems(
                """{"items":[{"id":"p1:m:42","title":"A","runtime":5400,"year":1999,"tmdbId":7,"backdrop":"https://image.tmdb.org/x.jpg","affinity":1.4},
                   {"id":"p1:m:43","title":"Stub","runtime":300},{"id":"p1:m:44","title":"L","runtime":5400,"langUnknown":true},
                   {"id":"p1:s:45","title":"Series","runtime":5400},{"id":"p1:m:42","title":"dup","runtime":5400}],"pending":3}""",
            )
        assertEquals(listOf("p1:m:42"), films.map { it.id })
        assertEquals(1.0, films[0].aff!!, 0.0)
        assertEquals("7", films[0].tmdb)
        assertEquals(3, pending)
        val (eps, _) =
            Channels.iptvEpisodeItems(
                """{"items":[{"id":"p1:e:9","seriesId":"p1:s:3","seriesTitle":"Show","season":1,"episode":2,"runtime":1500},
                   {"id":"p1:e:10","seriesId":"p1:s:3","seriesTitle":"Show","season":0,"episode":2,"runtime":1500}]}""",
            )
        assertEquals(listOf("p1:e:9"), eps.map { it.id })
        assertEquals("Show · S1 E2 · Episode 2", Channels.playTitle(eps[0]))
    }
}
