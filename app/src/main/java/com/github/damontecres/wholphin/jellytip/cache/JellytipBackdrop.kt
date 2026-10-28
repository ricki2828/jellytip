package com.github.damontecres.wholphin.jellytip.cache

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipFocal
import com.github.damontecres.wholphin.services.ImageUrlService
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import kotlin.math.roundToInt

const val HERO_BACKDROP_MAX_WIDTH = 1920

private fun usesSeriesBackdrop(item: BaseItem) =
    item.data.seriesId != null && (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON)

/** Item whose backdrops the hero shows (and the companion ranks): the series for episodes and seasons. */
fun jellytipHeroItemId(item: BaseItem): UUID = item.data.seriesId?.takeIf { usesSeriesBackdrop(item) } ?: item.id

private fun backdropTags(item: BaseItem): List<String> =
    (if (usesSeriesBackdrop(item)) item.data.parentBackdropImageTags else item.data.backdropImageTags).orEmpty()

/**
 * Backdrop index to request for [item]: the companion's [index] mapped BY TAG onto the item's current backdrop tags,
 * and always within them (else 0), so we never ask Jellyfin for an index the item no longer has.
 */
fun jellytipResolveBackdropIndex(
    currentTags: List<String>,
    itemId: UUID,
    index: Int?,
): Int {
    if (currentTags.isEmpty() || index == null) return 0
    val scored = JellytipApi.shared?.peekInfo(itemId)?.tags?.get(index)
    return when {
        scored != null -> currentTags.indexOf(scored).takeIf { it >= 0 } ?: 0
        index in currentTags.indices -> index
        else -> 0
    }
}

/** True when the home card draws a backdrop (not a good baked-in-title Thumb), see selectJellytipCardArt. */
private fun cardDrawsBackdrop(item: BaseItem): Boolean {
    val api = JellytipApi.shared
    return if (item.type == BaseItemKind.EPISODE && item.data.seriesId != null) {
        val thumb = item.data.parentThumbItemId
        val titled = item.data.parentThumbImageTag != null && thumb != null && api?.isThumbGood(thumb) != false
        !titled && (ImageType.PRIMARY !in item.data.imageTags.orEmpty() || api?.isEpisodeStillUsable(item.id) == false)
    } else {
        ImageType.THUMB !in item.data.imageTags.orEmpty() || api?.isThumbGood(item.id) == false
    }
}

/** A picked backdrop: its [url] and where the subject sits in it ([focal], null when the companion has no data). */
data class ImagePick(
    val url: String?,
    val focal: JellytipFocal?,
)

/**
 * Which backdrop each screen shows. Home hero = best photo, details = the next best, and neither may look like the tile
 * (companion perceptual-hash rule). Until the companion answers we still avoid the backdrop the card is drawing and,
 * for details, the one the hero uses. Call [load] once per item to fetch the answer.
 */
object JellytipImagePick {
    suspend fun load(item: BaseItem) {
        JellytipApi.shared?.backdropInfo(jellytipHeroItemId(item))
    }

    fun forHome(
        service: ImageUrlService,
        item: BaseItem,
        heroIndex: Int? = JellytipApi.shared?.peekHeroIndex(jellytipHeroItemId(item)),
    ): ImagePick = pick(service, item, heroIndex, details = false)

    fun forDetails(
        service: ImageUrlService,
        item: BaseItem,
        detailsIndex: Int? = JellytipApi.shared?.peekDetailsIndex(jellytipHeroItemId(item)),
    ): ImagePick = pick(service, item, detailsIndex, details = true)

    /** Backdrop index for the hero ([details] false) or the details page. */
    fun index(
        item: BaseItem,
        companion: Int?,
        details: Boolean,
    ): Int {
        val count = backdropTags(item).size
        if (count <= 1) return 0
        val id = jellytipHeroItemId(item)
        val card =
            if (cardDrawsBackdrop(item)) jellytipResolveBackdropIndex(backdropTags(item), id, JellytipApi.shared?.peekBestBackdropIndex(id)) else -1
        val wanted = companion?.let { jellytipResolveBackdropIndex(backdropTags(item), id, it) }?.takeIf { it != card }
        if (wanted != null) return wanted
        // No (usable) companion answer: first index that is not the card's, and for details not the hero's fallback either
        val avoid = mutableSetOf(card)
        if (details) {
            val home = JellytipApi.shared?.peekHeroIndex(id)?.takeIf { it in 0 until count && it != card }
            avoid += home ?: (0 until count).first { it != card }
        }
        return (0 until count).firstOrNull { it !in avoid } ?: 0
    }

    private fun pick(
        service: ImageUrlService,
        item: BaseItem,
        companion: Int?,
        details: Boolean,
    ): ImagePick {
        val tags = backdropTags(item)
        if (tags.isEmpty()) return ImagePick(null, null)
        val id = jellytipHeroItemId(item)
        val i = index(item, companion, details)
        val url =
            service.getItemImageUrl(
                itemId = id,
                imageType = ImageType.BACKDROP,
                maxWidth = HERO_BACKDROP_MAX_WIDTH,
                tag = tags[i],
                imageIndex = i,
            )
        return ImagePick(url, JellytipApi.shared?.peekInfo(id)?.focal?.get(i))
    }
}

/** Hero backdrop URL for the home screen (prefetcher and snapshot use this). */
fun jellytipBackdropUrl(
    imageUrlService: ImageUrlService,
    item: BaseItem,
    heroIndex: Int? = JellytipApi.shared?.peekHeroIndex(jellytipHeroItemId(item)),
): String? = JellytipImagePick.forHome(imageUrlService, item, heroIndex).url

/** Share of the container height the focal point is placed at: upper third, so heads are never cropped at the top edge. */
const val FOCAL_TARGET_Y = 0.40f

/**
 * Crop alignment keeping [focal] in frame: horizontally proportional (like a bias), vertically placing the focal point
 * at [FOCAL_TARGET_Y] of the container. With no focal data, aligns towards the top (heads sit in the upper half).
 */
fun focalAlignment(focal: JellytipFocal?): Alignment =
    if (focal == null) {
        androidx.compose.ui.BiasAlignment(0f, -0.4f)
    } else {
        Alignment { size: IntSize, space: IntSize, _: LayoutDirection ->
            val x = (focal.x * (space.width - size.width)).roundToInt().coerceIn(minOf(space.width - size.width, 0), 0)
            val lowest = minOf(space.height - size.height, 0)
            val y =
                if (focal.y < FOCAL_TOP_ZONE) {
                    0 // subject in the top quarter: show the top of the frame rather than crop into it
                } else {
                    // Focal point at the target height, but never hide the head: keep headroom above it visible
                    val headroom = if (focal.face) FACE_HEADROOM else SALIENCY_HEADROOM
                    val desired = (FOCAL_TARGET_Y * space.height - focal.y * size.height).roundToInt()
                    val limit = -(maxOf(focal.y - headroom, 0f) * size.height).roundToInt()
                    desired.coerceAtLeast(limit).coerceIn(lowest, 0)
                }
            IntOffset(x, y)
        }
    }

private const val FOCAL_TOP_ZONE = 0.25f
private const val FACE_HEADROOM = 0.14f // of image height: a head extends about this far above the face centre
private const val SALIENCY_HEADROOM = 0.2f

/** Ken Burns zoom origin at the focal point's on-screen position, so the zoom never pushes the subject out of frame. */
fun focalOrigin(focal: JellytipFocal?): TransformOrigin =
    if (focal == null) TransformOrigin(0.5f, 0.3f) else TransformOrigin(focal.x.coerceIn(0.2f, 0.8f), FOCAL_TARGET_Y)
