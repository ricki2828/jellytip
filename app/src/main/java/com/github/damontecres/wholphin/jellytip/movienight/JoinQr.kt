package com.github.damontecres.wholphin.jellytip.movienight

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/*
 * Movie night join QR (TV host screen): the web app's join link for the code, as a QR a phone camera opens straight into
 * Movie night → Join. The web app sits next to the companion (…/jellytip-api → …/jellytip); its hash route is
 * #/movienight?join=CODE (web App.tsx). Pure; tested in JoinQrTest.
 */
object JoinQr {
    /** The web join link, or null when the companion address doesn't look like …/jellytip-api (no guessing). */
    fun joinUrl(
        companionBase: String,
        code: String,
    ): String? {
        val base = companionBase.trim().trimEnd('/')
        if (!base.startsWith("http://", ignoreCase = true) && !base.startsWith("https://", ignoreCase = true)) return null
        if (!base.endsWith("-api", ignoreCase = true)) return null
        val c = code.uppercase().filter { it in MovieNight.CODE_ALPHABET }
        if (c.length != code.length || c.isEmpty()) return null
        return "${base.dropLast(4)}/#/movienight?join=$c"
    }

    /** QR modules (true = dark), row by row, with a 2-module quiet zone; low error correction (fewer, bigger modules: a
     *  screen is clean, and the code has to read from the sofa). */
    fun matrix(text: String): Array<BooleanArray> {
        val m =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
            )
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }
}
