package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ricki 22-Nov: Celebrity Traitors S2E2 is an mkv with no Cues, so ExoPlayer can't seek it; skips and resumes go through the remux. */
class SkipPolicyTest {
    @Test
    fun unseekableFilmOrEpisodeSkipsThroughTheRemux() {
        assertTrue(SkipPolicy.viaRemux(live = false, recording = false, remux = false, seekable = false))
    }

    @Test
    fun everythingElseSeeksAsBefore() {
        assertFalse("seekable stream", SkipPolicy.viaRemux(live = false, recording = false, remux = false, seekable = true))
        assertFalse("live channel", SkipPolicy.viaRemux(live = true, recording = false, remux = false, seekable = false))
        assertFalse("recording", SkipPolicy.viaRemux(live = false, recording = true, remux = false, seekable = false))
        assertFalse("already the remux (its own reopen path)", SkipPolicy.viaRemux(live = false, recording = false, remux = true, seekable = false))
    }

    @Test
    fun resumeGoesThroughTheRemuxOnlyWhenItMatters() {
        assertTrue(SkipPolicy.resumeViaRemux(live = false, recording = false, seekable = false, startMs = 439_000))
        assertFalse("near the start: just play", SkipPolicy.resumeViaRemux(live = false, recording = false, seekable = false, startMs = 3_000))
        assertFalse("seekable: ExoPlayer resumed itself", SkipPolicy.resumeViaRemux(live = false, recording = false, seekable = true, startMs = 439_000))
        assertFalse("live", SkipPolicy.resumeViaRemux(live = true, recording = false, seekable = false, startMs = 439_000))
    }
}
