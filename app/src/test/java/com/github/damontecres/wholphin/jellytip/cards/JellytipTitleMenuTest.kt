package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleMenu.Direction
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleMenu.Entry
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleMenu.Id
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleMenu.Other
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleMenu.State
import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hold-OK menu's common block is the same list, order and words for a library tile and an IPTV tile. */
class JellytipTitleMenuTest {
    private fun ids(l: List<Entry>) = l.map { it.id }

    private fun labels(l: List<Entry>) = l.map { it.label }

    private fun film(
        inProgress: Boolean = false,
        other: Other? = null,
        taste: Boolean = true,
    ) = State(canPlay = true, inProgress = inProgress, inMyList = false, watched = false, tasteAvailable = taste, other = other)

    @Test
    fun filmNotStartedIsPlayDetailsMyListWatchedTaste() {
        val m = JellytipTitleMenu.common(film())
        assertEquals(
            listOf("Play", "Details", "Add to My list", "Mark as watched", "I like this", "Not for me", "Not interested (hide from rows)"),
            labels(m),
        )
        assertEquals(listOf(Id.PLAY, Id.DETAILS, Id.MY_LIST, Id.WATCHED, Id.LIKE, Id.NOT_FOR_ME, Id.NOT_INTERESTED), ids(m))
    }

    @Test
    fun inProgressIsResumeAndPlayFromStart() {
        val m = JellytipTitleMenu.common(film(inProgress = true))
        assertEquals(listOf("Resume", "Play from start", "Details"), labels(m).take(3))
        assertEquals(listOf(Id.PLAY, Id.PLAY_FROM_START, Id.DETAILS), ids(m).take(3))
        // Not in progress: no Play from start
        assertTrue(ids(JellytipTitleMenu.common(film())).none { it == Id.PLAY_FROM_START })
    }

    @Test
    fun libraryFilmAndIptvFilmShareTheBlock() {
        for (progress in listOf(false, true)) {
            for (other in listOf<Other?>(null, Other(Direction.TO_IPTV, "4K"))) {
                val s = film(inProgress = progress, other = other)
                assertEquals(JellytipTitleMenu.forLibrary(s), JellytipTitleMenu.forIptv(s))
            }
        }
    }

    @Test
    fun libraryFilmWithIptvCopyEndsWithPlayFromIptvQuality() {
        val m = JellytipTitleMenu.common(film(other = Other(Direction.TO_IPTV, "4K")))
        assertEquals("Play from IPTV · 4K", m.last().label)
        assertEquals(Id.PLAY_OTHER, m.last().id)
    }

    @Test
    fun unknownQualityOmitsTheSuffix() {
        assertEquals("Play from IPTV", JellytipTitleMenu.otherLabel(Other(Direction.TO_IPTV, null)))
        assertEquals("Play from IPTV", JellytipTitleMenu.otherLabel(Other(Direction.TO_IPTV, "  ")))
    }

    @Test
    fun iptvFilmWithLibraryCopyEndsWithPlayFromLibrary() {
        val m = JellytipTitleMenu.common(film(other = Other(Direction.TO_LIBRARY)))
        assertEquals("Play from library", m.last().label)
    }

    @Test
    fun noSecondSourceNoPlayFromItem() {
        assertTrue(ids(JellytipTitleMenu.common(film())).none { it == Id.PLAY_OTHER })
    }

    @Test
    fun seriesBlocksMatch() {
        // Library series (Play offered) and IPTV series with a named next episode: same block; with no next episode IPTV leaves Play out
        val lib = State(canPlay = true, inProgress = false, inMyList = true, watched = true, tasteAvailable = true)
        val iptv = lib.copy()
        assertEquals(JellytipTitleMenu.forLibrary(lib), JellytipTitleMenu.forIptv(iptv))
        assertEquals(listOf("Play", "Details", "Remove from My list", "Mark as unwatched"), labels(JellytipTitleMenu.common(lib.copy(tasteAvailable = false))))
        val noNext = JellytipTitleMenu.common(lib.copy(canPlay = false))
        assertEquals(Id.DETAILS, noNext.first().id)
        // Series: the only second-source line is Play from library
        assertEquals("Play from library", JellytipTitleMenu.common(lib.copy(other = Other(Direction.TO_LIBRARY))).last().label)
    }

