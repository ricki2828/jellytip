package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Four favourites / Closet picks clip sound (21-Nov bug: Emma Stone's clip never had sound on the TV). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipSoundTest {
    @After
    fun reset() {
        JellytipTrailerControl.stop()
        JellytipTrailerControl.heroSoundSetting = false
    }

    @Test
    fun okTogglesSoundWhilePlaying() {
        assertTrue(JellytipTrailerControl.clipOkSound(playing = true, sound = false))
        assertFalse(JellytipTrailerControl.clipOkSound(playing = true, sound = true))
    }

    @Test
    fun okStartsWithSoundWhenNotPlaying() {
        assertTrue(JellytipTrailerControl.clipOkSound(playing = false, sound = false))
        assertTrue(JellytipTrailerControl.clipOkSound(playing = false, sound = true))
    }

    @Test
    fun autoplayFollowsTheHeroSetting() {
        // heroSoundChoice is untouched (null) in tests: the setting decides
        JellytipTrailerControl.heroSoundSetting = false
        assertFalse(JellytipTrailerControl.clipAutoSound())
        JellytipTrailerControl.heroSoundSetting = true
        assertTrue(JellytipTrailerControl.clipAutoSound())
    }

    @Test
    fun stopResetsSoundSoTheNextStartFadesIn() {
        JellytipTrailerControl.startSpecial("ff", "http://x/clip.mp4", sound = true)
        JellytipTrailerControl.stop()
        assertFalse(JellytipTrailerControl.sound)
        JellytipTrailerControl.startSpecial("ff", "http://x/clip.mp4", sound = true)
        assertTrue(JellytipTrailerControl.sound)
    }

    @Test
    fun okSoundReachesThePlayingClip() {
        JellytipTrailerControl.startSpecial("ff", "http://x/clip.mp4", sound = JellytipTrailerControl.clipAutoSound())
        val playing = JellytipTrailerControl.isPlayingForOwner("ff")
        assertTrue(playing)
        val before = JellytipTrailerControl.sound
        JellytipTrailerControl.sound = JellytipTrailerControl.clipOkSound(playing, before)
        assertEquals(!before, JellytipTrailerControl.sound)
    }
}
