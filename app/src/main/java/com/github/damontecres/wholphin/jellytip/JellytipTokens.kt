package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared design tokens for the Jellytip home and details pages. Values are in "design pixels" on a
 * 1920x1080 screen; [JellytipSizes] maps them to dp/sp for the real display so the layout looks the
 * same on any TV density.
 */
object JellytipTokens {
    /** Rest time before a muted trailer replaces the still (home hero and details). */
    const val TRAILER_DELAY_MS = 6_000L

    const val BODY_PX = 22f
    const val BADGE_PX = 19f
    const val TITLE_PX = 64f
    const val GAP_PX = 14f
    const val LOGO_W_PX = 380f
    const val LOGO_H_PX = 150f
    const val LEFT_PX = 96f
    const val BOTTOM_PX = 84f
    const val WIDTH_PX = 820f
}

@Immutable
class JellytipSizes(
    private val scale: Float,
    private val density: Float,
    private val fontScale: Float,
) {
    /** [designPx] at 1080p, as dp for this display. */
    fun px(designPx: Float): Dp = (designPx * scale / density).dp

    /** [designPx] at 1080p, as sp for this display (the system font scale is still honoured). */
    fun sp(designPx: Float): TextUnit = (designPx * scale / (density * fontScale)).sp
}

@Composable
fun rememberJellytipSizes(): JellytipSizes {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(density) {
        val height = context.resources.displayMetrics.heightPixels
        JellytipSizes(height / 1080f, density.density, density.fontScale)
    }
}
