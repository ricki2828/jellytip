package com.github.damontecres.wholphin.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 25-Nov: the hero's Down / Back target when the saved row is not drawn (focus was trapped on the hero buttons). */
class HomeNearestRowTest {
    @Test
    fun savedRowDrawnWins() = assertEquals(0, homeNearestComposedRow(0, setOf(0, 1, 2), 5))

    @Test
    fun savedRowMissingGoesToNearestDrawn() = assertEquals(1, homeNearestComposedRow(0, setOf(1, 2), 5))

    @Test
    fun tieBetweenAboveAndBelowGoesDown() = assertEquals(3, homeNearestComposedRow(2, setOf(1, 3), 5))

    @Test
    fun nothingDrawnIsNull() = assertNull(homeNearestComposedRow(0, emptySet(), 5))

    @Test
    fun outOfRangeIgnored() = assertEquals(1, homeNearestComposedRow(4, setOf(1, 7), 3))
}
