package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvVodTest {
    @Test
    fun imagesTmdbKeptRelativeResolvedProviderRefused() {
        val base = "https://x.example/jellytip-api"
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", IptvVodParse.image(base, "https://image.tmdb.org/t/p/w342/a.jpg"))
        assertEquals("$base/v1/iptv/img/1:m:2.abcd", IptvVodParse.image(base, "v1/iptv/img/1:m:2.abcd"))
        assertNull(IptvVodParse.image(base, "rtmp://panel/img.png"))
        assertNull(IptvVodParse.image(base, ""))
    }

    @Test
    fun resumeOnlyWhenStartedAndNotNearlyDone() {
        assertTrue(IptvProgress(600.0, 6000.0, false).resumable)
        assertFalse(IptvProgress(10.0, 6000.0, false).resumable)
        assertFalse(IptvProgress(5500.0, 6000.0, false).resumable)
        assertFalse(IptvProgress(600.0, 6000.0, true).resumable)
    }
}
