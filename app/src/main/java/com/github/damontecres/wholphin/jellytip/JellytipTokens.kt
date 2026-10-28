package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
class JellytipSizes(
    private val density: Float,
    private val fontScale: Float,
) {
    /** Physical px at 1080p (see [com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens]) as dp. */
    fun px(physicalPx: Float): Dp = (physicalPx / density).dp

    fun px(physicalPx: Int): Dp = px(physicalPx.toFloat())

    /** Physical px at 1080p as sp (the system font scale is still honoured). */
    fun sp(physicalPx: Float): TextUnit = (physicalPx / (density * fontScale)).sp

    fun sp(physicalPx: Int): TextUnit = sp(physicalPx.toFloat())
}

@Composable
fun rememberJellytipSizes(): JellytipSizes {
    val density = LocalDensity.current
    return remember(density) { JellytipSizes(density.density, density.fontScale) }
}
