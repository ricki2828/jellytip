package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvOwnerPolicyTest {
    @Test
    fun guideOnlyIsMuted() {
        val p = IptvOwnerPolicy()
        p.attach("guide")
        assertEquals(0f, p.volume(), 0f)
    }

    @Test
    fun fullScreenOverGuideHasSound() {
        val p = IptvOwnerPolicy()
        p.attach("guide")
        p.attach("full#1")
        assertEquals(1f, p.volume(), 0f)
    }

    /** Old logic: guide kept composed, its attach never re-ran, volume stayed 1f from full screen. */
    @Test
    fun backToComposedGuideMutesWithoutGuideReattach() {
        val p = IptvOwnerPolicy()
        p.attach("guide")
        p.attach("full#1")
        assertFalse(p.detach("full#1"))
        assertEquals(0f, p.volume(), 0f)
    }

    @Test
    fun swappedFullPagesKeepSound() {
        val p = IptvOwnerPolicy()
        p.attach("guide")
        p.attach("full#1")
        p.attach("full#2")
        p.detach("full#1")
        assertEquals(1f, p.volume(), 0f)
    }

    @Test
    fun lastDetachReportsEmpty() {
        val p = IptvOwnerPolicy()
        p.attach("full#1")
        assertTrue(p.detach("full#1"))
        assertTrue(p.isEmpty)
    }

    @Test
    fun staleOpenIsRejectedAfterStop() {
        val p = IptvOwnerPolicy()
        val open = p.bump()
        assertTrue(p.isCurrent(open))
        p.bump() // stop()
        assertFalse(p.isCurrent(open))
    }

    @Test
    fun newerOpenSupersedesOlder() {
        val p = IptvOwnerPolicy()
        val a = p.bump()
        val b = p.bump()
        assertFalse(p.isCurrent(a))
        assertTrue(p.isCurrent(b))
    }
}
