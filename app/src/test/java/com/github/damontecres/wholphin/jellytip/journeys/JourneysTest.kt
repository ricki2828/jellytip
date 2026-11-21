package com.github.damontecres.wholphin.jellytip.journeys

import com.github.damontecres.wholphin.jellytip.rows.HubCollection
import com.github.damontecres.wholphin.jellytip.rows.HubSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The web's journeys.test.ts, ported (routes excepted: the TV passes the id in its Destination). */
class JourneysTest {
    private fun id(n: Int) = n.toString(16).padStart(32, '0')

    private fun dashed(s: String) = "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"

    private fun part(
        title: String,
        year: Int?,
        itemId: String?,
    ) = JourneyPart(itemId, title, year)

    @Test
    fun franchiseOrder() {
        val d =
            Journeys.franchiseDef(
                "10",
                FranchisePage("Alien", listOf(part("C", 1992, "c"), part("A", 1979, "a"), part("X", null, null), part("B", 1986, null), part("B2", 1986, "b2"))),
            )!!
        assertEquals(listOf("A", "B", "X", "B2", "C"), d.parts.map { it.title })
        assertNull(d.parts[1].itemId)
        assertEquals("Alien in order · 5 films", Journeys.journeyTitle(d))
        assertNull(Journeys.franchiseDef("1", FranchisePage("F", listOf(part("a", null, "a"), part("b", null, null)))))
    }

    @Test
    fun missingAndVirtual() {
        val d = Journeys.franchiseDef("1", FranchisePage("F", listOf(part("a", 1, "a"), part("b", 2, "b"), part("c", 3, "c"))))!!
        val e = Journeys.buildEntries(d, listOf(JourneyItem("a", "A", played = true), JourneyItem("b", "B", locationType = "Virtual"), JourneyItem("c", "C")))
        assertEquals(listOf(true, false, true), e.map { it.playable })
        assertEquals("c", Journeys.nextUp(e)!!.first.itemId)
        assertEquals(Journeys.Progress(1, 2, 3), Journeys.progress(e))
    }

    @Test
    fun nextUpAndResume() {
        val d = JourneyDef("l:x", JourneyKind.LIST, "X", listOf("a", "b", "c").map { JourneyPart(it, "", null) })
        var e = Journeys.buildEntries(d, listOf(JourneyItem("a", "A", played = true), JourneyItem("b", "B", positionTicks = 500), JourneyItem("c", "C", positionTicks = 900)))
        val n = Journeys.nextUp(e)!!
        assertEquals("b", n.first.itemId)
        assertTrue(n.second)
        assertEquals(500L, n.first.positionTicks)
        e = Journeys.buildEntries(d, listOf("a", "b", "c").map { JourneyItem(it, played = true) })
        assertNull(Journeys.nextUp(e))
        e = Journeys.buildEntries(d, listOf("a", "b", "c").map { JourneyItem(it) })
        assertFalse(Journeys.nextUp(e)!!.second)
        // ids match with or without dashes, any case
        val one = JourneyDef("l:x", JourneyKind.LIST, "X", listOf(JourneyPart("ABCD", "", null)))
        assertTrue(Journeys.buildEntries(one, listOf(JourneyItem("ab-cd", "T")))[0].playable)
    }

    @Test
    fun director() {
        fun film(
            n: Int,
            y: Int?,
            loc: String? = null,
        ) = JourneyItem(id(n), "F$n", y, loc)
        assertNull(Journeys.directorDef(id(1), "Hitchcock", listOf(film(1, 1950), film(2, 1951), film(3, 1952))))
        assertNull(Journeys.directorDef(id(1), "Hitchcock", listOf(film(1, 1950), film(2, 1951), film(3, 1952), film(4, 1953, "Virtual"))))
        assertNull(Journeys.directorDef(id(1), "Hitchcock", listOf(film(1, 1950), film(2, 1951), film(3, 1952), film(3, 1952))))
        val many = (0 until 9).map { film(it + 1, 2000 - it) }
        val d = Journeys.directorDef(id(1), "Hitchcock", many)!!
        assertEquals(Journeys.DIRECTOR_CAP, d.parts.size)
        assertEquals(listOf(1992, 1993, 1994, 1995, 1996, 1997), d.parts.map { it.year })
        assertEquals("Hitchcock: earliest 6 of 9 films", Journeys.journeyTitle(d))
        assertEquals(6, Journeys.directorDef(id(1), "H", many.take(6))!!.parts.size)
        assertEquals("H in order · 5 films", Journeys.journeyTitle(Journeys.directorDef(id(1), "H", many.take(5))!!))
        assertEquals("H: earliest 6 of 14 films", Journeys.journeyTitle(Journeys.directorDef(id(1), "H", many.take(6), 14)!!))
    }

