package com.github.damontecres.wholphin.jellytip.search

import com.github.damontecres.wholphin.jellytip.search.SearchPressHold.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPressHoldTest {
    @Test
    fun quickTapIsShortOnKeyUp() {
        val p = SearchPressHold()
        assertEquals(Press.NONE, p.down(1_000, 0))
        assertTrue(p.inPress)
        assertEquals(Press.SHORT, p.up(1_120))
        assertFalse(p.inPress)
    }

    @Test
    fun holdPastThresholdFiresOnceWhileHeldAndUpIsNothing() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        // Android repeats: first after ~400 ms, then every ~50 ms
        assertEquals(Press.NONE, p.down(1_400, 1))
        assertEquals(Press.NONE, p.down(1_450, 2))
        assertEquals(Press.LONG, p.down(1_500, 3))
        assertEquals(Press.NONE, p.down(1_550, 4))
        assertEquals(Press.NONE, p.down(1_600, 5))
        assertTrue("the rest of the held press is still ours to consume", p.inPress)
        assertEquals("a hold never also opens Search", Press.NONE, p.up(2_000))
        assertFalse(p.inPress)
    }

    @Test
    fun repeatsUnderThresholdThenReleaseIsShort() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        p.down(1_400, 1)
        p.down(1_450, 2)
        assertEquals(Press.SHORT, p.up(1_480))
    }

    @Test
    fun holdWithoutRepeatsIsLongOnKeyUp() {
        // a remote that sends no repeats: the time between down and up decides
        val p = SearchPressHold()
        p.down(1_000, 0)
        assertEquals(Press.LONG, p.up(1_700))
    }

    @Test
    fun exactlyAtThresholdIsAHold() {
        val p = SearchPressHold()
        p.down(0, 0)
        assertEquals(Press.LONG, p.down(SearchPressHold.HOLD_MS, 1))
    }

    @Test
    fun upWithoutDownIsNothing() {
        val p = SearchPressHold()
        assertEquals(Press.NONE, p.up(1_000))
        assertFalse(p.inPress)
    }

    @Test
    fun repeatsWithoutDownAreNothing() {
        // the press started on another control and focus moved here mid-hold
        val p = SearchPressHold()
        assertEquals(Press.NONE, p.down(1_000, 7))
        assertEquals(Press.NONE, p.down(2_000, 8))
        assertFalse(p.inPress)
        assertEquals(Press.NONE, p.up(2_100))
    }

    @Test
    fun twoQuickTapsAreTwoShorts() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        assertEquals(Press.SHORT, p.up(1_090))
        p.down(1_200, 0)
        assertEquals(Press.SHORT, p.up(1_280))
    }

    @Test
    fun tapAfterAHoldStartsClean() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        assertEquals(Press.LONG, p.down(1_550, 3))
        p.up(1_700)
        p.down(2_000, 0)
        assertEquals(Press.SHORT, p.up(2_100))
    }

    @Test
    fun missedKeyUpThenFreshDownStartsOver() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        // the key-up went elsewhere; a new press much later must not count as a hold from the first down
        p.down(5_000, 0)
        assertEquals(Press.SHORT, p.up(5_100))
    }

    @Test
    fun resetDropsThePress() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        p.reset()
        assertFalse(p.inPress)
        assertEquals(Press.NONE, p.down(1_600, 4))
        assertEquals(Press.NONE, p.up(1_700))
    }

    @Test
    fun customThreshold() {
        val p = SearchPressHold(holdMs = 800)
        p.down(0, 0)
        assertEquals(Press.NONE, p.down(600, 3))
        assertEquals(Press.LONG, p.down(800, 7))
    }

    @Test
    fun aRemoteWithoutRepeatsHoldsByTimerWhileTheKeyIsDown() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        assertEquals("too early", Press.NONE, p.tick(1_300))
        assertEquals(Press.LONG, p.tick(1_520))
        assertEquals("once", Press.NONE, p.tick(1_600))
        assertEquals("a late repeat does not fire it again", Press.NONE, p.down(1_700, 1))
        assertEquals("and the key-up after it is nothing", Press.NONE, p.up(8_000))
    }

    @Test
    fun theTimerAfterAKeyUpOrResetIsNothing() {
        val p = SearchPressHold()
        p.down(1_000, 0)
        assertEquals(Press.SHORT, p.up(1_100))
        assertEquals(Press.NONE, p.tick(1_520))
        p.down(2_000, 0)
        p.reset()
        assertEquals(Press.NONE, p.tick(2_600))
    }
}
