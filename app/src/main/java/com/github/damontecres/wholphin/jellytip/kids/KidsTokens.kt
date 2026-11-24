package com.github.damontecres.wholphin.jellytip.kids

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R

/** Nunito (SIL OFL, LICENSES/OFL-Nunito.txt): 700 for body, 900 for headings. */
val NunitoFamily =
    FontFamily(
        Font(R.font.nunito_bold, FontWeight.Bold),
        Font(R.font.nunito_black, FontWeight.Black),
    )

/**
 * Look B "Playground" (mock/b-tv-*.html, 1920x1080 px = 960x540 dp here). Sizes are the mock's px halved.
 * Only this one set exists in v1 (decision A1); a second look would be a second instance, not a fork of the composables.
 */
@Immutable
class KidsTokens(
    val canvasTop: Color,
    val canvasMid: Color,
    val canvasBottom: Color,
    val blobCoral: Color,
    val blobBlue: Color,
    val ink: Color,
    val text: Color,
    val textDim: Color,
    val sub: Color,
    val ring: Color,
    val jelly: Color,
    val accent: Color,
    val homeColor: Color,
    val showsColor: Color,
    val moviesColor: Color,
    val listColor: Color,
    val heroFallbackA: Color,
    val heroFallbackB: Color,
    val heroScrim: Color,
    val tileRadius: Dp,
    val heroRadius: Dp,
    val tileBorder: Dp,
    val heroBorder: Dp,
    val borderColor: Color,
    val heroBorderColor: Color,
    val chipColor: Color,
    val tileWidth: Dp,
    val tileGap: Dp,
    val bubble: Dp,
    val pagePad: Dp,
    val focusScale: Float,
    val focusMillis: Int,
    val fontFamily: FontFamily,
) {
    fun sectionColor(section: KidsSection): Color =
        when (section) {
            KidsSection.HOME -> homeColor
            KidsSection.SHOWS -> showsColor
            KidsSection.MOVIES -> moviesColor
            KidsSection.MY_LIST -> listColor
        }

    companion object {
        val Playground =
            KidsTokens(
                canvasTop = Color(0xFF241A5E),
                canvasMid = Color(0xFF150F3D),
                canvasBottom = Color(0xFF0D0A24),
                blobCoral = Color(0xFFE2264D),
                blobBlue = Color(0xFF4D7CFF),
                ink = Color(0xFF1B1730),
                text = Color(0xFFF2F2F0),
                textDim = Color(0xD9F2F2F0),
                sub = Color(0xFFFFE9B0),
                ring = Color.White,
                jelly = Color(0xFFE2264D),
                accent = Color(0xFFFFD24D),
                homeColor = Color(0xFFFF8F6B),
                showsColor = Color(0xFF7FE0B8),
                moviesColor = Color(0xFFB79CF2),
                listColor = Color(0xFFFF9FB5),
                heroFallbackA = Color(0xFF3B2A7A),
                heroFallbackB = Color(0xFF8A6BD6),
                heroScrim = Color(0xFF140C38),
                tileRadius = 15.dp,
                heroRadius = 26.dp,
                tileBorder = 2.5.dp,
                heroBorder = 3.dp,
                borderColor = Color(0x24FFFFFF),
                heroBorderColor = Color(0x29FFFFFF),
                chipColor = Color(0x2EFFFFFF),
                tileWidth = 164.dp,
                tileGap = 12.dp,
                bubble = 82.dp,
                pagePad = 36.dp,
                focusScale = 1.06f,
                focusMillis = 250,
                fontFamily = NunitoFamily,
            )
    }
}

val LocalKidsTokens = staticCompositionLocalOf { KidsTokens.Playground }
