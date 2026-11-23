package com.github.damontecres.wholphin.jellytip.specials

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's "My Channels" row (Ricki 23-Nov): directly under Continue Watching, before Next Up and the IPTV rows. */
class JellytipMyChannelsEntryTest {
    private fun row(
        title: String,
        type: HomeRowConfig?,
    ): HomeRowLoadingState = HomeRowLoadingState.Success(StringStringProvider(title), listOf(null), rowType = type)

    private val cw = row("Continue watching", HomeRowConfig.ContinueWatching())
    private val next = row("Next up", HomeRowConfig.NextUp())
    private val films = row("Films", null)

    private fun keys(e: List<JellytipHomeEntry>) = e.map { it.key }

    @Test
    fun underContinueWatchingBeforeNextUpAndIptvRows() {
        val e = jellytipHomeEntries(listOf(cw, next, films), emptyList(), leadingRows = 2, reminders = listOf(), myChannels = true)
        assertEquals(listOf("row-0", "my-channels", "row-1", "row-2"), keys(e))
    }

    @Test
    fun underCombinedContinueRow() {
        val combined = row("Continue watching", HomeRowConfig.ContinueWatchingCombined())
        assertEquals(listOf("row-0", "my-channels", "row-1"), keys(jellytipHomeEntries(listOf(combined, films), emptyList(), leadingRows = 1, myChannels = true)))
    }

    @Test
    fun underTheIptvOnlyContinueRow() {
        val e = jellytipHomeEntries(listOf(films), emptyList(), leadingRows = 0, iptvContinue = true, myChannels = true)
        assertEquals(listOf("iptv-continue", "my-channels", "row-0"), keys(e))
    }

    @Test
    fun firstWhenThereIsNoContinueRow() {
        assertEquals(listOf("my-channels", "row-0", "row-1"), keys(jellytipHomeEntries(listOf(next, films), emptyList(), leadingRows = 1, myChannels = true)))
    }

    @Test
    fun offLeavesTheListAlone() {
        assertEquals(listOf("row-0", "row-1", "row-2"), keys(jellytipHomeEntries(listOf(cw, next, films), emptyList(), leadingRows = 2)))
    }
}