    @Test
    fun list() {
        val ids = (0 until 12).map { "i$it" }
        val d = Journeys.listDef(JourneyList("best", "Best", listOf("i0", "i0") + ids))!!
        assertEquals(Journeys.LIST_CAP, d.parts.size)
        assertEquals(ids.take(8), d.parts.map { it.itemId })
        assertNull(Journeys.listDef(JourneyList("s", "S", listOf("a", "a"))))
        assertEquals("Best: first 8 of 12 films", Journeys.journeyTitle(d))
    }

    private fun card(
        cid: String,
        title: String,
        libCount: Int,
        person: String? = null,
        mediaType: String? = "movie",
    ) = HubCollection(cid, title, null, null, libCount, libCount, 0, false, mediaType, personId = person?.let { UUID.fromString(dashed(it)) })

    @Test
    fun summaries() {
        val hub =
            listOf(
                HubSection("franchises", "F", listOf(card("tmdb:1", "Small", 2), card("tmdb:2", "Big", 9), card("tmdb:3", "One", 1), card("tmdb:4", "Show", 5, mediaType = "tv"), card("weird", "W", 5))),
                HubSection("directors", "D", listOf(card("dir:1", "Few", 3, id(1)), card("dir:2", "Lots", 20, id(2)), card("dir:3", "Four", 4, id(3)), card("dir:4", "NoPerson", 8))),
            )
        val g = Journeys.summaries(hub, listOf(JourneyList("a", "A", listOf("1", "2", "3")), JourneyList("b", "B", listOf("1")), JourneyList("bad slug", "C", listOf("1", "2"))))
        assertEquals(listOf("Big in order · 9 films", "Small in order · 2 films"), g.franchise.map { it.title })
        assertEquals(listOf("f:2", "f:1"), g.franchise.map { it.id })
        assertEquals(listOf("Director" to "Lots", "Director" to "Four"), g.director.map { it.kicker to it.title })
        assertEquals("d:${id(2)}", g.director[0].id)
        assertEquals(listOf("l:a"), g.list.map { it.id })
        assertTrue(Journeys.summaries(null, null).isEmpty)
        val items = (0 until 40).map { card("tmdb:${it + 1}", "F$it", 5) }
        val lists = (0 until 40).map { JourneyList("s$it", "L$it", listOf("a", "b")) }
        val capped = Journeys.summaries(listOf(HubSection("franchises", "F", items)), lists)
        assertEquals(12, capped.franchise.size)
        assertEquals(10, capped.list.size)
    }

    @Test
    fun ids() {
        assertEquals(JourneyKind.FRANCHISE to "10", Journeys.parseJourneyId(Journeys.journeyId(JourneyKind.FRANCHISE, "10")))
        assertEquals(JourneyKind.DIRECTOR to id(5), Journeys.parseJourneyId(Journeys.journeyId(JourneyKind.DIRECTOR, id(5).uppercase())))
        assertEquals(JourneyKind.LIST to "best-of_2020", Journeys.parseJourneyId("l:best-of_2020"))
        assertEquals(JourneyKind.FRANCHISE to "10", Journeys.parseJourneyId("F:10"))
        assertEquals(JourneyKind.DIRECTOR to id(5), Journeys.parseJourneyId("D:" + dashed(id(5))))
        for (bad in listOf("f:../x", "d:123", "x:1", "l:", "f:1/2", "f:abc", "l:a b")) assertNull(bad, Journeys.parseJourneyId(bad))
    }

    @Test
    fun cache() =
        runBlocking {
            val c = JourneyCache { 0L }
            fun ok(name: String): JourneyResult = JourneyResult.Ok(JourneyDef("l:x", JourneyKind.LIST, name, emptyList()))
            fun nm(r: JourneyResult) = if (r is JourneyResult.Ok) r.def.name else r.toString()
            assertEquals("A", nm(c.get("a", "l:x") { ok("A") }))
            assertEquals("B", nm(c.get("b", "l:x") { ok("B") }))
            assertEquals("A", nm(c.get("a", "l:x") { ok("again") }))
            c.clear()
            assertEquals("fresh", nm(c.get("a", "l:x") { ok("fresh") }))
            assertEquals(JourneyResult.Error, c.get("a", "e") { throw IllegalStateException("x") })
            assertEquals("recovered", nm(c.get("a", "e") { ok("recovered") }))
            assertEquals(JourneyResult.None, c.get("a", "n") { JourneyResult.None })
            assertEquals(JourneyResult.None, c.get("a", "n") { ok("not asked") }) // "not found" is cached
            var t = 0L
            val timed = JourneyCache { t }
            timed.get("a", "k") { ok("one") }
            t = JourneyCache.TTL_MS
            assertEquals("two", nm(timed.get("a", "k") { ok("two") }))
        }

