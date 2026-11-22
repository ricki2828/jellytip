package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** Continue Watching: an IPTV episode the library also has plays from the library (Celebrity Traitors S2E2, 22-Nov). */
class IptvResumeLibraryTest {
    private val a = UUID.fromString("ff7b098e-b481-1105-eadb-d2f25199f299")
    private val b = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val eps = listOf(IptvResumeLibrary.Ep(b, 2, 1), IptvResumeLibrary.Ep(a, 2, 2), IptvResumeLibrary.Ep(b, 0, 2))

    @Test
    fun sameSeasonAndEpisodeOnly() {
        assertEquals(a, IptvResumeLibrary.pick(eps, 2, 2))
        // Not in the library (S2E3): the IPTV player
        assertNull(IptvResumeLibrary.pick(eps, 2, 3))
        // Unknown numbers never guess
        assertNull(IptvResumeLibrary.pick(eps, null, 2))
        assertNull(IptvResumeLibrary.pick(eps, 2, null))
    }

    @Test
    fun seriesLibraryIdFromTheEntry() {
        fun r(
            kind: String,
            lib: String?,
            sources: List<IptvSource> = emptyList(),
        ) = IptvResume(kind, "11dd6d87:e:2111120", "11dd6d87:s:49252", "Episode 2", "The Celebrity Traitors", 2, 2, 194.0, 3600.0, 0L, null, null, emptyList(), seriesLibraryId = lib, seriesSources = sources)
        assertEquals(UUID.fromString("48fcd920-aa5c-a098-5a16-70879ae1a105"), r("episode", "48fcd920aa5ca0985a1670879ae1a105").seriesLibraryUuid)
        assertEquals(UUID.fromString("48fcd920-aa5c-a098-5a16-70879ae1a105"), r("episode", null, listOf(IptvSource("library", "48fcd920aa5ca0985a1670879ae1a105"))).seriesLibraryUuid)
        assertNull(r("episode", null).seriesLibraryUuid)
        assertNull(r("movie", "48fcd920aa5ca0985a1670879ae1a105").seriesLibraryUuid)
    }
}
