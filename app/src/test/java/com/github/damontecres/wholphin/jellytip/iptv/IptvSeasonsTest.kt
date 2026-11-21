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
}
