package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NflTeamsTest {
    @Test
    fun espnAndOldCodesMapToSleeper() {
        assertEquals("WAS", NflTeams.code("WSH"))
        assertEquals("WAS", NflTeams.code(" wsh "))
        assertEquals("JAX", NflTeams.code("JAC"))
        assertEquals("LAR", NflTeams.code("LA"))
        assertEquals("LAR", NflTeams.code("STL"))
        assertEquals("LAC", NflTeams.code("SD"))
        assertEquals("LV", NflTeams.code("OAK"))
        assertEquals("ARI", NflTeams.code("ARZ"))
        assertNull(NflTeams.code(" "))
        assertNull(NflTeams.code(null))
    }

    @Test
    fun everyAliasLandsOnACanonicalCodeAndCanonicalIsStable() {
        NflTeams.ALIAS.values.forEach { assertTrue(it, it in NflTeams.CODES) }
        NflTeams.CODES.forEach { assertEquals(it, NflTeams.code(it)) }
        assertEquals(32, NflTeams.CODES.size)
        NflTeams.ALIAS.keys.forEach { assertFalse(it, it in NflTeams.CODES) }
    }

    @Test
    fun same() {
        assertTrue(NflTeams.same("WAS", "WSH"))
        assertTrue(NflTeams.same("kc", "KC"))
        assertFalse(NflTeams.same("NYG", "NYJ"))
        assertFalse(NflTeams.same(null, null))
    }
}