    // ---------- build() with injected fetchers ----------

    private class Deps(
        val calls: MutableList<String> = mutableListOf(),
        val page: suspend (Int) -> FranchisePage? = { FranchisePage("Alien", listOf(JourneyPart("a", "a", 1979), JourneyPart("b", "b", 1986), JourneyPart(null, "c", 1992))) },
        val films: suspend (String) -> Pair<List<JourneyItem>, Int?> = { p -> (0 until 5).map { JourneyItem(it.toString(16).padStart(32, '0'), "F$it", 1950 + it) } to null },
        val name: suspend (String) -> String = { "Hitchcock" },
        val lists: suspend (String) -> JourneyList? = { s -> if (s == "best") JourneyList(s, "Best", listOf("a", "b", "c")) else null },
    ) : JourneyDeps {
        override suspend fun collectionPage(tmdbId: Int) = page(tmdbId)

        override suspend fun directorFilms(personId: String): Pair<List<JourneyItem>, Int?> {
            calls.add("films:$personId")
            return films(personId)
        }

        override suspend fun personName(personId: String) = name(personId)

        override suspend fun list(slug: String) = lists(slug)
    }

    @Test
    fun build() =
        runBlocking {
            val d = Deps()
            val r = Journeys.build("d:" + id(7), d)
            assertEquals(listOf("films:" + id(7)), d.calls)
            assertEquals("Hitchcock in order · 5 films", Journeys.journeyTitle((r as JourneyResult.Ok).def))
            // a truncated director result reports the library total from the server
            val sixty = (0 until 60).map { JourneyItem(id(it + 1), "F$it", 1900 + it) }
            val t = Journeys.build("d:" + id(7), Deps(films = { sixty to 75 }))
            assertEquals("Hitchcock: earliest 6 of 75 films", Journeys.journeyTitle((t as JourneyResult.Ok).def))
            // under 4 playable films is "short", not "none"
            val short = Journeys.build("d:" + id(7), Deps(films = { listOf(JourneyItem(id(1)), JourneyItem(id(2)), JourneyItem(id(3)), JourneyItem(id(4), locationType = "Virtual")) to null }))
            assertEquals(JourneyResult.Short(id(7), 3), short)
            assertEquals(3, (Journeys.build("f:10", Deps()) as JourneyResult.Ok).def.parts.size)
            assertEquals(JourneyResult.None, Journeys.build("f:10", Deps(page = { null })))
            // a failed fetch is "error", an empty result is "none" or "short"
            val boom: suspend (Any) -> Nothing = { throw IllegalStateException("net") }
            assertEquals(JourneyResult.Error, Journeys.build("f:10", Deps(page = { boom(it) })))
            assertEquals(JourneyResult.Error, Journeys.build("d:" + id(7), Deps(films = { boom(it) })))
            assertEquals(JourneyResult.Error, Journeys.build("d:" + id(7), Deps(name = { boom(it) })))
            assertEquals(JourneyResult.Error, Journeys.build("l:best", Deps(lists = { boom(it) })))
            assertEquals(JourneyResult.None, Journeys.build("l:nope", Deps()))
            assertTrue(Journeys.build("d:" + id(7), Deps(films = { emptyList<JourneyItem>() to null })) is JourneyResult.Short)
            assertEquals(JourneyResult.None, Journeys.build("garbage", Deps()))
        }

    @Test
    fun orderStableAndNotFound() {
        assertEquals(listOf("b", "a", "c"), Journeys.orderByRelease(listOf(2 to "a", 1 to "b", 2 to "c")) { it.first }.map { it.second })
        assertTrue(Journeys.isNotFoundStatus(404))
        assertFalse(Journeys.isNotFoundStatus(null))
        assertFalse(Journeys.isNotFoundStatus(500))
        assertFalse(Journeys.isNotFoundStatus(200))
    }
}
