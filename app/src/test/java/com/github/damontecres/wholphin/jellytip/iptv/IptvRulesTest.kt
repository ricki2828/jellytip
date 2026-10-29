package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvRulesTest {
    private fun p(
        start: Long,
        stop: Long,
        title: String = "x",
    ) = IptvProg(start, stop, title, null, false)

    @Test
    fun staleAndExpiredLinksAreRenewedOnce() {
        assertEquals(IptvRules.LinkFailure.RENEW, IptvRules.linkFailure(410, "stale"))
        assertEquals(IptvRules.LinkFailure.RENEW, IptvRules.linkFailure(403, "expired"))
        assertEquals(IptvRules.LinkFailure.FATAL, IptvRules.linkFailure(403, "bad_token"))
        assertEquals(IptvRules.LinkFailure.FULL, IptvRules.linkFailure(409, null))
        assertEquals(IptvRules.LinkFailure.FATAL, IptvRules.linkFailure(502, "provider_error"))
    }

    @Test
    fun darkEventChannels() {
        val none = IptvNowNext(null, null)
        assertTrue(IptvRules.isDark("|AU| KAYO 3", "|AU| KAYO PPV", none))
        assertFalse(IptvRules.isDark("NRL 01: Storm v Broncos", "|UK| NRL TV PPV", none))
        assertFalse(IptvRules.isDark("|AU| KAYO 3", "|AU| KAYO PPV", IptvNowNext(p(1, 2), null)))
        // A normal channel is never hidden, even without a guide
        assertFalse(IptvRules.isDark("|UK| SKY SPORTS MAIN EVENT HD", "|UK| SPORTS", none))
    }

    @Test
    fun tidyTrimsOverlapsAndDropsNested() {
        val out = IptvRules.tidy(listOf(p(100, 200, "b"), p(0, 150, "a"), p(110, 120, "nested"), p(200, 200, "zero")))
        assertEquals(listOf("a" to (0L to 100L), "b" to (100L to 200L)), out.map { it.title to (it.start to it.stop) })
    }

    @Test
    fun sportGuess() {
        assertTrue(IptvRules.looksLikeSport("|NZ| SKY SPORT 1 NRL: Storm v Broncos"))
        assertTrue(IptvRules.looksLikeSport("Kayo 3"))
        assertFalse(IptvRules.looksLikeSport("|UK| BBC ONE The News at Six"))
    }

    @Test
    fun windowAndProgAt() {
        assertEquals(1_800L, IptvRules.windowStart(2_000L))
        val progs = listOf(p(0, 100, "a"), p(100, 200, "b"), p(300, 400, "c"))
        assertEquals("b", IptvRules.progAt(progs, 150)?.title)
        assertEquals("c", IptvRules.progAt(progs, 290)?.title)
    }
}
