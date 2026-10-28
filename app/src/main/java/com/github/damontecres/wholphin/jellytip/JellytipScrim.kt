package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.mutableStateMapOf
import coil3.Image
import coil3.toBitmap
import kotlin.math.sqrt

/**
 * How strong the corner scrim behind the hero/details text must be for a given image (0 = base, 1 = maximum).
 * Measured once per image URL when it decodes: mean luminance and spread of the region under the text block
 * (bottom-left 45% x 45%). Bright pastel or busy art (e.g. Moonbeam City) gets a much denser, wider vignette.
 */
object JellytipScrim {
    private val strengths = mutableStateMapOf<String, Float>()

    fun strength(url: String?): Float = url?.let { strengths[it] } ?: 0f

    /** Vignette opacity for [base]..[max] scaled by [strength]. */
    fun lerp(
        base: Float,
        max: Float,
        strength: Float,
    ): Float = base + (max - base) * strength

    private fun smooth(
        x: Float,
        lo: Float,
        hi: Float,
    ): Float {
        val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun inspect(
        url: String?,
        image: Image,
    ) {
        if (url == null || strengths.containsKey(url)) return
        strengths[url] =
            try {
                val w = 64
                val h = (w * image.height / image.width.coerceAtLeast(1)).coerceIn(8, 256)
                val bmp = image.toBitmap(w, h)
                val x1 = (bmp.width * 0.45f).toInt().coerceAtLeast(1)
                val y0 = (bmp.height * 0.55f).toInt()
                val px = IntArray(x1 * (bmp.height - y0))
                bmp.getPixels(px, 0, x1, 0, y0, x1, bmp.height - y0)
                var sum = 0.0
                var sumSq = 0.0
                for (c in px) {
                    val l = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
                    sum += l
                    sumSq += l * l
                }
                val mean = (sum / px.size).toFloat()
                val std = sqrt((sumSq / px.size - mean * mean).coerceAtLeast(0.0)).toFloat()
                maxOf(smooth(mean, 0.38f, 0.58f), smooth(std, 0.16f, 0.30f))
            } catch (e: Exception) {
                0f
            }
    }
}
