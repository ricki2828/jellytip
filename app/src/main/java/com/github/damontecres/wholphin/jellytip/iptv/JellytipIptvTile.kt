package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.cards.JellytipCardArt
import com.github.damontecres.wholphin.jellytip.cards.JellytipTech
import com.github.damontecres.wholphin.jellytip.cards.JellytipTileModel
import com.github.damontecres.wholphin.ui.abbreviateNumber
import com.github.damontecres.wholphin.ui.formatEpisodeNumber

/*
 * IPTV item -> the source-neutral tile model (JellytipTile). Pure: no Compose, no stores (the My list state is passed in),
 * so JellytipTileModelTest covers every rule.
 */

/** Tile pills for an IPTV item: its free tokens ("4k", "hdr", "dv", "atmos"...) mapped through [JellytipTech.normalize]; unknown ones are dropped. */
fun IptvVodItem.tileTech(): List<String> =
    JellytipTech.tileBadges((tech + quality + listOfNotNull(audio)).flatMap(JellytipTech::normalize).toSet())

/** Watched, as the server sends it (`played`, or the progress block saying so). */
val IptvVodItem.tilePlayed: Boolean get() = played || progress?.played == true

/**
 * The progress bar as the library draws it: a percentage strictly between 0 and 100 for an item that is not played and has
 * a position and a length; 0.0 (no bar) otherwise. (No "> 30 s / < 90 %" rule here: that rule still decides what Continue Watching lists.)
 */
val IptvVodItem.tilePercent: Double
    get() {
        val p = progress ?: return 0.0
        if (tilePlayed || p.positionSecs <= 0 || p.durationSecs <= 0) return 0.0
        val pct = p.positionSecs / p.durationSecs * 100.0
        return if (pct > 0.0 && pct < 100.0) pct else 0.0
    }

/** The library's corner pill: "E5" for an episode tile, the unplayed count for a series; none for posters and films. */
fun IptvVodItem.tileCornerText(poster: Boolean): String? =
    when {
        poster -> null
        episodeNumber != null -> formatEpisodeNumber(episodeNumber)
        isSeries && (unplayedCount ?: 0) > 0 -> abbreviateNumber(unplayedCount ?: 0)
        else -> null
    }

/**
 * Art: a poster tile is the poster (it carries its own title). A landscape tile is the text-free backdrop when the server
 * sent one (or says the backdrop itself has no text) with the logo over it, exactly where a library tile draws its logo;
 * with no logo (or no way to know the art is text-free) the title is set in the art as a library tile without a logo does.
 */
fun IptvVodItem.tileArt(poster: Boolean): JellytipCardArt {
    if (poster) return JellytipCardArt(this.poster ?: backdrop, true, null)
    val textless = textlessBackdrop ?: backdrop?.takeIf { backdropTextless }
    return JellytipCardArt(textless ?: backdrop ?: this.poster, false, logo.takeIf { textless != null })
}

/** [favorite] = the person's My list state (the store can override what the server sent). */
fun IptvVodItem.toTileModel(
    poster: Boolean,
    favorite: Boolean = favourite,
): JellytipTileModel =
    JellytipTileModel(
        title = title,
        art = tileArt(poster),
        tech = if (poster) emptyList() else tileTech(),
        played = tilePlayed,
        favorite = favorite,
        playPercent = tilePercent,
        cornerText = tileCornerText(poster),
        poster = poster,
        info = if (poster) null else infoLine,
    )
