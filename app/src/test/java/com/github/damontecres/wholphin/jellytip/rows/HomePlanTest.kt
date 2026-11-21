package com.github.damontecres.wholphin.jellytip.rows

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Home row plan, TV part (web homePlan.ts): thin rows dropped, repeats thinned, pins untouched. */
class HomePlanTest {
    private val ids = List(10) { UUID.randomUUID() }

    private fun row(
        id: String,
        vararg take: Int,
        family: String = "genre",
    ) = JellytipServerRow(id, "jellytip", family, id, "standard", "movie", take.map { ids[it] }, null)

    @Test
    fun thinRowsDroppedUnlessPinnedOrMyList() {
        val out = JellytipHomePlan.plan(listOf(row("a", 0, 1, 2), row("b", 0, 1, 2, 3), row("m", 0, family = "mylist"), row("p", 5)), setOf("p"))
        assertEquals(listOf("b", "m", "p"), out.map { it.id })
    }

    @Test
    fun repeatsThinnedAfterThreeEarlierRowsIfFourRemain() {
        val rows = listOf(row("r1", 0, 1, 2, 3), row("r2", 0, 1, 2, 3), row("r3", 0, 1, 2, 3), row("r4", 0, 4, 5, 6, 7), row("r5", 0, 1, 4, 5))
        val out = JellytipHomePlan.plan(rows, emptySet())
        assertEquals(listOf(ids[4], ids[5], ids[6], ids[7]), out[3].itemIds) // id 0 was in 3 earlier rows
        assertEquals(rows[4].itemIds, out[4].itemIds) // thinning would leave < 4: kept whole
    }

    @Test
    fun shortFranchisePersonAndUnknownPrefsKept() {
        val trilogy = row("f", 0, 1, 2).copy(layout = "franchise", collection = JellytipCollection(1, "Trilogy", "", null, null, emptyList(), 0, 3, 3, emptyList()))
        val person = row("p", 3, 4, 5).copy(person = JellytipServerRow.Person(UUID.randomUUID(), "X", "Actor", null))
        assertEquals(listOf("f", "p"), JellytipHomePlan.plan(listOf(trilogy, person), emptySet()).map { it.id })
        // prefs not known yet: nothing dropped for being short
        assertEquals(listOf("a"), JellytipHomePlan.plan(listOf(row("a", 0, 1)), null).map { it.id })
    }
}
