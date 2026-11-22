package com.github.damontecres.wholphin.ui.main

import org.jellyfin.sdk.model.api.BaseItemKind

/** What OK does on a tile of a Home-style row. */
enum class HomeTileAction {
    /** Play (resume) the item through the playback navigation. */
    PLAY,

    /** Open the item's page (`BaseItem.destination()`). */
    OPEN,
}

/**
 * OK on a Continue Watching / Next Up tile: an EPISODE plays (its own resume position); a film (or anything else) keeps what it did
 * before, which is "play" only when the person turned on "click to play" in the home settings and "open" otherwise.
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
    if (inContinueOrNextUpRow && (type == BaseItemKind.EPISODE || clickToPlay)) {
        HomeTileAction.PLAY
    } else {
        HomeTileAction.OPEN
    }

/** Hold OK on a Continue Watching / Next Up episode tile gets an extra first menu item, "Details" (the series page at that episode). */
fun homeTileHasDetailsItem(
    inContinueOrNextUpRow: Boolean,
    type: BaseItemKind?,
): Boolean = inContinueOrNextUpRow && type == BaseItemKind.EPISODE
