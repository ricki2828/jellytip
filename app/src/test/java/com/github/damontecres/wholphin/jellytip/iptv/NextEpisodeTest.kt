package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.iptv.NextEpisode.Ep
import com.github.damontecres.wholphin.jellytip.iptv.NextEpisode.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Next episode across a split series (t5n 22-Nov): library + IPTV-only seasons, by the IptvSeasons rule. */
class NextEpisodeTest {
    private fun iep(
        s: Int,
        n: Int,
    ) = IptvEpisode(id = "p1:e:$s$n", episode = n, title = "I S${s}E$n", runtimeMin = null, overview = null, still = null, progress = null)

    private fun iseason(
        s: Int,
        vararg eps: Int,
    ) = IptvSeason(s, eps.map { iep(s, it) })

    private fun lib(
        s: Int,
        n: Int,
    ) = Ep(Source.LIBRARY, "lib-$s-$n", s, n, "L S${s}E$n")

    private fun cur(
        source: Source,
        s: Int,
        n: Int,
    ) = Ep(source, "cur", s, n, "now")

    @Test
    fun iptvOnlySeriesGoesToTheNextEpisode() {
        val iptv = listOf(iseason(2, 1, 2, 3))
        val next = NextEpisode.after(cur(Source.IPTV, 2, 1), emptyList(), iptv)
        assertEquals(Ep(Source.IPTV, "p1:e:22", 2, 2, "I S2E2"), next)
        assertEquals("S2 E2", next?.code)
    }

    @Test
    fun lastEpisodeOfASeasonGoesToTheNextSeason() {
        val iptv = listOf(iseason(1, 1, 2), iseason(2, 1, 2))
        assertEquals("p1:e:21", NextEpisode.after(cur(Source.IPTV, 1, 2), emptyList(), iptv)?.id)
    }

    @Test
    fun libraryS1ThenIptvS2AndBack() {
        val library = listOf(lib(1, 1), lib(1, 2), lib(3, 1))
        // IPTV has S1 too (the library wins it), S2 only on IPTV
        val iptv = listOf(iseason(1, 1, 2, 3), iseason(2, 1, 2))
        // From a library episode into IPTV's season
        assertEquals(Ep(Source.IPTV, "p1:e:21", 2, 1, "I S2E1"), NextEpisode.after(cur(Source.LIBRARY, 1, 2), library, iptv))
        // Within IPTV's season
        assertEquals(Source.IPTV, NextEpisode.after(cur(Source.IPTV, 2, 1), library, iptv)?.source)
        // And back to the library
        assertEquals(lib(3, 1), NextEpisode.after(cur(Source.IPTV, 2, 2), library, iptv))
        // IPTV's S1E3 is not used: the library has season 1 (onlySeasons rule)
        assertEquals(Source.IPTV, NextEpisode.after(cur(Source.LIBRARY, 1, 2), library, iptv)?.source)
        assertEquals(2, NextEpisode.after(cur(Source.LIBRARY, 1, 2), library, iptv)?.season)
    }

    @Test
    fun currentFromIptvInALibrarySeasonFindsTheLibraryNext() {
        // Playing IPTV S1E1 while the library holds season 1: the next is the library's S1E2
        val library = listOf(lib(1, 1), lib(1, 2))
        assertEquals(lib(1, 2), NextEpisode.after(cur(Source.IPTV, 1, 1), library, listOf(iseason(1, 1, 2))))
    }

    @Test
    fun duplicateInBothSourcesLibraryWins() {
        val library = listOf(lib(1, 1), lib(1, 2), lib(1, 2).copy(id = "lib-dup"))
        val merged = NextEpisode.merged(library, listOf(iseason(1, 2), iseason(2, 1)))
        assertEquals(listOf("lib-1-1", "lib-1-2", "p1:e:21"), merged.map { it.id })
        assertEquals(lib(1, 2), NextEpisode.after(cur(Source.IPTV, 1, 1), library, listOf(iseason(1, 2))))
        // Duplicate IPTV season blocks: the first copy wins
        val twice = listOf(IptvSeason(2, listOf(iep(2, 1).copy(id = "first"))), IptvSeason(2, listOf(iep(2, 1).copy(id = "second"))))
        assertEquals("first", NextEpisode.after(cur(Source.IPTV, 1, 9), emptyList(), twice)?.id)
    }

