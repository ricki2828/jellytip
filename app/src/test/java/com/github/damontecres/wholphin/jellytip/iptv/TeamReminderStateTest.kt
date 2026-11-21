package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamReminderStateTest {
    private val a = listOf(TeamReminder("r1", "t1", "Chiefs", "football", "nfl"))

    @Test
    fun resetClearsTheList() {
        val s = TeamReminderState()
        assertTrue(s.loaded(s.gen, a))
        assertEquals(a, s.items.value)
        assertEquals(true, s.available.value)
        s.reset()
        assertNull(s.items.value)
        assertNull(s.available.value)
    }

    @Test
    fun answerForThePreviousUserIsDropped() {
        val s = TeamReminderState()
        val g = s.gen // fetch starts for user A
        s.reset() // user B signs in before it lands
        assertFalse(s.loaded(g, a))
        assertNull(s.items.value)
        assertFalse(s.missing(g))
        assertNull(s.available.value)
        assertTrue(s.loaded(s.gen, emptyList()))
        assertEquals(emptyList<TeamReminder>(), s.items.value)
    }

    @Test
    fun missingHidesTheFeature() {
        val s = TeamReminderState()
        assertTrue(s.missing(s.gen))
        assertEquals(false, s.available.value)
    }
}
