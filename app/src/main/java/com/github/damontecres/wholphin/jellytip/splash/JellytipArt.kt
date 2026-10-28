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
    if (bites >= 1) drawCircle(BiteColor, 11f, Offset(80f, 30f))
    if (bites >= 2) drawCircle(BiteColor, 10f, Offset(70f, 18f))
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
            drawJellyTipUnits(bites, dripT)
        }
    }
}

/** One caption line of an intro scene; [key] lines are drawn in the amber "key" colour. */
data class CaptionLine(
    val text: String,
    val key: Boolean = false,
)

data class IntroScene(
    val background: Color,
    val captions: List<CaptionLine>,
)

val IntroScenes =
    listOf(
        IntroScene(
            Color(0xFFFFCF3A),
            listOf(CaptionLine("NOW SHOWING:", true), CaptionLine("ONE BUZZY BEE, FLAT STICK. TOP SPEED 4 KM/H.")),
        ),
        IntroScene(
            Color.Black,
            listOf(CaptionLine("TELETEXT P801:", true), CaptionLine("SUBTITLES NOW ONE BUTTON. PROGRESS SINCE 1986.")),
        ),
        IntroScene(
            Color(0xFF3F6B3A),
            listOf(CaptionLine("IF IT WASN'T FOR YOUR GUMBOOTS...", true), CaptionLine("WE DON'T KNOW HOW LUCKY WE ARE.")),
        ),
        IntroScene(
            Color(0xFFC15A1E),
            listOf(CaptionLine("HOLDEN KINGSWOOD. VINYL SEATS. 38°C."), CaptionLine("DRIVE-IN SOLD OUT. STAYING IN.", true)),
        ),
        IntroScene(
            Color(0xFF09122E),
            listOf(CaptionLine("THE KIWI IS GOING TO BED."), CaptionLine("YOU ARE NOT.", true)),
        ),
    )

private val Ink = Color(0xFF111111)

private fun DrawScope.fillStroke(
    draw: (Color, androidx.compose.ui.graphics.drawscope.DrawStyle) -> Unit,
    fill: Color,
    stroke: Color,
    width: Float,
) {
    draw(fill, androidx.compose.ui.graphics.drawscope.Fill)
    draw(stroke, Stroke(width))
}

