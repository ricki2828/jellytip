package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.details.buildDetailsHero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hero mapper must produce exactly what JellytipDetailsBlock computed inline before the hero model existed. */
class JellytipDetailsHeroTest {
    private fun minutes(m: Int) = m * 600_000_000L

    @Test
    fun fullFilm() {
        val h =
            buildDetailsHero(
                title = "Heat",
                productionYear = 1995,
                officialRating = "R",
                runTimeTicks = minutes(170),
                premium = listOf("4K", "Dolby Vision", "Dolby Atmos"),
                genres = listOf("Crime", "Drama", "Thriller", "Action"),
                fileBadges = listOf("4K", "HEVC", "EAC3 5.1"),
                directors = listOf("Michael Mann"),
                country = "United States of America",
                overview = "A group of high-end professional thieves.\nA lieutenant closes in.",
            )
        assertEquals("1995  ·  R  ·  2h 50m  ·  4K  ·  Dolby Vision  ·  Dolby Atmos  ·  Crime, Drama, Thriller", h.infoText)
        // 4K is already in the premium formats, so the file badge is dropped
        assertEquals(listOf("HEVC", "EAC3 5.1"), h.badges)
        assertEquals("Directed by Michael Mann  ·  United States of America", h.dimLine)
        assertEquals("A group of high-end professional thieves.\nA lieutenant closes in.", h.overview)
        assertEquals("Heat", h.title)
        assertNull(h.statusLine)
        assertNull(h.sourceBadge)
    }

    @Test
    fun noYearNoRating() {
        val h = buildDetailsHero(title = "X", runTimeTicks = minutes(45), genres = listOf("Drama"))
        assertEquals("45m  ·  Drama", h.infoText)
    }

    @Test
    fun yearOnlyAndEmptyInfo() {
        assertEquals("2001", buildDetailsHero(title = "X", productionYear = 2001).infoText)
        assertEquals("", buildDetailsHero(title = "X").infoText)
    }

    @Test
    fun runtimeEdges() {
        assertEquals("", buildDetailsHero(title = "X", runTimeTicks = 0L).infoText)
        assertEquals("", buildDetailsHero(title = "X", runTimeTicks = minutes(0) + 1).infoText)
        assertEquals("1h 0m", buildDetailsHero(title = "X", runTimeTicks = minutes(60)).infoText)
        assertEquals("59m", buildDetailsHero(title = "X", runTimeTicks = minutes(59)).infoText)
    }

    @Test
    fun fourKBadgeKeptWithoutPremium() {
        val h = buildDetailsHero(title = "X", fileBadges = listOf("4K", "HEVC"))
        assertEquals(listOf("4K", "HEVC"), h.badges)
        val p = buildDetailsHero(title = "X", premium = listOf("Dolby Atmos"), fileBadges = listOf("4K"))
        assertEquals(listOf("4K"), p.badges)
    }

    @Test
    fun dimLineVariants() {
        assertNull(buildDetailsHero(title = "X").dimLine)
        assertEquals("Directed by A, B", buildDetailsHero(title = "X", directors = listOf("A", " ", "B")).dimLine)
        assertEquals("Canada", buildDetailsHero(title = "X", country = "Canada").dimLine)
        assertEquals(
            "Réalisé par A  ·  France",
            buildDetailsHero(title = "X", directors = listOf("A"), country = "France", directedBy = { "Réalisé par $it" }).dimLine,
        )
        assertNull(buildDetailsHero(title = "X", directors = listOf(" ")).dimLine)
    }

    @Test
    fun overviewBlankIsNull() {
        assertNull(buildDetailsHero(title = "X", overview = "  \n ").overview)
        assertNull(buildDetailsHero(title = "X", overview = null).overview)
    }

    @Test
    fun genresLimitedToThree() {
        assertEquals("A, B, C", buildDetailsHero(title = "X", genres = listOf("A", "B", "C", "D")).infoText)
        assertEquals("", buildDetailsHero(title = "X", genres = listOf(" ")).infoText)
    }

    @Test
    fun passThroughFields() {
        val h = buildDetailsHero(title = "X", subtitle = "Double feature with Y", statusLine = "3 seasons", audienceLine = "Requested by Z", sourceBadge = "IPTV", logoUrl = "u")
        assertEquals("Double feature with Y", h.subtitle)
        assertEquals("3 seasons", h.statusLine)
        assertEquals("Requested by Z", h.audienceLine)
        assertEquals("IPTV", h.sourceBadge)
        assertEquals("u", h.logoUrl)
    }
}
