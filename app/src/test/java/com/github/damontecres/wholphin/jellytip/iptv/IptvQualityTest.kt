package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvQualityTest {
    private fun q(
        bucket: String?,
        height: Int? = null,
        audio: String? = null,
    ) = IptvQuality(bucket, height, null, audio)

    @Test
    fun bucketChips() {
        assertEquals("UHD", q("uhd").label)
        assertEquals("1080 50fps", q("fhd50").label)
        assertEquals("1080", q("fhd").label)
        assertEquals("720", q("hd").label)
        assertEquals("SD", q("sd").label)
        assertNull(q("dead").label)
        assertNull(q("unknown").label)
        assertNull(q(null).label)
    }

    @Test
    fun slowChannelsKeepTheirResolutionAndBufferHint() {
        assertEquals("1080", q("slow", 1080).label)
        assertNull(q("slow").label)
        assertTrue(q("slow", 1080).slow)
        assertFalse(q("fhd").slow)
    }

    @Test
    fun audioChipOnlyForAtmosAndSurround() {
        assertEquals("ATMOS", q("fhd", audio = "atmos").audioLabel)
        assertEquals("5.1", q("fhd", audio = "5.1").audioLabel)
        assertNull(q("fhd", audio = "stereo").audioLabel)
        assertNull(q("fhd").audioLabel)
    }

    @Test
    fun alsoOn() {
        assertNull(alsoOnText(0))
        assertEquals("also on 1 channel", alsoOnText(1))
        assertEquals("also on 3 channels", alsoOnText(3))
    }
}
