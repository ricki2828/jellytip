package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.ask.AskItem
import com.github.damontecres.wholphin.jellytip.ask.ChipData
import com.github.damontecres.wholphin.jellytip.tonight.Tonight
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pick for tonight ranking (web lib/tonight.ts ranked / nextThree). */
class TonightTest {
    private fun c(
        key: String,
        tier: Int,
        rank: Int = 0,
    ) = Tonight.Cand(key, tier, key, null, "r", "Play", null, rank)

    @Test
    fun ranksByTierThenRankAndNeverRepeats() {
        val cands = listOf(c("rated1", 3), c("next", 1), c("resume2", 0, 2), c("resume1", 0, 1), c("recs", 2))
        val shown = mutableSetOf<String>()
        assertEquals(listOf("resume1", "resume2", "next"), Tonight.nextThree(cands, shown).map { it.key })
        assertEquals(listOf("recs", "rated1"), Tonight.nextThree(cands, shown).map { it.key })
        assertEquals(emptyList<String>(), Tonight.nextThree(cands, shown).map { it.key })
    }

    @Test
    fun reasonsAndDedupe() {
        assertEquals("Started recently", Tonight.into(60.0))
        assertEquals("You're 23 min into this", Tonight.into(23 * 60.0))
        assertEquals(listOf("a", "b"), Tonight.dedupe(listOf(c("a", 0), c("b", 2), c("a", 3))).map { it.key })
    }

    private fun film(
        key: String,
        min: Int? = 100,
        genres: List<String>? = null,
        rating: String? = null,
        started: Boolean = false,
    ) = Tonight.Cand(key, if (started) 0 else 2, key, null, "r", "Play", min, 0, probe = AskItem("Movie", min?.let { it * 600_000_000L }, null, genres), rating = rating, started = started)

    @Test
    fun constraintsMapToAskChips() {
        assertEquals(listOf(ChipData.MaxRuntime::class), Tonight.chipsFor(setOf("short")).map { it.data::class })
        assertEquals(listOf(ChipData.Unplayed::class), Tonight.chipsFor(setOf("new")).map { it.data::class })
        assertEquals(listOf(ChipData.Kids::class), Tonight.chipsFor(setOf("kids")).map { it.data::class })
        assertEquals(true, Tonight.chipsFor(setOf("light")).let { l -> l.isNotEmpty() && l.all { it.data is ChipData.Genre } })
        assertEquals(0, Tonight.chipsFor(emptySet()).size)
    }

    @Test
    fun shortUsesTimeLeftAndDropsUnknown() {
        // "long": 200 min film with 80 min left (probe carries time left)
        val c = listOf(film("long", 80, started = true), film("big", 150), film("small", 95), film("unk", null))
        assertEquals(listOf("long", "small"), Tonight.applyConstraints(c, setOf("short")).map { it.key }.sorted())
    }

    @Test
    fun lightNewAndKids() {
        val c =
            listOf(
                film("res", 60, listOf("Comedy"), started = true),
                film("com", genres = listOf("Comedy")),
                film("dra", genres = listOf("Drama")),
                film("kid", genres = listOf("Animation"), rating = "G"),
                film("kidx", genres = listOf("Animation"), rating = "R"),
                film("norate", genres = listOf("Animation")),
            )
        assertEquals(listOf("com", "kid", "kidx", "norate", "res"), Tonight.applyConstraints(c, setOf("light")).map { it.key }.sorted())
        assertEquals(false, Tonight.applyConstraints(c, setOf("new")).any { it.key == "res" })
        assertEquals(listOf("kid"), Tonight.applyConstraints(c, setOf("kids")).map { it.key })
        assertEquals(listOf("kid"), Tonight.applyConstraints(c, setOf("kids", "new")).map { it.key })
        // kids + light still need a Family/Animation/Kids genre
        val k = listOf(film("pgcom", genres = listOf("Comedy"), rating = "PG"), film("fam", genres = listOf("Comedy", "Family"), rating = "PG"))
        assertEquals(listOf("fam"), Tonight.applyConstraints(k, setOf("kids", "light")).map { it.key })
    }

    @Test
    fun eachConstraintSetStartsFresh() {
        val c = (0 until 5).map { c("g$it", 3, it) }
        val store = mutableMapOf<String, MutableSet<String>>()
        val a = Tonight.nextThree(c, Tonight.shownFor(store, emptySet()))
        assertEquals(listOf("g3", "g4"), Tonight.nextThree(c, Tonight.shownFor(store, emptySet())).map { it.key })
        assertEquals(a, Tonight.nextThree(c, Tonight.shownFor(store, setOf("new")), setOf("new")))
    }
}
