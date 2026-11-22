package com.github.damontecres.wholphin.jellytip.askchat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile
import com.github.damontecres.wholphin.jellytip.specials.PosterCard
import com.github.damontecres.wholphin.jellytip.specials.SpecialTokens

/**
 * THE one call site for a chat result tile (t5m's source-neutral tile will replace both branches with one tile that takes an item plus
 * onClick / onLongClick). 100 x 150 posters, the tiles' own focus treatment; no badges, source labels, "IPTV" text or dots of our own
 * (decided: the source is not shown on tiles). A library title is a PosterCard, a TV title an IptvVodTile in poster form.
 */
@Composable
internal fun ChatCard(
    placed: AskChat.Placed<BaseItem>,
    onOpenLibrary: (BaseItem) -> Unit,
    onOpenTv: (ChatTitle.Iptv) -> Unit,
    onLongClickLibrary: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = placed.item
    val card = placed.card
    if (item != null) {
        PosterCard(item, onClick = { onOpenLibrary(item) }, onLongClick = { onLongClickLibrary(item) }, modifier = modifier)
    } else if (card is ChatTitle.Iptv) {
        IptvVodTile(
            item = card.toVodItem(),
            width = SpecialTokens.POSTER_W,
            height = SpecialTokens.POSTER_H,
            poster = true,
            onClick = { onOpenTv(card) },
            modifier = modifier,
        )
    }
}

/** The IPTV tile's own item type, from a chat result's fields (the /v1/search IPTV entry). */
internal fun ChatTitle.Iptv.toVodItem(): IptvVodItem =
    IptvVodItem(
        id = iptvId,
        kind = if (kind == "film") "movie" else "series",
        tmdbId = tmdbId,
        title = title.orEmpty(),
        year = year,
        quality = quality,
        poster = poster,
        backdrop = backdrop,
        overview = "",
        rating = rating,
        providerName = null,
        progress = null,
        tech = tech,
        audio = audio,
        libraryId = libraryId,
        sources = sources,
    )
