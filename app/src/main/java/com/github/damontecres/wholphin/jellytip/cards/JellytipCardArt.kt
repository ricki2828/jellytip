package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.ui.logCoilError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.withTimeoutOrNull
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.cache.jellytipResolveBackdropIndex
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/** Landscape cards that should show titled imagery instead of a text caption underneath. */
fun jellytipTitledCard(
    item: BaseItem?,
    aspectRatio: Float,
): Boolean = item != null && item.imageUrlOverride == null && aspectRatio >= AspectRatios.FOUR_THREE

/**
 * What to draw for one card: a [base] image and, when the base carries no title of its own,
 * an optional clearlogo ([logoUrl]) or the plain title text.
 */
data class JellytipCardArt(
    val baseUrl: String?,
    val titledAlready: Boolean,
    val logoUrl: String?,
)

/**
 * Image priority (sizes are physical px of the card so the 2 GB device decodes no more than it shows):
 * 1. Thumb (title baked into landscape art; a Thumb the companion knows is textless gets the logo or title on top)
 * 2. episodes: the episode's own still + series logo, else series Thumb/backdrop
 * 3. backdrop + clearlogo, 4. backdrop + title text (caller draws it)
 */
fun selectJellytipCardArt(
    service: ImageUrlService,
    item: BaseItem,
    widthPx: Int,
    heightPx: Int,
    backdropIndex: Int? = null,
): JellytipCardArt {
    val d = item.data
    val tags = d.imageTags.orEmpty()
    val isEpisode = item.type == BaseItemKind.EPISODE
    val logoW = (widthPx * LOGO_WIDTH_FRACTION).toInt()
    val logoH = (heightPx * LOGO_HEIGHT_FRACTION).toInt()

    fun logo(
        id: java.util.UUID?,
        tag: String?,
    ): String? =
        if (id != null && tag != null) {
            service.getItemImageUrl(itemId = id, imageType = ImageType.LOGO, maxWidth = logoW, maxHeight = logoH, tag = tag)
        } else {
            null
        }

    fun fill(
        id: java.util.UUID,
        type: ImageType,
        tag: String?,
        index: Int? = null,
    ) = service.getItemImageUrl(
        itemId = id,
        imageType = type,
        fillWidth = widthPx,
        fillHeight = heightPx,
        tag = tag,
        imageIndex = index,
    )

    val seriesId = d.seriesId
    if (isEpisode && seriesId != null) {
        val seriesLogo = logo(d.parentLogoItemId ?: seriesId, d.parentLogoImageTag)
        if (ImageType.PRIMARY in tags && JellytipApi.shared?.isEpisodeStillUsable(item.id) != false) {
            return JellytipCardArt(fill(item.id, ImageType.PRIMARY, tags[ImageType.PRIMARY]), false, seriesLogo)
        }
        val thumbId = d.parentThumbItemId
        if (thumbId != null && d.parentThumbImageTag != null && JellytipApi.shared?.isThumbGood(thumbId) != false) {
            val untitled = JellytipThumbTitles.isUntitled(thumbId)
            return JellytipCardArt(fill(thumbId, ImageType.THUMB, d.parentThumbImageTag), !untitled, if (untitled) seriesLogo else null)
        }
        val backdropId = d.parentBackdropItemId
        val backdropTag = d.parentBackdropImageTags?.firstOrNull()
        if (backdropId != null && backdropTag != null) {
            val tags = d.parentBackdropImageTags.orEmpty()
            val idx = jellytipResolveBackdropIndex(tags, backdropId, backdropIndex)
            return JellytipCardArt(fill(backdropId, ImageType.BACKDROP, tags[idx], idx), false, seriesLogo)
        }
        return JellytipCardArt(null, false, seriesLogo)
    }

    val logoUrl = if (ImageType.LOGO in tags) logo(item.id, tags[ImageType.LOGO]) else null
    if (ImageType.THUMB in tags && JellytipApi.shared?.isThumbGood(item.id) != false) {
        val untitled = JellytipThumbTitles.isUntitled(item.id)
        return JellytipCardArt(fill(item.id, ImageType.THUMB, tags[ImageType.THUMB]), !untitled, if (untitled) logoUrl else null)
    }
    val backdropTag = d.backdropImageTags?.firstOrNull()
    return when {
        backdropTag != null -> {
            val tags = d.backdropImageTags.orEmpty()
            val idx = jellytipResolveBackdropIndex(tags, item.id, backdropIndex)
            JellytipCardArt(fill(item.id, ImageType.BACKDROP, tags[idx], idx), false, logoUrl)
        }
        ImageType.PRIMARY in tags -> JellytipCardArt(fill(item.id, ImageType.PRIMARY, tags[ImageType.PRIMARY]), false, logoUrl)
        else -> JellytipCardArt(null, false, logoUrl)
    }
}

private data class RankState(
    val index: Int?,
    val ready: Boolean,
)

private const val RANK_WAIT_MS = 300L
private const val LOGO_WIDTH_FRACTION = 0.4f
private const val LOGO_HEIGHT_FRACTION = 0.35f

