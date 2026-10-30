package com.github.damontecres.wholphin.jellytip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/** One pull-quote for the details page: [rating] in stars when known. */
data class JellytipPullQuote(
    val text: String,
    val author: String,
    val rating: Double?,
    val source: String,
    val mark: String? = null,
)

private const val QUOTE_MAX_CHARS = 220

/**
 * Which review to show: the user's own review if it has text, else the server's chosen quote, else the first
 * non-spoiler excerpt (trimmed at a word boundary). Null when there is none.
 */
fun jellytipPullQuote(reviews: JellytipReviews?): JellytipPullQuote? {
    reviews ?: return null
    reviews.reviews
        .firstOrNull { it.own && !it.spoiler && (it.text.isNotBlank() || it.excerpt.isNotBlank()) }
        ?.let { return JellytipPullQuote(trimAtWord(it.text.ifBlank { it.excerpt }), it.author, it.rating, it.source) }
    reviews.quote?.let { q ->
        val rating = reviews.reviews.firstOrNull { it.author == q.author }?.rating
        return JellytipPullQuote(trimAtWord(q.text), q.author, rating, q.source, q.mark)
    }
    return reviews.reviews
        .firstOrNull { !it.spoiler && it.excerpt.isNotBlank() }
        ?.let { JellytipPullQuote(trimAtWord(it.excerpt), it.author, it.rating, it.source) }
}

private fun trimAtWord(text: String): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.length <= QUOTE_MAX_CHARS) return clean
    val cut = clean.lastIndexOf(' ', QUOTE_MAX_CHARS).takeIf { it > QUOTE_MAX_CHARS / 2 } ?: QUOTE_MAX_CHARS
    return clean.substring(0, cut).trimEnd(',', ';', ':', '-', ' ') + "…"
}

/**
 * The details page's bottom-right pull-quote, balancing the bottom-left info block: up to 3 lines of small italic text,
 * stars in jelly red, "— author on Letterboxd", over a soft corner vignette. Fades in once when the reviews (loaded with
 * the page) arrive, never on focus moves; not focusable. [alpha] follows the header (hidden below the fold).
 */
@Composable
fun BoxScope.JellytipDetailsPullQuote(
    reviews: JellytipReviews?,
    alpha: () -> Float,
) {
    val quote = remember(reviews) { jellytipPullQuote(reviews) } ?: return
    val sizes = rememberJellytipSizes()
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(600)) }
    val shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 8f)
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { this.alpha = alpha() * fade.value },
    ) {
        // Soft vignette in the bottom-right corner so the quote reads over any still
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to JellytipPalette.Night.copy(alpha = 0.7f),
                        0.5f to JellytipPalette.Night.copy(alpha = 0.3f),
                        1f to Color.Transparent,
                        center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                        radius = 1100f,
                    ),
                ),
        )
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = sizes.px(JellytipHomeTokens.DETAILS_LEFT_PX), bottom = sizes.px(JellytipHomeTokens.DETAILS_BOTTOM_PX))
                    .width(sizes.px(QUOTE_WIDTH_PX)),
        ) {
            Text(
                text = "“${quote.text}”",
                color = JellytipPalette.Vanilla.copy(alpha = 0.82f),
                fontFamily = JellytipFontFamily,
                fontStyle = FontStyle.Italic,
                fontSize = sizes.sp(JellytipHomeTokens.QUOTE_PX),
                textAlign = TextAlign.End,
                style = TextStyle(shadow = shadow),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            MarkText(
                text =
                    androidx.compose.ui.text.buildAnnotatedString {
                        quote.rating?.let {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = JellytipPalette.Jelly))
                            append(reviewStars(it))
                            pop()
                            append("  ")
                        }
                        appendQuoteSource(quote.source, quote.author, quote.mark)
                    },
                color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                fontSize = sizes.sp(JellytipHomeTokens.CARD_SUBTITLE_PX),
                style = TextStyle(shadow = shadow, fontFamily = JellytipFontFamily),
            )
        }
    }
}

/** Width of the quote block at 1080p: clear of the 820 px info block on the left. */
private const val QUOTE_WIDTH_PX = 620
