package com.github.damontecres.wholphin.jellytip

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

/**
 * Keeps a details page glued to its hero view while focus is in the header (left/right along the buttons must never
 * scroll or reveal the sections below); returning to the header from a section scrolls back to the exact top.
 */
@Composable
fun HeroScrollLock(
    listState: LazyListState,
    headerFocused: Boolean,
) {
    LaunchedEffect(headerFocused) {
        if (headerFocused) listState.animateScrollToItem(0)
    }
    LaunchedEffect(headerFocused, listState) {
        if (headerFocused) {
            snapshotFlow { listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0 }
                .distinctUntilChanged()
                .collect { moved -> if (moved) listState.scrollToItem(0) }
        }
    }
}

/** Small off-white down chevron at the bottom edge: "there is more below". Static when animations are off. */
@Composable
fun BoxScopeDownHint(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animate =
        remember {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
    val bob =
        if (animate) {
            val t = rememberInfiniteTransition(label = "jellytip-hint")
            val v by t.animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bob",
            )
            v
        } else {
            0f
        }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(400)), exit = fadeOut(tween(200)), modifier = modifier) {
        Box(Modifier.padding(bottom = 10.dp)) {
            Canvas(
                Modifier
                    .size(width = 28.dp, height = 14.dp)
                    .graphicsLayer { translationY = bob * 6.dp.toPx() },
            ) {
                val w = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val c = JellytipFocus.Cream.copy(alpha = 0.6f)
                val mid = Offset(size.width / 2f, size.height)
                drawLine(c, Offset(0f, 0f), mid, w.width, StrokeCap.Round)
                drawLine(c, mid, Offset(size.width, 0f), w.width, StrokeCap.Round)
            }
        }
    }
}
