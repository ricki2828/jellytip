package com.github.damontecres.wholphin.jellytip.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRefreshPolicyTest {
    private val loadedAt = 1_000_000L

    private fun refresh(
        now: Long,
        stoppedAt: Long = 0L,
        loadedBefore: Boolean = true,
        lastLoaded: Long = loadedAt,
        force: Boolean = false,
        version: Long = 0L,
        loadedAtVersion: Long = 0L,
    ) = HomeRefreshPolicy.shouldRefresh(loadedBefore, now, lastLoaded, stoppedAt, version, loadedAtVersion, force)

    @Test
    fun neverLoadedAlwaysRefreshes() {
        assertTrue(refresh(loadedAt + 1, loadedBefore = false))
        assertTrue(refresh(loadedAt + 1, lastLoaded = 0L))
    }

    @Test
    fun quickBlinkWithFreshRowsSkips() {
        // Stopped 5 s after the load, back 3 s later: a dialog or screensaver wake, not a playback
        assertFalse(refresh(now = loadedAt + 8_000, stoppedAt = loadedAt + 5_000))
    }

    @Test
    fun resumeWithoutAnyStopSkipsWhileFresh() {
        assertFalse(refresh(now = loadedAt + 60_000))
    }

    @Test
    fun awayLongEnoughForPlaybackRefreshes() {
        assertTrue(refresh(now = loadedAt + 60_000, stoppedAt = loadedAt + 10_000))
    }

    @Test
    fun awayExactlyAtThresholdRefreshes() {
        val stopped = loadedAt + 1_000
        assertTrue(refresh(now = stopped + HomeRefreshPolicy.MIN_AWAY_MS, stoppedAt = stopped))
        assertFalse(refresh(now = stopped + HomeRefreshPolicy.MIN_AWAY_MS - 1, stoppedAt = stopped))
    }

    @Test
    fun oldRowsRefreshWhateverTheAwayTime() {
        assertTrue(refresh(now = loadedAt + HomeRefreshPolicy.MAX_AGE_MS))
        assertFalse(refresh(now = loadedAt + HomeRefreshPolicy.MAX_AGE_MS - 1))
    }

    @Test
    fun aStopBeforeTheLastLoadDoesNotCount() {
        // Stopped, came back, reloaded; that old stop must not trigger another refresh
        assertFalse(refresh(now = loadedAt + 60_000, stoppedAt = loadedAt - 30_000))
    }

    @Test
    fun forceAlwaysRefreshes() {
        assertTrue(refresh(now = loadedAt + 1, force = true))
    }

    @Test
    fun lateRowThatShiftsTheFocusedEntryWaits() {
        val current = listOf("hero", "continue", "movies")
        val candidate = listOf("hero", "continue", "companion", "movies")
        assertTrue(HomeRefreshPolicy.focusWouldMove(true, "movies", current, candidate))
    }

    @Test
    fun lateRowBelowTheFocusedEntryMergesAtOnce() {
        val current = listOf("hero", "continue", "movies")
        val candidate = listOf("hero", "continue", "movies", "companion")
        assertFalse(HomeRefreshPolicy.focusWouldMove(true, "continue", current, candidate))
    }

    @Test
    fun noFocusHeldNeverBlocks() {
        val current = listOf("a", "b")
        val candidate = listOf("x", "a", "b")
        assertFalse(HomeRefreshPolicy.focusWouldMove(false, "b", current, candidate))
        assertFalse(HomeRefreshPolicy.focusWouldMove(true, null, current, candidate))
        assertEquals(false, HomeRefreshPolicy.focusWouldMove(true, "gone", current, current))
    }

    @Test
    fun playbackSignalAlwaysRefreshesHoweverShort() {
        assertTrue(refresh(now = loadedAt + 2_000, stoppedAt = loadedAt + 1_000, version = 5, loadedAtVersion = 4))
        assertFalse(refresh(now = loadedAt + 2_000, stoppedAt = loadedAt + 1_000, version = 5, loadedAtVersion = 5))
    }

    @Test
    fun ageIsMeasuredFromLoadStart() {
        // Load started at loadedAt, would have "finished" during a long playback: still old by its start
        assertTrue(refresh(now = loadedAt + HomeRefreshPolicy.MAX_AGE_MS + 1))
    }

    @Test
    fun refocusFollowsTheItemTheRefreshMoved() {
        assertEquals(2, HomeRefreshPolicy.refocusColumn(listOf("n", "a", "b"), "b", 1))
        assertEquals(null, HomeRefreshPolicy.refocusColumn(listOf("a", "b"), "b", 1))
        assertEquals(null, HomeRefreshPolicy.refocusColumn(listOf("a"), "gone", 0))
        assertEquals(null, HomeRefreshPolicy.refocusColumn(listOf("a"), null, 0))
    }

    @Test
    fun focusLeavingTheRowEmitsNoFix() {
        assertEquals(null, HomeRefreshPolicy.refocusColumn(listOf("n", "a", "b"), "b", 1, refocus = false))
        assertEquals(2, HomeRefreshPolicy.refocusColumn(listOf("n", "a", "b"), "b", 1, refocus = true))
    }

    @Test
    fun failedLoadResetCountsAsStale() {
        // markLoadIncomplete(): start = 0, version = -1
        assertTrue(refresh(now = loadedAt + 1, lastLoaded = 0L, version = 0, loadedAtVersion = -1))
        assertTrue(refresh(now = loadedAt + 1, version = 0, loadedAtVersion = -1))
    }
}
