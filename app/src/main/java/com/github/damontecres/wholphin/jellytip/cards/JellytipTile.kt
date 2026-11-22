package com.github.damontecres.wholphin.jellytip.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.JellytipFocus
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.jellytipSiblingDim
import com.github.damontecres.wholphin.jellytip.specials.Caption
import com.github.damontecres.wholphin.jellytip.specials.SpecialCard
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.cards.WatchedIcon
import com.github.damontecres.wholphin.ui.logCoilError

/** Test tags on the badge nodes: the same tags on library and IPTV tiles, because both draw them through [JellytipTileBadges]. */
object JellytipTileTags {
    const val TICK = "jt-tile-tick"
    const val HEART = "jt-tile-heart"
    const val BAR = "jt-tile-progress"
    const val PILL = "jt-tile-corner-pill"
    const val INFO = "jt-tile-info"
}

/**
 * What a Jellytip tile shows, from plain values and no source: a library item and an IPTV item both become one of these.
 * [art] + [title] + [tech] are drawn by [JellytipTitledArt] (landscape) or as a bare poster (poster).
 */
data class JellytipTileModel(
    val title: String,
    val art: JellytipCardArt,
    /** Tile pills, best first ("4K DV", "ATMOS"): [JellytipTech.tileBadges] output. Landscape only. */
    val tech: List<String> = emptyList(),
    val played: Boolean = false,
    val favorite: Boolean = false,
    /** 0..100 exclusive: the progress bar; anything else = no bar. */
    val playPercent: Double = 0.0,
    /** The corner pill ("E5", an unplayed count). */
    val cornerText: String? = null,
    /** 2:3 poster tile (the art is a poster that carries its own title) instead of the 16:9 titled tile. */
    val poster: Boolean = false,
    /** Continue Watching / Next Up info line ("S2 E5 · 23 min left"); null = none. Landscape only. */
    val info: String? = null,
)

/**
 * The badges every Jellytip tile draws over its art: watched tick (top-end), corner pill (top-end, after the tick), heart
 * (top-start), progress bar (bottom-start). Extracted unchanged from BannerCard; [titled] = the Jellytip look (3 dp jelly bar).
 */
@Composable
fun BoxScope.JellytipTileBadges(
    played: Boolean,
    favorite: Boolean,
    playPercent: Double,
    cornerText: String?,
    titled: Boolean,
) {
    if (played || cornerText != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
        ) {
            if (played && (playPercent <= 0 || playPercent >= 100)) {
                WatchedIcon(Modifier.size(24.dp).testTag(JellytipTileTags.TICK))
            }
            if (cornerText != null) {
                Box(
                    modifier =
                        Modifier
                            .testTag(JellytipTileTags.PILL)
                            .background(
                                AppColors.TransparentBlack50,
                                shape = RoundedCornerShape(25),
                            ),
                ) {
                    Text(
                        text = cornerText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
    }
    if (favorite) {
        Text(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .testTag(JellytipTileTags.HEART),
            color = colorResource(android.R.color.holo_red_light),
            text = stringResource(R.string.fa_heart),
            fontSize = 16.sp,
            fontFamily = FontAwesome,
        )
    }
    if (playPercent > 0 && playPercent < 100) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .testTag(JellytipTileTags.BAR)
                    .background(
                        if (titled) JellytipPalette.Jelly else MaterialTheme.colorScheme.tertiary,
                    ).clip(RectangleShape)
                    .height(if (titled) 3.dp else Cards.playedPercentHeight)
                    .fillMaxWidth((playPercent / 100).toFloat()),
        )
    }
}

/**
 * One tile for every source: the library row tile's shell (tv Card, focus scale / 3 dp cream border / glow, sibling dim) and
 * art ([JellytipTitledArt]) and badges ([JellytipTileBadges]) fed by a plain [model]. Landscape tiles reproduce what
 * BannerCard draws for a Jellytip landscape library item; poster tiles reproduce PosterCard (SpecialCard + bare poster).
 * Callable with plain values: no view model, no CompositionLocal without a default (sibling dim reads LocalJellytipRowFocused, default false).
 */
@Composable
fun JellytipTile(
    model: JellytipTileModel,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    if (model.poster) {
        SpecialCard(width, height, onClick, onLongClick, modifier) {
            val url = model.art.baseUrl
            var failed by remember(url) { mutableStateOf(false) }
            if (url != null && !failed) {
                AsyncImage(
                    model = url,
                    contentDescription = model.title,
                    contentScale = ContentScale.Crop,
                    onError = {
                        logCoilError(url, it.result)
                        failed = true
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Caption(model.title, alpha = 0.9f, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            JellytipTileBadges(model.played, model.favorite, model.playPercent, model.cornerText, titled = true)
        }
        return
    }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Card(
        modifier = modifier.size(width, height).jellytipSiblingDim(focused),
        onClick = { currentOnClick() },
        onLongClick = { currentOnLongClick() },
        interactionSource = source,
        colors = CardDefaults.colors(),
        scale = JellytipFocus.scaleFor(width),
        border = JellytipFocus.border(),
        glow = JellytipFocus.glow(),
    ) {
        Box(Modifier.fillMaxSize()) {
            JellytipTitledArt(art = model.art, title = model.title, tech = model.tech, cardWidth = width, cardHeight = height, info = model.info)
            JellytipTileBadges(model.played, model.favorite, model.playPercent, model.cornerText, titled = true)
        }
    }
}
