package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.JellytipTrackPick
import com.github.damontecres.wholphin.jellytip.JellytipTrackPick.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Default tracks from language prefs (web lib/tracks.ts). */
class TrackPickTest {
    private fun a(
        i: Int,
        lang: String?,
        title: String? = null,
        def: Boolean = false,
    ) = Stream(i, "Audio", lang, title, def, false, false)

    private fun s(
        i: Int,
        lang: String?,
        forced: Boolean = false,
        def: Boolean = false,
        text: Boolean = true,
    ) = Stream(i, "Subtitle", lang, null, def, forced, text)

    @Test
    fun audioFollowsOrderedLanguagesAndSkipsCommentary() {
        val st = listOf(a(1, "fre"), a(2, "eng", "Commentary"), a(3, "eng"))
        assertEquals(3, JellytipTrackPick.pickAudio(st, listOf("en", "fr"), 1))
        assertEquals(1, JellytipTrackPick.pickAudio(st, listOf("fr"), 3))
        // no pref match: the fallback, unless it's commentary and a plain one exists
        assertEquals(1, JellytipTrackPick.pickAudio(listOf(a(1, "ger"), a(2, "ger", "Director's commentary")), listOf("en"), 2))
        assertEquals(5, JellytipTrackPick.pickAudio(st, listOf("ja"), 5))
    }

    @Test
    fun subtitlesForcedWhenAudioMatchesFullWhenForeign() {
        val st = listOf(a(1, "eng"), a(2, "jpn"), s(10, "eng", forced = true), s(11, "eng"), s(12, "eng", text = false))
        assertEquals(10, JellytipTrackPick.pickSubtitle(st, 1, listOf("en"), emptyList(), -2))
        assertEquals(11, JellytipTrackPick.pickSubtitle(st, 2, listOf("en"), emptyList(), -2))
        // audio language unknown: never assumed foreign
        assertEquals(-2, JellytipTrackPick.pickSubtitle(listOf(a(1, "und"), s(11, "eng")), 1, listOf("en"), emptyList(), -2))
    }

    @Test
    fun languageCodes() {
        assertEquals("fr", JellytipTrackPick.canonLang("fra"))
        assertEquals("", JellytipTrackPick.canonLang("und"))
        assertEquals(true, JellytipTrackPick.langIs("eng-US", "en"))
    }
}
