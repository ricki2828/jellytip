package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** IPTV audio / subtitle track names (web TrackMenu). */
class IptvTracksTest {
    @Test
    fun labels() {
        assertEquals("English · 5.1", IptvTracks.label(null, "en", 6, 1))
        assertEquals("Commentary (English)", IptvTracks.label("Commentary", "en", 2, 2))
        assertEquals("English AC3", IptvTracks.label("English AC3", "en", 0, 1))
        assertEquals("Track 3", IptvTracks.label(null, "und", 0, 3))
    }
}
