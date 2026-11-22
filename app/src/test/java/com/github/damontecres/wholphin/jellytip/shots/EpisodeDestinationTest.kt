package com.github.damontecres.wholphin.jellytip.shots

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.detail.series.SeasonEpisodeIds
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** `BaseItem.destination()` for episodes and seasons: the series page opened at them (Robolectric: BaseItem reads the application). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeDestinationTest {
    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray())

    private val seriesId = uuid("series")
    private val seasonId = uuid("season")
    private val episodeId = uuid("episode")

    private fun episode(
        withSeries: Boolean = true,
        withSeason: Boolean = true,
    ) = BaseItem(
        BaseItemDto(
            id = episodeId,
            type = BaseItemKind.EPISODE,
            name = "Hostage",
            indexNumber = 4,
            parentIndexNumber = 2,
            seriesId = seriesId.takeIf { withSeries },
            seasonId = seasonId.takeIf { withSeason },
        ),
    )

    @Test
    fun episodeOpensTheSeriesPageAtThatEpisode() {
        val dest = episode().destination()
        assertEquals(
            Destination.MediaItem(
                itemId = seriesId,
                type = BaseItemKind.SERIES,
                openAt = SeasonEpisodeIds(seasonId, 2, episodeId, 4),
            ),
            dest,
        )
    }

    @Test
    fun seasonOpensTheSeriesPageAtThatSeason() {
        val season =
            BaseItem(
                BaseItemDto(id = seasonId, type = BaseItemKind.SEASON, name = "Season 2", indexNumber = 2, seriesId = seriesId),
            )
        assertEquals(
            Destination.MediaItem(
                itemId = seriesId,
                type = BaseItemKind.SERIES,
                openAt = SeasonEpisodeIds(seasonId, 2, null, null),
            ),
            season.destination(),
        )
    }

    @Test
    fun withoutASeriesTheOldFallbackStays() {
        val dest = episode(withSeries = false).destination()
        assertTrue("episode without a series id opens its own page", dest is Destination.MediaItem)
        dest as Destination.MediaItem
        assertEquals(BaseItemKind.EPISODE, dest.type)
        assertEquals(episodeId, dest.itemId)
        assertNull(dest.openAt)

        val season = BaseItem(BaseItemDto(id = seasonId, type = BaseItemKind.SEASON, name = "Season 2", indexNumber = 2))
        val seasonDest = season.destination() as Destination.MediaItem
        assertEquals(BaseItemKind.SEASON, seasonDest.type)
        assertNull(seasonDest.openAt)
    }

    @Test
    fun withoutASeasonIdTheOldFallbackStays() {
        val dest = episode(withSeason = false).destination() as Destination.MediaItem
        assertEquals(BaseItemKind.EPISODE, dest.type)
        assertNull(dest.openAt)
    }

    @Test
    fun theSeriesItselfHasNoOpenAt() {
        val series = BaseItem(BaseItemDto(id = seriesId, type = BaseItemKind.SERIES, name = "Slow Horses"))
        val dest = series.destination() as Destination.MediaItem
        assertNull(dest.openAt)
    }
}
