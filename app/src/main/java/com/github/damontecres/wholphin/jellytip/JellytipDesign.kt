package com.github.damontecres.wholphin.jellytip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.components.DialogItemEntry
import com.github.damontecres.wholphin.ui.components.DialogPopupContent
import kotlinx.coroutines.delay

/*
 * Design polish (re-score 21-Nov, ~/ai/state/jellytip-tv-design-review-20261121.md): one focus treatment for buttons,
 * chips and rows (the cards already share JellytipFocus.scale/border/glow), a skeleton for loading, Jellytip dialogs and
 * a subtle enter motion for overlay cards. Design refs: dark canvas, one pastel, 200-300 ms eased motion, animations-off
 * respected (~/ai/plans/design-refs).
 */
object JellytipSurfaceFocus {
    /** The one pastel: focused buttons, chips and rows fill with it (no more pure-white fills). */
    val Pastel = Color(0xFFF4DCD0)

    /** Text on the pastel. */
    val Ink = Color(0xFF141414)

    /** Resting fill for a button / chip / row on the dark canvas. */
    val Rest = Color.White.copy(alpha = 0.08f)

    const val SCALE = 1.04f

    /** Rows and wide surfaces barely grow (a full-width row would collide with its neighbours). */
    const val ROW_SCALE = 1.01f

    @Composable
    fun colors(
        container: Color = Rest,
        content: Color = JellytipPalette.Vanilla,
    ) = ClickableSurfaceDefaults.colors(
        containerColor = container,
        contentColor = content,
        focusedContainerColor = Pastel,
        focusedContentColor = Ink,
        pressedContainerColor = Pastel,
        pressedContentColor = Ink,
    )

    /** Cream ring on focus (2 dp; the cards use 3 dp). */
    @Composable
    fun border(shape: Shape) = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, JellytipFocus.Cream), shape = shape))

    @Composable
    fun scale(row: Boolean = false) = ClickableSurfaceDefaults.scale(focusedScale = if (row) ROW_SCALE else SCALE)
}

/** System animations off (developer option / accessibility): no motion, no shimmer. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { JellytipSession.reducedMotion(context) }
}

/**
 * Subtle enter for overlay cards (kick-off, full time, catch me up, stream recovery): fades in and rises 16 dp over
 * 260 ms, eased. Nothing moves when animations are off. Keyed on [key] so a new card animates again.
 */
@Composable
fun Modifier.jellytipEnter(
    key: Any? = Unit,
    rise: Dp = 16.dp,
): Modifier {
    if (rememberReducedMotion()) return this
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * risePx
    }
}

/** The skeleton pulse: one animation per skeleton, read only while drawing (static when animations are off). */
@Composable
private fun rememberPulse(): () -> Float {
    if (rememberReducedMotion()) return { 0.10f }
    val t = rememberInfiniteTransition(label = "jellytip-skeleton")
    val v = t.animateFloat(0.06f, 0.14f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "jellytip-skeleton-a")
    return { v.value }
}

/** A placeholder block; [alpha] is read in the draw phase only. */
@Composable
fun JellytipSkeletonBlock(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    alpha: () -> Float = { 0.10f },
) {
    Spacer(
        modifier.size(width, height).drawBehind {
            drawRoundRect(Color.White.copy(alpha = alpha()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()))
        },
    )
}

/**
 * Loading state for a page of tiles: a title bar and [rows] rows of [perRow] tiles ([tileW] x [tileH]); replaces the plain
 * "Loading…" text; screen readers still hear "Loading".
 */
@Composable
fun JellytipSkeleton(
    tileW: Dp,
    tileH: Dp,
    modifier: Modifier = Modifier,
    rows: Int = 2,
    perRow: Int = 4,
    title: Boolean = true,
) {
    val pulse = rememberPulse()
    Column(modifier.semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(rows) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (title) JellytipSkeletonBlock(180.dp, 18.dp, radius = 6.dp, alpha = pulse)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(perRow) { JellytipSkeletonBlock(tileW, tileH, alpha = pulse) }
                }
            }
        }
    }
}

/** Loading state for a list of rows (Journeys films, Reminders, settings). */
@Composable
fun JellytipSkeletonList(
    modifier: Modifier = Modifier,
    lines: Int = 5,
    width: Dp = 640.dp,
    lineHeight: Dp = 44.dp,
) {
    val pulse = rememberPulse()
    Column(modifier.semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(lines) { i -> JellytipSkeletonBlock(width * (1f - i * 0.06f), lineHeight, radius = 8.dp, alpha = pulse) }
    }
}

/**
 * The hold-OK / pick menu, Jellytip-styled: same items, keys, long-press guard and wrap scrolling as the stock DialogPopup
 * (its content is reused), on the dark canvas with the pastel focus instead of the stock grey sheet.
 */
@Composable
fun JellytipDialog(
    showDialog: Boolean,
    title: String,
    dialogItems: List<DialogItemEntry>,
    onDismissRequest: () -> Unit,
    dismissOnClick: Boolean = true,
    waitToLoad: Boolean = true,
    properties: DialogProperties = DialogProperties(),
) {
    if (!showDialog) return
    var waiting by remember { mutableStateOf(waitToLoad) }
    if (waitToLoad) {
        LaunchedEffect(Unit) {
            // a long press that opened the menu must not also pick its first item (as DialogPopup)
            waiting = true
            delay(1000)
            waiting = false
        }
    }
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        // The app's own scheme (selected / error colours stay), with Jellytip surfaces and the pastel focus
        MaterialTheme(
            colorScheme =
                MaterialTheme.colorScheme.copy(
                    surface = Color(0xFF151515),
                    onSurface = JellytipPalette.Vanilla,
                    onSurfaceVariant = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                    inverseSurface = JellytipSurfaceFocus.Pastel,
                    inverseOnSurface = JellytipSurfaceFocus.Ink,
                    border = JellytipFocus.Cream,
                ),
            typography = MaterialTheme.typography,
        ) {
            DialogPopupContent(
                title = title,
                dialogItems = dialogItems,
                waiting = waiting,
                onDismissRequest = onDismissRequest,
                dismissOnClick = dismissOnClick,
                modifier =
                    Modifier
                        .onKeyEvent { e ->
                            // a released OK / Enter ends the long-press guard early (as DialogPopup)
                            val code = e.nativeKeyEvent.keyCode
                            if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP &&
                                (code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                            ) {
                                waiting = false
                            }
                            false
                        }
                        .width(480.dp)
                        .jellytipEnter(rise = 8.dp) // the whole sheet (and its shadow) rises in
                        .shadow(12.dp, RoundedCornerShape(16.dp))
                        .graphicsLayer {
                            clip = true
                            shape = RoundedCornerShape(16.dp)
                        }.drawBehind { drawRect(Color(0xFF151515)) }
                        .padding(PaddingValues(horizontal = 20.dp, vertical = 18.dp)),
            )
        }
    }
}