/** Draw scene [index] in a 200 x 130 coordinate space (caller scales). */
fun DrawScope.drawIntroScene(
    index: Int,
    textMeasurer: TextMeasurer,
    mono: FontFamily,
) {
    fun text(
        s: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Color,
    ) {
        drawText(
            textMeasurer,
            s,
            Offset(x, baseline - size * 0.78f),
            TextStyle(color = color, fontFamily = mono, fontSize = size.toSp()),
        )
    }
    when (index) {
        0 -> {
            val blue = Color(0xFF1D6FD6)
            val yellow = Color(0xFFFFD400)
            val red = Color(0xFFE53935)
            drawCircle(blue, 14f, Offset(55f, 112f))
            drawCircle(blue, 14f, Offset(145f, 112f))
            fillStroke({ c, st -> drawOval(c, Offset(38f, 34f), Size(124f, 76f), style = st) }, yellow, Ink, 4f)
            drawRect(Ink, Offset(72f, 36f), Size(14f, 72f))
            drawRect(Ink, Offset(104f, 36f), Size(14f, 72f))
            fillStroke({ c, st -> drawCircle(c, 20f, Offset(152f, 58f), style = st) }, yellow, Ink, 4f)
            drawCircle(Ink, 4f, Offset(158f, 54f))
            rotate(-18f, Offset(80f, 26f)) {
                fillStroke({ c, st -> drawOval(c, Offset(54f, 12f), Size(52f, 28f), style = st) }, red, Ink, 3f)
            }
            rotate(14f, Offset(118f, 24f)) {
                fillStroke({ c, st -> drawOval(c, Offset(92f, 10f), Size(52f, 28f), style = st) }, red, Ink, 3f)
            }
            drawLine(Ink, Offset(160f, 40f), Offset(166f, 20f), 4f, StrokeCap.Round)
            drawLine(Ink, Offset(168f, 44f), Offset(182f, 30f), 4f, StrokeCap.Round)
        }

        1 -> {
            drawRect(Color.Black, Offset.Zero, Size(200f, 130f))
            drawRect(Color(0xFF0000FF), Offset.Zero, Size(200f, 22f))
            text("P801 TELETEXT 801 21:47", 6f, 17f, 20f, Color(0xFFFFFF00))
            text("SUBTITLES", 8f, 50f, 22f, Color(0xFF00FFFF))
            text("ENGLISH........... ON", 8f, 74f, 18f, Color.White)
            text("TE REO MAORI...... ON", 8f, 94f, 18f, Color.White)
            drawRect(Color(0xFFFF0000), Offset(0f, 108f), Size(50f, 22f))
            drawRect(Color(0xFF00FF00), Offset(50f, 108f), Size(50f, 22f))
            drawRect(Color(0xFFFFFF00), Offset(100f, 108f), Size(50f, 22f))
            drawRect(Color(0xFF00FFFF), Offset(150f, 108f), Size(50f, 22f))
        }

        2 -> {
            val boot1 = path("M50,10 h34 v80 h28 a16,16 0 0 1 16,16 v10 h-78 z")
            val boot2 = path("M118,10 h34 v80 h28 a16,16 0 0 1 16,16 v10 h-78 z")
            drawPath(boot1, Ink)
            withTransform({ translate(-6f, 0f) }) { drawPath(boot2, Ink) }
            drawRect(Color(0xFFE53935), Offset(50f, 10f), Size(34f, 8f))
            drawRect(Color(0xFFE53935), Offset(112f, 10f), Size(34f, 8f))
            drawOval(Color(0xFF5A3B1C), Offset(10f, 118f), Size(180f, 12f))
        }

        3 -> {
            val body = path("M12,84 h176 v-18 l-30,-6 l-22,-24 h-66 l-26,26 l-32,4 z")
            val window = path("M78,42 h52 l18,20 h-86 z")
            drawPath(body, Color(0xFFE8DCC0))
            drawPath(body, Ink, style = Stroke(4f))
            drawPath(window, Color(0xFF9FD3FF))
            drawPath(window, Ink, style = Stroke(3f))
            drawCircle(Color(0xFF222222), 16f, Offset(52f, 90f))
            drawCircle(Color(0xFF222222), 16f, Offset(148f, 90f))
            drawCircle(Color(0xFFAAAAAA), 6f, Offset(52f, 90f))
            drawCircle(Color(0xFFAAAAAA), 6f, Offset(148f, 90f))
        }

        4 -> {
            val rail = Color(0xFF9AA6C9)
            drawCircle(Color(0xFFFFF6C4), 12f, Offset(168f, 22f))
            drawLine(rail, Offset(120f, 120f), Offset(140f, 20f), 4f)
            drawLine(rail, Offset(150f, 120f), Offset(140f, 20f), 4f)
            for (i in 0..4) {
                drawLine(rail, Offset(124f + i * 3f, 100f - i * 18f), Offset(146f - i * 1.5f, 100f - i * 18f), 3f)
            }
            drawPath(path("M118,30 a26,18 -30 0 1 44,-8"), Color(0xFFC9D2EE), style = Stroke(5f))
            val brown = Color(0xFF7A5A3A)
            drawOval(brown, Offset(44f, 76f), Size(52f, 40f))
            drawCircle(brown, 9f, Offset(92f, 82f))
            val sand = Color(0xFFD9C39A)
            drawPath(path("M100,82 q14,4 18,12"), sand, style = Stroke(3f))
            drawLine(sand, Offset(60f, 116f), Offset(60f, 124f), 3f)
            drawLine(sand, Offset(76f, 116f), Offset(76f, 124f), 3f)
            drawPath(path("M48,84 q22,-30 44,-6 z"), Color(0xFF5B8FD6))
            drawCircle(Color.White, 4f, Offset(48f, 84f))
        }
    }
}
