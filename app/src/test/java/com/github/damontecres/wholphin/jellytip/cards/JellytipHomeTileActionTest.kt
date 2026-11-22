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
    fun filmInContinueOrNextUpPlaysWhateverClickToPlaySays() {
        for (type in listOf(BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)) {
            assertEquals(HomeTileAction.PLAY, homeTileAction(true, type, clickToPlay = false))
            assertEquals(HomeTileAction.PLAY, homeTileAction(true, type, clickToPlay = true))
        }
    }

    @Test
    fun otherTypesInContinueOrNextUpFollowClickToPlay() {
        assertEquals(HomeTileAction.OPEN, homeTileAction(true, BaseItemKind.SEASON, clickToPlay = false))
        assertEquals(HomeTileAction.PLAY, homeTileAction(true, BaseItemKind.SEASON, clickToPlay = true))
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
    fun detailsItemOnlyForEpisodesAndFilmsInThoseRows() {
        assertTrue(homeTileHasDetailsItem(true, BaseItemKind.EPISODE))
        assertTrue(homeTileHasDetailsItem(true, BaseItemKind.MOVIE))
        assertFalse(homeTileHasDetailsItem(true, BaseItemKind.SERIES))
        assertFalse(homeTileHasDetailsItem(false, BaseItemKind.EPISODE))
        assertFalse(homeTileHasDetailsItem(false, BaseItemKind.MOVIE))
    }
}
