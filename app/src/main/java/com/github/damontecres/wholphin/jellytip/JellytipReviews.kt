package com.github.damontecres.wholphin.jellytip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.ScrollableDialog
import com.github.damontecres.wholphin.ui.ifElse
import org.json.JSONObject
import java.util.Locale

data class JellytipReview(
    val id: String,
    val author: String,
    val own: Boolean,
    /** Out of 5, half steps; null when the reviewer gave no rating. */
    val rating: Double?,
    val likes: Long?,
    /** ~200 chars; empty for spoiler-flagged reviews and stars-only own ratings. */
    val excerpt: String,
    val text: String,
    val spoiler: Boolean,
    val source: String,
)

data class JellytipQuote(
    val text: String,
    val author: String,
    val source: String,
    /** Source mark id when the server sends one (`rt_fresh` / `rt_rotten` for the Critics Consensus). */
    val mark: String? = null,
) {
    companion object {
        fun parse(o: JSONObject): JellytipQuote? {
            val text = o.optString("text").takeIf { it.isNotBlank() && it != "null" } ?: return null
            return JellytipQuote(
                text,
                o.optString("author").takeIf { it != "null" }.orEmpty(),
                o.optString("source", "letterboxd"),
                o.optString("mark").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

/** Companion `/v1/items/{id}/reviews`: [source] is "letterboxd", "tmdb" or "none". */
data class JellytipReviews(
    val source: String,
    val quote: JellytipQuote?,
    val reviews: List<JellytipReview>,
) {
    companion object {
        fun parse(json: String): JellytipReviews {
            val root = JSONObject(json)
            fun JSONObject.str(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
            val reviews =
                root.optJSONArray("reviews")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        JellytipReview(
                            id = o.str("id") ?: i.toString(),
                            author = o.str("author") ?: "",
                            own = o.optBoolean("own", false),
                            rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { !it.isNaN() },
                            likes = if (o.isNull("likes")) null else o.optLong("likes"),
                            excerpt = o.str("excerpt") ?: "",
                            text = o.str("text") ?: "",
                            spoiler = o.optBoolean("spoiler", false),
                            source = o.str("source") ?: "letterboxd",
                        )
                    }
                }.orEmpty()
            return JellytipReviews(
                source = root.str("source") ?: "none",
                quote = root.optJSONObject("quote")?.let { JellytipQuote.parse(it) },
                reviews = reviews,
            )
        }
    }
}

/** "★★★★½" for a 0-5 rating in half steps. */
internal fun reviewStars(rating: Double): String {
    val whole = rating.toInt().coerceIn(0, 5)
    return "★".repeat(whole) + if (rating - whole >= 0.25) "½" else ""
}

private fun likesText(n: Long): String =
    when {
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> String.format(Locale.ROOT, "%.1fk", n / 1000.0)
        else -> n.toString()
    }

private val CARD_BG = Color(0xFF111111)

/**
 * Below-the-fold "Reviews" row: short pull-quote cards, Ricki's own first (warm accent), then the most-liked.
 * Selecting a card opens the full text. Renders nothing when there are no reviews.
 */
@Composable
fun JellytipReviewsRow(
    reviews: JellytipReviews,
    modifier: Modifier = Modifier,
) {
    if (reviews.reviews.isEmpty()) return
    var open by remember { mutableStateOf<JellytipReview?>(null) }
    val firstFocus = remember { FocusRequester() }
    var position by remember { mutableStateOf(0) }
    val sourceLabel =
        when {
            reviews.source == "tmdb" -> "TMDB"
            reviews.source == "letterboxd" -> "Letterboxd"
            isRtSource(reviews.source) -> "Rotten Tomatoes"
            else -> null
        }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        ItemRowTitle(if (sourceLabel != null) "Reviews · $sourceLabel" else "Reviews")
        JellytipRowFocusScope { rowFocusModifier ->
            LazyRow(
                state = rememberLazyListState(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(8.dp),
                modifier =
                    rowFocusModifier
                        .fillMaxWidth()
                        .focusRestorer(firstFocus),
            ) {
                itemsIndexed(reviews.reviews, key = { _, r -> r.id }) { index, review ->
                    JellytipReviewCard(
                        review = review,
                        onClick = {
                            position = index
                            if (review.text.isNotBlank()) open = review
                        },
                        modifier = Modifier.ifElse(index == position, Modifier.focusRequester(firstFocus)),
                    )
                }
            }
        }
    }
    open?.let { JellytipReviewDialog(it, onDismissRequest = { open = null }) }
}

@Composable
private fun JellytipReviewCard(
    review: JellytipReview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val accent = if (review.own) WARM else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CardDefaults.shape(shape),
        border = JellytipFocus.border(shape),
        scale = JellytipFocus.scale(),
        glow = JellytipFocus.glow(),
        colors = CardDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.size(width = 300.dp, height = 150.dp).jellytipSiblingDim(focused),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(if (review.own) WARM.copy(alpha = 0.10f) else CARD_BG, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (review.own) "${review.author} · you" else review.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                review.rating?.let {
                    Text(
                        text = "  " + reviewStars(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (review.own) WARM else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
            val body =
                when {
                    review.spoiler -> "Contains spoilers · select to read"
                    review.excerpt.isNotBlank() -> review.excerpt
                    review.own -> "Rated, no review"
                    else -> ""
                }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = if (review.spoiler || review.excerpt.isBlank()) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (review.spoiler) 0.55f else 0.85f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            review.likes?.takeIf { it > 0 }?.let {
                Text(
                    text = "♥ ${likesText(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Full review text; D-pad Up/Down scrolls (ScrollableDialog), Back closes. */
@Composable
private fun JellytipReviewDialog(
    review: JellytipReview,
    onDismissRequest: () -> Unit,
) {
    ScrollableDialog(
        onDismissRequest = onDismissRequest,
        width = 640.dp,
        maxHeight = 440.dp,
        itemSpacing = 8.dp,
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = review.author + if (review.own) " · your review" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (review.own) WARM else MaterialTheme.colorScheme.onSurface,
                )
                val meta =
                    listOfNotNull(
                        review.rating?.let { reviewStars(it) },
                        review.likes?.takeIf { it > 0 }?.let { "♥ ${likesText(it)}" },
                        when {
                            review.source == "tmdb" -> "TMDB"
                            isRtSource(review.source) -> "Rotten Tomatoes"
                            else -> "Letterboxd"
                        },
                    ).joinToString("   ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            Text(
                text = review.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            )
        }
    }
}
