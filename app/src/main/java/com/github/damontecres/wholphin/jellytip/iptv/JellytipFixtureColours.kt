package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.roundToInt

/**
 * Team colours on fixture tiles and the hero, straight from `/v1/iptv/sport/fixtures` (`home.color`, `home.altColor`,
 * `away.color`, `away.altColor`: '#rrggbb', key absent when unknown). The colours are raw, so they're guarded here:
 *  - a colour that vanishes into the dark tile (ΔE < [DARK_DE] from the base) or glares (luminance > [LIGHT_LUM]) is
 *    swapped for the side's alt colour, or dropped (neutral) when the alt is no better;
 *  - two sides that read as the same colour (ΔE < [CLASH_DE], web broadcast.ts) give the away side its alt colour, or
 *    neutral when the alt still clashes.
 */
object FixtureColours {
    const val DARK_DE = 12.0
    const val LIGHT_LUM = 0.8f
    const val CLASH_DE = 20.0

    /** A colour that reads on the dark tile and the 22 % wash. */
    fun usable(c: Color): Boolean = deltaE(c, Broadcast.Base) >= DARK_DE && c.luminance() <= LIGHT_LUM

    private fun parse(hex: String?): Color? = hex?.let { teamColor(it, Color.Unspecified) }?.takeIf { it != Color.Unspecified }

    private fun Color.hex(): String = "#%06x".format(((red * 255).roundToInt() shl 16) or ((green * 255).roundToInt() shl 8) or (blue * 255).roundToInt())

    /** The side's colour: the primary if usable, else the alt if usable, else null (neutral). */
    fun side(
        color: String?,
        alt: String?,
    ): Color? = parse(color)?.takeIf(::usable) ?: parse(alt)?.takeIf(::usable)

    /** (home, away) hex colours to paint, null = neutral for that side; (null, null) = no colours at all. */
    fun resolve(
        homeColor: String?,
        homeAlt: String?,
        awayColor: String?,
        awayAlt: String?,
    ): Pair<String?, String?> {
        val home = side(homeColor, homeAlt)
        var away = side(awayColor, awayAlt)
        if (home != null && away != null && deltaE(home, away) < CLASH_DE) {
            // The away side gives way, as on the web; its alt only if that fixes the clash
            away = parse(awayAlt)?.takeIf { usable(it) && deltaE(home, it) >= CLASH_DE && it != away }
        }
        return home?.hex() to away?.hex()
    }

    /** The fixture's own colours, or null when it carried none (then the match record is asked, as before). */
    fun of(f: FxItem): Pair<String?, String?>? {
        if (f.homeColor == null && f.homeAltColor == null && f.awayColor == null && f.awayAltColor == null) return null
        return resolve(f.homeColor, f.homeAltColor, f.awayColor, f.awayAltColor)
    }
}
