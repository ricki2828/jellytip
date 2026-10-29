package com.github.damontecres.wholphin.jellytip.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRulesTest {
    private val decade = SearchChip("decade", "1990s", "1990")
    private val country = SearchChip("country", "France", "FR")
    private val genre = SearchChip("genre", "Crime", "Crime")

    @Test
    fun removesTheWordsBehindAChip() {
        val all = listOf(decade, country, genre)
        assertEquals("french crime", SearchRules.removeChip("90s french crime", "", decade, all))
        assertEquals("90s crime", SearchRules.removeChip("90s french crime", "", country, all))
        assertEquals("90s french", SearchRules.removeChip("90s french crime", "", genre, all))
    }

    @Test
    fun personTechAndRuntime() {
        assertEquals("heist", SearchRules.removeChip("heist with Al Pacino", "heist", SearchChip("person", "With Al Pacino", "Al Pacino"), emptyList()))
        assertEquals("atmos films", SearchRules.removeChip("4k atmos films", "films", SearchChip("tech", "4K", "4k"), emptyList()))
        assertEquals("comedies", SearchRules.removeChip("comedies under 2h", "comedies", SearchChip("runtime", "Under 2h", "120"), emptyList()))
    }

    @Test
    fun rebuildsWhenNothingMatches() {
        val g = SearchChip("genre", "Science Fiction", "Science Fiction")
        assertEquals("space 1990s", SearchRules.removeChip("90s sci-fi space", "space", g, listOf(decade, g)))
    }
}
