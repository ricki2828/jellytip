package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.details.PlayCandidate
import com.github.damontecres.wholphin.jellytip.details.myListLabel
import com.github.damontecres.wholphin.jellytip.details.resolveSeriesPlay
import com.github.damontecres.wholphin.jellytip.details.seasonChipLabel
import com.github.damontecres.wholphin.jellytip.details.seriesPlayLabel
import com.github.damontecres.wholphin.jellytip.details.seriesStatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

class JellytipSeriesPlayTest {
    private fun id(n: Int) = UUID.nameUUIDFromBytes("ep$n".toByteArray())

    private fun cand(
        n: Int,
        season: Int? = 1,
        episode: Int? = n,
        ticks: Long = 0L,
        played: LocalDateTime? = null,
    ) = PlayCandidate(id(n), season, episode, ticks, played)

    private val t0 = LocalDateTime.of(2026, 11, 1, 20, 0)

    // ---- label ----

    @Test
    fun labelResume() = assertEquals("Resume S2 E4", seriesPlayLabel(2, 4, resumable = true))

    @Test
    fun labelPlay() = assertEquals("Play S2 E4", seriesPlayLabel(2, 4, resumable = false))

    @Test
    fun labelFirstEpisode() = assertEquals("Play S1 E1", seriesPlayLabel(1, 1, resumable = false))

    @Test
    fun labelSpecials() = assertEquals("Play S0 E3", seriesPlayLabel(0, 3, resumable = false))

    @Test
    fun labelNoSeasonNumber() = assertEquals("Resume E4", seriesPlayLabel(null, 4, resumable = true))

    @Test
    fun labelNoEpisodeNumber() {
        assertEquals("Resume", seriesPlayLabel(2, null, resumable = true))
        assertEquals("Play", seriesPlayLabel(2, null, resumable = false))
        assertEquals("Play", seriesPlayLabel(null, null, resumable = false))
    }

    @Test
    fun labelUsesGivenWords() = assertEquals("Reprendre S1 E2", seriesPlayLabel(1, 2, true, resumeWord = "Reprendre", playWord = "Lire"))

    // ---- status line ----

    @Test
    fun statusSeasonsAndNext() = assertEquals("3 seasons  ·  Next: S2 E4", seriesStatusLine(listOf(1, 2, 3), 2, 4))

    @Test
    fun statusSingularSeason() = assertEquals("1 season  ·  Next: S1 E1", seriesStatusLine(listOf(1), 1, 1))

    @Test
    fun statusNoNextWhenNoEpisode() = assertEquals("3 seasons", seriesStatusLine(listOf(1, 2, 3), null, null))

    @Test
    fun statusSpecialsNotCounted() = assertEquals("2 seasons", seriesStatusLine(listOf(0, 1, 2), null, null))

    @Test
    fun statusNextOnlyWhenNoSeasons() = assertEquals("Next: S1 E1", seriesStatusLine(emptyList(), 1, 1))

    @Test
    fun statusNothing() = assertNull(seriesStatusLine(emptyList(), null, null))

    // ---- names ----

    @Test
    fun seasonNames() {
        assertEquals("Season 1", seasonChipLabel(1, 0))
        assertEquals("Season 12", seasonChipLabel(12, 11))
        assertEquals("Specials", seasonChipLabel(0, 0))
        assertEquals("Season 3", seasonChipLabel(null, 2))
    }

    @Test
    fun myListWords() {
        assertEquals("♡  My list", myListLabel(false))
        assertEquals("♥  In My list", myListLabel(true))
    }

    // ---- resolver ----

    @Test
    fun resumeBeatsNextUp() {
        val partWatched = cand(3, ticks = 100, played = t0)
        val got = resolveSeriesPlay(listOf(partWatched), nextUp = cand(4), first = cand(1))
        assertEquals(id(3), got?.id)
    }

    @Test
    fun mostRecentlyPlayedPartWatchedWins() {
        val older = cand(2, ticks = 50, played = t0)
        val newer = cand(5, season = 2, episode = 1, ticks = 50, played = t0.plusDays(2))
        val middle = cand(3, ticks = 50, played = t0.plusDays(1))
        assertEquals(id(5), resolveSeriesPlay(listOf(older, newer, middle), cand(9), cand(1))?.id)
        assertEquals(id(5), resolveSeriesPlay(listOf(newer, middle, older), cand(9), cand(1))?.id)
    }

    @Test
    fun dateTieKeepsServerOrder() {
        val a = cand(2, ticks = 50, played = t0)
        val b = cand(3, ticks = 50, played = t0)
        assertEquals(id(2), resolveSeriesPlay(listOf(a, b), null, null)?.id)
    }

    @Test
    fun datedBeatsUndated() {
        val undated = cand(2, ticks = 50, played = null)
        val dated = cand(3, ticks = 50, played = t0)
        assertEquals(id(3), resolveSeriesPlay(listOf(undated, dated), null, null)?.id)
        assertEquals(id(3), resolveSeriesPlay(listOf(dated, undated), null, null)?.id)
    }

    @Test
    fun resumeItemsWithoutPositionAreIgnored() {
        val got = resolveSeriesPlay(listOf(cand(2, ticks = 0, played = t0)), nextUp = cand(4), first = cand(1))
        assertEquals(id(4), got?.id)
    }

    @Test
    fun nextUpWhenNothingPartWatched() = assertEquals(id(4), resolveSeriesPlay(emptyList(), cand(4), cand(1))?.id)

    @Test
    fun firstEpisodeWhenNothingElse() = assertEquals(id(1), resolveSeriesPlay(emptyList(), null, cand(1))?.id)

    @Test
    fun nothingKnown() = assertNull(resolveSeriesPlay(emptyList(), null, null))

    @Test
    fun labelAndActionComeFromTheSameCandidate() {
        // The button is built from the one chosen candidate: its position decides the verb, its numbers the code
        val chosen = resolveSeriesPlay(listOf(cand(3, season = 2, episode = 4, ticks = 77, played = t0)), cand(9), cand(1))!!
        assertEquals("Resume S2 E4", seriesPlayLabel(chosen.season, chosen.episode, chosen.positionTicks > 0L))
        val next = resolveSeriesPlay(emptyList(), cand(6, season = 2, episode = 4), cand(1))!!
        assertEquals("Play S2 E4", seriesPlayLabel(next.season, next.episode, next.positionTicks > 0L))
    }
}
