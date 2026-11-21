package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HideChannelTest {
    private fun ch(
        id: String,
        group: String = "p1:7",
        pid: String = "p1",
    ) = IptvChannel(id, pid, null, "Ch $id", null, group, null, 0, false)

    @Test
    fun ownerOnlyAndIdsSplit() {
        assertEquals(HideTarget("p1", "7", "123"), HideChannelLogic.target(ch("p1:123"), setOf("p1")))
        assertNull(HideChannelLogic.target(ch("p1:123"), setOf("p2"))) // shared provider
        assertNull(HideChannelLogic.target(ch("p1:123"), null)) // not loaded
        assertNull(HideChannelLogic.target(ch("p1:123", group = "fav"), setOf("p1")))
        assertNull(HideChannelLogic.target(ch("p2:123"), setOf("p1"))) // id from another provider
        assertEquals(HideTarget("p1", "7", "a:b"), HideChannelLogic.target(ch("p1:a:b"), setOf("p1")))
    }

    @Test
    fun undoPutsItBack() {
        val a = ch("p1:a")
        val b = ch("p1:b")
        val c = ch("p1:c")
        assertEquals(listOf(a, b, c), HideChannelLogic.restore(listOf(a, c), HiddenChannel(b, 1, "t")))
        assertEquals(listOf(a, c, b), HideChannelLogic.restore(listOf(a, c), HiddenChannel(b, 9, "t")))
        assertEquals(listOf(a, b), HideChannelLogic.restore(listOf(a, b), HiddenChannel(b, 0, null)))
    }

    @Test
    fun neighbour() {
        assertEquals("c", HideChannelLogic.neighbour(listOf("a", "b", "c"), "b"))
        assertEquals("b", HideChannelLogic.neighbour(listOf("a", "b"), "b"))
        assertNull(HideChannelLogic.neighbour(listOf("b"), "b"))
        assertNull(HideChannelLogic.neighbour(listOf("a"), "x"))
    }
}
