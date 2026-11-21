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
    fun switchClearsThePreviousPersonsList() {
        assertTrue(s.loaded(s.start(), a))
        assertEquals(a, s.items.value)
        token = "B"
        s.switched(signedOut = false)
        assertNull(s.items.value)
        assertNull(s.available.value)
    }

    @Test
    fun answerForThePreviousPersonIsDropped() {
        val g = s.start() // fetch starts as A
        token = "B" // B signs in before it lands
        assertFalse(s.loaded(g, a))
        assertNull(s.items.value)
        assertFalse(s.missing(g))
        assertNull(s.available.value)
        assertTrue(s.loaded(s.start(), emptyList()))
        assertEquals(emptyList<TeamReminder>(), s.items.value)
    }

    @Test
    fun newPersonsListFetchedBeforeTheSwitchIsKept() {
        token = "B" // token moved, switch not announced yet
        assertTrue(s.loaded(s.start(), a))
        s.switched(signedOut = false)
        assertEquals(a, s.items.value)
    }

    @Test
    fun signOutClears() {
        assertTrue(s.loaded(s.start(), a))
        s.switched(signedOut = true)
        assertNull(s.items.value)
    }

    @Test
    fun missingHidesTheFeature() {
        assertTrue(s.missing(s.start()))
        assertEquals(false, s.available.value)
    }
}
