package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class StarredFirstTest {
    private val ch = IptvChannel("p:1", "p", null, "Ch", null, "", null, 0, false)

    private fun up(
        key: String,
        fav: Boolean,
        start: Long,
    ) = IptvUpcoming(key, key, null, fav, start, start + 7200, null, ch, emptyList(), true, null)

    @Test
    fun favouritesFirstThenLiveThenServerOrder() {
        val now = 10_000L
        val list = listOf(up("a", false, now + 600), up("b", false, now - 60), up("c", true, now + 900), up("d", true, now - 30), up("e", false, now + 1200))
        assertEquals(listOf("d", "c", "b", "a", "e"), starredFirst(list, now).map { it.key })
    }
}
