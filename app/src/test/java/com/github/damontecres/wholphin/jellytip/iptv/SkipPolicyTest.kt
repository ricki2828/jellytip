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

    @Test
    fun pressesDuringAConversionGiveOneFollowUpOnlyIfTheTargetMoved() {
        // Ricki 22-Nov 17:22: a second press restarted a 30 s+ conversion; now it waits, then one follow-up skip
        assertTrue(SkipPolicy.followUp(openedMs = 80_000, targetMs = 160_000))
        assertFalse("a few seconds off: just play", SkipPolicy.followUp(openedMs = 80_000, targetMs = 86_000))
        assertTrue("backwards counts too", SkipPolicy.followUp(openedMs = 160_000, targetMs = 80_000))
    }

    @Test
    fun skipNoteSaysWhatIsHappening() {
        org.junit.Assert.assertEquals("Skipping to 1:20…", SkipPolicy.note(80_000, 2))
        org.junit.Assert.assertEquals("Skipping to 1:20…  12 s", SkipPolicy.note(80_000, 12))
        org.junit.Assert.assertEquals("Skipping to 31:20…  This file is slow to skip (25 s)", SkipPolicy.note(1_880_000, 25))
    }
}
