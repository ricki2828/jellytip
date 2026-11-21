package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserOwnershipTest {
    private var token = "A"
    private val own = UserOwnership { token }

    @Test
    fun answerFromBeforeTheTokenMovedIsDropped() {
        val s = own.start()
        token = "B"
        assertFalse(own.still(s))
    }

    @Test
    fun answerFetchedAsTheNewPersonIsKept() {
        token = "B" // token moved, switch not announced yet
        assertTrue(own.still(own.start()))
    }

    @Test
    fun signOutDropsAnswersFetchedWhileSignedIn() {
        val s = own.start()
        token = ""
        assertFalse(own.still(s))
    }
}
