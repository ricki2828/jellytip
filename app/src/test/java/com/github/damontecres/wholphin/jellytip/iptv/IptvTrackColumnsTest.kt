package com.github.damontecres.wholphin.jellytip.iptv

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio & subtitles menu: two columns, Off tops Subtitles, ✓ on the current pick, focus starts on it. */
class IptvTrackColumnsTest {
    private fun opt(
        type: Int,
        i: Int,
        label: String,
        selected: Boolean,
    ) = IptvTracks.Option(type, i, 0, label, selected)

    @Test
    fun subtitlesStartWithOff() {
        val subs = IptvTrackColumns.subtitles(listOf(opt(C.TRACK_TYPE_TEXT, 3, "English", false), opt(C.TRACK_TYPE_TEXT, 4, "French", false)))
        assertEquals(listOf("Off", "English", "French"), subs.map { it.label })
        assertTrue(subs[0].off && subs[0].selected)
        assertEquals(0, IptvTrackColumns.focusIndex(subs))
    }

    @Test
    fun currentSubtitleIsMarkedNotOff() {
        val subs = IptvTrackColumns.subtitles(listOf(opt(C.TRACK_TYPE_TEXT, 3, "English", false), opt(C.TRACK_TYPE_TEXT, 4, "French", true)))
        assertFalse(subs[0].selected)
        assertEquals(listOf(false, false, true), subs.map { it.selected })
        assertEquals(2, IptvTrackColumns.focusIndex(subs))
    }

    @Test
    fun noSubtitlesKeepsOffAndANote() {
        val subs = IptvTrackColumns.subtitles(emptyList())
        assertEquals(2, subs.size)
        assertTrue(subs[0].pickable)
        assertFalse(subs[1].pickable)
    }

    @Test
    fun audioMarksTheCurrentTrackAndFocusesIt() {
        val audio = IptvTrackColumns.audio(listOf(opt(C.TRACK_TYPE_AUDIO, 1, "English · 5.1", false), opt(C.TRACK_TYPE_AUDIO, 2, "Commentary", true)))
        assertEquals(1, IptvTrackColumns.focusIndex(audio))
        assertTrue(audio[1].selected)
    }

    @Test
    fun singleTrackAudioIsANote() {
        val audio = IptvTrackColumns.audio(emptyList())
        assertEquals(1, audio.size)
        assertFalse(audio[0].pickable)
        assertEquals(0, IptvTrackColumns.focusIndex(audio))
    }
}
