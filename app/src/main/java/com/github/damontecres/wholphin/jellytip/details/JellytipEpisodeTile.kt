package com.github.damontecres.wholphin.jellytip.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.jellytipSiblingDim
import com.github.damontecres.wholphin.jellytip.jellytipTileBorder
import com.github.damontecres.wholphin.jellytip.jellytipTileScale
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.cards.WatchedIcon
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/** Width and still height of an episode tile (16:9); the IPTV episode card is the same size. */
val JellytipEpisodeTileWidth = 240.dp
val JellytipEpisodeTileHeight = 135.dp

/**
 * The one episode tile: a 240 x 135 dp 16:9 still (watched tick, progress bar and a "Next" pill on it) with "E3 · Name"
 * and one overview line under it. Takes plain values, so a library episode or an IPTV one can feed it and a test can
 * draw it alone.
 *
 * @param image the still: a url or any Coil model; null (or a failed load) shows the dark placeholder with the number
 * @param progress 0..1, drawn as a bar only while strictly between 0 and 1
 * @param isNext draws the "Next" pill (the episode the series button plays)
 * @param sourceMark small quiet line under the title line ("Library", "IPTV", "IPTV · Failed 2 h ago"); null draws nothing
 */
@Composable
fun JellytipEpisodeTile(
    image: Any?,
    number: Int?,
    name: String?,
    overview: String?,
    played: Boolean,
    progress: Float,
    isNext: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    sourceMark: String? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    var failed by remember(image) { mutableStateOf(false) }
    val title = listOfNotNull(number?.let { "E$it" }, name?.takeIf { it.isNotBlank() }).joinToString(" · ")
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.width(JellytipEpisodeTileWidth),
    ) {
        // Focus (scale + 3 dp cream border) is the tile's own; the text below stays put
        var focusedSurface by remember { mutableStateOf(false) }
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF141414)),
            border = jellytipTileBorder(shape),
            scale = jellytipTileScale(JellytipEpisodeTileWidth),
            modifier =
                Modifier
                    .size(JellytipEpisodeTileWidth, JellytipEpisodeTileHeight)
                    .testTag("jellytip-episode-tile-${number ?: 0}")
                    .onFocusChanged { focusedSurface = it.isFocused }
                    .jellytipSiblingDim(focusedSurface),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (image != null && !failed) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { failed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = number?.let { "E$it" } ?: "",
                        color = Color.White.copy(alpha = 0.35f),
                        fontFamily = JellytipFontFamily,
                        fontSize = 24.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                // Soft floor so the tick and the bar read on any still
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))),
                )
                if (played || isNext) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    ) {
                        if (isNext) {
                            Box(Modifier.background(AppColors.TransparentBlack50, RoundedCornerShape(50))) {
                                Text(
                                    text = "Next",
                                    color = Color.White,
                                    fontFamily = JellytipFontFamily,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (played) WatchedIcon(Modifier.size(20.dp))
                    }
                }
                if (progress > 0f && progress < 1f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(JellytipPalette.Jelly),
                    )
                }
            }
        }
        // Under the still, 240 dp wide, line height 1.2x the font size on both lines
        val ink = MaterialTheme.colorScheme.onSurface
        Text(
            text = title,
            style = TextStyle(fontFamily = JellytipFontFamily, fontSize = 14.sp, lineHeight = 14.sp * 1.2f, fontWeight = FontWeight.Medium, textAlign = TextAlign.Start),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        sourceMark?.let {
            Text(
                text = it,
                style = TextStyle(fontFamily = JellytipFontFamily, fontSize = 12.sp, lineHeight = 12.sp * 1.2f),
                color = JellytipPalette.Jelly,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = overview?.replace('\n', ' ') ?: "",
            style = TextStyle(fontFamily = JellytipFontFamily, fontSize = 12.sp, lineHeight = 12.sp * 1.2f, textAlign = TextAlign.Start),
            color = ink.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
