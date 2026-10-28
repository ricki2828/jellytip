package com.github.damontecres.wholphin.jellytip.cache

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.services.ImageUrlService
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

const val HERO_BACKDROP_MAX_WIDTH = 1920

private fun usesSeriesBackdrop(item: BaseItem) =
    item.data.seriesId != null && (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON)

/** Item whose backdrops the hero shows (and the companion ranks): the series for episodes and seasons. */
fun jellytipHeroItemId(item: BaseItem): UUID = item.data.seriesId?.takeIf { usesSeriesBackdrop(item) } ?: item.id

private fun backdropTags(item: BaseItem): List<String> =
    (if (usesSeriesBackdrop(item)) item.data.parentBackdropImageTags else item.data.backdropImageTags).orEmpty()

/** True when the home card draws a backdrop (not a baked-in-title Thumb), see selectJellytipCardArt. */
private fun cardDrawsBackdrop(item: BaseItem): Boolean =
    if (item.type == BaseItemKind.EPISODE && item.data.seriesId != null) {
        item.data.parentThumbImageTag == null && ImageType.PRIMARY !in item.data.imageTags.orEmpty()
    } else {
        ImageType.THUMB !in item.data.imageTags.orEmpty()
    }

/**
 * Backdrop index the hero draws. The companion's [heroIndex] (a photo that does not look like the card art) wins;
 * until it answers we still never pick the backdrop the card is showing, so hero and tile are never the same image.
 */
fun jellytipHeroIndex(
    item: BaseItem,
    heroIndex: Int?,
): Int {
    val count = backdropTags(item).size
    if (count <= 1) return 0
    val cardIndex =
        if (cardDrawsBackdrop(item)) {
            (JellytipApi.shared?.peekBestBackdropIndex(jellytipHeroItemId(item)) ?: 0).coerceIn(0, count - 1)
        } else {
            -1
        }
    val wanted = heroIndex?.takeIf { it in 0 until count }
    return when {
        wanted != null && wanted != cardIndex -> wanted
        cardIndex == 0 -> 1
        else -> 0
    }
}

/**
 * Single Jellyfin backdrop capped at 1920 wide; episodes and seasons use their series' backdrop.
 * [heroIndex] defaults to the companion answer if already cached.
 */
fun jellytipBackdropUrl(
    imageUrlService: ImageUrlService,
    item: BaseItem,
    heroIndex: Int? = JellytipApi.shared?.peekHeroIndex(jellytipHeroItemId(item)),
): String? {
    val tags = backdropTags(item)
    if (tags.isEmpty()) return null
    val index = jellytipHeroIndex(item, heroIndex)
    return imageUrlService.getItemImageUrl(
        itemId = jellytipHeroItemId(item),
        imageType = ImageType.BACKDROP,
        maxWidth = HERO_BACKDROP_MAX_WIDTH,
        tag = tags[index],
        imageIndex = index,
    )
}
