package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.data.filter.SubtitlesFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Library "Subtitles" filter (web 54110c6e HasSubtitles). */
class LibraryFilterTest {
    @Test
    fun subtitlesFilterReachesTheRequest() {
        val on = SubtitlesFilter.set(true, GetItemsFilter())
        assertEquals(true, SubtitlesFilter.get(on))
        assertEquals(true, on.applyTo(GetItemsRequest()).hasSubtitles)
        assertEquals(false, SubtitlesFilter.set(false, GetItemsFilter()).applyTo(GetItemsRequest()).hasSubtitles)
        assertNull(GetItemsFilter().applyTo(GetItemsRequest()).hasSubtitles)
    }

    @Test
    fun countClearAndMerge() {
        val f = SubtitlesFilter.set(true, GetItemsFilter())
        assertEquals(1, f.countFilters(listOf(SubtitlesFilter)))
        assertNull(f.delete(listOf(SubtitlesFilter)).hasSubtitles)
        assertEquals(true, GetItemsFilter().merge(f).hasSubtitles)
        assertEquals(false, GetItemsFilter(hasSubtitles = false).merge(f).hasSubtitles)
    }
}
