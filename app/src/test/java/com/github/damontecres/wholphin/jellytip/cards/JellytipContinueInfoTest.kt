package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvResume
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The Continue Watching / Next Up info line: same text from a library item and an IPTV resume entry. */
class JellytipContinueInfoTest {
    private fun min(m: Long) = m * 600_000_000L

    private fun lib(
        type: BaseItemKind,
        posMin: Long = 0,
        runMin: Long? = null,
        season: Int? = null,
        episode: Int? = null,
    ): BaseItem {
        val id = UUID.randomUUID()
        return BaseItem(
            BaseItemDto(
                id = id,
                type = type,
                runTimeTicks = runMin?.let { min(it) },
                parentIndexNumber = season,
                indexNumber = episode,
                userData = UserItemDataDto(playbackPositionTicks = min(posMin), playCount = 0, isFavorite = false, played = false, key = "", itemId = id),
            ),
        )
    }

    private fun iptv(
        kind: String,
        posMin: Double,
        runMin: Double,
        season: Int? = null,
        episode: Int? = null,
    ) = IptvResume(kind, "id", if (kind == "episode") "s" else null, "T", null, season, episode, posMin * 60, runMin * 60, 1L, null, null, emptyList())

    @Test
    fun filmInProgress() {
        assertEquals("1h 18m left", JellytipContinueInfo.forLibrary(lib(BaseItemKind.MOVIE, 52, 130)))
        assertEquals("1h 18m left", JellytipContinueInfo.forIptv(iptv("movie", 52.0, 130.0)))
        assertEquals("23 min left", JellytipContinueInfo.forLibrary(lib(BaseItemKind.MOVIE, 100, 123)))
        assertEquals("23 min left", JellytipContinueInfo.forIptv(iptv("movie", 100.0, 123.0)))
    }

    @Test
    fun episodeInProgress() {
        assertEquals("S2 E5 · 28 min left", JellytipContinueInfo.forLibrary(lib(BaseItemKind.EPISODE, 20, 48, 2, 5)))
        assertEquals("S2 E5 · 28 min left", JellytipContinueInfo.forIptv(iptv("episode", 20.0, 48.0, 2, 5)))
    }

    @Test
    fun nextUpEpisodeNotStarted() {
        assertEquals("Next: S2 E6", JellytipContinueInfo.forLibrary(lib(BaseItemKind.EPISODE, 0, 48, 2, 6)))
        assertEquals("Next: S2 E6", JellytipContinueInfo.forIptv(iptv("episode", 0.0, 0.0, 2, 6)))
    }

    @Test
    fun unknownRuntimeShowsOnlyTheEpisodePart() {
        assertEquals("S2 E5", JellytipContinueInfo.forLibrary(lib(BaseItemKind.EPISODE, 20, null, 2, 5)))
        assertEquals("S2 E5", JellytipContinueInfo.forIptv(iptv("episode", 20.0, 0.0, 2, 5)))
        assertNull(JellytipContinueInfo.forLibrary(lib(BaseItemKind.MOVIE, 20, null)))
        assertNull(JellytipContinueInfo.forIptv(iptv("movie", 20.0, 0.0)))
    }

    @Test
    fun nothingToSay() {
        assertNull(JellytipContinueInfo.forLibrary(lib(BaseItemKind.MOVIE, 0, 100)))
        assertNull(JellytipContinueInfo.forLibrary(lib(BaseItemKind.EPISODE, 0, 48)))
        assertEquals("1 min left", JellytipContinueInfo.text(false, null, null, 3590.0, 3600.0))
    }
}
