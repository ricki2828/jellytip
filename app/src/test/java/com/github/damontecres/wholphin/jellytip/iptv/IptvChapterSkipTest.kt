package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class IptvChapterSkipTest {
    private val body =
        """{"chapters":[{"s":0,"e":62,"t":"Intro"},{"s":62,"e":2400,"t":null},{"s":2400,"e":2500,"t":"End Credits"},{"s":9,"e":3}],
           "skip":[{"kind":"intro","s":0,"e":62},{"kind":"credits","s":2400,"e":2500},{"kind":"bogus","s":1,"e":2}]}"""

    @Test
    fun parsesChaptersAndSkips() {
        val c = IptvChapterSkip.parse(body)!!
        assertEquals(listOf(IptvChapter(0.0, 62.0, "Intro"), IptvChapter(62.0, 2400.0, null), IptvChapter(2400.0, 2500.0, "End Credits")), c.chapters)
        assertEquals(listOf("intro", "credits"), c.skip.map { it.kind })
    }

    @Test
    fun notProbedYetIsNullAndNoneIsEmpty() {
        assertNull(IptvChapterSkip.parse("""{"chapters":null,"skip":[]}""")!!.chapters)
        assertEquals(emptyList<IptvChapter>(), IptvChapterSkip.parse("""{"chapters":[],"skip":[]}""")!!.chapters)
        assertNull(IptvChapterSkip.parse("not json"))
    }

    @Test
    fun activeOnlyInsideThePartAndNotInItsLastSeconds() {
        val skip = IptvChapterSkip.parse(body)!!.skip
        assertEquals("intro", IptvChapterSkip.active(skip, 0)?.kind)
        assertEquals("intro", IptvChapterSkip.active(skip, 58_000)?.kind)
        assertNull(IptvChapterSkip.active(skip, 59_500)) // the last 3 s
        assertNull(IptvChapterSkip.active(skip, 600_000))
        assertEquals("credits", IptvChapterSkip.active(skip, 2_400_000)?.kind)
    }

    @Test
    fun shownForTenSecondsFromWhenItWasReachedUnlessDismissed() {
        val seg = IptvSkipSeg("intro", 0.0, 62.0)
        assertTrue(IptvChapterSkip.visible(seg, 5_000, null, false)) // the first tick, before firstSeen is stored
        assertTrue(IptvChapterSkip.visible(seg, 14_000, 5_000, false))
        assertFalse(IptvChapterSkip.visible(seg, 15_000, 5_000, false))
        assertFalse(IptvChapterSkip.visible(seg, 4_000, 5_000, false)) // seeked back before where it was first seen
        assertFalse(IptvChapterSkip.visible(seg, 6_000, 5_000, true))
        assertFalse(IptvChapterSkip.visible(null, 6_000, 5_000, false))
    }

    @Test
    fun creditsPlayTheNextEpisodeWhenThereIsOne() {
        val credits = IptvSkipSeg("credits", 2400.0, 2500.0)
        assertEquals(IptvChapterSkip.Action.Next, IptvChapterSkip.action(credits, hasNext = true))
        assertEquals(IptvChapterSkip.Action.Seek(2_500_000), IptvChapterSkip.action(credits, hasNext = false))
        assertEquals(IptvChapterSkip.Action.Seek(62_000), IptvChapterSkip.action(IptvSkipSeg("intro", 0.0, 62.0), hasNext = true))
        assertEquals("Skip intro", IptvChapterSkip.label("intro"))
        assertEquals("Skip recap", IptvChapterSkip.label("recap"))
        assertEquals("Skip credits", IptvChapterSkip.label("credits"))
    }

    @Test
    fun marksAreChapterStartsInsideTheBar() {
        val chs = IptvChapterSkip.parse(body)!!.chapters
        assertEquals(listOf(62f / 2500f, 2400f / 2500f), IptvChapterSkip.marks(chs, 2_500_000))
        assertEquals(emptyList<Float>(), IptvChapterSkip.marks(chs, 0))
        assertEquals(emptyList<Float>(), IptvChapterSkip.marks(null, 2_500_000))
    }
}
