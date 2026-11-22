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
        // IPTV has S1 too (the library wins its episodes), S2 only on IPTV
        val iptv = listOf(iseason(1, 1, 2), iseason(2, 1, 2))
        // From a library episode into IPTV's season
        assertEquals(Ep(Source.IPTV, "p1:e:21", 2, 1, "I S2E1"), NextEpisode.after(cur(Source.LIBRARY, 1, 2), library, iptv))
        // Within IPTV's season
        assertEquals(Source.IPTV, NextEpisode.after(cur(Source.IPTV, 2, 1), library, iptv)?.source)
        // And back to the library
        assertEquals(lib(3, 1), NextEpisode.after(cur(Source.IPTV, 2, 2), library, iptv))
        // IPTV's S1E1 / S1E2 are not used: the library has them
        assertEquals(lib(1, 2), NextEpisode.after(cur(Source.LIBRARY, 1, 1), library, iptv))
    }

    @Test
    fun iptvFillsEpisodesMissingInsideALibrarySeason() {
        // Library S1E1-3, IPTV S1E1-4: after S1E3 comes IPTV's S1E4 (series page interleave rule), then the library again
        val library = listOf(lib(1, 1), lib(1, 2), lib(1, 3), lib(2, 1))
        val iptv = listOf(iseason(1, 1, 2, 3, 4), iseason(2, 1, 2))
        assertEquals(Ep(Source.IPTV, "p1:e:14", 1, 4, "I S1E4"), NextEpisode.after(cur(Source.LIBRARY, 1, 3), library, iptv))
        assertEquals(lib(2, 1), NextEpisode.after(cur(Source.IPTV, 1, 4), library, iptv))
        assertEquals("p1:e:22", NextEpisode.after(cur(Source.LIBRARY, 2, 1), library, iptv)?.id)
        // A hole in the middle of a library season
        assertEquals("p1:e:12", NextEpisode.after(cur(Source.LIBRARY, 1, 1), listOf(lib(1, 1), lib(1, 3)), iptv)?.id)
        assertEquals(listOf("lib-1-1", "lib-1-2", "lib-1-3", "p1:e:14", "lib-2-1", "p1:e:22"), NextEpisode.merged(library, iptv).map { it.id })
    }

    @Test
    fun currentFromIptvInALibrarySeasonFindsTheLibraryNext() {
        // Playing IPTV S1E1 while the library holds season 1: the next is the library's S1E2
        val library = listOf(lib(1, 1), lib(1, 2))
        assertEquals(lib(1, 2), NextEpisode.after(cur(Source.IPTV, 1, 1), library, listOf(iseason(1, 1, 2))))
    }

    @Test
    fun librarySeasonPartlyOnIptv() {
        // Library S2E1-3, IPTV S2E1-8 (review P1)
        val library = listOf(lib(2, 1), lib(2, 2), lib(2, 3))
        val iptv = listOf(IptvSeason(2, (1..8).map { iep(2, it) }))
        assertEquals("p1:e:25", NextEpisode.after(cur(Source.IPTV, 2, 4), library, iptv)?.id)
        assertEquals(Ep(Source.IPTV, "p1:e:24", 2, 4, "I S2E4"), NextEpisode.after(cur(Source.LIBRARY, 2, 3), library, iptv))
        assertNull(NextEpisode.after(cur(Source.IPTV, 2, 8), library, iptv))
    }

    @Test
    fun libraryNextKeepsItsResumePoint() {
        val library = listOf(lib(1, 1), lib(1, 2).copy(resumeMs = 754_000))
        assertEquals(754_000L, NextEpisode.after(cur(Source.IPTV, 1, 1), library, emptyList())?.resumeMs)
    }

    @Test
    fun searchTitleDropsProviderDecorations() {
        assertEquals("The Celebrity Traitors", UpNextTiming.searchTitle("UK: The Celebrity Traitors (2025)"))
        assertEquals("Show", UpNextTiming.searchTitle("Show [4K]"))
        assertEquals("Show", UpNextTiming.searchTitle("Show (2023)_ger"))
        assertEquals("Show", UpNextTiming.searchTitle("US | Show"))
        assertEquals("Plain Show", UpNextTiming.searchTitle("Plain Show"))
        assertEquals("(2024)", UpNextTiming.searchTitle("(2024)"))
    }

    @Test
    fun duplicateInBothSourcesLibraryWins() {
        val library = listOf(lib(1, 1), lib(1, 2))
        val merged = NextEpisode.merged(library, listOf(iseason(1, 1, 2), iseason(2, 1)))
        assertEquals(listOf("lib-1-1", "lib-1-2", "p1:e:21"), merged.map { it.id })
        assertEquals(lib(1, 2), NextEpisode.after(cur(Source.IPTV, 1, 1), library, listOf(iseason(1, 2))))
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
    fun specialsAreNeverANext() {
        // The shared split list (splitEpisodes) leaves Specials out of both sources
        val library = listOf(lib(0, 1), lib(1, 1))
        val iptv = listOf(iseason(0, 1, 2), iseason(2, 1))
        assertEquals(listOf("lib-1-1", "p1:e:21"), NextEpisode.merged(library, iptv).map { it.id })
        assertEquals(lib(1, 1), NextEpisode.after(lib(0, 1), library, iptv))
    }

    @Test
    fun libraryNextUpWouldSkipIptvEpisodes() {
        // Celebrity Traitors 22-Nov: library S2E1, S2E2, S2E6; IPTV S2E3-E5. Jellyfin says E6 after E2; the next is IPTV E3
        val library = listOf(lib(2, 1), lib(2, 2), lib(2, 6))
        val iptv = listOf(iseason(2, 3, 4, 5))
        assertEquals(Ep(Source.IPTV, "p1:e:23", 2, 3, "I S2E3"), NextEpisode.after(lib(2, 2), library, iptv))
        assertEquals(lib(2, 6), NextEpisode.after(cur(Source.IPTV, 2, 5), library, iptv))
        assertEquals(listOf("lib-2-1", "lib-2-2", "p1:e:23", "p1:e:24", "p1:e:25", "lib-2-6"), NextEpisode.merged(library, iptv).map { it.id })
    }

    @Test
    fun missingLibraryEpisodesAreNeverPlayed() {
        // S1E2 is a Jellyfin placeholder with no IPTV copy: a tile on the page, skipped as a next
        val library = listOf(lib(1, 1), lib(1, 2).copy(missing = true), lib(1, 3))
        assertEquals(lib(1, 3), NextEpisode.after(lib(1, 1), library, emptyList()))
    }

    /** The series page's tiles for the same data: mergeSeasons then seasonTiles per chip, as the page draws them. */
    private fun pageTiles(
        library: List<Ep>,
        iptv: List<IptvSeason>,
    ): List<String> {
        val seasons = library.map { it.season }.distinct()
        val refs = seasons.mapIndexed { i, n -> com.github.damontecres.wholphin.jellytip.details.LibrarySeasonRef(i, n) }
        return com.github.damontecres.wholphin.jellytip.details.mergeSeasons(refs, iptv).flatMap { m ->
            val eps = m.libraryIndex?.let { i -> library.filter { it.season == seasons[i] } }.orEmpty()
            com.github.damontecres.wholphin.jellytip.details
                .seasonTiles(m, eps.size, true, { eps[it].episode }, { eps[it].missing })
                .map { t ->
                    when (t) {
                        is IptvSeasons.SeasonTile.Lib -> eps[t.index].id
                        is IptvSeasons.SeasonTile.Iptv -> t.episode.id
                    }
                }
        }
    }

    @Test
    fun mergedOrderEqualsTheSeriesPagePlayableTiles() {
        // Library S1 + IPTV S2
        val a = listOf(lib(1, 1), lib(1, 2)) to listOf(iseason(2, 1, 2))
        // Library S2E1-3 + IPTV S2E1-8
        val b = listOf(lib(2, 1), lib(2, 2), lib(2, 3)) to listOf(IptvSeason(2, (1..8).map { iep(2, it) }))
        // A missing library episode in the middle, with and without an IPTV copy
        val c = listOf(lib(1, 1), lib(1, 2).copy(missing = true), lib(1, 3), lib(1, 4).copy(missing = true), lib(1, 5)) to listOf(iseason(1, 2))
        assertEquals(listOf("lib-1-1", "lib-1-2", "p1:e:21", "p1:e:22"), NextEpisode.merged(a.first, a.second).map { it.id })
        assertEquals(listOf("lib-2-1", "lib-2-2", "lib-2-3", "p1:e:24", "p1:e:25", "p1:e:26", "p1:e:27", "p1:e:28"), NextEpisode.merged(b.first, b.second).map { it.id })
        assertEquals(listOf("lib-1-1", "p1:e:12", "lib-1-3", "lib-1-5"), NextEpisode.merged(c.first, c.second).map { it.id })
        // The page also draws the uncopied placeholder (lib-1-4) as a tile; it is never a next
        for ((library, iptv) in listOf(a, b, c)) {
            val missing = library.filter { it.missing }.map { it.id }.toSet()
            assertEquals(pageTiles(library, iptv).filter { it !in missing }, NextEpisode.merged(library, iptv).map { it.id })
        }
        // And the player's next follows those tiles: the IPTV copy replaces the placeholder, the uncopied one is skipped
        assertEquals("p1:e:12", NextEpisode.after(lib(1, 1), c.first, c.second)?.id)
        assertEquals(lib(1, 5), NextEpisode.after(lib(1, 3), c.first, c.second))
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
        // A stream that stops at 10:00 of 60:00 is not the end: no card (review P2.2)
        assertFalse(UpNextTiming.showCard(true, false, true, true, 10 * 60_000L, hour))
        assertTrue(UpNextTiming.showCard(true, false, true, true, hour - 2_000, hour))
        // No next, cancelled, or another page's stream: never
        assertFalse(UpNextTiming.showCard(false, false, true, true, 0, 0))
        assertFalse(UpNextTiming.showCard(true, true, true, true, hour, hour))
        assertFalse(UpNextTiming.showCard(true, false, false, true, hour, hour))
    }

    @Test
    fun beforeTheEndTheCardCountsTheTimeLeft() {
        val hour = 3_600_000L
        assertEquals(30L, UpNextTiming.secondsLeft(true, hour - 30_000, hour))
        assertEquals(13L, UpNextTiming.secondsLeft(true, hour - 12_400, hour))
        assertEquals(0L, UpNextTiming.secondsLeft(true, hour + 500, hour))
        assertNull(UpNextTiming.secondsLeft(false, hour - 10_000, hour))
        assertNull(UpNextTiming.secondsLeft(true, 0, 0))
    }

    @Test
    fun autoAdvanceOnlyAtTheEnd() {
        // The card was up before the end (it counted the time left): advance at ENDED at once
        assertEquals(0L, UpNextTiming.endCountdown(true, 15, cardSeenBeforeEnd = true))
        // Unknown runtime (first shown at ENDED): the library player's seconds
        assertEquals(15L, UpNextTiming.endCountdown(true, 15, cardSeenBeforeEnd = false))
        assertEquals(0L, UpNextTiming.endCountdown(true, -3, cardSeenBeforeEnd = false))
        // Auto play next off: OK only
        assertNull(UpNextTiming.endCountdown(false, 15, cardSeenBeforeEnd = true))
        // Library player's card (at its ENDED)
        assertEquals(15L, UpNextTiming.countdownStart(true, 15))
        assertNull(UpNextTiming.countdownStart(false, 15))
    }
}
