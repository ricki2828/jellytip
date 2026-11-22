package com.github.damontecres.wholphin.jellytip.ask

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV reads IPTV asks exactly as the web: resources/jellytip/askvod-vectors.json from web/src/lib/askVod.ts (7d8ca9e7). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AskVodTest {
    private val v: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/askvod-vectors.json")!!.bufferedReader().readText())
    }

    private fun JSONObject.map() = keys().asSequence().associateWith { getString(it) }

    private fun queries(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).let { q -> q.getString("kind") to q.getJSONObject("params").map() } }

    private fun ours(list: List<VodQuery>) = list.map { it.kind to it.params }

    @Test
    fun tmdbTableMatchesWeb() {
        assertEquals(v.getJSONObject("tmdb").keys().asSequence().associateWith { v.getJSONObject("tmdb").getInt(it) }, AskVod.TMDB_GENRES)
    }

    @Test
    fun queriesKeepAndNotesMatchWeb() {
        val raw = v.getJSONArray("items")
        val items =
            (0 until raw.length()).map { i ->
                val o = raw.getJSONObject(i)
                val g = o.getJSONArray("genreIds")
                VodView(
                    kind = o.getString("kind"),
                    year = if (o.isNull("year")) null else o.getInt("year"),
                    genreIds = (0 until g.length()).map { g.getInt(it) },
                    certification = if (o.isNull("certification")) null else o.getString("certification"),
                    runtime = if (o.isNull("runtime")) null else o.getInt("runtime"),
                    played = if (o.isNull("played")) null else o.getBoolean("played"),
                )
            }
        val cases = v.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("q")
            val chips = AskParse.parse(text, 2026).chips
            assertEquals("queries: $text", queries(c.getJSONArray("queries")), ours(AskVod.vodQueries(chips)))
            val keep = c.getJSONArray("keep")
            assertEquals("keep: $text", (0 until keep.length()).map { keep.getBoolean(it) }, items.map { AskVod.vodKeep(it, chips) })
            assertEquals("note: $text", if (c.isNull("note")) null else c.getString("note"), AskVod.vodNote(chips))
        }
        val person = AskParse.chipsFromData(listOf(ChipData.Person("d1", "C", "Director")))
        assertEquals(emptyList<VodQuery>(), AskVod.vodQueries(person))
        assertEquals(v.getJSONObject("person").getString("note"), AskVod.vodNote(person))
    }
}
