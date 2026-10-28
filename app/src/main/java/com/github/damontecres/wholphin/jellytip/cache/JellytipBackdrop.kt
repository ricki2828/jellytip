package com.github.damontecres.wholphin.jellytip.cache

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.ImageUrlService
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

const val HERO_BACKDROP_MAX_WIDTH = 1920

/** Single Jellyfin backdrop capped at 1920 wide; episodes and seasons use their series' backdrop. */
fun jellytipBackdropUrl(
    imageUrlService: ImageUrlService,
    item: BaseItem,
): String? {
    // Episodes lead with their own still; series art is only the fallback when the episode has none.
    item.data.imageTags
        ?.get(ImageType.PRIMARY)
        ?.takeIf { item.type == BaseItemKind.EPISODE }
        ?.let { tag ->
            return imageUrlService.getItemImageUrl(
                itemId = item.id,
                imageType = ImageType.PRIMARY,
                maxWidth = HERO_BACKDROP_MAX_WIDTH,
                tag = tag,
            )
        }
    val seriesId = item.data.seriesId
    return if (seriesId != null && (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON)) {
        imageUrlService.getItemImageUrl(
            itemId = seriesId,
            imageType = ImageType.BACKDROP,
            maxWidth = HERO_BACKDROP_MAX_WIDTH,
            tag = item.data.parentBackdropImageTags?.firstOrNull(),
        )
    } else {
        val tag = item.data.backdropImageTags?.firstOrNull() ?: return null
        imageUrlService.getItemImageUrl(
            itemId = item.id,
            imageType = ImageType.BACKDROP,
            maxWidth = HERO_BACKDROP_MAX_WIDTH,
            tag = tag,
        )
    }
}
