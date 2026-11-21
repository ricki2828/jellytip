package com.github.damontecres.wholphin.jellytip.rows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Collections hub Movies | TV Shows | All switch (web f16e2120). */
class HubTabsTest {
    private fun card(
        id: String,
        mediaType: String?,
    ) = HubCollection(id, id, null, null, 1, 1, 0, false, mediaType)

    private val sections =
        listOf(
            HubSection("franchises", "Franchises", listOf(card("bond", "movie"), card("trek", "mixed"), card("x", null))),
            HubSection("tv", "TV", listOf(card("sitcoms", "tv"))),
        )

    @Test
    fun allIsUnchanged() = assertSame(sections, HubTabs.filter(sections, HubTabs.ALL))

    @Test
    fun moviesKeepUnknownAndMixedAndDropEmptySections() {
        val out = HubTabs.filter(sections, HubTabs.MOVIE)
        assertEquals(listOf("franchises"), out.map { it.key })
        assertEquals(listOf("bond", "trek", "x"), out[0].items.map { it.id })
    }

    @Test
    fun tvShowsOnlyTvUnknownMixed() {
        val out = HubTabs.filter(sections, HubTabs.TV)
        assertEquals(listOf("trek", "x"), out[0].items.map { it.id })
        assertEquals(listOf("sitcoms"), out[1].items.map { it.id })
    }
}
