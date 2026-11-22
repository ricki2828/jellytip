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
        assertEquals("tap mode: the recognizer's own end sends", ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = false, tap = true, restarts = 0))
        assertEquals(ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = true, tap = true, restarts = 0))
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
        assertFalse("not in tap mode", ChatVoiceText.isGlitchAfterRestart(c, held = true, tap = true, restarts = 1, glitchUsed = false))
        assertFalse("other errors still fail", ChatVoiceText.isGlitchAfterRestart(SpeechRecognizer.ERROR_AUDIO, held = true, tap = false, restarts = 1, glitchUsed = false))
        // and the restart it triggers still counts toward the cap
        assertEquals(ChatVoiceText.Next.Finish, ChatVoiceText.afterEnd(held = true, tap = false, restarts = ChatVoiceText.MAX_RESTARTS))
    }
}