    @Test
    fun lastEpisodeHasNoNext() {
        val library = listOf(lib(1, 1), lib(1, 2))
        assertNull(NextEpisode.after(cur(Source.LIBRARY, 1, 2), library, listOf(iseason(1, 1, 2))))
        assertNull(NextEpisode.after(cur(Source.IPTV, 2, 3), emptyList(), listOf(iseason(2, 1, 2, 3))))
        assertNull(NextEpisode.after(cur(Source.IPTV, 1, 1), emptyList(), emptyList()))
    }

    @Test
    fun gapsInNumberingAreSkipped() {
        val iptv = listOf(iseason(1, 1, 2, 5), iseason(4, 3))
        assertEquals(5, NextEpisode.after(cur(Source.IPTV, 1, 2), emptyList(), iptv)?.episode)
        assertEquals(Ep(Source.IPTV, "p1:e:43", 4, 3, "I S4E3"), NextEpisode.after(cur(Source.IPTV, 1, 5), emptyList(), iptv))
        // A current episode missing from the list still finds the one after its number
        assertEquals(5, NextEpisode.after(cur(Source.IPTV, 1, 3), emptyList(), iptv)?.episode)
    }

    @Test
    fun specialsNeverComeFromIptvOrTheLibrary() {
        val library = listOf(lib(0, 1), lib(1, 1))
        val iptv = listOf(iseason(0, 1, 2), iseason(2, 1))
        assertEquals(listOf("lib-1-1", "p1:e:21"), NextEpisode.merged(library, iptv).map { it.id })
    }

    @Test
    fun iptvEpisodeIsFoundById() {
        val iptv = listOf(iseason(1, 1), iseason(2, 1, 2))
        assertEquals(Ep(Source.IPTV, "p1:e:22", 2, 2, "I S2E2"), NextEpisode.iptvEpisode(iptv, "p1:e:22"))
        assertNull(NextEpisode.iptvEpisode(iptv, "nope"))
    }

    @Test
    fun cardShowsInTheLastThirtySecondsOrAtEnd() {
        val hour = 3_600_000L
        assertFalse(UpNextTiming.showCard(true, false, true, false, hour - 31_000, hour))
        assertTrue(UpNextTiming.showCard(true, false, true, false, hour - 30_000, hour))
        assertTrue(UpNextTiming.showCard(true, false, true, false, hour - 1_000, hour))
        // Seeking back out of the window hides it again
        assertFalse(UpNextTiming.showCard(true, false, true, false, hour / 2, hour))
        // Unknown or nonsense runtime: only at ENDED
        assertFalse(UpNextTiming.showCard(true, false, true, false, 0, 0))
        assertFalse(UpNextTiming.showCard(true, false, true, false, 10_000, 20_000))
        assertTrue(UpNextTiming.showCard(true, false, true, true, 0, 0))
        // No next, cancelled, or another page's stream: never
        assertFalse(UpNextTiming.showCard(false, false, true, true, 0, 0))
        assertFalse(UpNextTiming.showCard(true, true, true, true, hour, hour))
        assertFalse(UpNextTiming.showCard(true, false, false, true, hour, hour))
    }

    @Test
    fun countdownUsesTheLibrarySeconds() {
        assertEquals(15L, UpNextTiming.countdownStart(true, 15))
        assertEquals(0L, UpNextTiming.countdownStart(true, 0))
        assertNull(UpNextTiming.countdownStart(false, 15))
    }
}
