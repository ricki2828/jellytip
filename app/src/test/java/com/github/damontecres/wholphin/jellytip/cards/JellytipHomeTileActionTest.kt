package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.ui.main.HomeTileAction
import com.github.damontecres.wholphin.ui.main.homeTileAction
import com.github.damontecres.wholphin.ui.main.homeTileHasDetailsItem
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellytipHomeTileActionTest {
    @Test
    fun episodeInContinueOrNextUpPlays() {
        assertEquals(HomeTileAction.PLAY, homeTileAction(true, BaseItemKind.EPISODE, clickToPlay = false))
        assertEquals(HomeTileAction.PLAY, homeTileAction(true, BaseItemKind.EPISODE, clickToPlay = true))
    }

    @Test
    fun filmInContinueOrNextUpKeepsTodaysBehaviour() {
        assertEquals(HomeTileAction.OPEN, homeTileAction(true, BaseItemKind.MOVIE, clickToPlay = false))
        assertEquals(HomeTileAction.PLAY, homeTileAction(true, BaseItemKind.MOVIE, clickToPlay = true))
    }

    @Test
    fun seriesTileInAGroupedRowOpens() {
        assertEquals(HomeTileAction.OPEN, homeTileAction(true, BaseItemKind.SERIES, clickToPlay = false))
    }

    @Test
    fun anyOtherRowOpens() {
        for (type in listOf(BaseItemKind.EPISODE, BaseItemKind.MOVIE, BaseItemKind.SEASON, BaseItemKind.SERIES, null)) {
            for (click in listOf(false, true)) {
                assertEquals("$type in another row", HomeTileAction.OPEN, homeTileAction(false, type, click))
            }
        }
    }

    @Test
    fun detailsItemOnlyForEpisodesInThoseRows() {
        assertTrue(homeTileHasDetailsItem(true, BaseItemKind.EPISODE))
        assertFalse(homeTileHasDetailsItem(true, BaseItemKind.MOVIE))
        assertFalse(homeTileHasDetailsItem(false, BaseItemKind.EPISODE))
    }
}
