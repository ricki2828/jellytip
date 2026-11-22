package com.github.damontecres.wholphin.jellytip.ask

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
 * The TV must read a phrase exactly as the web does. resources/jellytip/ask-vectors.json was made by running the web's own
 * lib/askParse.ts (jellytip-web-askrefine 0b53f604) under node 24 over the web test phrases, the examples and edge cases; the generator
 * is ask-vectors.gen.mts.txt next to it. The named tests below are the web's askParse.test.ts, ported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AskParseTest {
    private val v: JSONObject by lazy {
        JSONObject(
            javaClass.classLoader!!
                .getResourceAsStream("jellytip/ask-vectors.json")!!
                .bufferedReader()
                .readText(),
        )
    }
    private val year = 2026

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun JSONObject.map() = keys().asSequence().associateWith { getString(it) }

    private fun parse(q: String) = AskParse.parse(q, year)

    private fun ids(q: String) = parse(q).chips.map { it.id }

    private fun q(q: String) = AskParse.buildQuery(parse(q).chips).params()

    private data class It(
        val id: String,
        val type: String = "Movie",
        val mins: Int? = null,
        val childCount: Int? = null,
        val genres: List<String>? = null,
    )

    private val view: (It) -> AskItem = { AskItem(it.type, it.mins?.let { m -> m * 600_000_000L }, it.childCount, it.genres) }

    // ---------- golden vectors ----------

    @Test
    fun readingMatchesWeb() {
        val cases = v.getJSONArray("cases")
        assertTrue(cases.length() > 100)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("q")
            val r = parse(text)
            val want = c.getJSONArray("chips")
            assertEquals("chips: $text", (0 until want.length()).map { want.getJSONObject(it).getString("id") }, r.chips.map { it.id })
            for (j in 0 until want.length()) {
                val w = want.getJSONObject(j)
                assertEquals("label: $text", w.getString("label"), r.chips[j].label)
                assertEquals("pos: $text", w.getInt("pos"), r.chips[j].pos)
                assertEquals("src: $text", w.getString("src"), r.chips[j].src)
            }
            assertEquals("ignored: $text", c.getJSONArray("ignored").strings(), r.ignored)
            assertEquals("notes: $text", c.getJSONArray("notes").strings(), r.notes)
            assertEquals("query: $text", c.getJSONObject("query").map(), AskParse.buildQuery(r.chips).params())
            assertEquals("shuffled: $text", c.getJSONObject("shuffled").map(), AskParse.buildQuery(r.chips, shuffle = true).params())
            assertEquals("limit: $text", c.getInt("limit"), AskParse.fetchLimit(r.chips))
            assertEquals("client: $text", c.getBoolean("client"), AskParse.needsClientFilter(r.chips))
            assertEquals("applied: $text", c.getJSONArray("applied").strings(), AskParse.appliedNotes(r.chips))
        }
    }

    @Test
    fun picksMatchWeb() {
        val raw = v.getJSONArray("items")
        val items =
            (0 until raw.length()).map { i ->
                val o = raw.getJSONObject(i)
                AskItem(
                    o.getString("Type"),
                    if (o.has("RunTimeTicks")) o.getLong("RunTimeTicks") else null,
                    if (o.has("ChildCount")) o.getInt("ChildCount") else null,
                    o.getJSONArray("Genres").strings(),
                ) to o.getString("Id")
            }
        val big = (0 until AskParse.FETCH_LIMIT).map { AskItem("Movie", 80 * 600_000_000L, null, listOf("Comedy", "Horror")) to "b$it" }
        val picks = v.getJSONArray("picks")
        for (i in 0 until picks.length()) {
            val c = picks.getJSONObject(i)
            val chips = parse(c.getString("q")).chips
            val page = if (c.getString("page") == "big") big else items
            val total = if (c.isNull("total")) null else c.getInt("total")
            val what = "${c.getString("q")} ${c.getString("page")} $total ${c.getBoolean("shuffled")}"
            val kept = AskParse.filterItems(page, chips) { it.first }.map { it.second }
            assertEquals("kept count: $what", c.getInt("keptCount"), kept.size)
            val wantKept = c.getJSONArray("kept").strings()
            assertEquals("kept: $what", wantKept, kept.take(wantKept.size))
            val p = AskParse.pickFrom(page, total, chips, c.getBoolean("shuffled")) { it.first }
            assertEquals("ids: $what", c.getJSONArray("ids").strings(), p.items.map { it.second })
            assertEquals("note: $what", c.getString("note"), p.note)
        }
    }

    // ---------- the web's own tests ----------

    @Test
    fun funnyUnder90() {
        val r = parse("something funny under 90 minutes")
        assertEquals(listOf("under 90 min", "Comedy"), r.chips.map { it.label })
        assertEquals(emptyList<String>(), r.ignored)
    }

    @Test
    fun seventiesThriller() {
        val r = parse("a 70s thriller I haven't seen")
        assertEquals(listOf("genre:Thriller", "unplayed", "years:1970-1979"), r.chips.map { it.id }.sorted())
        val p = AskParse.buildQuery(r.chips).params()
        assertEquals((1970..1979).joinToString(","), p["Years"])
        assertEquals("IsUnplayed", p["Filters"])
        assertEquals("Thriller", p["Genres"])
    }

    @Test
    fun kids() {
        val r = parse("feel-good for the kids")
        val p = AskParse.buildQuery(r.chips).params()
        assertEquals("Comedy|Family|Animation|Kids", p["Genres"])
        assertEquals("G|PG|TV-Y|TV-Y7|TV-G|TV-PG|U", p["OfficialRatings"])
        assertTrue(AskParse.appliedNotes(r.chips).joinToString(" ").contains("rated G, PG"))
        val alone = parse("something for the kids")
        assertEquals("Family|Animation|Kids", AskParse.buildQuery(alone.chips).params()["Genres"])
        assertTrue(AskParse.appliedNotes(alone.chips)[0].contains("Family or Animation"))
    }

    @Test
    fun decadesRuntimeRating() {
        assertTrue(ids("after 2010").contains("years:2011-2026"))
        assertTrue(ids("before 2000").contains("years:1900-1999"))
        assertTrue(ids("recent").contains("years:2022-2026"))
        assertTrue(ids("under 1.5 hours").contains("maxrt:90"))
        assertTrue(ids("over 2 hours").contains("minrt:120"))
        assertEquals("7", q("well rated")["MinCommunityRating"])
        assertEquals("7.5", q("rated 7.5+")["MinCommunityRating"])
        assertNull(q("rated 90s")["MinCommunityRating"])
        assertEquals("rated 7+", parse("7.0 stars").chips.single().label)
    }

    @Test
    fun kindAndShowMe() {
        assertEquals("Movie", q("a movie")["IncludeItemTypes"])
        assertEquals("Series", q("a tv show")["IncludeItemTypes"])
        assertEquals("Movie,Series", q("something")["IncludeItemTypes"])
        assertEquals("Series", q("short series, mind-bending")["IncludeItemTypes"])
        assertEquals(listOf("genre:Comedy"), ids("show me something funny"))
    }

    @Test
    fun ignoredAndEmpty() {
        val r = parse("I want something funny with zebra and quokka")
        assertEquals(listOf("zebra", "quokka"), r.ignored)
        assertEquals(AskReading(emptyList(), emptyList(), emptyList()), parse(""))
        assertEquals(AskReading(emptyList(), emptyList(), emptyList()), parse("   "))
        for (e in AskParse.EXAMPLES) {
            val x = parse(e)
            assertTrue(e, x.chips.isNotEmpty())
            assertEquals(e, emptyList<String>(), x.ignored)
        }
    }

    @Test
    fun chipRemoval() {
        val r = parse("something funny under 90 minutes unwatched")
        var removed = AskParse.removeChip(emptyList(), "unplayed")
        removed = AskParse.removeChip(removed, "unplayed")
        assertEquals(listOf("unplayed"), removed)
        val chips = AskParse.activeChips(r, removed)
        assertFalse(chips.any { it.id == "unplayed" })
        assertNull(AskParse.buildQuery(chips).params()["Filters"])
        assertNull(AskParse.buildQuery(AskParse.activeChips(r, AskParse.removeChip(removed, "genre:Comedy"))).params()["Genres"])
    }

    @Test
    fun queryShape() {
        val chips = parse("funny").chips
        val a = AskParse.buildQuery(chips)
        assertEquals(listOf("CommunityRating", "SortName"), a.sortBy)
        assertEquals(AskParse.PICKS, a.limit)
        assertEquals(50, AskParse.buildQuery(chips, limit = 50).limit)
        val b = AskParse.buildQuery(chips, shuffle = true)
        assertEquals(listOf("Random"), b.sortBy)
        assertNull(b.sortOrder)
        assertEquals(listOf("PrimaryImageAspectRatio", "Genres"), AskParse.buildQuery(parse("scary comedy").chips).fields)
        assertEquals(listOf("PrimaryImageAspectRatio", "ChildCount"), AskParse.buildQuery(parse("short series").chips).fields)
    }

    @Test
    fun clientFilters() {
        val under = AskParse.filterItems(listOf(It("a", mins = 85), It("b", mins = 90), It("c", mins = 91), It("d"), It("e", "Series", mins = 30)), parse("under 90 minutes").chips, view)
        assertEquals(3, under.size)
        val moods = AskParse.filterItems(listOf(It("a", genres = listOf("Horror")), It("b", genres = listOf("Comedy", "Horror")), It("c", genres = listOf("comedy"))), parse("scary comedy").chips, view)
        assertEquals(listOf("b"), moods.map { it.id })
        val seasons =
            AskParse.filterItems(
                listOf(It("a", "Series", childCount = 2), It("b", "Series", childCount = 3), It("c", "Series", childCount = 4), It("d", "Series")),
                parse("short series").chips,
                view,
            )
        assertEquals(2, seasons.size)
        val aliases = AskParse.filterItems(listOf(It("a", "Series", genres = listOf("Sci-Fi & Fantasy", "Action & Adventure")), It("b", genres = listOf("Science Fiction"))), parse("sci-fi action").chips, view)
        assertEquals(1, aliases.size)
    }

    @Test
    fun coverage() {
        val chips = parse("funny under 90 minutes").chips
        val page = (0 until AskParse.FETCH_LIMIT).map { It("p$it", mins = 80, genres = listOf("Comedy")) }
        val p = AskParse.pickFrom(page, 1200, chips, false, view)
        assertEquals(AskParse.PICKS, p.items.size)
        assertTrue(p.note.contains("the first 300 of 1200 matches"))
        assertTrue(p.note.contains("only checked within those"))
        assertTrue(p.note.contains("Showing 24 of 300"))
        assertTrue(AskParse.pickFrom(page, 1200, chips, true, view).note.contains("a random 300 of 1200"))
        val small = AskParse.pickFrom(listOf(It("a", mins = 80, genres = listOf("Comedy")), It("b", mins = 120, genres = listOf("Comedy"))), 2, chips, false, view)
        assertTrue(small.note.contains("Checked all 2 matches"))
        assertEquals(1, small.items.size)
        assertTrue(AskParse.pickFrom(listOf(It("a")), 1, parse("funny").chips, false, view).note.startsWith("All 1 matches"))
        assertTrue(AskParse.pickFrom(page, null, parse("funny").chips, false, view).note.contains("the first 300 matches"))
    }

    @Test
    fun negativesAndContradictions() {
        // Negated genres leave those genres out
        for ((text, id) in listOf("not scary" to "notgenre:Horror", "no horror" to "notgenre:Horror", "anything but comedy" to "notgenre:Comedy", "non-animated" to "notgenre:Animation")) {
            val r = parse(text)
            assertEquals(text, listOf(id), r.chips.map { it.id })
            assertEquals(text, emptyList<String>(), r.ignored)
        }
        // Not a genre ("sad" would drop every drama): never guessed
        for ((text, ign) in listOf("not sad" to "not sad", "without violence" to "without violence")) {
            val r = parse(text)
            assertEquals(text, 0, r.chips.size)
            assertEquals(text, listOf(ign), r.ignored)
            assertTrue(text, r.notes.joinToString(" ").contains("Negatives only work for genres"))
        }
        assertEquals(emptyList<String>(), ids("none"))
        // A negation never turns into the genre it rules out (articles, or-lists); "and" does not carry it
        assertEquals(listOf("kind:Movie", "notgenre:Horror"), ids("not a scary movie").sorted())
        assertEquals(listOf("notgenre:Animation", "notgenre:Horror"), ids("nothing scary or animated").sorted())
        assertEquals(listOf("genre:Comedy", "notgenre:Horror"), ids("not scary and funny").sorted())
        // "not X or Y" is neither, whatever X is
        assertEquals(listOf("notgenre:Comedy"), ids("not too long or funny"))
        assertEquals(listOf("notgenre:Animation", "notgenre:Horror"), ids("nothing scary or long or animated").sorted())
        assertEquals(listOf("notgenre:Animation", "notgenre:Horror"), ids("no horror or sequels or not animated").sorted())
        val r = parse("funny but not for kids")
        assertEquals(listOf("genre:Comedy"), r.chips.map { it.id })
        assertTrue(r.ignored.contains("but not for kids"))
        assertTrue(ids("no more than 90 minutes").contains("maxrt:90"))
        assertEquals((1980..1999).joinToString(","), q("80s or 90s")["Years"])
        val b = parse("movie or series")
        assertEquals(listOf("kind:Series"), b.chips.map { it.id })
        assertTrue(b.ignored.contains("movie"))
        val c = parse("rewatch but unwatched")
        assertEquals(listOf("unplayed"), c.chips.map { it.id })
        assertTrue(c.ignored.contains("rewatch"))
    }

    @Test
    fun fromDataMatchesWeb() {
        // Chips built from filter data (refine chips, an LLM-produced query) read exactly as on the web
        fun data(o: JSONObject): ChipData =
            when (o.getString("t")) {
                "genre" -> ChipData.Genre(o.getJSONArray("genres").strings())
                "notGenre" -> ChipData.NotGenre(o.getJSONArray("genres").strings())
                "kind" -> ChipData.Kind(o.getString("kind"))
                "maxRuntime" -> ChipData.MaxRuntime(o.getInt("min"))
                "minRuntime" -> ChipData.MinRuntime(o.getInt("min"))
                "years" -> ChipData.Years(o.getInt("from"), o.getInt("to"))
                "unplayed" -> ChipData.Unplayed
                "played" -> ChipData.Played
                "kids" -> ChipData.Kids(o.getJSONArray("ratings").strings(), o.getJSONArray("genres").strings())
                "minRating" -> ChipData.MinRating(o.getDouble("value"))
                "maxSeasons" -> ChipData.MaxSeasons(o.getInt("n"))
                else -> error(o.toString())
            }
        val cases = v.getJSONArray("fromData")
        assertEquals(3, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val list = c.getJSONArray("list")
            val chips = AskParse.chipsFromData((0 until list.length()).map { data(list.getJSONObject(it)) })
            val want = c.getJSONArray("chips")
            assertEquals("ids $i", (0 until want.length()).map { want.getJSONObject(it).getString("id") }, chips.map { it.id })
            assertEquals("labels $i", (0 until want.length()).map { want.getJSONObject(it).getString("label") }, chips.map { it.label })
            assertEquals("query $i", c.getJSONObject("query").map(), AskParse.buildQuery(chips).params())
            assertEquals("applied $i", c.getJSONArray("applied").strings(), AskParse.appliedNotes(chips))
        }
    }

    @Test
    fun rickiWithTheKidsNothingAnimatedNothingScary() {
        val r = parse("I'm here with my two kids, want something non animated not scary that would work for all")
        assertEquals(listOf("kids", "notgenre:Animation", "notgenre:Horror"), r.chips.map { it.id }.sorted())
        val p = AskParse.buildQuery(r.chips).params()
        assertEquals("Family|Kids", p["Genres"])
        assertEquals("PrimaryImageAspectRatio,Genres", p["Fields"])
        val out = AskParse.filterItems(listOf(It("a", genres = listOf("Family", "Adventure")), It("b", genres = listOf("Family", "Animation")), It("c", genres = listOf("Kids", "Horror")), It("d")), r.chips, view)
        assertEquals(listOf("a", "d"), out.map { it.id })
        // Same ids from data as from text, so a refine chip never doubles a parsed one
        assertEquals(ids("no sci-fi").single(), AskParse.chipFor(ChipData.NotGenre(listOf("Science Fiction", "Sci-Fi & Fantasy"))).id)
    }

    @Test
    fun sdkRequest() {
        val req = AskParse.buildQuery(parse("a 70s thriller I haven't seen").chips).toGetItemsRequest(null)
        assertEquals(false, req.isPlayed)
        assertEquals(listOf("Thriller"), req.genres)
        assertEquals((1970..1979).toList(), req.years)
        assertEquals(true, req.enableTotalRecordCount)
        val kids = AskParse.buildQuery(parse("cartoons for the kids well rated").chips).toGetItemsRequest(null)
        assertEquals(AskParse.KIDS_RATINGS, kids.officialRatings)
        assertEquals(7.0, kids.minCommunityRating!!, 0.0)
    }
}
