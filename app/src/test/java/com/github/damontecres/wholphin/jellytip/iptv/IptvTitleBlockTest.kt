package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvTitleBlockTest {
    private fun ep(
        id: String,
        n: Int,
        title: String,
    ) = IptvEpisode(id = id, episode = n, title = title, runtimeMin = 60, overview = null, still = null, progress = null)

    private fun detail(
        kind: String,
        title: String,
        year: Int? = 2025,
        logo: String? = "https://x/logo.png",
        seasons: List<IptvSeason> = emptyList(),
    ) = IptvVodDetail(
        item =
            IptvVodItem(
                id = "p:s:1", kind = kind, tmdbId = 1, title = title, year = year, quality = emptyList(), poster = null,
                backdrop = null, overview = "", rating = null, providerName = null, progress = null,
            ),
        runtimeMin = null, genre = null, director = null, cast = null, seasons = seasons, logo = logo,
    )

    private val traitors =
        detail(
            "series", "The Celebrity Traitors",
            seasons = listOf(IptvSeason(2, listOf(ep("p:e:2111119", 1, "Episode 1"), ep("p:e:2111120", 2, "The Round Table")))),
        )

    @Test
    fun episodeShowsTheShowAndTheCodeLine() {
        val b = IptvTitleBlock.of("The Celebrity Traitors · The Round Table", traitors, "p:e:2111120")
        assertEquals("The Celebrity Traitors", b.title)
        assertEquals("S2 E2 · The Round Table", b.subtitle)
        assertEquals("https://x/logo.png", b.logo)
    }

    @Test
    fun aGenericEpisodeTitleIsLeftOut() {
        assertEquals("S2 E1", IptvTitleBlock.of("x", traitors, "p:e:2111119").subtitle)
        assertEquals("S1 E5", IptvTitleBlock.episodeLine(1, 5, "Ep. 5"))
        assertEquals("S1 E5", IptvTitleBlock.episodeLine(1, 5, " "))
        assertEquals("S1 E5 · Pilot", IptvTitleBlock.episodeLine(1, 5, "Pilot"))
    }

    @Test
    fun filmShowsTitleAndYear() {
        val b = IptvTitleBlock.of("EN - Heat", detail("movie", "Heat", year = 1995), null)
        assertEquals("Heat", b.title)
        assertEquals("1995", b.subtitle)
    }

    @Test
    fun beforeTheDetailLoadsTheLabelIsSplit() {
        val b = IptvTitleBlock.of("Black Doves · Episode title", null, "p:e:9")
        assertEquals("Black Doves", b.title)
        assertEquals("Episode title", b.subtitle)
        assertNull(b.logo)
        assertEquals(IptvTitleBlock.Block("Heat · Director's Cut", null, null), IptvTitleBlock.of("Heat · Director's Cut", null, null))
    }

    @Test
    fun anEpisodeTheDetailDoesNotListKeepsTheLabelsPart() {
        val b = IptvTitleBlock.of("Traitors · New one", traitors, "p:e:999")
        assertEquals("The Celebrity Traitors", b.title)
        assertEquals("New one", b.subtitle)
        assertEquals("https://x/logo.png", b.logo)
    }
}
