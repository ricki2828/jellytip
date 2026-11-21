package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPTV-only seasons on the library series page (web 97458f9a / eb04f1d0). */
class IptvSeasonsTest {
    private fun ep(n: Int, id: String = "p1:e:$n") = IptvEpisode(id = id, episode = n, title = "E$n", runtimeMin = null, overview = null, still = null, progress = null)

    private fun vod(id: String, title: String, tmdb: Int?, kind: String = "series") =
        IptvVodItem(id, kind, tmdb, title, 2020, emptyList(), null, null, "", null, null, null)

    @Test
    fun foreignDubsAreSkipped() {
        assertTrue(IptvSeasons.foreignCopy("Show (2023)_ger"))
        assertTrue(IptvSeasons.foreignCopy("Show - FR"))
        assertFalse(IptvSeasons.foreignCopy("Show (2023)"))
        assertFalse(IptvSeasons.foreignCopy("Show EN"))
    }

    @Test
    fun pickSameTmdbAllowedProviderEnglish() {
        val items = listOf(vod("p2:s:1", "Show", 5), vod("p1:s:2", "Show_ger", 5), vod("p1:s:3", "Show", 5), vod("p1:m:4", "Show", 5, "movie"))
        assertEquals("p1:s:3", IptvSeasons.pickSeries(items, 5, listOf("p1"))?.id)
        assertNull(IptvSeasons.pickSeries(items, null, listOf("p1")))
        assertNull(IptvSeasons.pickSeries(items, 6, listOf("p1")))
    }

    @Test
    fun onlyMissingSeasonsMergedAndOrdered() {
        val iptv =
            listOf(
                IptvSeason(0, listOf(ep(1))),
                IptvSeason(3, listOf(ep(2), ep(1))),
                IptvSeason(1, listOf(ep(1))),
                IptvSeason(3, listOf(ep(2, "p1:e:dup"), ep(3))),
                IptvSeason(2, emptyList()),
            )
        val out = IptvSeasons.onlySeasons(setOf(1), iptv)
        assertEquals(listOf(3), out.map { it.season })
        assertEquals(listOf(1, 2, 3), out[0].episodes.map { it.episode })
        assertEquals("p1:e:2", out[0].episodes[1].id)
    }

    @Test
    fun mixedSeasonSlotsIptvEpisodesInByNumberLibraryWins() {
        val iptv = listOf(ep(1, "i1"), ep(2, "i2"), ep(3, "i3"), ep(4, "i4"), ep(6, "i6"))
        // library has 1, 2, 5 (loaded) and an unloaded entry
        val out = IptvSeasons.interleave(listOf(1, 2, 5, null), listOf(false, false, false, false), iptv)
        val shown =
            out.map {
                when (it) {
                    is IptvSeasons.SeasonTile.Lib -> "L${it.index}"
                    is IptvSeasons.SeasonTile.Iptv -> it.episode.id
                }
            }
        assertEquals(listOf("L0", "L1", "i3", "i4", "L2", "L3", "i6"), shown)
    }

    @Test
    fun placeholderGivesWayToIptvCopy() {
        val out = IptvSeasons.interleave(listOf(1, 2), listOf(false, true), listOf(ep(2, "i2")))
        assertEquals(listOf("L0", "i2"), out.map { if (it is IptvSeasons.SeasonTile.Lib) "L${it.index}" else (it as IptvSeasons.SeasonTile.Iptv).episode.id })
        // no IPTV copy: the placeholder stays
        assertEquals(2, IptvSeasons.interleave(listOf(1, 2), listOf(false, true), emptyList()).size)
    }

    @Test
    fun episodesForMergesDuplicateBlocks() {
        val iptv = listOf(IptvSeason(2, listOf(ep(2, "a"), ep(1, "b"))), IptvSeason(2, listOf(ep(2, "dup"), ep(3, "c"))), IptvSeason(1, listOf(ep(1, "x"))))
        assertEquals(listOf("b", "a", "c"), IptvSeasons.episodesFor(iptv, 2).map { it.id })
    }

    @Test
    fun libraryCopiesSameTmdbAllowedEnglishFirst() {
        val items =
            listOf(
                vod("p1:m:1", "Heat", 949, "movie").copy(langUnknown = true),
                vod("p1:m:2", "Heat", 949, "movie"),
                vod("p2:m:3", "Heat", 949, "movie"),
                vod("p1:m:4", "Heat_ger", 949, "movie"),
                vod("p1:s:5", "Heat", 949, "series"),
                vod("p1:m:6", "Heat 2", 1, "movie"),
            )
        assertEquals(listOf("p1:m:2", "p1:m:1"), LibraryIptvCopies.pick(items, "movie", 949, listOf("p1")).map { it.id })
    }
}
