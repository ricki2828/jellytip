package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ricki 22-Nov: Celebrity Traitors S2E2 is an mkv with no Cues, so ExoPlayer can't seek it; skips and resumes go through the remux. */
class SkipPolicyTest {
    @Test
    fun unseekableFilmOrEpisodeSkipsThroughTheRemux() {
        assertTrue(SkipPolicy.viaRemux(film = true, remux = false, seekable = false, remuxAsked = false))
    }

    @Test
    fun everythingElseSeeksAsBefore() {
        assertFalse("seekable stream", SkipPolicy.viaRemux(film = true, remux = false, seekable = true, remuxAsked = false))
        assertFalse("live / catch-up / recording (not a film or episode)", SkipPolicy.viaRemux(film = false, remux = false, seekable = false, remuxAsked = false))
        assertFalse("already the remux (its own reopen path)", SkipPolicy.viaRemux(film = true, remux = true, seekable = false, remuxAsked = true))
    }

    @Test
    fun remuxAskedButFileSentAsIsNeverLoops() {
        // A server that ignores remux=1 would otherwise be asked again on every key press / READY
        assertFalse(SkipPolicy.viaRemux(film = true, remux = false, seekable = false, remuxAsked = true))
        assertFalse(SkipPolicy.resumeViaRemux(film = true, seekable = false, remuxAsked = true, startMs = 439_000))
    }

    @Test
    fun resumeGoesThroughTheRemuxOnlyWhenItMatters() {
        assertTrue(SkipPolicy.resumeViaRemux(film = true, seekable = false, remuxAsked = false, startMs = 439_000))
        assertFalse("near the start: just play", SkipPolicy.resumeViaRemux(film = true, seekable = false, remuxAsked = false, startMs = 3_000))
        assertFalse("seekable: ExoPlayer resumed itself", SkipPolicy.resumeViaRemux(film = true, seekable = true, remuxAsked = false, startMs = 439_000))
        assertFalse("not a film / episode", SkipPolicy.resumeViaRemux(film = false, seekable = false, remuxAsked = false, startMs = 439_000))
    }
}