/** Fills its parent (the card). */
@Composable
fun JellytipTitledImage(
    item: BaseItem,
    cardWidth: Dp,
    cardHeight: Dp,
    modifier: Modifier = Modifier,
    /** False (home specials): use only what the companion already answered, never ask it per tile. */
    fetchRanking: Boolean = true,
) {
    val service = LocalImageUrlService.current
    val density = LocalDensity.current
    val sizes = rememberJellytipSizes()
    val widthPx = remember(cardWidth, density) { with(density) { cardWidth.roundToPx() } }
    val heightPx = remember(cardHeight, density) { with(density) { cardHeight.roundToPx() } }
    // Photo-ranked backdrop: wait up to 300 ms for the companion before first paint, then settle on index 0 and upgrade if it arrives late
    val rankedId = item.data.seriesId?.takeIf { item.type == BaseItemKind.EPISODE } ?: item.id
    val initial = RankState(JellytipApi.shared?.peekBestBackdropIndex(rankedId), JellytipApi.shared == null || !fetchRanking)
    val ranking by produceState(initial, rankedId) {
        val api = JellytipApi.shared?.takeIf { fetchRanking } ?: return@produceState
        if (value.index != null) return@produceState
        val quick = withTimeoutOrNull(RANK_WAIT_MS) { api.bestBackdropIndex(rankedId) }
        value = RankState(quick, true)
        if (quick == null) api.bestBackdropIndex(rankedId)?.let { value = RankState(it, true) }
    }
    // Episode stills: which are usable (not black/blurry/non-photo) comes from the companion once per series
    val episodeArtReady by produceState(item.type != BaseItemKind.EPISODE, rankedId) {
        if (item.type == BaseItemKind.EPISODE && fetchRanking) JellytipApi.shared?.loadEpisodeArt(rankedId)
        value = true
    }
    val hasTitledArt = ImageType.THUMB in item.data.imageTags.orEmpty()
    val bestIndex = ranking.index
    // Snapshot read here (not inside remember) so a late "textless Thumb" answer recomputes the art
    val thumbUntitled =
        JellytipThumbTitles.isUntitled(item.id) || item.data.parentThumbItemId?.let { JellytipThumbTitles.isUntitled(it) } == true
    val art =
        remember(item, widthPx, heightPx, bestIndex, ranking.ready, episodeArtReady, thumbUntitled) {
            if (ranking.ready || bestIndex != null || hasTitledArt) {
                selectJellytipCardArt(service, item, widthPx, heightPx, bestIndex)
            } else {
                JellytipCardArt(null, false, null)
            }
        }
    var baseError by remember(art.baseUrl) { mutableStateOf(false) }
    var logoError by remember(art.logoUrl) { mutableStateOf(false) }
    val showLogo = !art.titledAlready && art.logoUrl != null && !logoError
    val showText = !art.titledAlready && !showLogo
    // Premium formats (4K/DV/HDR, ATMOS/DTS:X) from what the companion's payloads already carried; no request here
    val tech = JellytipTech.tileBadges(JellytipTech.of(item.id, item.data.seriesId?.takeIf { item.type == BaseItemKind.EPISODE }))
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF141414))) {
        if (art.baseUrl != null && !baseError) {
            AsyncImage(
                model = art.baseUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                onError = {
                    logCoilError(art.baseUrl, it.result)
                    baseError = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!art.titledAlready) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
            )
        }
        val pad = sizes.px(JellytipHomeTokens.START_PAD_PX)
        if (showLogo) {
            AsyncImage(
                model = art.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onError = {
                    logCoilError(art.logoUrl, it.result)
                    logoError = true
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = pad, bottom = pad)
                        .widthIn(max = cardWidth * LOGO_WIDTH_FRACTION)
                        .heightIn(max = cardHeight * LOGO_HEIGHT_FRACTION),
            )
        } else if (showText) {
            // Untitled art (textless Thumb, or a backdrop with no logo): small white title with a soft shadow over the
            // scrim. Badges share its row so the title wraps before them instead of running underneath.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = pad, end = if (tech.isEmpty()) pad else TECH_INSET, bottom = pad),
            ) {
                Text(
                    text = item.title ?: "",
                    color = Color.White,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 8f)),
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = sizes.sp(JellytipHomeTokens.TEXT_PX),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tech.isNotEmpty()) TechBadges(tech)
            }
        }
        if (!showText && tech.isNotEmpty()) {
            TechBadges(tech, Modifier.align(Alignment.BottomEnd).padding(end = TECH_INSET, bottom = TECH_INSET))
        }
    }
}

private val TECH_INSET = 6.dp

/** At most two tiny monochrome badges (video, audio): white at 80% on a subtle dark pill. */
@Composable
private fun TechBadges(
    badges: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        badges.take(2).forEach { b ->
            Text(
                text = b,
                color = Color.White.copy(alpha = 0.8f),
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                modifier =
                    Modifier
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
