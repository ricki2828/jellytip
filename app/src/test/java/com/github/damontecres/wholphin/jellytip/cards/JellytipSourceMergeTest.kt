package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge.Entry
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge.IptvRef
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge.LibRef
import com.github.damontecres.wholphin.jellytip.iptv.IptvResume
import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsParser
import com.github.damontecres.wholphin.jellytip.search.SearchParse
import com.github.damontecres.wholphin.jellytip.search.SearchResult
import com.github.damontecres.wholphin.jellytip.search.SearchRules
import com.github.damontecres.wholphin.jellytip.watchlist.mergeWatchlist
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** One tile per title across library and IPTV: the pure merge, its search / watchlist / continue users, and the new optional JSON fields. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class JellytipSourceMergeTest {
    private fun lib(
        id: String,
        tmdb: Int? = null,
        kind: String = "movie",
    ) = Entry("L:$id", lib = LibRef(id, kind, tmdb))

    private fun vod(
        id: String,
        tmdb: Int? = null,
        kind: String = "movie",
        libraryId: String? = null,
        sources: List<IptvSource> = emptyList(),
        quality: List<String> = emptyList(),
    ) = Entry("V:$id", iptv = IptvRef(id, kind, tmdb, libraryId, sources, quality))

    private fun payloads(m: JellytipSourceMerge.Merged<String>) = m.entries

    // ---- rule 1 (a) / (b) / (c)

    @Test
    fun libraryIdDropsTheIptvTwin() {
        val m = JellytipSourceMerge.merge(listOf(lib("aaaa"), vod("p:m:1", libraryId = "AAAA"), vod("p:m:2")))
        assertEquals(listOf("L:aaaa", "V:p:m:2"), payloads(m))
    }

    @Test
    fun libraryIdWithDashesMatches() {
        val id = "11111111-2222-3333-4444-555555555555"
        val m = JellytipSourceMerge.merge(listOf(lib(id), vod("p:m:1", libraryId = id.replace("-", ""))))
        assertEquals(listOf("L:$id"), payloads(m))
    }

    @Test
    fun libraryInSourcesDropsTheIptvTwin() {
        val src = listOf(IptvSource("library", "bbbb"), IptvSource("iptv", "p:m:1"))
        val m = JellytipSourceMerge.merge(listOf(vod("p:m:1", sources = src), lib("bbbb")))
        assertEquals(listOf("L:bbbb"), payloads(m))
    }

    @Test
    fun tmdbFallbackWhenNeitherFieldIsPresent() {
        val m = JellytipSourceMerge.merge(listOf(lib("a", tmdb = 949), vod("p:m:1", tmdb = 949), vod("p:m:2", tmdb = 950)))
        assertEquals(listOf("L:a", "V:p:m:2"), payloads(m))
    }

    @Test
    fun tmdbFallbackNeedsTheSameKind() {
        // movie 100 and tv 100 are different titles
        val m = JellytipSourceMerge.merge(listOf(lib("a", tmdb = 100, kind = "movie"), vod("s", tmdb = 100, kind = "series")))
        assertEquals(listOf("L:a", "V:s"), payloads(m))
    }

    @Test
    fun tmdbFallbackNotUsedWhenLibraryIdIsPresentButNotInTheRow() {
        // The server says this title's library copy is some other item: it is not this row's film, whatever the TMDB id says
        val m = JellytipSourceMerge.merge(listOf(lib("a", tmdb = 949), vod("p:m:1", tmdb = 949, libraryId = "zzzz")))
        assertEquals(listOf("L:a", "V:p:m:1"), payloads(m))
    }

    @Test
    fun tmdbFallbackNotUsedWhenLibrarySourcesArePresentButNotInTheRow() {
        val m = JellytipSourceMerge.merge(listOf(lib("a", tmdb = 949), vod("p:m:1", tmdb = 949, sources = listOf(IptvSource("library", "zzzz")))))
        assertEquals(2, m.entries.size)
    }

    @Test
    fun unknownIdsNeverDrop() {
        val m = JellytipSourceMerge.merge(listOf(lib("a"), vod("p:m:1"), vod("p:m:2"), lib("b"), vod("p:m:3", libraryId = "cccc")))
        assertEquals(listOf("L:a", "V:p:m:1", "V:p:m:2", "L:b", "V:p:m:3"), payloads(m))
    }

    @Test
    fun absentFieldsLeaveTheListUnchanged() {
        val input = listOf(vod("p:m:1", tmdb = 1), lib("a", tmdb = 2), vod("p:s:1", tmdb = 3, kind = "series"), lib("b"))
        assertEquals(input.map { it.payload }, payloads(JellytipSourceMerge.merge(input)))
        assertEquals(emptyList<String>(), payloads(JellytipSourceMerge.merge<String>(emptyList())))
    }

    // ---- positions

    @Test
    fun libraryTileKeepsTheEarlierPosition() {
        // IPTV copy first: the library tile takes its slot
        val a = JellytipSourceMerge.merge(listOf(vod("p:m:1", libraryId = "a"), lib("x"), lib("a")))
        assertEquals(listOf("L:a", "L:x"), payloads(a))
        // library first: stays
        val b = JellytipSourceMerge.merge(listOf(lib("a"), lib("x"), vod("p:m:1", libraryId = "a")))
        assertEquals(listOf("L:a", "L:x"), payloads(b))
        // the others keep their relative order
        val c = JellytipSourceMerge.merge(listOf(lib("x"), vod("p:m:1", libraryId = "a"), vod("p:m:9"), lib("a")))
        assertEquals(listOf("L:x", "L:a", "V:p:m:9"), payloads(c))
    }

    // ---- two IPTV twins

    @Test
    fun twoIptvCopiesOfOneTitleKeepTheFirst() {
        val m = JellytipSourceMerge.merge(listOf(vod("p:m:1", tmdb = 7, quality = listOf("4k")), lib("x"), vod("q:m:9", tmdb = 7, quality = listOf("hd"))))
        assertEquals(listOf("V:p:m:1", "L:x"), payloads(m))
        assertEquals(listOf("q:m:9"), m.otherSources(JellytipSourceMerge.iptvKey("p:m:1")).map { it.id })
    }

    @Test
    fun twoIptvCopiesOfAKindDifferenceStayTwo() {
        val m = JellytipSourceMerge.merge(listOf(vod("p:m:1", tmdb = 7), vod("p:s:1", tmdb = 7, kind = "series")))
        assertEquals(2, m.entries.size)
    }

    @Test
    fun bothIptvCopiesFoldIntoTheLibraryTile() {
        // first copy knows the library id, the second only the TMDB id: both go, the library tile sits at the first slot
        val m = JellytipSourceMerge.merge(listOf(vod("p:m:1", tmdb = 7, libraryId = "a"), lib("x"), lib("a", tmdb = 7), vod("q:m:2", tmdb = 7)))
        assertEquals(listOf("L:a", "L:x"), payloads(m))
        assertEquals(listOf("p:m:1", "q:m:2"), m.otherSources(JellytipSourceMerge.libKey("a")).map { it.id })
    }

    // ---- the lookup of other sources

    @Test
    fun sourcesByIdRidesAlongOnTheLibraryTile() {
        val sbi = mapOf("AAAA-BBBB" to listOf(IptvSource("iptv", "p:m:5", listOf("4k"), listOf("HDR"), 2), IptvSource("library", "ignored")))
        val m = JellytipSourceMerge.merge(listOf(lib("aaaabbbb"), lib("c")), sbi)
        assertEquals(listOf("p:m:5"), m.otherSources(JellytipSourceMerge.libKey("AAAA-BBBB")).map { it.id })
        assertEquals(2, m.otherSources(JellytipSourceMerge.libKey("aaaabbbb")).first().copies)
        assertTrue(m.otherSources(JellytipSourceMerge.libKey("c")).isEmpty())
        assertEquals(listOf("L:aaaabbbb", "L:c"), payloads(m))
    }

    @Test
    fun aDroppedTwinIsRememberedAsASource() {
        // sources empty: synthesised from the item itself
        val m = JellytipSourceMerge.merge(listOf(lib("a"), vod("p:m:1", libraryId = "a", quality = listOf("4k"))))
        val s = m.otherSources(JellytipSourceMerge.libKey("a"))
        assertEquals(listOf("iptv:p:m:1"), s.map { "${it.type}:${it.id}" })
        assertEquals(listOf("4k"), s.first().quality)
        // sources present and listing the item: used as sent, nothing doubled by the sourcesById seed
        val src = listOf(IptvSource("library", "a"), IptvSource("iptv", "p:m:1", copies = 3), IptvSource("iptv", "q:m:2"))
        val m2 = JellytipSourceMerge.merge(listOf(lib("a"), vod("p:m:1", sources = src)), mapOf("a" to listOf(IptvSource("iptv", "q:m:2"))))
        assertEquals(listOf("q:m:2", "p:m:1"), m2.otherSources(JellytipSourceMerge.libKey("a")).map { it.id })
    }

    @Test
    fun aKeptIptvTileListsItsOtherSources() {
        val src = listOf(IptvSource("iptv", "p:m:1"), IptvSource("iptv", "q:m:2"), IptvSource("library", "gone"))
        val m = JellytipSourceMerge.merge(listOf(vod("p:m:1", sources = src)))
        assertEquals(listOf("q:m:2", "gone"), m.otherSources(JellytipSourceMerge.iptvKey("p:m:1")).map { it.id })
    }

    // ---- BaseItem adapter + orderedMerged

    private fun movieItem(
        id: UUID,
        tmdb: String? = null,
        type: BaseItemKind = BaseItemKind.MOVIE,
    ) = BaseItem(BaseItemDto(id = id, type = type, name = "T", providerIds = tmdb?.let { mapOf("Tmdb" to it) }))

    private fun vodItem(
        id: String,
        tmdb: Int? = null,
        kind: String = "movie",
        libraryId: String? = null,
    ) = IptvVodItem(
        id = id,
        kind = kind,
        tmdbId = tmdb,
        title = "T",
        year = null,
        quality = emptyList(),
        poster = null,
        backdrop = null,
        overview = "",
        rating = null,
        providerName = null,
        progress = null,
        libraryId = libraryId,
    )

    @Test
    fun baseItemTmdbIdIsRead() {
        val id = UUID.randomUUID()
        assertEquals(LibRef(id.toString(), "movie", 949), movieItem(id, "949").libRef())
        assertEquals(LibRef(id.toString(), "series", 12), movieItem(id, "12", BaseItemKind.SERIES).libRef())
        assertNull(movieItem(id).libRef().tmdbId)
        // an episode never matches by TMDB id (its provider ids are the episode's)
        val ep = movieItem(id, "5", BaseItemKind.EPISODE).libRef()
        assertEquals("episode", ep.kind)
        assertNull(ep.tmdbId)
    }

    @Test
    fun orderedMergedKeepsTheServersOrderAndDropsTheTwin() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val m =
            JellytipSourceMerge.orderedMerged(
                order = listOf("p:m:1", "p:m:2", a.toString().replace("-", ""), b.toString()),
                library = listOf(movieItem(a, "949"), movieItem(b)),
                iptv = listOf(vodItem("p:m:1", tmdb = 949), vodItem("p:m:2", tmdb = 5)),
                sourcesById = emptyMap(),
                lib = { "L${it.id}" },
                vod = { "V${it.id}" },
            )
        // the library film takes the earlier slot of its IPTV twin (rule: the earlier of the two positions)
        assertEquals(listOf("L$a", "Vp:m:2", "L$b"), m.entries)
    }

    @Test
    fun noLibraryEntriesKeepsEveryIptvItem() {
        // The library half failed to load / is not there: nothing can be a twin, so every IPTV tile stays (even with libraryId, sources, same TMDB id)
        val src = listOf(IptvSource("library", "aaaa"), IptvSource("iptv", "p:m:1"))
        val m = JellytipSourceMerge.merge(listOf(vod("p:m:1", tmdb = 7, libraryId = "aaaa", sources = src), vod("p:m:2", tmdb = 8), vod("p:m:3", tmdb = 9, libraryId = "bbbb")))
        assertEquals(listOf("V:p:m:1", "V:p:m:2", "V:p:m:3"), payloads(m))
        val o =
            JellytipSourceMerge.orderedMerged(
                order = listOf("p:m:1", UUID.randomUUID().toString()),
                library = emptyList(),
                iptv = listOf(vodItem("p:m:1", tmdb = 949, libraryId = "aaaa"), vodItem("p:m:2", tmdb = 950)),
                sourcesById = emptyMap(),
                lib = { "L" },
                vod = { "V${it.id}" },
            )
        assertEquals(listOf("Vp:m:1", "Vp:m:2"), o.entries)
    }

    // ---- search

    private fun sr(
        source: String,
        title: String,
        tmdb: Int? = null,
        id: String? = null,
        iptvId: String? = null,
        libraryId: String? = null,
        sources: List<IptvSource> = emptyList(),
        kind: String = "film",
    ) = SearchResult(kind = kind, source = source, title = title, tmdbId = tmdb, id = id?.let { UUID.fromString(it) }, iptvId = iptvId, libraryId = libraryId, sources = sources)

    private val heatId = "11111111-2222-3333-4444-555555555555"
    private val otherId = "99999999-2222-3333-4444-555555555555"

    @Test
    fun searchTopHitLibraryTitleHasNoIptvTwinInTheRow() {
        val top = sr("library", "Heat", tmdb = 949, id = heatId)
        val rows =
            listOf(
                sr("library", "Heat 2", tmdb = 1, id = otherId),
                sr("iptv", "Heat", tmdb = 949, iptvId = "p:m:1"), // twin by TMDB
                sr("iptv", "Heat (4K)", iptvId = "p:m:9", libraryId = heatId.replace("-", "")), // twin by libraryId
                sr("iptv", "Collateral", tmdb = 2, iptvId = "p:m:2"),
            )
        assertEquals(listOf("Heat 2", "Collateral"), SearchRules.mergeResults(rows, top).map { it.title })
    }

    @Test
    fun searchTopHitIptvTitleHasNoLibraryTwinInTheRow() {
        val top = sr("iptv", "Heat", iptvId = "p:m:1", libraryId = heatId)
        val rows = listOf(sr("library", "Heat", id = heatId), sr("library", "Other", id = otherId))
        assertEquals(listOf("Other"), SearchRules.mergeResults(rows, top).map { it.title })
    }

    @Test
    fun searchRowKeepsRankOrderAndOnlyTheTopHitTwinsGo() {
        val rows =
            listOf(
                sr("iptv", "Collateral", tmdb = 2, iptvId = "p:m:2"),
                sr("library", "Heat", tmdb = 949, id = heatId),
                sr("iptv", "Heat", tmdb = 949, iptvId = "p:m:1"),
                sr("library", "Ronin", id = otherId),
            )
        // No top hit: nothing is dropped here (the row's merge drops a twin only when its library item loaded), rank order kept
        assertEquals(rows, SearchRules.mergeResults(rows))
        // The row's merge against the library items that DID load: Heat once; if the library failed to load every IPTV title stays
        val entries = rows.map { r -> if (r.source == "library") Entry(r.title, lib = LibRef(r.id.toString(), "movie", r.tmdbId)) else Entry(r.title, iptv = IptvRef(r.iptvId!!, "movie", r.tmdbId)) }
        assertEquals(listOf("Collateral", "Heat", "Ronin"), JellytipSourceMerge.merge(entries).entries)
        assertEquals(listOf("Collateral", "Heat"), JellytipSourceMerge.merge(entries.filter { it.iptv != null }).entries)
    }

    @Test
    fun searchAnswerHasOneResultsRowAndNoSourceSplit() {
        val json =
            """{"q":"heat","results":[
              {"kind":"film","source":"library","title":"Heat","tmdbId":949,"id":"$heatId"},
              {"kind":"film","source":"iptv","title":"Heat","tmdbId":949,"iptvId":"p:m:1","libraryId":"${heatId.replace("-", "")}",
               "sources":[{"type":"library","id":"$heatId"},{"type":"iptv","id":"p:m:1","quality":["4k"],"copies":2}]},
              {"kind":"film","source":"iptv","title":"Collateral","tmdbId":2,"iptvId":"p:m:2"},
              {"kind":"film","source":"library","title":"Ronin","id":"$otherId"},
              {"kind":"film","source":"iptv","title":"Thief","iptvId":"p:m:3"}
            ]}"""
        val a = SearchParse.answer(json)
        assertEquals("p:m:1", a.results!![1].iptvId)
        assertEquals(heatId.replace("-", ""), a.results!![1].libraryId)
        assertEquals(listOf("library:$heatId", "iptv:p:m:1"), a.results!![1].sources.map { "${it.type}:${it.id}" })
        assertEquals(2, a.results!![1].sources[1].copies)
        // the top hit is Heat (library): its IPTV twin is not in the row
        assertEquals("Heat", a.top?.title)
        val groups = a.rankedGroups
        assertEquals(listOf("results"), groups.map { it.type })
        val g = groups.single()
        assertEquals(listOf("p:m:2", otherId.replace("-", ""), "p:m:3"), g.order)
        assertEquals(listOf(UUID.fromString(otherId)), g.ids)
        assertEquals(listOf("p:m:2", "p:m:3"), g.iptv.map { it.id })
        assertFalse(groups.any { it.type == "results-library" || it.type == "results-iptv" })
    }

    @Test
    fun searchAnswerWithoutNewFieldsStillParses() {
        val a = SearchParse.answer("""{"q":"x","results":[{"kind":"film","source":"iptv","title":"A","iptvId":"p:m:1"},{"kind":"film","source":"iptv","title":"B","iptvId":"p:m:2"}]}""")
        assertNull(a.results!!.first().libraryId)
        assertTrue(a.results!!.first().sources.isEmpty())
        // A is the top hit; B is the one row
        assertEquals(listOf("p:m:2"), a.rankedGroups.single().iptv.map { it.id })
    }

    // ---- rows JSON

    @Test
    fun rowParsesSourcesByIdAndPerItemFields() {
        val o =
            JSONObject(
                """{"id":"r1","title":"For you","itemIds":["$heatId"],"order":["$heatId","p:m:1"],
                  "sourcesById":{"${heatId.replace("-", "").uppercase()}":[{"type":"iptv","id":"p:m:7","quality":["4k"],"copies":2},{"type":"bogus","id":"x"}]},
                  "iptvItems":[{"id":"p:m:1","kind":"movie","title":"Heat","libraryId":"$heatId","sources":[{"type":"library","id":"$heatId"}]}]}""",
            )
        val row = JellytipRowsParser.row(o)!!
        assertEquals(listOf("p:m:7"), row.sourcesById[heatId.replace("-", "")]!!.map { it.id })
        assertEquals(heatId, row.iptvItems.single().libraryId)
        assertEquals("library", row.iptvItems.single().sources.single().type)
        val none = JellytipRowsParser.row(JSONObject("""{"id":"r2","itemIds":["$heatId"]}"""))!!
        assertTrue(none.sourcesById.isEmpty())
    }

    // ---- Continue Watching / Next Up

    private fun resume(
        kind: String,
        id: String,
        tmdb: Int? = null,
        seriesTmdb: Int? = null,
    ) = IptvResume(kind, id, if (kind == "episode") "s:1" else null, "T", null, null, null, 10.0, 100.0, 1L, null, null, emptyList(), tmdbId = tmdb, seriesTmdbId = seriesTmdb)

    @Test
    fun continueWatchingLibraryFilmWinsOverTheIptvFilm() {
        val lib = listOf(LibRef("a", "movie", 949), LibRef("b", "movie", null))
        val iptv = listOf(resume("movie", "m1", tmdb = 949), resume("movie", "m2", tmdb = 2), resume("movie", "m3"), resume("recording", "r1"))
        assertEquals(listOf("m2", "m3", "r1"), JellytipSourceMerge.filterResume(lib, iptv).map { it.id })
    }

    @Test
    fun continueWatchingEpisodeOfTheSameSeriesGoes() {
        val lib = listOf(LibRef("e1", "episode", null, seriesTmdbId = 1399))
        val iptv = listOf(resume("episode", "e9", seriesTmdb = 1399), resume("episode", "e8", seriesTmdb = 5), resume("episode", "e7"))
        assertEquals(listOf("e8", "e7"), JellytipSourceMerge.filterResume(lib, iptv).map { it.id })
        // a film's TMDB id is not a series' TMDB id
        assertEquals(3, JellytipSourceMerge.filterResume(listOf(LibRef("a", "movie", 1399)), iptv).size)
    }

    @Test
    fun continueWatchingUnknownIdsKeepBothAndNoLibraryKeepsAll() {
        val iptv = listOf(resume("movie", "m1", tmdb = 949), resume("episode", "e1", seriesTmdb = 1))
        assertEquals(iptv, JellytipSourceMerge.filterResume(emptyList(), iptv))
        // library episodes carry no series TMDB id from the app today: nothing matches, both stay
        assertEquals(iptv, JellytipSourceMerge.filterResume(listOf(LibRef("e", "episode", null, null), LibRef("a", "movie", null)), iptv))
    }

    // ---- watchlist

    private fun row(
        title: String,
        vararg items: BaseItem,
    ) = HomeRowLoadingState.Success(StringStringProvider(title), items.toList(), HomeRowViewOptions(), showViewMore = false)

    private fun titleOf(r: HomeRowLoadingState) = (r.title as StringStringProvider).str

    @Test
    fun watchlistFilmsAndShowsJoinTheirRowsWithoutDuplicates() {
        val f1 = movieItem(UUID.randomUUID(), "949")
        val f2 = movieItem(UUID.randomUUID(), "950")
        val s1 = movieItem(UUID.randomUUID(), "12", BaseItemKind.SERIES)
        val rows = listOf(row("Films (2)", f1, f2), row("Shows (1)", s1))
        val films = listOf(vodItem("p:m:1", tmdb = 949), vodItem("p:m:2", tmdb = 5), vodItem("p:m:3", libraryId = f2.id.toString()))
        val shows = listOf(vodItem("p:s:1", tmdb = 12, kind = "series"))
        val out = mergeWatchlist(rows, films, shows)
        // Films: p:m:1 (TMDB twin of f1) and p:m:3 (libraryId of f2) are the library's; p:m:2 is IPTV-only
        assertEquals(listOf("Films (3)", "Shows (1)"), out.rows.map { titleOf(it) })
        assertEquals(listOf("p:m:2"), out.appended[0]!!.map { it.id })
        assertFalse(out.appended.containsKey(1))
        assertTrue(out.iptvRows.isEmpty())
    }

    @Test
    fun watchlistKindWithoutALibraryRowGetsItsOwnRowAndNothingIsRenamedWhenNothingJoins() {
        val f1 = movieItem(UUID.randomUUID(), "949")
        val rows = listOf(row("Films (1)", f1))
        val out = mergeWatchlist(rows, listOf(vodItem("p:m:1", tmdb = 949)), listOf(vodItem("p:s:1", tmdb = 1, kind = "series"), vodItem("q:s:1", tmdb = 1, kind = "series")))
        assertEquals(rows, out.rows)
        assertTrue(out.appended.isEmpty())
        assertEquals(listOf("Shows"), out.iptvRows.map { it.first })
        assertEquals(listOf("p:s:1"), out.iptvRows.single().second.map { it.id })
    }
}
