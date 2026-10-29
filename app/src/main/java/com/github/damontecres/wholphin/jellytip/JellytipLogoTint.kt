package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import coil3.Image
import com.github.damontecres.wholphin.data.model.BaseItem
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.UUID
import coil3.toBitmap
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import kotlin.math.sqrt

/**
 * Hero/details clearlogo treatment (behind [JellytipHomeTokens.WHITE_LOGO]).
 * Single-tone logos become a flat off-white silhouette. Multi-tone logos (3D bevels, outlines, shading) would turn
 * illegible when all white, so they get a luminance duotone: light areas near-white, dark areas near-black, alpha kept.
 */
object JellytipLogoTint {
    private const val FLAT_STD_DEV = 0.12f
    private const val CONTRAST = 1.6f
    private val LIGHT = floatArrayOf(242f, 242f, 240f)
    private val DARK = floatArrayOf(10f, 10f, 10f)

    private val flatSilhouette = ColorFilter.tint(JellytipHomeTokens.LOGO_TINT, BlendMode.SrcIn)

    private val duotone: ColorFilter by lazy {
        val lum = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val m = FloatArray(20)
        for (ch in 0..2) {
            val span = LIGHT[ch] - DARK[ch]
            for (i in 0..2) m[ch * 5 + i] = span * CONTRAST * lum[i]
            m[ch * 5 + 4] = DARK[ch] + span * (0.5f - 0.5f * CONTRAST)
        }
        m[18] = 1f // alpha passes through
        ColorFilter.colorMatrix(ColorMatrix(m))
    }

    /** true = flat silhouette, false = duotone; decided once per logo URL after it has decoded. */
    private val flat = mutableStateMapOf<String, Boolean>()

    /**
     * Per-item treatment chosen by the companion (`logoStyle` on /backdrops, `logoStyles` maps in specials/rows):
     * "white" = the white treatment below, "mono" = greyscale with the shading kept, "original" = untouched.
     * Items it has not answered for keep the white treatment.
     */
    private val styles = mutableStateMapOf<UUID, String>()

    fun setStyle(
        itemId: UUID,
        style: String?,
    ) {
        when (style) {
            STYLE_WHITE, STYLE_MONO, STYLE_ORIGINAL -> if (styles[itemId] != style) styles[itemId] = style
            else -> {}
        }
    }

    /** A `{"<itemId>": "white|mono|original"}` object from a payload (ids with or without dashes). */
    fun setStyles(map: org.json.JSONObject?) {
        map ?: return
        map.keys().forEach { key ->
            runCatching { key.toUUID() }.getOrNull()?.let { setStyle(it, map.optString(key)) }
        }
    }

    const val STYLE_WHITE = "white"
    const val STYLE_MONO = "mono"
    const val STYLE_ORIGINAL = "original"

    /** Greyscale (saturation 0) with a contrast/brightness lift so bevels and shading survive; alpha kept. */
    private val mono: ColorFilter by lazy {
        val lum = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val m = FloatArray(20)
        for (ch in 0..2) {
            for (i in 0..2) m[ch * 5 + i] = MONO_CONTRAST * lum[i]
            m[ch * 5 + 4] = MONO_LIFT + 128f * (1f - MONO_CONTRAST)
        }
        m[18] = 1f
        ColorFilter.colorMatrix(ColorMatrix(m))
    }

    private const val MONO_CONTRAST = 1.25f
    private const val MONO_LIFT = 24f

    /**
     * The one place logos get their colour treatment (hero, details). [item] picks the companion's per-item style
     * (its own id, then its series / logo owner); [url] the white treatment's flat-or-duotone decision.
     */
    fun filter(
        url: String?,
        item: BaseItem? = null,
    ): ColorFilter? {
        val style =
            item?.let { i -> listOfNotNull(i.id, i.data.seriesId, i.data.parentLogoItemId).firstNotNullOfOrNull { styles[it] } }
        return when {
            style == STYLE_ORIGINAL -> null
            style == STYLE_MONO -> mono
            !JellytipHomeTokens.WHITE_LOGO -> null
            url != null && flat[url] == false -> duotone
            else -> flatSilhouette
        }
    }

    /** Call from the image's onSuccess; samples opaque pixels and records whether the logo is essentially single-tone. */
    fun inspect(
        url: String?,
        image: Image,
    ) {
        if (url == null || !JellytipHomeTokens.WHITE_LOGO || flat.containsKey(url)) return
        flat[url] =
            try {
                val w = 64
                val bmp = image.toBitmap(w, (w * image.height / image.width.coerceAtLeast(1)).coerceIn(8, 256))
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                var n = 0
                var sum = 0.0
                var sumSq = 0.0
                for (c in px) {
                    if ((c ushr 24) < 128) continue
                    val l = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
                    n++
                    sum += l
                    sumSq += l * l
                }
                if (n < 20) {
                    true
                } else {
                    val mean = sum / n
                    sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0)) < FLAT_STD_DEV
                }
            } catch (e: Exception) {
                true
            }
    }
}
