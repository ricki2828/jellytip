package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.iptv.IptvPlayControls.Dir
import com.github.damontecres.wholphin.jellytip.iptv.IptvPlayControls.Hidden
import com.github.damontecres.wholphin.jellytip.iptv.IptvPlayControls.More
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** IPTV player overlay (Ricki 21-Nov): key map, the More list, the time line. */
class IptvPlayControlsTest {
    @Test
    fun hiddenKeyMap() {
        assertEquals(Hidden.SHOW, IptvPlayControls.hidden(Dir.OK))
        assertEquals(Hidden.SHOW, IptvPlayControls.hidden(Dir.UP))
        assertEquals(Hidden.SHOW, IptvPlayControls.hidden(Dir.DOWN))
        assertEquals(Hidden.SEEK, IptvPlayControls.hidden(Dir.LEFT))
        assertEquals(Hidden.SEEK, IptvPlayControls.hidden(Dir.RIGHT))
        assertEquals(Hidden.NONE, IptvPlayControls.hidden(Dir.OTHER))
    }

    @Test
    fun moreListHasOnlyWhatApplies() {
        assertEquals(listOf(More.START_OVER), IptvPlayControls.more(seekable = true, recording = false, inProgressWithChannel = false))
        assertEquals(listOf(More.START_OVER, More.DELETE_RECORDING), IptvPlayControls.more(seekable = true, recording = true, inProgressWithChannel = false))
        assertEquals(
            listOf(More.START_OVER, More.JUMP_TO_LIVE, More.DELETE_RECORDING),
            IptvPlayControls.more(seekable = true, recording = true, inProgressWithChannel = true),
        )
        assertEquals(emptyList<More>(), IptvPlayControls.more(seekable = false, recording = false, inProgressWithChannel = false))
    }

    @Test
    fun noStreamReportOnTheTv() {
        // Ricki 21-Nov: "This stream isn't working" is gone from the TV
        assertFalse(More.entries.any { it.label.contains("working", ignoreCase = true) })
    }

    @Test
    fun timeLine() {
        assertEquals("1:02:03", IptvPlayControls.clock(3_723_000))
        assertEquals("4:05", IptvPlayControls.clock(245_000))
        assertEquals("-50:00", IptvPlayControls.left(10 * 60_000L, 60 * 60_000L))
        assertNull(IptvPlayControls.left(0, 0))
        // 20:00 UTC + 50 min left = 20:50
        val now = java.time.Instant.parse("2026-11-21T20:00:00Z").toEpochMilli()
        assertEquals("Ends 20:50", IptvPlayControls.endsAt(now, 10 * 60_000L, 60 * 60_000L, ZoneId.of("UTC")))
        assertNull(IptvPlayControls.endsAt(now, 0, 0, ZoneId.of("UTC")))
    }

    @Test
    fun scrubBarSeeksOnlyWhenFocused() {
        // Ricki 22-Nov: with the overlay up, Left / Right moved between buttons and nothing reached the seek bar
        assertEquals(true, IptvPlayControls.scrubSeeks(overlay = true, scrubFocused = true, dir = Dir.LEFT))
        assertEquals(true, IptvPlayControls.scrubSeeks(overlay = true, scrubFocused = true, dir = Dir.RIGHT))
        assertFalse(IptvPlayControls.scrubSeeks(overlay = true, scrubFocused = true, dir = Dir.OK))
        assertFalse(IptvPlayControls.scrubSeeks(overlay = true, scrubFocused = true, dir = Dir.UP))
        assertFalse(IptvPlayControls.scrubSeeks(overlay = true, scrubFocused = false, dir = Dir.RIGHT))
        assertFalse(IptvPlayControls.scrubSeeks(overlay = false, scrubFocused = true, dir = Dir.RIGHT))
    }
}
