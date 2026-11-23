package com.github.damontecres.wholphin.jellytip.askchat

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind press-to-talk (the recognizer wiring itself needs a real TV). */
class ChatVoiceTextTest {
    @Test
    fun segmentsAppendWithOneSpace() {
        assertEquals("something funny", ChatVoiceText.join("something", "funny"))
        assertEquals("a b", ChatVoiceText.join("  a ", "  b  "))
        assertEquals("kept", ChatVoiceText.join("kept", ""))
        assertEquals("heard", ChatVoiceText.join("", "heard"))
        assertEquals("", ChatVoiceText.join("", "  "))
        assertEquals("a b c", ChatVoiceText.join(ChatVoiceText.join("a", "b"), "c"))
        assertEquals("typed then spoken", ChatVoiceText.join("typed", "then\n spoken"))
    }

    @Test
    fun aPauseWhileHeldRestartsAndAppends() {
        // segment 1 ends on silence while OK is still down: restart, text kept; segment 2 appended; release ends it
        var committed = ""
        committed = ChatVoiceText.join(committed, "something funny")
        assertEquals(ChatVoiceText.Next.Restart, ChatVoiceText.afterEnd(held = true, tap = false, restarts = 0))
        committed = ChatVoiceText.join(committed, "for the kids")
        assertEquals(ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = false, tap = false, restarts = 1))
        assertEquals("something funny for the kids", committed)
    }

    @Test
    fun tapAndRestartCap() {
        assertEquals("tap: a window that heard nothing ends it", ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = false, tap = true, restarts = 0, added = false))
        assertEquals("tap: a window with words was a pause, go on", ChatVoiceText.Next.Restart, ChatVoiceText.afterEnd(held = false, tap = true, restarts = 0, added = true))
        assertEquals(ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = true, tap = true, restarts = 0))
        assertEquals("held: even a silent window restarts", ChatVoiceText.Next.Restart, ChatVoiceText.afterEnd(held = true, tap = false, restarts = 3, added = false))
        assertEquals("released: over", ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = false, tap = false, restarts = 0, added = true))
        assertEquals("tap stops at the cap too", ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = false, tap = true, restarts = ChatVoiceText.MAX_RESTARTS, added = true))
        assertEquals("a runaway hold stops restarting", ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = true, tap = false, restarts = ChatVoiceText.MAX_RESTARTS))
        assertEquals(ChatVoiceText.Next.Restart, ChatVoiceText.afterEnd(held = true, tap = false, restarts = ChatVoiceText.MAX_RESTARTS - 1))
    }

    @Test
    fun tapThreshold() {
        assertTrue(ChatVoiceText.isTap(0))
        assertTrue(ChatVoiceText.isTap(299))
        assertFalse(ChatVoiceText.isTap(300))
        assertFalse(ChatVoiceText.isTap(2500))
    }

    @Test
    fun quietErrorsRestartOthersFail() {
        assertTrue(ChatVoiceText.isQuietError(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(ChatVoiceText.isQuietError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertTrue(ChatVoiceText.isQuietError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertFalse(ChatVoiceText.isQuietError(SpeechRecognizer.ERROR_NETWORK))
        assertFalse(ChatVoiceText.isQuietError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(ChatVoiceText.isQuietError(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test
    fun levelIsQuantizedToQuarters() {
        assertEquals(0f, ChatVoiceText.quantizeLevel(-5f), 0f)
        assertEquals(1f, ChatVoiceText.quantizeLevel(30f), 0f)
        assertEquals(0.5f, ChatVoiceText.quantizeLevel(4f), 0f)
    }

    @Test
    fun theFirstClientOrServerErrorAfterARestartIsQuiet() {
        val c = SpeechRecognizer.ERROR_CLIENT
        val sv = SpeechRecognizer.ERROR_SERVER
        assertTrue(ChatVoiceText.isGlitchAfterRestart(c, held = true, tap = false, restarts = 1, glitchUsed = false))
        assertTrue(ChatVoiceText.isGlitchAfterRestart(sv, held = true, tap = false, restarts = 3, glitchUsed = false))
        assertFalse("only the first per restart", ChatVoiceText.isGlitchAfterRestart(c, held = true, tap = false, restarts = 1, glitchUsed = true))
        assertFalse("never on the first session (a real failure)", ChatVoiceText.isGlitchAfterRestart(c, held = true, tap = false, restarts = 0, glitchUsed = false))
        assertFalse("not after release", ChatVoiceText.isGlitchAfterRestart(c, held = false, tap = false, restarts = 1, glitchUsed = false))
        assertTrue("tap mode keeps listening too", ChatVoiceText.isGlitchAfterRestart(c, held = false, tap = true, restarts = 1, glitchUsed = false))
        assertTrue("server disconnected (11)", ChatVoiceText.isGlitchAfterRestart(ChatVoiceText.ERROR_SERVER_DISCONNECTED, held = true, tap = false, restarts = 1, glitchUsed = false))
        assertFalse("other errors still fail", ChatVoiceText.isGlitchAfterRestart(SpeechRecognizer.ERROR_AUDIO, held = true, tap = false, restarts = 1, glitchUsed = false))
        // and the restart it triggers still counts toward the cap
        assertEquals(ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = true, tap = false, restarts = ChatVoiceText.MAX_RESTARTS))
    }

    @Test
    fun aSilentPressNeverResendsTypedText() {
        assertTrue(ChatVoiceText.heardNothing("under 90 minutes", "under 90 minutes"))
        assertTrue(ChatVoiceText.heardNothing("  under  90 minutes ", "under 90 minutes"))
        assertTrue(ChatVoiceText.heardNothing("", ""))
        assertTrue(ChatVoiceText.heardNothing("", "   "))
        assertFalse(ChatVoiceText.heardNothing("under 90 minutes", "under 90 minutes with Paul Rudd"))
        assertFalse(ChatVoiceText.heardNothing("", "something funny"))
        assertFalse("the same words spoken again are new speech", ChatVoiceText.heardNothing("funny", ChatVoiceText.join("funny", "funny")))
    }

    @Test
    fun twoGlitchesWithoutAResultFailFast() {
        val c = SpeechRecognizer.ERROR_CLIENT
        var used = false
        // restart 1: first glitch is tolerated
        assertTrue(ChatVoiceText.isGlitchAfterRestart(c, held = true, tap = false, restarts = 1, glitchUsed = used))
        used = ChatVoiceText.glitchUsedAfter(used, glitch = true, resultArrived = false)
        // restart 2, still no result: the second glitch is a real failure
        assertFalse(ChatVoiceText.isGlitchAfterRestart(c, held = true, tap = false, restarts = 2, glitchUsed = used))
        // a result proves the recognizer works: the allowance is refilled
        used = ChatVoiceText.glitchUsedAfter(used, glitch = false, resultArrived = true)
        assertFalse(used)
        assertTrue(ChatVoiceText.isGlitchAfterRestart(c, held = true, tap = false, restarts = 3, glitchUsed = used))
        // no event leaves it as it was
        assertTrue(ChatVoiceText.glitchUsedAfter(true, glitch = false, resultArrived = false))
        assertFalse(ChatVoiceText.glitchUsedAfter(false, glitch = false, resultArrived = false))
    }

    // ---- push-to-talk: the remote's OK events (Ricki 23-Nov: an 8 s hold on Chat sent only "90s") ----

    /** A tiny driver: OK events through the key gate into the press / release decisions, as ChatMicButton + ChatVoice do. */
    private class Ptt {
        val keys = ChatPttKeys()
        var active = false
        var held = false
        var tap = false
        var tookOver = false
        var downAt = 0L
        val log = mutableListOf<String>()

        fun down(
            t: Long,
            repeat: Int = 0,
        ) {
            if (!keys.down(t, repeat)) return
            when (ChatVoiceText.onPress(active, held, tap)) {
                ChatVoiceText.OnPress.Start -> {
                    active = true
                    held = true
                    tap = false
                    tookOver = false
                    downAt = t
                    log += "start"
                }

                ChatVoiceText.OnPress.TakeOver -> {
                    held = true
                    tap = false
                    tookOver = true
                    downAt = t
                    log += "takeover"
                }

                ChatVoiceText.OnPress.Ignore -> log += "ignore"
            }
        }

        fun up(t: Long) {
            if (!keys.up()) return
            when (ChatVoiceText.onRelease(active, held, t - downAt, tookOver)) {
                ChatVoiceText.OnRelease.ToTap -> {
                    held = false
                    tap = true
                    log += "tap"
                }

                ChatVoiceText.OnRelease.Stop -> {
                    held = false
                    tap = false
                    active = false
                    log += "stop"
                }

                ChatVoiceText.OnRelease.Ignore -> Unit
            }
        }

        /** The recognizer ended a window while in this state. */
        fun windowEnded(added: Boolean): ChatVoiceText.Next = ChatVoiceText.afterEnd(held, tap, 0, added)
    }

    @Test
    fun anEightSecondHoldWithRepeatsIsOnePress() {
        val p = Ptt()
        p.down(0)
        var t = 500L
        while (t < 8_000) {
            p.down(t, repeat = ((t - 450) / 50).toInt())
            // the recognizer ends windows on pauses while held: always another window, never the end
            assertEquals(ChatVoiceText.Next.Restart, p.windowEnded(added = t % 1000 == 0L))
            t += 50
        }
        p.up(8_000)
        assertEquals(listOf("start", "stop"), p.log)
        assertEquals("after release a window end sends", ChatVoiceText.Next.Finish, p.windowEnded(added = true))
    }

    @Test
    fun aRemoteWithoutRepeatsIsTheSame() {
        val p = Ptt()
        p.down(0)
        assertEquals(ChatVoiceText.Next.Restart, p.windowEnded(added = false))
        p.up(7_900)
        assertEquals(listOf("start", "stop"), p.log)
    }

    @Test
    fun extraKeyDownsWhileHeldAreIgnored() {
        // some remotes send further key-downs with repeatCount 0 while the key is held
        val p = Ptt()
        p.down(0)
        p.down(400)
        p.down(900)
        p.down(1_300, repeat = 1)
        p.up(3_000)
        assertEquals(listOf("start", "stop"), p.log)
    }

    @Test
    fun aKeyUpWithoutItsKeyDownDoesNothing() {
        // focus arrived on the mic mid-press (from the sidebar), or the key-down went elsewhere
        val p = Ptt()
        p.down(100, repeat = 3)
        p.up(200)
        assertEquals(emptyList<String>(), p.log)
        assertFalse(p.active)
    }

    @Test
    fun aMissedKeyUpThenANewPressStartsAgain() {
        val p = Ptt()
        p.down(0)
        // key-up lost; a fresh press long after is a new press (the watchdog ended the old listen meanwhile)
        p.active = false
        p.held = false
        p.down(ChatPttKeys.STALE_MS + 10)
        assertEquals(listOf("start", "start"), p.log)
    }

    @Test
    fun aShortPressIsATapThenAPressTakesItOverAndItsReleaseSends() {
        val p = Ptt()
        p.down(0)
        p.up(120)
        assertEquals(listOf("start", "tap"), p.log)
        assertEquals("tap: words, keep going", ChatVoiceText.Next.Restart, p.windowEnded(added = true))
        assertEquals("tap: quiet window, send", ChatVoiceText.Next.Finish, p.windowEnded(added = false))
        p.down(2_000)
        assertEquals("takeover", p.log.last())
        assertEquals("now a hold: no end on silence", ChatVoiceText.Next.Restart, p.windowEnded(added = false))
        p.up(2_100) // even a quick release of the takeover sends now
        assertEquals("stop", p.log.last())
    }

    @Test
    fun releaseDecisions() {
        assertEquals(ChatVoiceText.OnRelease.Ignore, ChatVoiceText.onRelease(active = false, held = true, heldMs = 5_000, tookOver = false))
        assertEquals(ChatVoiceText.OnRelease.Ignore, ChatVoiceText.onRelease(active = true, held = false, heldMs = 5_000, tookOver = false))
        assertEquals(ChatVoiceText.OnRelease.ToTap, ChatVoiceText.onRelease(active = true, held = true, heldMs = 299, tookOver = false))
        assertEquals(ChatVoiceText.OnRelease.Stop, ChatVoiceText.onRelease(active = true, held = true, heldMs = 300, tookOver = false))
        assertEquals(ChatVoiceText.OnRelease.Stop, ChatVoiceText.onRelease(active = true, held = true, heldMs = 50, tookOver = true))
        assertEquals(ChatVoiceText.OnPress.Start, ChatVoiceText.onPress(active = false, held = false, tap = false))
        assertEquals(ChatVoiceText.OnPress.Ignore, ChatVoiceText.onPress(active = true, held = true, tap = false))
        assertEquals(ChatVoiceText.OnPress.TakeOver, ChatVoiceText.onPress(active = true, held = false, tap = true))
    }

    @Test
    fun theLevelMeterRisesFastAndFallsSlowly() {
        var l = 0f
        l = ChatVoiceText.smoothLevel(l, 10f) // loud: half way at once
        assertEquals(0.5f, l, 0.001f)
        val peak = ChatVoiceText.smoothLevel(l, 10f)
        assertTrue(peak > l)
        // one quiet 50 ms reading between words barely moves it (no flicker)
        val dip = ChatVoiceText.smoothLevel(peak, -2f)
        assertTrue("dip $dip vs $peak", peak - dip < 0.1f)
        assertEquals(ChatVoiceText.levelStep(peak), ChatVoiceText.levelStep(dip), 0.1001f)
        // silence brings it down within about a second (~20 readings)
        var s = peak
        repeat(20) { s = ChatVoiceText.smoothLevel(s, -2f) }
        assertEquals(0f, ChatVoiceText.levelStep(s), 0.1001f)
        assertEquals(0.3f, ChatVoiceText.levelStep(0.27f), 0f)
        assertEquals(1f, ChatVoiceText.levelStep(3f), 0f)
    }

    @Test
    fun repeatsAloneNeverStartAPress() {
        val k = ChatPttKeys()
        assertFalse(k.down(0, repeatCount = 5))
        assertFalse(k.pressed)
        assertTrue(k.down(10, repeatCount = 0))
        assertFalse(k.down(60, repeatCount = 1))
        assertFalse(k.down(ChatPttKeys.STALE_MS + 50, repeatCount = 0)) // repeats kept it fresh: still the same press
        assertTrue(k.up())
        assertFalse(k.up())
    }
}
