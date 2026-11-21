package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/*
 * Game Centre / Home live hero artwork (Ricki 21-Nov): the fixture's `art` (league fanart, served path) behind the hero and
 * tiles, with the team colours as an overlay. Hero = art full-bleed, a black dim, home colour from the left and away from
 * the right at [TEAM_ALPHA] meeting in a dark centre, a bottom scrim; tiles = the art dimmed with a lighter colour wash
 * (the colour edges stay; no runtime blur: too costly per tile on a Chromecast GPU). No art, or art that fails to load = today's look.
 *
 * The alphas were picked against the worst case (a white photo, a light-grey team colour that still passes
 * [FixtureColours.usable]); [FixtureArtTest] re-checks the contrast with [heroBehind] / [tileBehind].
 */
object FixtureArt {
    const val HERO_DIM = 0.5f
    const val TEAM_ALPHA = 0.55f

    /** The dark centre runs from [CENTRE_FROM] to 1 - [CENTRE_FROM] of the width. */
    const val CENTRE_FROM = 0.4f
    const val CENTRE_ALPHA = 0.85f
    const val SCRIM_FROM = 0.55f
    const val SCRIM_ALPHA = 0.6f
    const val TILE_DIM = 0.72f
    const val TILE_WASH = 0.18f

    /** The tile wash fades out over this much of the width from each edge. */
    const val TILE_WASH_SPAN = 0.35f
    const val PAN_MS = 30_000
    const val PAN_ZOOM = 1.08f

    /** Sized art link (the art proxy scales like the logo proxy: s=96|192|480|960). */
    fun sized(
        url: String,
        s: Int,
    ): String = if (url.contains('?')) url else "$url?s=$s"

    /** Plain sRGB mix (what the canvas does; Compose's lerp(Color) works in Oklab). */
    private fun mix(
        a: Color,
        b: Color,
        t: Float,
    ): Color = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

    private fun over(
        top: Color,
        alpha: Float,
        under: Color,
    ): Color = mix(under, top, alpha)

    /**
     * The colour behind hero text at [x] (0..1 of the width), above the photo pixel [art], with [home] / [away] team colours.
     * Mirrors the brush stops (Android interpolates the unpremultiplied colours and the alpha); the bottom scrim is left out
     * (it only darkens).
     */
    fun heroBehind(
        x: Float,
        art: Color,
        home: Color,
        away: Color,
    ): Color {
        val dimmed = over(Color.Black, HERO_DIM, art)
        val (c, a) =
            when {
                x <= CENTRE_FROM -> (x / CENTRE_FROM).let { t -> mix(home, Broadcast.Base, t) to TEAM_ALPHA + (CENTRE_ALPHA - TEAM_ALPHA) * t }
                x >= 1f - CENTRE_FROM -> ((1f - x) / CENTRE_FROM).let { t -> mix(away, Broadcast.Base, t) to TEAM_ALPHA + (CENTRE_ALPHA - TEAM_ALPHA) * t }
                else -> Broadcast.Base to CENTRE_ALPHA
            }
        return over(c, a, dimmed)
    }

    /** The colour behind tile text at [x] from the home edge (0..1), above the photo pixel [art]. */
    fun tileBehind(
        x: Float,
        art: Color,
        home: Color,
    ): Color {
        val dimmed = over(Color.Black, TILE_DIM, art)
        val a = TILE_WASH * (1f - x / TILE_WASH_SPAN).coerceAtLeast(0f)
        return over(home, a, dimmed)
    }

    /** WCAG contrast ratio. */
    fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return if (la > lb) la / lb else lb / la
    }
}

/**
 * The hero background with art: the photo (a slow one-way pan when [motion]), dim, team colours meeting in a dark centre,
 * bottom scrim. [onFailed]: the photo did not load (the caller goes back to today's look).
 */
@Composable
fun FixtureArtHeroBackdrop(
    art: String,
    /** Restarts the pan (league art is shared by every fixture of the league). */
    panKey: Any,
    home: Color,
    away: Color,
    motion: Boolean,
    onFailed: () -> Unit,
) {
    val pan = remember(panKey) { Animatable(0f) }
    LaunchedEffect(panKey, motion) {
        if (motion) pan.animateTo(1f, tween((FixtureArt.PAN_MS * (1f - pan.value)).toInt(), easing = LinearEasing))
    }
    val wash =
        remember(home, away) {
            val edge = FixtureArt.TEAM_ALPHA
            val mid = FixtureArt.CENTRE_ALPHA
            Brush.horizontalGradient(
                0f to home.copy(alpha = edge),
                FixtureArt.CENTRE_FROM to Broadcast.Base.copy(alpha = mid),
                1f - FixtureArt.CENTRE_FROM to Broadcast.Base.copy(alpha = mid),
                1f to away.copy(alpha = edge),
            )
        }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = FixtureArt.sized(art, 960),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { onFailed() },
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    if (motion) {
                        scaleX = FixtureArt.PAN_ZOOM
                        scaleY = FixtureArt.PAN_ZOOM
                        // Left to right across the zoom margin, once per fixture
                        translationX = size.width * (FixtureArt.PAN_ZOOM - 1f) / 2f * (pan.value * 2f - 1f)
                    }
                },
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = FixtureArt.HERO_DIM)))
        Box(Modifier.fillMaxSize().background(wash))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(FixtureArt.SCRIM_FROM to Color.Transparent, 1f to Color.Black.copy(alpha = FixtureArt.SCRIM_ALPHA)),
            ),
        )
    }
}

/** The tile background with art: the photo (small size, no runtime blur: jank on weak TV GPUs), dim, a light team-colour wash. */
@Composable
fun FixtureArtTileBackdrop(
    art: String,
    home: Color,
    away: Color?,
    onFailed: () -> Unit,
) {
    val wash =
        remember(home, away) {
            val span = FixtureArt.TILE_WASH_SPAN
            Brush.horizontalGradient(
                0f to home.copy(alpha = FixtureArt.TILE_WASH),
                span to Color.Transparent,
                1f - span to Color.Transparent,
                1f to (away ?: Color.Transparent).copy(alpha = if (away != null) FixtureArt.TILE_WASH else 0f),
            )
        }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = FixtureArt.sized(art, 192),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { onFailed() },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = FixtureArt.TILE_DIM)))
        Box(Modifier.fillMaxSize().background(wash))
    }
}
