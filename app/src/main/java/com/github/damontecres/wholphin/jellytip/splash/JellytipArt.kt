package com.github.damontecres.wholphin.jellytip.splash

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

/** Logo geometry in the 100 x 170 box of design/splash.html. */
private object LogoPaths {
    val stick = path("M43,119 A7,7 0 0 1 57,119 V159 A7,7 0 0 1 43,159 Z")
    val body = path("M48,18 H52 A30,30 0 0 1 82,48 V92 A30,30 0 0 1 52,122 H48 A30,30 0 0 1 18,92 V48 A30,30 0 0 1 48,18 Z")
    val choc = path("M18,70 V48 A32,32 0 0 1 82,48 V70 Q74,78 66,70 Q58,62 50,70 Q42,78 34,70 Q26,62 18,70 Z")
    val jelly = path("M27,36 A23,23 0 0 1 73,36 Q67,42 61,36 Q56,31 50,36 Q44,42 39,36 Q33,31 27,36 Z")
    val play = path("M44,84 L62,96 L44,108 Z")
}

private val Vanilla = Color(0xFFF7E8C4)
private val Choc = Color(0xFF5B3421)
private val Jelly = Color(0xFFE2264D)
private val Stick = Color(0xFFD9A86C)
private val BiteColor = Color(0xFF21163D)

/** Draw the Jelly Tip in a 100 x 170 coordinate space (caller scales). */
fun DrawScope.drawJellyTipUnits(
    bites: Int,
    dripT: Float,
    biteColor: Color = BiteColor,
) {
    drawPath(LogoPaths.stick, Stick)
    drawPath(LogoPaths.body, Vanilla)
    drawPath(LogoPaths.choc, Choc)
    drawPath(LogoPaths.jelly, Jelly)
    // drip: sits still for 40% of the cycle, then falls and fades
    val (dy, da) =
        when {
            dripT < 0.4f -> 0f to 1f
            dripT < 0.9f -> 26f * (dripT - 0.4f) / 0.5f to 1f - (dripT - 0.4f) / 0.5f
            else -> 26f to 0f
        }
    drawOval(Jelly, Offset(30.8f, 37.4f + dy), Size(6.4f, 9.2f), alpha = da)
    drawPath(LogoPaths.play, Choc)
    drawOval(Color(0xFFFF8AA3), Offset(34f, 23f), Size(12f, 6f), alpha = 0.7f)
    if (bites >= 1) drawCircle(biteColor, 11f, Offset(80f, 30f))
    if (bites >= 2) drawCircle(biteColor, 10f, Offset(70f, 18f))
}

/**
 * The Jelly Tip logo. With [animate] the stick pops in, the drip falls and each new bite mark chomps.
 * Without it (reduced motion) the finished logo is drawn static.
 */
@Composable
fun JellyTipLogo(
    bites: Int,
    animate: Boolean,
    modifier: Modifier = Modifier,
    biteColor: Color = BiteColor,
) {
    val pop = remember { Animatable(if (animate) 0f else 1f) }
    val chomp = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (animate) pop.animateTo(1f, tween(700, easing = CubicBezierEasing(0.2f, 1.6f, 0.4f, 1f)))
    }
    LaunchedEffect(bites) {
        if (animate && bites > 0) {
            chomp.snapTo(1f)
            chomp.animateTo(0f, tween(160))
        }
    }
    val dripT =
        if (animate) {
            val transition = rememberInfiniteTransition(label = "drip")
            val v by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
                label = "dripT",
            )
            v
        } else {
            0f
        }
    Canvas(
        modifier
            .aspectRatio(100f / 170f)
            .graphicsLayer {
                val c = chomp.value
                scaleX = 1f - 0.08f * c
                scaleY = 1f - 0.08f * c
                rotationZ = -4f * c
            },
    ) {
        val s = size.width / 100f
        val p = pop.value
        withTransform({
            translate(0f, (1f - p) * 0.4f * size.height)
            rotate(-25f * (1f - p), center)
            scale(0.4f + 0.6f * p, 0.4f + 0.6f * p, center)
            scale(s, s, Offset.Zero)
        }) {
            drawJellyTipUnits(bites, dripT, biteColor)
        }
    }
}
