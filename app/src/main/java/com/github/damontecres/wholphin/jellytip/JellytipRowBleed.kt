package com.github.damontecres.wholphin.jellytip

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The page's side padding that horizontal rows should draw through (30-Oct, Ricki: rows cut off on the LHS). The nav
 * drawer pads every page by the rail's width; a LazyRow clips at its own bounds, so tiles scrolled left vanished at
 * x ≈ 72 dp instead of the screen edge. Pages that want edge-to-edge rows (Home, Movies, Shows) provide the padding
 * here; rows widen by it ([jellytipRowBleed]) and add it to their content padding ([jellytipRowPadding]), so the first
 * tile still lines up with the row title. Zero (default) = rows as before.
 */
data class JellytipRowBleed(
    val start: Dp,
    val end: Dp,
)

val LocalJellytipRowBleed = compositionLocalOf { JellytipRowBleed(0.dp, 0.dp) }

/** Widen a lazy row by the page's side padding on both sides (place before fillMaxWidth). */
@Composable
fun Modifier.jellytipRowBleed(): Modifier {
    val b = LocalJellytipRowBleed.current
    if (b.start == 0.dp && b.end == 0.dp) return this
    return layout { measurable, constraints ->
        val s = b.start.roundToPx()
        val e = b.end.roundToPx()
        val wide =
            if (constraints.hasBoundedWidth) {
                constraints.copy(minWidth = constraints.minWidth + s + e, maxWidth = constraints.maxWidth + s + e)
            } else {
                constraints
            }
        val p = measurable.measure(wide)
        layout((p.width - s - e).coerceAtLeast(0), p.height) { p.place(-s, 0) }
    }
}

/** A lazy row's content padding: [horizontal] plus the page's side padding the row bleeds through. */
@Composable
fun jellytipRowPadding(
    horizontal: Dp,
    vertical: Dp,
): PaddingValues {
    val b = LocalJellytipRowBleed.current
    return PaddingValues(start = horizontal + b.start, end = horizontal + b.end, top = vertical, bottom = vertical)
}

/**
 * A bleeding row's scroll rule: [base]'s rule applied to the visible part only (the row minus the side padding it
 * draws through), so a focused tile never comes to rest under the nav rail or at the screen edge.
 */
class JellytipBleedBringIntoViewSpec(
    private val base: androidx.compose.foundation.gestures.BringIntoViewSpec,
    private val startPx: Float,
    private val endPx: Float,
) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = base.calculateScrollDistance(offset - startPx, size, (containerSize - startPx - endPx).coerceAtLeast(size))
}
