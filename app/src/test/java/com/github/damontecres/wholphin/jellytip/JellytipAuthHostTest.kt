package com.github.damontecres.wholphin.jellytip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellytipAuthHostTest {
    private val server = "https://ricki28.palawan.usbx.me/jellyfin"

    @Test
    fun sameHostMatches() {
        assertTrue(JellytipAuthHost.matches("ricki28.palawan.usbx.me", server))
        assertTrue(JellytipAuthHost.matches("RICKI28.palawan.usbx.me", server))
    }

    @Test
    fun otherHostsDoNotMatch() {
        assertFalse(JellytipAuthHost.matches("image.tmdb.org", server))
        assertFalse(JellytipAuthHost.matches("evil.ricki28.palawan.usbx.me", server))
    }

    @Test
    fun missingOrBadServerUrlNeverMatches() {
        assertFalse(JellytipAuthHost.matches("ricki28.palawan.usbx.me", null))
        assertFalse(JellytipAuthHost.matches("ricki28.palawan.usbx.me", "not a url"))
    }
}
