package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamReminderStateTest {
    private val a = listOf(TeamReminder("r1", "t1", "Chiefs", "football", "nfl"))
    private var token = "A"
    private val s = TeamReminderState(UserOwnership { token })

    @Test
    fun resetClearsEverything() {
        assertTrue(s.loaded(s.start(), a))
        assertEquals(a, s.items.value)
        assertEquals(true, s.available.value)
        s.reset()
        assertNull(s.items.value)
        assertNull(s.available.value)
    }

    @Test
    fun answerForThePreviousPersonIsNeverWritten() {
        val g = s.start() // fetch starts as A
        token = "B" // B signs in before it lands
        assertFalse(s.loaded(g, a))
        assertFalse(s.missing(g))
        assertNull(s.items.value)
        assertNull(s.available.value)
        assertTrue(s.loaded(s.start(), emptyList()))
        assertEquals(emptyList<TeamReminder>(), s.items.value)
    }

    @Test
    fun missingHidesTheFeatureAndDropsTheList() {
        assertTrue(s.loaded(s.start(), a))
        assertTrue(s.missing(s.start()))
        assertEquals(false, s.available.value)
        assertNull(s.items.value)
    }
}
