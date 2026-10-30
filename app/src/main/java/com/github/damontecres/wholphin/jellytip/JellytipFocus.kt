package com.github.damontecres.wholphin.jellytip

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme

/**
 * One focus language for every Jellytip card: 1.08x scale, 3 dp off-white border, soft white glow,
 * and siblings in the same row dimmed to 70% so the focused card reads from across the room.
 */
object JellytipFocus {
    val Cream = Color(0xFFF2F2F0)
    const val SCALE = 1.08f
    const val DIM_ALPHA = 0.7f

    /** Wide cards zoom less: 1.08x on a 188 dp 16:9 tile grows ~7.5 dp per side into the 10 dp gap (30-Oct: crowding). */
    const val WIDE_SCALE = 1.04f
    val WIDE_CARD_WIDTH = 150.dp

    @Composable
    fun scale() = CardDefaults.scale(focusedScale = SCALE)

    @Composable
    fun scaleFor(width: androidx.compose.ui.unit.Dp) = CardDefaults.scale(focusedScale = if (width >= WIDE_CARD_WIDTH) WIDE_SCALE else SCALE)

    @Composable
    fun border(shape: Shape = MaterialTheme.shapes.medium) =
        CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Cream), shape = shape))

    @Composable
    fun glow() = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.45f), 12.dp))
}

/** True while focus is inside the enclosing card row (provided by [Modifier.trackRowFocus] users). */
val LocalJellytipRowFocused = compositionLocalOf { false }

/** Dims a card to 70% when its row has focus but the card itself does not. */
@Composable
fun Modifier.jellytipSiblingDim(focused: Boolean): Modifier {
    val rowFocused = LocalJellytipRowFocused.current
    val a by animateFloatAsState(if (rowFocused && !focused) JellytipFocus.DIM_ALPHA else 1f, tween(150), label = "jellytip-dim")
    return alpha(a)
}

/** Wraps a row: [content] receives a modifier that tracks row focus, and its cards see [LocalJellytipRowFocused]. */
@Composable
fun JellytipRowFocusScope(content: @Composable (Modifier) -> Unit) {
    var rowFocused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalJellytipRowFocused provides rowFocused) {
        content(Modifier.onFocusChanged { rowFocused = it.hasFocus })
    }
}

/**
 * Returning from a trailer/external activity resumes the same composition, so no one re-requests focus and it
 * falls to the nav drawer (which then opens). On resume, if nothing on this page holds focus, put it back on [header].
 */
@Composable
fun RestoreFocusOnResume(
    header: androidx.compose.ui.focus.FocusRequester,
    pageHasFocus: Boolean,
) {
    val hasFocus by androidx.compose.runtime.rememberUpdatedState(pageHasFocus)
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (!hasFocus) {
            try {
                header.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
        onPauseOrDispose { }
    }
}
