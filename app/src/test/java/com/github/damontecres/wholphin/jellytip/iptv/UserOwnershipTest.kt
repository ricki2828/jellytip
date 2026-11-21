package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserOwnershipTest {
    private var token = "A"
    private val own = UserOwnership { token }

    @Test
    fun answerFromBeforeTheTokenMovedIsDropped() {
        val s = own.start() // fetched as A
        token = "B"
        assertFalse(own.accept(s))
        assertNull(own.owner)
    }

    @Test
    fun answerFetchedInTheGapBeforeTheSwitchIsKept() {
        // changeUser: token moves to B, a fetch runs as B, then the switch is announced
        assertTrue(own.accept(own.start()))
        token = "B"
        assertTrue(own.accept(own.start()))
        assertFalse(own.mustClear(signedOut = false)) // B's own list survives the switch (the old P3)
        assertEquals("B", own.owner)
    }

    @Test
    fun switchClearsSomeoneElsesAnswer() {
        assertTrue(own.accept(own.start()))
        token = "B"
        assertTrue(own.mustClear(signedOut = false))
        assertNull(own.owner)
        assertFalse(own.mustClear(signedOut = false)) // nothing held: nothing to clear
    }

    @Test
    fun signOutAlwaysClears() {
        assertTrue(own.accept(own.start()))
        assertTrue(own.mustClear(signedOut = true)) // signed out before the token is dropped
    }
}
