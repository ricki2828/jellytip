package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.details.SplitEp
import com.github.damontecres.wholphin.jellytip.details.SplitLibEp
import com.github.damontecres.wholphin.jellytip.details.nextAfter
import com.github.damontecres.wholphin.jellytip.details.splitEpisodes
import com.github.damontecres.wholphin.jellytip.details.splitNextUp
import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvProgress
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Next up over a split series (Celebrity Traitors, 22-Nov: library S2E2 + E6-10, IPTV all of S2). */
class JellytipSplitNextTest {
    private fun lib(
        e: Int,
        played: Boolean = false,
        pos: Long = 0L,
        season: Int = 2,
        virtual: Boolean = false,
    ) = SplitLibEp(UUID(0L, (season * 100 + e).toLong()), season, e, played, pos, virtual)

    private fun tv(
        e: Int,
        played: Boolean = false,
        pos: Double = 0.0,
    ) = IptvEpisode("p:e:$e", e, "Episode $e", 60, null, null, if (played || pos > 0) IptvProgress(if (played) 3500.0 else pos, 3600.0, played) else null)

    private val iptvS2 = listOf(IptvSeason(2, (1..10).map { tv(it, played = it == 1) }))
    private val library = listOf(lib(2, played = true)) + (6..10).map { lib(it) }

    private fun code(e: SplitEp?) = e?.let { "S${it.season}E${it.episode}${if (it.lib != null) "L" else "I"}" }

    @Test
    fun afterLibraryE2TheNextIsTheIptvOnlyE3() {
        val eps = splitEpisodes(library, iptvS2)
        assertEquals(10, eps.size)
        // E2 is the library copy (it wins the slot); E3-E5 IPTV only; E6+ library
        assertEquals(listOf("S2E1I", "S2E2L", "S2E3I", "S2E5I", "S2E6L"), listOf(0, 1, 2, 4, 5).map { code(eps[it]) })
        assertEquals("S2E3I", code(splitNextUp(eps)))
        // The player's Up next after library E2: IPTV E3 (not the library's E6)
        assertEquals("S2E3I", code(nextAfter(eps, 2, 2)))
        assertEquals("S2E6L", code(nextAfter(eps, 2, 5)))
        assertNull(nextAfter(eps, 2, 10))
    }

    @Test
    fun watchedOnEitherSourceCounts() {
        // E3 watched on IPTV, E4 in progress on IPTV: Resume E4
        val eps = splitEpisodes(library, listOf(IptvSeason(2, (1..10).map { tv(it, played = it <= 3, pos = if (it == 4) 600.0 else 0.0) })))
        val n = splitNextUp(eps)!!
        assertEquals("S2E4I", code(n))
        assertTrue(n.inProgress)
        // Library E7 watched later: the next is after it (E8), earlier gaps stay behind
        val later = splitEpisodes(library.map { if (it.episode == 7) it.copy(played = true) else it }, iptvS2)
        assertEquals("S2E8L", code(splitNextUp(later)))
    }

    @Test
    fun edges() {
        // Nothing watched: the first real episode (Specials left out)
        assertEquals("S1E1I", code(splitNextUp(splitEpisodes(listOf(lib(1, season = 0)), listOf(IptvSeason(1, listOf(tv(1), tv(2))))))))
        // All watched: null (the page keeps its own answer)
        assertNull(splitNextUp(splitEpisodes(listOf(lib(1, played = true, season = 1)), emptyList())))
        // A missing library placeholder gives its slot to the IPTV copy
        val eps = splitEpisodes(listOf(lib(3, virtual = true)), listOf(IptvSeason(2, listOf(tv(3)))))
        assertEquals("S2E3I", code(eps.single()))
        // Unknown position: no next
        assertNull(nextAfter(eps, null, 3))
    }
}

class SeriesNextClaimTest {
    private val tv = { e: Int -> com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode("11dd6d87:e:$e", e, "E$e", 60, null, null, null) }
    private val libId = UUID.fromString("ff7b098e-b481-1105-eadb-d2f25199f299")
    private val eps =
        com.github.damontecres.wholphin.jellytip.details.splitEpisodes(
            listOf(com.github.damontecres.wholphin.jellytip.details.SplitLibEp(libId, 2, 2, true)),
            listOf(com.github.damontecres.wholphin.jellytip.iptv.IptvSeason(2, (1..5).map(tv))),
        )

    @Test
    fun theLiveTraitorsAnswer() {
        val body = """{"seriesNext":{"48fcd920aa5ca0985a1670879ae1a105":{"source":"iptv","id":"11dd6d87:e:3","season":2,"episode":3,"inProgress":true}}}"""
        val c = com.github.damontecres.wholphin.jellytip.details.SeriesNextParse.parse(body, "48fcd920-aa5c-a098-5a16-70879ae1a105")!!
        assertTrue(c.inProgress)
        val got = com.github.damontecres.wholphin.jellytip.details.claimedNext(c, eps) as com.github.damontecres.wholphin.jellytip.details.ClaimedNext.Episode
        assertEquals(3, got.ep.episode)
        assertEquals("11dd6d87:e:3", got.ep.iptv?.id)
    }

    @Test
    fun libraryIdFinishedNoClaimAndFallbacks() {
        val p = com.github.damontecres.wholphin.jellytip.details.SeriesNextParse
        val c = p.parse("""{"seriesNext":{"S1":{"source":"library","id":"ff7b098eb4811105eadbd2f25199f299","season":2,"episode":2}}}""", "s1")!!
        assertEquals(libId, (com.github.damontecres.wholphin.jellytip.details.claimedNext(c, eps) as com.github.damontecres.wholphin.jellytip.details.ClaimedNext.Episode).ep.lib?.id)
        // finished
        assertEquals(com.github.damontecres.wholphin.jellytip.details.ClaimedNext.Finished, com.github.damontecres.wholphin.jellytip.details.claimedNext(p.parse("""{"seriesNext":{"s1":{"source":null}}}""", "s1")!!, eps))
        // {} / another series / junk source / not JSON: no claim
        assertNull(p.parse("""{"seriesNext":{}}""", "s1"))
        assertNull(p.parse("""{"seriesNext":{"s2":{"source":"iptv"}}}""", "s1"))
        assertNull(p.parse("""{"seriesNext":{"s1":{"source":"plex"}}}""", "s1"))
        assertNull(p.parse("nope", "s1"))
        // unknown id: matched by season / episode; not on the page at all: null (the page's own rule)
        val bySe = p.parse("""{"seriesNext":{"s1":{"source":"iptv","id":"x:e:99","season":2,"episode":4}}}""", "s1")!!
        assertEquals(4, (com.github.damontecres.wholphin.jellytip.details.claimedNext(bySe, eps) as com.github.damontecres.wholphin.jellytip.details.ClaimedNext.Episode).ep.episode)
        assertNull(com.github.damontecres.wholphin.jellytip.details.claimedNext(p.parse("""{"seriesNext":{"s1":{"source":"iptv","id":"x","season":9,"episode":9}}}""", "s1")!!, eps))
    }
}
