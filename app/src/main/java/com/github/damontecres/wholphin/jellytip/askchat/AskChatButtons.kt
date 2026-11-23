package com.github.damontecres.wholphin.jellytip.askchat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/* Chat's chip and button (moved here 23-Nov when the old Ask page was deleted: Search and Chat are one box now). */

@Composable
internal fun AskChipButton(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (on) JellytipPalette.Jelly.copy(alpha = 0.35f) else JellytipPalette.Vanilla.copy(alpha = 0.12f),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        modifier = modifier,
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
    }
}

@Composable
internal fun AskButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) JellytipPalette.Jelly else JellytipPalette.Vanilla.copy(alpha = 0.12f),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = if (primary) Color.White else JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        modifier = modifier,
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(label, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}
