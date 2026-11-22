package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.details.iptvHeroOf
import com.github.damontecres.wholphin.jellytip.details.iptvScoreText
import com.github.damontecres.wholphin.jellytip.details.resolveIptvSeriesPlay
import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvProgress
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodDetail
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellytipIptvPlayTest {
    private val watched = IptvProgress(2700.0, 2800.0, true)
    private val part = IptvProgress(1260.0, 2800.0, false)

    private fun ep(
        s: Int,
        e: Int,
        p: IptvProgress? = null,
    ) = IptvEpisode("id:$s:$e", e, "Ep $e", 46, null, null, p)

    private fun season(
        n: Int,
        vararg eps: IptvEpisode,
    ) = IptvSeason(n, eps.toList())

    // ---- resolver ----

    @Test
    fun partWatchedWins() {
        val t = resolveIptvSeriesPlay(listOf(season(1, ep(1, 1, watched), ep(1, 2, watched), ep(1, 3, part), ep(1, 4))))!!
        assertEquals("id:1:3", t.episode.id)
        assertTrue(t.resumable)
        assertEquals(0, t.seasonIndex)
        assertEquals(2, t.episodeIndex)
    }

    @Test
    fun firstPartWatchedInOrder() {
        val t = resolveIptvSeriesPlay(listOf(season(1, ep(1, 1), ep(1, 2, part)), season(2, ep(2, 1, part))))!!
        assertEquals("id:1:2", t.episode.id)
    }

    @Test
    fun nextAfterLastWatched() {
        val t = resolveIptvSeriesPlay(listOf(season(1, ep(1, 1, watched), ep(1, 2, watched), ep(1, 3)), season(2, ep(2, 1))))!!
        assertEquals("id:1:3", t.episode.id)
        assertFalse(t.resumable)
    }

    @Test
    fun nextRollsIntoNextSeason() {
        val t = resolveIptvSeriesPlay(listOf(season(1, ep(1, 1, watched), ep(1, 2, watched)), season(2, ep(2, 1), ep(2, 2))))!!
        assertEquals("id:2:1", t.episode.id)
        assertEquals(1, t.seasonIndex)
        assertEquals(0, t.episodeIndex)
    }

    @Test
    fun nothingWatchedIsFirstEpisode() {
        val t = resolveIptvSeriesPlay(listOf(season(1, ep(1, 1), ep(1, 2))))!!
        assertEquals("id:1:1", t.episode.id)
        assertFalse(t.resumable)
    }

    @Test
    fun allWatchedStartsOver() {
        val t = resolveIptvSeriesPlay(listOf(season(1, ep(1, 1, watched), ep(1, 2, watched))))!!
        assertEquals("id:1:1", t.episode.id)
    }

    @Test
    fun specialsAreSkippedForTheFallback() {
        val t = resolveIptvSeriesPlay(listOf(season(0, ep(0, 1)), season(1, ep(1, 1), ep(1, 2))))!!
        assertEquals("id:1:1", t.episode.id)
        assertEquals(1, t.seasonIndex)
    }

    @Test
    fun specialsOnlyStillPlay() {
        assertEquals("id:0:1", resolveIptvSeriesPlay(listOf(season(0, ep(0, 1))))!!.episode.id)
    }

    @Test
    fun partWatchedSpecialCanBeResumed() {
        assertEquals("id:0:1", resolveIptvSeriesPlay(listOf(season(0, ep(0, 1, part)), season(1, ep(1, 1))))!!.episode.id)
    }

    @Test
    fun sendOrderDoesNotMatter() {
        val t = resolveIptvSeriesPlay(listOf(season(2, ep(2, 1)), season(1, ep(1, 2, watched), ep(1, 1, watched))))!!
        assertEquals("id:2:1", t.episode.id)
        assertEquals(0, t.seasonIndex)
    }

    @Test
    fun noEpisodes() {
        assertNull(resolveIptvSeriesPlay(emptyList()))
        assertNull(resolveIptvSeriesPlay(listOf(season(1))))
    }

    // ---- hero adapter ----

    private fun detail(
        kind: String,
        rating: Double? = 7.44,
        langUnknown: Boolean = false,
        tech: List<String> = listOf("4K", "HDR"),
        director: String? = "Michael Mann",
    ) = IptvVodDetail(
        item =
            IptvVodItem(
                id = "p1:m:1",
                kind = kind,
                tmdbId = 1,
                title = "Heat",
                year = 1995,
                quality = listOf("4k"),
                poster = null,
                backdrop = null,
                overview = "Thieves.",
                rating = rating,
                providerName = "Provider One",
                progress = null,
                tech = tech,
                langUnknown = langUnknown,
            ),
        runtimeMin = 170,
        genre = "Crime",
        director = director,
        cast = "Al Pacino",
        seasons = emptyList(),
        logo = "https://h/logo.png",
        genres = listOf("Crime", "Drama", "Thriller", "Action"),
    )

    @Test
    fun heroFilm() {
        val h = iptvHeroOf(detail("movie"), "fallback")
        assertEquals("Heat", h.title)
        assertEquals("https://h/logo.png", h.logoUrl)
        assertEquals("1995  ·  2h 50m  ·  4K  ·  HDR  ·  Crime, Drama, Thriller", h.infoText)
        assertEquals(listOf<String>(), h.badges)
        assertEquals("IPTV", h.sourceBadge)
        assertEquals("Directed by Michael Mann", h.dimLine)
        assertEquals("TMDB 7.4", h.scores.toString())
        assertEquals("Thieves.", h.overview)
        assertNull(h.statusLine)
        assertNull(h.subtitle)
    }

    @Test
    fun heroSeriesHasNoRuntimeAndTakesTheStatusLine() {
        val h = iptvHeroOf(detail("series"), "fallback", statusLine = "3 seasons  ·  Next: S2 E4")
        assertEquals("1995  ·  4K  ·  HDR  ·  Crime, Drama, Thriller", h.infoText)
        assertEquals("3 seasons  ·  Next: S2 E4", h.statusLine)
    }

    @Test
    fun heroLanguageUnknownIsABadge() {
        assertEquals(listOf("Language unknown"), iptvHeroOf(detail("movie", langUnknown = true), "f").badges)
    }

    @Test
    fun heroWithoutRatingOrDirector() {
        val h = iptvHeroOf(detail("movie", rating = null, director = null), "f")
        assertNull(h.scores)
        assertNull(h.dimLine)
    }

    @Test
    fun heroQualityWordsWhenNoPremiumTech() {
        val h = iptvHeroOf(detail("movie", tech = emptyList()), "f")
        assertEquals("1995  ·  2h 50m  ·  4K  ·  Crime, Drama, Thriller", h.infoText)
    }

    @Test
    fun scoreText() {
        assertEquals("TMDB 7.4", iptvScoreText(7.44))
        assertNull(iptvScoreText(null))
        assertNull(iptvScoreText(0.0))
    }
}
