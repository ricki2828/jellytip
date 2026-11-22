package com.github.damontecres.wholphin.jellytip.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Ricki 22-Nov: a focus preview stays muted; an explicit OK (click) on a trailer means sound. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TrailerOkSoundTest {
    private val owner = "trailer:2026-11-22"
    private val akira = UUID.fromString("74d51d96-f470-cec7-0286-4e5e0b44a5e6")

    @After
    fun reset() {
        JellytipTrailerControl.stop()
        JellytipTrailerControl.heroTrailerRequest = null
    }

    @Test
    fun theRule() {
        assertEquals(TrailerOk.START_WITH_SOUND, JellytipTrailerControl.trailerOk(playing = false, sound = false))
        assertEquals(TrailerOk.SOUND_ON, JellytipTrailerControl.trailerOk(playing = true, sound = false))
        assertEquals(TrailerOk.OPEN_DETAILS, JellytipTrailerControl.trailerOk(playing = true, sound = true))
    }

    @Test
    fun trailerBreakClickOnTheMutedPreviewTurnsTheSoundOn() {
        // The 10:44:09 log: focus starts the preview muted (sound=false), as it should
        JellytipTrailerControl.startSpecial(owner, "https://x/t.mp4", sound = false, itemId = akira)
        assertFalse(JellytipTrailerControl.sound)
        // OK on the tile: the same trailer goes on with sound (before: details opened, the trailer stopped)
        assertEquals(TrailerOk.SOUND_ON, JellytipTrailerControl.specialOk(owner))
        assertTrue(JellytipTrailerControl.sound)
        assertTrue(JellytipTrailerControl.isPlayingForOwner(owner))
        // OK again (already with sound): the details
        assertEquals(TrailerOk.OPEN_DETAILS, JellytipTrailerControl.specialOk(owner))
    }

    @Test
    fun trailerBreakClickBeforeThePreviewStartsItWithSound() {
        // Not playing yet (or autoplay off): the caller starts it with sound
        assertEquals(TrailerOk.START_WITH_SOUND, JellytipTrailerControl.specialOk(owner))
        // Another special's trailer playing is not this tile's
        JellytipTrailerControl.startSpecial("closet", "https://x/c.mp4", sound = false)
        assertEquals(TrailerOk.START_WITH_SOUND, JellytipTrailerControl.specialOk(owner))
        assertFalse(JellytipTrailerControl.sound)
    }

    @Test
    fun aSoundTrailerOutlivesOnlyTheCarouselHold() {
        val c = JellytipTrailerControl
        // Carousel hold (he went Down to the row): his trailer with sound stays
        assertTrue(c.keepHeroTrailer(mine = true, sound = true, mayPlay = c.heroMayPlay(pageFocused = true, resumed = true, specialFocused = false)))
        // TV Home button / screensaver, the drawer, a video special: it stops
        assertFalse(c.heroMayPlay(pageFocused = true, resumed = false, specialFocused = false))
        assertFalse(c.heroMayPlay(pageFocused = false, resumed = true, specialFocused = false))
        assertFalse(c.heroMayPlay(pageFocused = true, resumed = true, specialFocused = true))
        assertFalse(c.keepHeroTrailer(mine = true, sound = true, mayPlay = false))
        // A muted preview, or another item's trailer: not kept
        assertFalse(c.keepHeroTrailer(mine = true, sound = false, mayPlay = true))
        assertFalse(c.keepHeroTrailer(mine = false, sound = true, mayPlay = true))
    }

    @Test
    fun heroTrailerButtonAsksForTheTrailerWithSound() {
        // Nothing playing: the hero is asked to start this slide's trailer with sound
        assertNull(JellytipTrailerControl.heroTrailerRequest)
        assertEquals(TrailerOk.START_WITH_SOUND, JellytipTrailerControl.heroTrailerOk(akira))
        assertEquals(akira, JellytipTrailerControl.heroTrailerRequest)
    }
}
