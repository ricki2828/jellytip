package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.details.LibrarySeasonRef
import com.github.damontecres.wholphin.jellytip.details.MergedSeason
import com.github.damontecres.wholphin.jellytip.details.SeasonSource
import com.github.damontecres.wholphin.jellytip.details.episodeSourceMark
import com.github.damontecres.wholphin.jellytip.details.mergeSeasons
import com.github.damontecres.wholphin.jellytip.details.seasonTiles
import com.github.damontecres.wholphin.jellytip.details.selectedMergedIndex
import com.github.damontecres.wholphin.jellytip.details.seriesIsSplit
import com.github.damontecres.wholphin.jellytip.details.splitSourceBadge
import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellytipSeasonMergeTest {
    private fun lib(vararg numbers: Int?) = numbers.mapIndexed { i, n -> LibrarySeasonRef(i, n) }

    private fun ep(
        s: Int,
        e: Int,
    ) = IptvEpisode("p:s:1:$s:$e", e, "S${s}E$e", 46, null, null, null)

    private fun season(
        n: Int,
        vararg eps: Int,
    ) = IptvSeason(n, eps.map { ep(n, it) })

    private fun numbers(m: List<MergedSeason>) = m.map { it.number }

    private fun sources(m: List<MergedSeason>) = m.map { it.source }

    // ---- merge ----

    @Test
    fun libraryOnly() {
        val m = mergeSeasons(lib(1, 2), emptyList())
        assertEquals(listOf(1, 2), numbers(m))
        assertEquals(listOf(SeasonSource.LIBRARY, SeasonSource.LIBRARY), sources(m))
        assertEquals(listOf(0, 1), m.map { it.libraryIndex })
        assertTrue(m.all { it.iptv == null })
    }

    @Test
    fun iptvAddsALaterSeason() {
        val m = mergeSeasons(lib(1, 2), listOf(season(3, 1, 2, 3)))
        assertEquals(listOf(1, 2, 3), numbers(m))
        assertEquals(listOf(SeasonSource.LIBRARY, SeasonSource.LIBRARY, SeasonSource.IPTV), sources(m))
        assertNull(m[2].libraryIndex)
        assertEquals(3, m[2].iptv!!.episodes.size)
    }

    @Test
    fun iptvFillsAGapBetweenLibrarySeasons() {
        val m = mergeSeasons(lib(1, 3), listOf(season(2, 1, 2)))
        assertEquals(listOf(1, 2, 3), numbers(m))
        assertEquals(listOf(SeasonSource.LIBRARY, SeasonSource.IPTV, SeasonSource.LIBRARY), sources(m))
        // the library indexes keep naming the library list, not the merged one
        assertEquals(listOf(0, null, 1), m.map { it.libraryIndex })
    }

    @Test
    fun overlappingSeasonIsBothAndKeepsTheLibraryIndex() {
        val m = mergeSeasons(lib(1, 2), listOf(season(2, 1, 2, 3), season(3, 1)))
        assertEquals(listOf(1, 2, 3), numbers(m))
        assertEquals(listOf(SeasonSource.LIBRARY, SeasonSource.BOTH, SeasonSource.IPTV), sources(m))
        assertEquals(1, m[1].libraryIndex)
        assertEquals(listOf(1, 2, 3), m[1].iptv!!.episodes.map { it.episode })
    }

    @Test
    fun specialsStayWhereTheLibraryPutsThem() {
        // Jellyfin lists Specials first
        val first = mergeSeasons(lib(0, 1), listOf(season(2, 1), season(0, 1, 2)))
        assertEquals(listOf(0, 1, 2), numbers(first))
        assertEquals(listOf(SeasonSource.LIBRARY, SeasonSource.LIBRARY, SeasonSource.IPTV), sources(first))
        // a library that lists them last keeps them last: the IPTV season goes after the last real season
        val last = mergeSeasons(lib(1, 2, 0), listOf(season(3, 1)))
        assertEquals(listOf(1, 2, 3, 0), numbers(last))
    }

    @Test
    fun iptvSeasonZeroIsIgnored() {
        val m = mergeSeasons(lib(0, 1), listOf(season(0, 1, 2, 3)))
        assertEquals(listOf(SeasonSource.LIBRARY, SeasonSource.LIBRARY), sources(m))
        assertNull(m[0].iptv)
    }

    @Test
    fun seasonsWithoutANumberKeepTheirLibraryPosition() {
        val m = mergeSeasons(lib(1, null, 3), listOf(season(2, 1)))
        // season 2 goes before the first higher number (3), after the unnumbered one that sits before it
        assertEquals(listOf(1, null, 2, 3), numbers(m))
        assertEquals(listOf(0, 1, null, 2), m.map { it.libraryIndex })
    }

    @Test
    fun noLibrarySeasonsAtAll() {
        val m = mergeSeasons(emptyList(), listOf(season(2, 1), season(1, 1)))
        assertEquals(listOf(1, 2), numbers(m))
        assertTrue(m.all { it.source == SeasonSource.IPTV })
    }

    @Test
    fun duplicateIptvSeasonBlocksMergeOnce() {
        val m = mergeSeasons(lib(1), listOf(season(2, 1), season(2, 1, 2)))
        assertEquals(listOf(1, 2), numbers(m))
        assertEquals(listOf(1, 2), m[1].iptv!!.episodes.map { it.episode })
    }

    // ---- selection identity ----

    @Test
    fun lateIptvAnswerDoesNotChangeTheSelectedSeason() {
        val before = mergeSeasons(lib(1, 3), emptyList())
        val after = mergeSeasons(lib(1, 3), listOf(season(2, 1), season(4, 1)))
        // Library season index 1 is Season 3: the chip moves from position 1 to 2 but still names Season 3
        assertEquals(3, before[selectedMergedIndex(before, 1, null)].number)
        assertEquals(1, selectedMergedIndex(before, 1, null))
        assertEquals(3, after[selectedMergedIndex(after, 1, null)].number)
        assertEquals(2, selectedMergedIndex(after, 1, null))
        // and the first library season stays first
        assertEquals(0, selectedMergedIndex(after, 0, null))
    }

    @Test
    fun pickedIptvSeasonIsFoundByNumberNotPosition() {
        val m = mergeSeasons(lib(1, 4), listOf(season(3, 1)))
        assertEquals(1, selectedMergedIndex(m, 0, 3))
        // a later answer that adds a season before it moves its position, not its identity
        val later = mergeSeasons(lib(1, 4), listOf(season(2, 1), season(3, 1)))
        assertEquals(2, selectedMergedIndex(later, 0, 3))
        // an IPTV season that is not (or no longer) there falls back to the library selection
        assertEquals(2, selectedMergedIndex(m, 1, 9))
        assertEquals(0, selectedMergedIndex(emptyList(), 5, null))
    }

    // ---- tiles ----

    @Test
    fun iptvOnlySeasonShowsItsEpisodes() {
        val m = mergeSeasons(lib(1, 2), listOf(season(3, 1, 2)))
        val tiles = seasonTiles(m[2], 0, true, { null }, { false })
        assertEquals(2, tiles.size)
        assertTrue(tiles.all { it is IptvSeasons.SeasonTile.Iptv })
    }

    @Test
    fun overlappingSeasonInterleavesByEpisodeNumber() {
        val m = mergeSeasons(lib(1), listOf(season(1, 2, 4, 7)))
        // library has E1..E3 and E5; IPTV supplies E4 and E7 (E2 is in the library)
        val nums = listOf(1, 2, 3, 5)
        val tiles = seasonTiles(m[0], nums.size, true, { nums[it] }, { false })
        assertEquals(
            listOf("L0", "L1", "L2", "I4", "L3", "I7"),
            tiles.map {
                when (it) {
                    is IptvSeasons.SeasonTile.Lib -> "L${it.index}"
                    is IptvSeasons.SeasonTile.Iptv -> "I${it.episode.episode}"
                }
            },
        )
    }

    @Test
    fun libraryOnlyOrNotReadyOrTooLongSkipsTheInterleave() {
        val libOnly = mergeSeasons(lib(1), emptyList())
        assertEquals(3, seasonTiles(libOnly[0], 3, true, { it + 1 }, { false }).size)
        val both = mergeSeasons(lib(1), listOf(season(1, 9)))
        assertTrue(seasonTiles(both[0], 3, false, { it + 1 }, { false }).all { it is IptvSeasons.SeasonTile.Lib })
        assertTrue(seasonTiles(both[0], 201, true, { it + 1 }, { false }).all { it is IptvSeasons.SeasonTile.Lib })
        assertEquals(4, seasonTiles(both[0], 3, true, { it + 1 }, { false }).size)
    }

    // ---- split + marks ----

    @Test
    fun splitOnlyWhenIptvIsActuallyShown() {
        val libOnly = mergeSeasons(lib(1, 2), emptyList())
        assertFalse(seriesIsSplit(libOnly, seasonTiles(libOnly[0], 2, true, { it + 1 }, { false })))
        // an IPTV copy that adds nothing to the shown season is not a split
        val dup = mergeSeasons(lib(1), listOf(season(1, 1, 2)))
        assertFalse(seriesIsSplit(dup, seasonTiles(dup[0], 2, true, { it + 1 }, { false })))
        // an IPTV-only season anywhere on the page, or an IPTV tile in the shown season, is
        val extraSeason = mergeSeasons(lib(1), listOf(season(2, 1)))
        assertTrue(seriesIsSplit(extraSeason, seasonTiles(extraSeason[0], 2, true, { it + 1 }, { false })))
        val extraEpisode = mergeSeasons(lib(1), listOf(season(1, 5)))
        assertTrue(seriesIsSplit(extraEpisode, seasonTiles(extraEpisode[0], 2, true, { it + 1 }, { false })))
    }

    @Test
    fun badgeAndMarks() {
        assertNull(splitSourceBadge(false))
        assertEquals("Library + IPTV", splitSourceBadge(true))
        assertNull(episodeSourceMark(split = false, iptv = false))
        assertNull(episodeSourceMark(split = false, iptv = true))
        assertEquals("Library", episodeSourceMark(split = true, iptv = false))
        assertEquals("IPTV", episodeSourceMark(split = true, iptv = true))
        assertEquals("IPTV · Failed 2 h ago", episodeSourceMark(split = true, iptv = true, failNote = "Failed 2 h ago"))
        // a library tile never carries a failure note
        assertEquals("Library", episodeSourceMark(split = true, iptv = false, failNote = "Failed 2 h ago"))
    }
}
