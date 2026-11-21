package com.github.damontecres.wholphin.jellytip.movienight

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinQrTest {
    @Test
    fun joinUrl() {
        assertEquals("https://host.example/jellytip/#/movienight?join=ABC234", JoinQr.joinUrl("https://host.example/jellytip-api", "ABC234"))
        assertEquals("https://host.example/jellytip/#/movienight?join=ABC234", JoinQr.joinUrl("https://host.example/jellytip-api/", "abc234"))
        assertNull(JoinQr.joinUrl("https://host.example/api", "ABC234"))
        assertEquals("HTTPS://host.example/jellytip/#/movienight?join=ABC234", JoinQr.joinUrl("HTTPS://host.example/jellytip-API", "ABC234"))
        assertNull(JoinQr.joinUrl("host.example/jellytip-api", "ABC234"))
        assertNull(JoinQr.joinUrl("https://host.example/jellytip-api", "AB 1/"))
        assertNull(JoinQr.joinUrl("https://host.example/jellytip-api", ""))
    }

    /** What the TV draws decodes back to the link (the same modules, scaled up as pixels). */
    @Test
    fun roundTrip() {
        val url = "https://host.example/jellytip/#/movienight?join=QRT789"
        val m = JoinQr.matrix(url)
        assertTrue(m.size >= 25 && m.size == m[0].size)
        // orientation (the decoder forgives a mirror; these don't): finder corners top-left and top-right, none bottom-right
        val last = m.size - 3
        assertTrue(m[2][2])
        assertTrue(m[2][last])
        assertTrue(m[last][2])
        assertTrue(!(m[last][last] && m[last - 6][last] && m[last][last - 6]))
        val scale = 4
        val n = m.size * scale
        val px = IntArray(n * n) { i -> if (m[(i / n) / scale][(i % n) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(n, n, px))))
        assertEquals(url, read.text)
    }
}
