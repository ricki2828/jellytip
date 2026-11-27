package com.github.damontecres.wholphin.ui.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemRowKeysTest {
    @Test
    fun uniqueIdsKeepTheirIdAsKey() {
        assertEquals(listOf("a", "b", "c"), ItemRowKeys.build(listOf("a", "b", "c")))
    }

    @Test
    fun repeatedIdGetsSuffixSoLazyRowNeverSeesDuplicateKeys() {
        val keys = ItemRowKeys.build(listOf("a", "b", "a", "a"))
        assertEquals(listOf("a", "b", "a#2", "a#3"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun placeholdersKeyBySlotAndNeverClashWithIds() {
        val keys = ItemRowKeys.build(listOf(null, "x", null))
        assertEquals(listOf("slot-0", "x", "slot-2"), keys)
    }

    @Test
    fun insertingATileAtTheFrontKeepsTheOthersKeys() {
        val before = ItemRowKeys.build(listOf("a", "b", "c"))
        val after = ItemRowKeys.build(listOf("new", "a", "b", "c"))
        assertTrue(after.containsAll(before))
    }

    @Test
    fun emptyRowHasNoKeys() {
        assertTrue(ItemRowKeys.build(emptyList()).isEmpty())
    }
}
