package com.github.damontecres.wholphin.jellytip.home

import com.github.damontecres.wholphin.jellytip.iptv.FxChannel
import com.github.damontecres.wholphin.jellytip.iptv.FxItem
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
class HeroCarouselTest {
    private val now = 1_800_000_000L

    private fun fx(fid: String) =
        FxItem(
            fid, "Rugby Test", "rugby", "rugby", "New Zealand", "England", null, null, "NZL", "ENG", null, null,
            now - 1200, "in", null, null, 1.0, 1.0, true, false,
            listOf(FxChannel("c1", "Sky Sport 1", null, null, null, 1.0, false, false, now - 1800, now + 5400, true, null)),
            "c1", null, null, "m$fid",
        )

    @Test
    fun oneLiveGameAtMostLeadsTheSlides() {
        // Five live games of his: only the top pick is a slide (no games carousel inside the hero)
        val s = JellytipHeroCarousel.slides(listOf(fx("a"), fx("b"), fx("c"), fx("d"), fx("e")), emptyList())
        assertEquals(listOf("m:a"), s.map { it.key })
        assertTrue(JellytipHeroCarousel.slides(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun rightWrapsLeftAtTheFirstSlideIsNotTaken() {
        // Right on the last button: next, and from the last slide back to the first
        assertEquals(1, JellytipHeroCarousel.arrow(0, 3, right = true))
        assertEquals(0, JellytipHeroCarousel.arrow(2, 3, right = true))
        // Left on the first button: previous, but on the first slide Left keeps its old job (null = not consumed)
        assertEquals(1, JellytipHeroCarousel.arrow(2, 3, right = false))
        assertNull(JellytipHeroCarousel.arrow(0, 3, right = false))
        // One slide (or none): the arrows are the buttons' own
        assertNull(JellytipHeroCarousel.arrow(0, 1, right = true))
        assertNull(JellytipHeroCarousel.arrow(0, 0, right = false))
    }

    @Test
    fun stepWraps() {
        assertEquals(0, JellytipHeroCarousel.step(4, 5, 1))
        assertEquals(4, JellytipHeroCarousel.step(0, 5, -1))
        assertEquals(0, JellytipHeroCarousel.step(3, 0, 1))
    }

    @Test
    fun aRebuildKeepsTheSlideOnScreen() {
        val g = HeroSlide.Match(fx("b"))
        val x = HeroSlide.Match(fx("x"))
        val y = HeroSlide.Match(fx("y"))
        // The slide on screen moved (a game slide came or went in front of it): it keeps its place by key
        assertEquals(0, JellytipHeroCarousel.keep(listOf(g, y), "m:b", 1))
        assertEquals(2, JellytipHeroCarousel.keep(listOf(x, y, g), "m:b", 0))
        val after = listOf<HeroSlide>(g)
        // The slide on screen is gone: same place, clamped
        assertEquals(0, JellytipHeroCarousel.keep(after, "m:zzz", 5))
        assertEquals(0, JellytipHeroCarousel.keep(emptyList(), "m:b", 3))
    }

    @Test
    fun pausesOnlyWhenHeIsBusyOrAway() {
        assertFalse(JellytipHeroCarousel.paused(buttonsHeld = false, trailerWithSound = false, resumed = true, pageFocused = true))
        assertTrue(JellytipHeroCarousel.paused(buttonsHeld = true, trailerWithSound = false, resumed = true, pageFocused = true))
        assertTrue(JellytipHeroCarousel.paused(buttonsHeld = false, trailerWithSound = true, resumed = true, pageFocused = true))
        assertTrue(JellytipHeroCarousel.paused(buttonsHeld = false, trailerWithSound = false, resumed = false, pageFocused = true))
        // Focus in the nav drawer / a dialog
        assertTrue(JellytipHeroCarousel.paused(buttonsHeld = false, trailerWithSound = false, resumed = true, pageFocused = false))
    }

    @Test
    fun mixTakesOneFromEachSourceInTurn() {
        val out =
            JellytipHeroCarousel.interleave(
                listOf("New" to listOf(1, 2, 3, 4, 5, 6), "Added" to listOf(10, 1, 11), "Top" to listOf(20)),
                id = { it },
                ok = { it != 3 },
                start = 0,
            )
        // Round robin, each id once (1 already taken from "New"), at most PER_SOURCE (4) per source, filtered
        assertEquals(listOf("New" to 1, "Added" to 10, "Top" to 20, "New" to 2, "Added" to 11, "New" to 4, "New" to 5), out)
    }

    @Test
    fun aDifferentSourceLeadsEachDayAndTheMixIsCapped() {
        val sources = listOf("A" to listOf(1), "B" to listOf(2), "C" to listOf(3))
        assertEquals(2, JellytipHeroCarousel.interleave(sources, { it }, { true }, start = 1).first().second)
        assertEquals(3, JellytipHeroCarousel.interleave(sources, { it }, { true }, start = 5).first().second)
        val many = (0 until 10).map { s -> "S$s" to (0 until 4).map { s * 10 + it } }
        assertEquals(JellytipHeroCarousel.MAX_SLIDES, JellytipHeroCarousel.interleave(many, { it }, { true }, 0).size)
        assertTrue(JellytipHeroCarousel.interleave(emptyList<Pair<String, List<Int>>>(), { it }, { true }, 0).isEmpty())
    }

    @Test
    fun serverRowKickersMatchTheWeb() {
        assertEquals("New release", JellytipHeroCarousel.rowKicker("x", "New releases"))
        assertEquals("Just added", JellytipHeroCarousel.rowKicker("fresh", "Fresh in"))
        assertEquals("Trending", JellytipHeroCarousel.rowKicker("x", "Trending this week"))
        assertEquals("Highly rated", JellytipHeroCarousel.rowKicker("awards", "Oscar winners"))
        assertNull(JellytipHeroCarousel.rowKicker("perso", "Continue watching"))
    }
}
