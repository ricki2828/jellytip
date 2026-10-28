package com.github.damontecres.wholphin.jellytip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small source marks for the score line (IMDb, Rotten Tomatoes, Metacritic, Trakt, Letterboxd), drawn as vectors on a Canvas and
 * embedded in the text as inline content so they scale with the text. Simplified recreations, one visual weight (about 0.8 em).
 */
internal const val LB_MARK_ID = "lb"
private const val H = 0.8f // em: common height of every mark

private val IMDB = Color(0xFFF5C518)
private val RT_RED = Color(0xFFFA320A)
private val RT_GREEN = Color(0xFF0AC855)
private val RT_STEM = Color(0xFF00912D)
private val POPCORN = Color(0xFFFFE08A)
private val TRAKT = Color(0xFFED1C24)

internal fun mcId(score: Int) = "mc_${score.coerceIn(0, 100)}"

private fun mark(
    widthEm: Float,
    draw: @Composable (Modifier) -> Unit,
) = InlineTextContent(
    Placeholder(widthEm.em, H.em, PlaceholderVerticalAlign.TextCenter),
) { draw(Modifier.fillMaxSize()) }

private fun DrawScope.label(
    tm: TextMeasurer,
    text: String,
    color: Color,
    fontPx: Float,
    weight: FontWeight = FontWeight.Black,
) {
    val r = tm.measure(AnnotatedString(text), TextStyle(color = color, fontSize = fontPx.toSp(), fontWeight = weight))
    drawText(r, topLeft = Offset((size.width - r.size.width) / 2f, (size.height - r.size.height) / 2f))
}

private fun DrawScope.tomato(fresh: Boolean) {
    val d = size.height
    if (fresh) {
        drawCircle(RT_RED, radius = d * 0.44f, center = Offset(size.width / 2f, d * 0.58f))
        val leaf =
            Path().apply {
                moveTo(size.width / 2f, d * 0.30f)
                lineTo(size.width / 2f - d * 0.20f, d * 0.12f)
                lineTo(size.width / 2f - d * 0.04f, d * 0.20f)
                lineTo(size.width / 2f, d * 0.02f)
                lineTo(size.width / 2f + d * 0.06f, d * 0.20f)
                lineTo(size.width / 2f + d * 0.22f, d * 0.10f)
                close()
            }
        drawPath(leaf, RT_STEM)
    } else {
        // Rotten: green splat, a blob with drips
        val c = Offset(size.width / 2f, d * 0.52f)
        drawCircle(RT_GREEN, radius = d * 0.30f, center = c)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0 + 12.0)
            val len = if (i % 2 == 0) 0.40f else 0.33f
            drawCircle(RT_GREEN, radius = d * 0.13f, center = Offset(c.x + cos(a).toFloat() * d * len, c.y + sin(a).toFloat() * d * len))
        }
    }
}

private fun DrawScope.popcorn(upright: Boolean) {
    val w = size.width
    val h = size.height
    fun bucket() {
        val p =
            Path().apply {
                moveTo(w * 0.14f, h * 0.38f)
                lineTo(w * 0.86f, h * 0.38f)
                lineTo(w * 0.74f, h * 0.98f)
                lineTo(w * 0.26f, h * 0.98f)
                close()
            }
        drawPath(p, RT_RED)
        drawRect(Color.White, Offset(w * 0.44f, h * 0.38f), Size(w * 0.12f, h * 0.60f))
        listOf(0.26f to 0.30f, 0.46f to 0.22f, 0.66f to 0.30f, 0.36f to 0.36f, 0.58f to 0.36f).forEach { (x, y) ->
            drawCircle(POPCORN, radius = h * 0.15f, center = Offset(w * x, h * y))
        }
    }
    if (upright) bucket() else rotate(-32f, pivot = Offset(w * 0.5f, h * 0.9f)) { bucket() }
}

private fun DrawScope.trakt() {
    val d = size.height
    drawCircle(TRAKT, radius = d / 2f, center = Offset(size.width / 2f, d / 2f))
    val check =
        Path().apply {
            moveTo(size.width / 2f - d * 0.22f, d * 0.52f)
            lineTo(size.width / 2f - d * 0.05f, d * 0.68f)
            lineTo(size.width / 2f + d * 0.25f, d * 0.34f)
        }
    drawPath(check, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = d * 0.13f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

private fun DrawScope.letterboxd() {
    val d = size.height
    val r = d / 2f
    listOf(Color(0xFFFF8000), Color(0xFF00E054), Color(0xFF40BCF4)).forEachIndexed { i, c ->
        drawCircle(c, radius = r * 0.88f, center = Offset(r + i * d * 0.56f, r))
    }
}

private fun metacriticColor(score: Int) =
    when {
        score >= 61 -> Color(0xFF66CC33)
        score >= 40 -> Color(0xFFFFCC33)
        else -> Color(0xFFFF0000)
    }

@Composable
private fun MetacriticBox(
    modifier: Modifier,
    score: Int,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        drawRoundRect(metacriticColor(score), cornerRadius = CornerRadius(size.height * 0.16f))
        label(tm, score.toString(), Color.Black, size.height * 0.62f)
    }
}

/** All inline marks, keyed by id; pass as `inlineContent` to any Text showing a score line. */
internal val LetterboxdInline: Map<String, InlineTextContent> =
    buildMap {
        put(LB_MARK_ID, mark(H * 2.0f) { m -> Canvas(m) { letterboxd() } })
        put("imdb", mark(H * 2.15f) { m ->
            val tm = rememberTextMeasurer()
            Canvas(m) {
                drawRoundRect(IMDB, cornerRadius = CornerRadius(size.height * 0.2f))
                label(tm, "IMDb", Color.Black, size.height * 0.56f)
            }
        })
        put("rt_fresh", mark(H) { m -> Canvas(m) { tomato(true) } })
        put("rt_rotten", mark(H) { m -> Canvas(m) { tomato(false) } })
        put("aud_up", mark(H * 0.9f) { m -> Canvas(m) { popcorn(true) } })
        put("aud_tip", mark(H * 0.9f) { m -> Canvas(m) { popcorn(false) } })
        put("trakt", mark(H) { m -> Canvas(m) { trakt() } })
        for (n in 0..100) put(mcId(n), mark(H) { m -> MetacriticBox(m, n) })
    }

internal fun AnnotatedString.Builder.appendLbMark() {
    appendInlineContent(LB_MARK_ID, "LB")
    append(" ")
}

internal fun AnnotatedString.Builder.appendMark(
    id: String,
    alt: String,
) {
    appendInlineContent(id, alt)
    append(" ")
}
