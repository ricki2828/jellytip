package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class RemindersPageTest {
    private val ch = IptvChannel("p:1", "p", 1, "Ch", null, "g", null, 0, false)

    private fun u(
        key: String,
        start: Long,
        rid: String? = "r$key",
        fid: String? = null,
        league: String? = "NRL",
    ) = IptvUpcoming(key, "Game $key", league, false, start, start + 7200, null, ch, emptyList(), true, null, reminderId = rid, fid = fid)

    @Test
    fun upcomingDropsEndedAndUnremindedFoldsTwinsSortsByStart() {
        val now = 1_000_000L
        val list =
            listOf(
                u("late", now + 5000),
                u("ended", now - 10_000),
                u("none", now + 100, rid = null),
                u("a", now + 1000, fid = "f1"),
                u("twin", now + 1000, fid = "f1"),
                u("live", now - 60),
            )
        assertEquals(listOf("live", "a", "late"), RemindersPageLogic.upcoming(list, now).map { it.key })
    }

    @Test
    fun subtitle() {
        val utc = ZoneId.of("UTC")
        val start = 1_795_000_000L // Wed 18 Nov 2026 11:06 UTC
        assertEquals("NRL · ${RemindersPageLogic.whenText(start, utc)}", RemindersPageLogic.subtitle(u("x", start), start - 3600, utc))
        assertEquals("NRL · On now", RemindersPageLogic.subtitle(u("x", start), start + 60, utc))
        assertEquals("On now · no stream yet", RemindersPageLogic.subtitle(u("x", start, league = null).copy(noStream = true), start + 60, utc))
        assertEquals("Wed 18 Nov 11:06", RemindersPageLogic.whenText(start, utc))
    }
}
