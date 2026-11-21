package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quiet trailer preview on IPTV details (web de81df8e). */
class IptvPassiveTrailerTest {
    @Test
    fun onlyCachedTrailersWithAutoplayOn() {
        assertTrue(iptvPassiveTrailer("https://jt.example/v1/trailers/abc.mp4", autoplay = true))
        assertFalse(iptvPassiveTrailer("https://jt.example/v1/trailers/abc.mp4", autoplay = false))
        assertFalse(iptvPassiveTrailer(null, autoplay = true))
        assertFalse(iptvPassiveTrailer(" ", autoplay = true))
        assertFalse(iptvPassiveTrailer("https://www.youtube.com/watch?v=x", autoplay = true))
        assertFalse(iptvPassiveTrailer("https://youtu.be/x", autoplay = true))
    }
}
