package com.github.damontecres.wholphin.jellytip.rows

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
}