    @Test
    fun myListWatchedAndTasteFlipTheirWords() {
        val m =
            JellytipTitleMenu.common(
                State(canPlay = false, inProgress = false, inMyList = true, watched = true, tasteAvailable = true, thumb = 1, notInterested = true),
            )
        assertEquals(listOf("Details", "Remove from My list", "Mark as unwatched", "Liked · undo", "Not for me", "Show it in my rows again"), labels(m))
        val m2 = JellytipTitleMenu.common(State(canPlay = false, inProgress = false, inMyList = false, watched = false, tasteAvailable = true, thumb = -1))
        assertEquals("Not for me · undo", m2.first { it.id == Id.NOT_FOR_ME }.label)
    }

    @Test
    fun hiddenLinesAreLeftOut() {
        // Details page "More": no Play, Details, My list
        val more = JellytipTitleMenu.common(State(canPlay = false, inProgress = false, inMyList = false, watched = false, showDetails = false, showMyList = false, tasteAvailable = true, other = Other(Direction.TO_LIBRARY)))
        assertEquals(listOf(Id.WATCHED, Id.LIKE, Id.NOT_FOR_ME, Id.NOT_INTERESTED, Id.PLAY_OTHER), ids(more))
        // An IPTV title can only be marked watched while the taste service is available
        val off = JellytipTitleMenu.common(State(canPlay = true, inProgress = false, inMyList = false, watched = false, showWatched = false))
        assertEquals(listOf(Id.PLAY, Id.DETAILS, Id.MY_LIST), ids(off))
    }

    @Test
    fun extrasFollowTheCommonBlockUnchanged() {
        val extras = listOf(Entry("playlist", "Add to playlist"), Entry("series", "Go to series"))
        val m = JellytipTitleMenu.forLibrary(film(other = Other(Direction.TO_IPTV, "HDR")), extras)
        assertEquals(extras, m.takeLast(2))
        assertEquals(Id.PLAY_OTHER, m[m.size - 3].id)
    }

    @Test
    fun bestIptvIsServerOrderSkippingFailed() {
        val a = IptvSource("iptv", "a", listOf("1080p"))
        val b = IptvSource("iptv", "b", listOf("4k"))
        val lib = IptvSource("library", "L")
        assertEquals("a", JellytipTitleMenu.bestIptv(listOf(lib, a, b))?.id)
        assertEquals("b", JellytipTitleMenu.bestIptv(listOf(lib, a, b)) { it == "a" }?.id)
        // all failed: the first one anyway
        assertEquals("a", JellytipTitleMenu.bestIptv(listOf(a, b)) { true }?.id)
        assertNull(JellytipTitleMenu.bestIptv(listOf(lib)))
        assertNull(JellytipTitleMenu.bestIptv(emptyList()))
    }

    @Test
    fun otherForLibraryTileUsesTheBestCopysQuality() {
        val o = JellytipTitleMenu.otherForLibraryTile(listOf(IptvSource("iptv", "a", listOf("uhd")), IptvSource("iptv", "b", listOf("hdr"))))
        assertNotNull(o)
        assertEquals(Other(Direction.TO_IPTV, "4K"), o)
        // first copy failed: the next copy's quality
        assertEquals("HDR", JellytipTitleMenu.otherForLibraryTile(listOf(IptvSource("iptv", "a", listOf("uhd")), IptvSource("iptv", "b", listOf("hdr")))) { it == "a" }?.quality)
        assertNull(JellytipTitleMenu.otherForLibraryTile(emptyList()))
        assertNull(JellytipTitleMenu.otherForLibraryTile(listOf(IptvSource("library", "L"))))
        assertEquals(null, JellytipTitleMenu.otherForLibraryTile(listOf(IptvSource("iptv", "a")))?.quality)
    }
}
