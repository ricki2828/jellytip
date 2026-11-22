package com.github.damontecres.wholphin.jellytip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults

/**
 * One layout spec for every Jellytip page (sizing audit, 22-Nov-2026: ~/ai/state/jellytip-tv-design-audit-20261122.md).
 * The TV is 960 x 540 dp. Pages sit inside the nav drawer's content area, which starts 72 dp from the left edge and
 * ends 16 dp from the right, so the values here are "in page": [PageStart] 24 puts the left line at 96 dp on screen and
 * [PageEnd] 32 keeps text 48 dp (5%) from the right edge. Rows of tiles may bleed past the end; text and controls may not.
 */
object JellytipLayout {
    /** Left line of a page: its title, row titles and first tile all start here. */
    val PageStart = 24.dp

    /** A row's own inset (ItemRowTitle and the row's content padding both add 8 dp): a page made of rows starts at [RowPageStart]. */
    val RowInset = 8.dp
    val RowPageStart = PageStart - RowInset

    /** First line of a page sits below the 5% overscan (27 dp). */
    val PageTop = 28.dp
    val PageEnd = 32.dp
    val PageBottom = 28.dp

    /** Full-screen surfaces with no drawer (player overlays, dialogs): the 5% safe area itself. */
    val SafeH = 48.dp
    val SafeV = 28.dp

    /** Height the clock takes in the top-right corner (it sits at [SafeH] / [SafeV]): page content at the right edge starts this much lower. */
    val ClockClear = 28.dp

    val PageTitle = 26.sp
    val TitleGap = 16.dp
    val RowGap = 16.dp
    val TileGap = 12.dp

    /** Padding a row needs so a focused tile's growth and border are not cut: tiles up to 240 dp at 1.04x, and wider. */
    val FocusRoom = 8.dp
    val FocusRoomWide = 12.dp

    /** Type floor: nothing smaller, and body text at [Body] or more. */
    val MinText = 12.sp
    val Body = 14.sp

    /** Physical px at 1080p for [JellytipSizes.sp]: the floor (12 sp at the TV's density). */
    const val MIN_TEXT_PX = 24

    /** Lowest alpha for text on the dark canvas (AA at the floor size). */
    const val TEXT_ALPHA = 0.7f
}

/** [JellytipFocus] for tiles built on a clickable Surface instead of a Card: 1.08x under 150 dp wide, 1.04x from there (never the TV default 1.1x). */
@Composable
fun jellytipTileScale(width: Dp) =
    ClickableSurfaceDefaults.scale(focusedScale = if (width >= JellytipFocus.WIDE_CARD_WIDTH) JellytipFocus.WIDE_SCALE else JellytipFocus.SCALE)

/** The shared 3 dp cream focus border, for Surface tiles. */
@Composable
fun jellytipTileBorder(shape: Shape) = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, JellytipFocus.Cream), shape = shape))

/**
 * For a row that must line up with text above it: give the LazyRow `contentPadding(start = room)` so the first tile's focus
 * growth and border are not cut at the row's edge, and this modifier to pull the row back by the same amount.
 */
fun Modifier.jellytipFocusRoom(room: Dp = JellytipLayout.FocusRoom): Modifier = offset(x = -room)
