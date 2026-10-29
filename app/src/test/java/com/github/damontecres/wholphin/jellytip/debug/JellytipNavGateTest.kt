package com.github.damontecres.wholphin.jellytip.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for "Home jumped to another title's page 5-10 s after hovering, with no key press". */
class JellytipNavGateTest {
    @Test
    fun browsePageToTitleNeedsRecentOk() {
        assertTrue(JellytipNavGate.allowed(fromBrowse = true, toTitle = true, msSinceOk = 200))
        assertTrue(JellytipNavGate.allowed(fromBrowse = true, toTitle = true, msSinceOk = 1_600)) // long-press opens on key-up
        assertFalse(JellytipNavGate.allowed(fromBrowse = true, toTitle = true, msSinceOk = 7_000)) // the reported jump
        assertFalse(JellytipNavGate.allowed(fromBrowse = true, toTitle = true, msSinceOk = -1))
    }

    @Test
    fun otherNavigationIsUntouched() {
        assertTrue(JellytipNavGate.allowed(fromBrowse = false, toTitle = true, msSinceOk = 60_000)) // e.g. details -> playback
        assertTrue(JellytipNavGate.allowed(fromBrowse = true, toTitle = false, msSinceOk = 60_000)) // e.g. home -> settings
    }
}
