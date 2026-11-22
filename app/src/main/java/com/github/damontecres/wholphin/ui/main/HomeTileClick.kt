package com.github.damontecres.wholphin.ui.main

import org.jellyfin.sdk.model.api.BaseItemKind

/** What OK does on a tile of a Home-style row. */
enum class HomeTileAction {
    /** Play (resume) the item through the playback navigation. */
    PLAY,

    /** Open the item's page (`BaseItem.destination()`). */
    OPEN,
}

/** Item types that play on OK in a Continue Watching / Next Up row: an episode or a film (a series-grouped row shows SERIES tiles, which open). */
private fun isPlayableResumeType(type: BaseItemKind?): Boolean =
    type == BaseItemKind.EPISODE ||
        type == BaseItemKind.MOVIE ||
        type == BaseItemKind.VIDEO ||
        type == BaseItemKind.MUSIC_VIDEO

/**
 * OK on a Continue Watching / Next Up tile: an episode or a film plays (its own resume position), whatever the "click to play"
 * setting says. Anything else keeps what it did before: "play" only when "click to play" is on in the home settings, "open" otherwise.
 * Tiles in any other row always open.
 *
 * @param inContinueOrNextUpRow the tile's row is Continue Watching, Next Up or the combined resume row
 * @param type the tile's item type (a series-grouped row shows SERIES tiles, which open)
 * @param clickToPlay the existing "click to play" home preference
 */
fun homeTileAction(
    inContinueOrNextUpRow: Boolean,
    type: BaseItemKind?,
    clickToPlay: Boolean,
): HomeTileAction =
    if (inContinueOrNextUpRow && (isPlayableResumeType(type) || clickToPlay)) {
        HomeTileAction.PLAY
    } else {
        HomeTileAction.OPEN
    }

/** Hold OK on a Continue Watching / Next Up episode or film tile gets an extra first menu item, "Details" (the item's page), instead of the stock "Go To". */
fun homeTileHasDetailsItem(
    inContinueOrNextUpRow: Boolean,
    type: BaseItemKind?,
): Boolean = inContinueOrNextUpRow && isPlayableResumeType(type)
