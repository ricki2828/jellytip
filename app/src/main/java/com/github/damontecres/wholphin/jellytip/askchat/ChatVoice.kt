package com.github.damontecres.wholphin.jellytip.askchat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.roundToInt

/*
 * Press-to-talk for the TV chat: hold OK on the mic, speak, release to send. Wraps SpeechRecognizer directly.
 * - Held: the window asks for very long silences; if the recognizer still ends one (silence, end-of-speech, a result), the next window
 *   starts on the SAME recognizer at once (no destroy / create gap, which cost seconds on the Chromecast) and its words are appended.
 *   Only the key-up ends it: stopListening, wait for the last words, send.
 * - Tap (< TAP_MS, or a sidebar hold already released): listens, restarts while windows still add words, sends once a window hears
 *   nothing. A new press takes the tap-listen over as a hold.
 * The decisions are pure (ChatVoiceText / ChatPttKeys, unit-tested); this class only wires them to the recognizer. Main thread only.
 * Every step is in JellytipTrace ("voice"), so a TV capture shows what the recognizer did.
 */

/** What the page shows of the voice state. */
data class ChatVoiceUi(
    val listening: Boolean = false,
    /** 0..1 in tenths, smoothed (fast rise, slow fall), so the bar is steady while someone talks. */
    val level: Float = 0f,
    /** A string resource for the last failure, until the next press / the next typed character. */
    val errorRes: Int? = null,
    /** Listening after a tap (or a Search hold), not a held OK: it sends by itself when the speaker stops, so no "release" hint. */
    val tap: Boolean = false,
)

/** The pure part: how segments join, press / release / end decisions, and the level meter. */
object ChatVoiceText {
    const val TAP_MS = 300L
    const val MAX_RESTARTS = 40

    /** Spoken segments appended with one space, whitespace collapsed; an empty side adds nothing. */
    fun join(
        a: String,
        b: String,
    ): String = (a + " " + b).replace(Regex("\\s+"), " ").trim()

    /** A press shorter than [TAP_MS] is a tap. */
    fun isTap(heldMs: Long): Boolean = heldMs < TAP_MS

    enum class Next { Restart, Finish }

    /**
     * The recognizer ended one listening window (a result, or a no-match / speech-timeout error).
     * - Held (push-to-talk): restart, whatever the window heard: only the key-up ends it.
     * - Tap (listening without a held key): restart while windows still add words; a window that adds nothing means the speaker stopped.
     * - Released: the utterance is over. Every case stops at [MAX_RESTARTS].
     */
    fun afterEnd(
        held: Boolean,
        tap: Boolean,
        restarts: Int,
        added: Boolean = false,
    ): Next =
        when {
            restarts >= MAX_RESTARTS -> Next.Finish
            held && !tap -> Next.Restart
            tap && added -> Next.Restart
            else -> Next.Finish
        }

    /** What a key-down on the mic does. */
    enum class OnPress { Start, TakeOver, Ignore }

    /**
     * Idle: start a hold. Listening after a tap (or a sidebar hold already released): the new press takes it over, so it becomes a
     * hold and its release sends. Already held: nothing (a repeat or a second key-down of the same press).
     */
    fun onPress(
        active: Boolean,
        held: Boolean,
        tap: Boolean,
    ): OnPress =
        when {
            !active -> OnPress.Start
            tap && !held -> OnPress.TakeOver
            else -> OnPress.Ignore
        }

    /** What a key-up does. */
    enum class OnRelease { Ignore, ToTap, Stop }

    /**
     * Not holding: nothing. A press that took over a tap-listen: stop and send (the old "second tap sends now"). A short first press:
     * the tap fallback (keep listening, it sends when the speaker stops). Anything else: stop, wait for the final words, send.
     */
    fun onRelease(
        active: Boolean,
        held: Boolean,
        heldMs: Long,
        tookOver: Boolean,
    ): OnRelease =
        when {
            !active || !held -> OnRelease.Ignore
            tookOver -> OnRelease.Stop
            isTap(heldMs) -> OnRelease.ToTap
            else -> OnRelease.Stop
        }

    /** Errors that just mean "that listening window had nothing / is busy": restart while listening continues rather than fail. */
    fun isQuietError(error: Int): Boolean = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

    /**
     * Some TV recognizers answer ERROR_CLIENT / ERROR_SERVER (or SERVER_DISCONNECTED) when a window starts right after the previous
     * one. While listening continues (held or tap), the FIRST such error after each restart is quiet (start a fresh recognizer, counted
     * toward the cap), not a failure.
     */
    fun isGlitchAfterRestart(
        error: Int,
        held: Boolean,
        tap: Boolean,
        restarts: Int,
        glitchUsed: Boolean,
    ): Boolean = (held || tap) && restarts > 0 && !glitchUsed && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER || error == ERROR_SERVER_DISCONNECTED)

    /** SpeechRecognizer.ERROR_SERVER_DISCONNECTED (API 31). */
    const val ERROR_SERVER_DISCONNECTED = 11

    /**
     * The tolerated glitch is spent by a glitch and only refilled by a partial / final result (proof the recognizer works). Two glitches
     * with no result between them fail fast with the real error instead of looping to the restart cap.
     */
    fun glitchUsedAfter(
        used: Boolean,
        glitch: Boolean,
        resultArrived: Boolean,
    ): Boolean = if (resultArrived) false else used || glitch

    /** A silent press: nothing was added to what was in the box at press time, so there is nothing new to send (never re-send typed text). */
    fun heardNothing(
        base: String,
        text: String,
    ): Boolean = text.isBlank() || join(text, "") == join(base, "")

    fun quantizeLevel(rmsdB: Float): Float {
        val n = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        return (n * 4).roundToInt() / 4f
    }

    /** rms dB to 0..1 (about -2 dB silent, 10 dB loud speech). */
    fun normLevel(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

    const val LEVEL_ATTACK = 0.5f
    const val LEVEL_DECAY = 0.12f

    /**
     * The meter's next (unrounded) value: rises quickly toward a louder reading, falls slowly, so speech reads as one steady bar instead of
     * flicking between every 50 ms reading. Carried across restarts (a new window does not drop it to zero).
     */
    fun smoothLevel(
        prev: Float,
        rmsdB: Float,
    ): Float {
        val target = normLevel(rmsdB)
        val k = if (target > prev) LEVEL_ATTACK else LEVEL_DECAY
        return (prev + (target - prev) * k).coerceIn(0f, 1f)
    }

    /** What the bar shows: tenths, so a level change recomposes the page at most a few times a second. */
    fun levelStep(level: Float): Float = (level.coerceIn(0f, 1f) * 10).roundToInt() / 10f
}

/**
 * The mic button's key gate (pure): turns the remote's OK events into one press. The first key-down starts it; auto-repeats, and a
 * second key-down with repeatCount 0 while the press is still fresh (some remotes send those), are ignored; only a key-up of a press
 * that started here ends it. A key-down long after the last event means the key-up was missed: a new press.
 */
class ChatPttKeys(
    private val staleMs: Long = STALE_MS,
) {
    private var downAt = -1L
    private var lastAt = -1L

    val pressed: Boolean get() = downAt >= 0

    /** True = start talking. */
    fun down(
        now: Long,
        repeatCount: Int,
    ): Boolean {
        if (repeatCount > 0) {
            if (pressed) lastAt = now
            return false
        }
        if (pressed && now - lastAt < staleMs) {
            lastAt = now
            return false
        }
        downAt = now
        lastAt = now
        return true
    }

    /** True = stop talking (this press started here). */
    fun up(): Boolean {
        if (!pressed) return false
        reset()
        return true
    }

    fun reset() {
        downAt = -1L
        lastAt = -1L
    }

    companion object {
        const val STALE_MS = 1_500L
    }
}

class ChatVoice(
    private val context: Context,
    /** The live text for the input box: what was there + everything recognised so far (partials included). */
    private val onText: (String) -> Unit,
    /** The utterance is complete (release / tap end): send this as the turn. Never called with blank text. */
    private val onFinal: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _ui = MutableStateFlow(ChatVoiceUi())
    val ui: StateFlow<ChatVoiceUi> = _ui.asStateFlow()

    val isAvailable: Boolean by lazy { SpeechRecognizer.isRecognitionAvailable(context) }
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var recognizer: SpeechRecognizer? = null
    private var gen = 0 // one per listening window: callbacks of an older window on the same (reused) recognizer are dropped
    private var windowOpen = false // startListening was called and neither a result nor an error has ended that window yet
    private var recreate = false // the next restart builds a new recognizer (after a glitch) instead of reusing this one
    private var active = false
    private var held = false
    private var tap = false
    private var tookOver = false
    private var committed = ""
    private var pressBase = ""
    private var partial = ""
    private var restarts = 0
    private var glitchUsed = false // the tolerated post-restart ERROR_CLIENT / ERROR_SERVER is spent; refilled only by a result
    private var downAt = 0L
    private var level = 0f

    private val restartRun = Runnable { if (active && (held || tap)) restartWindow() }
    private val watchdog = Runnable { finish(send = true) } // a missed key-up must not leave the mic open
    private val stopFallback = Runnable { finish(send = true) } // the recognizer never answered stopListening()

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                handler.post {
                    if (active) {
                        trace("audio focus lost")
                        finish(send = true)
                    }
                }
            }
        }
    private val focusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setOnAudioFocusChangeListener(focusListener, handler).build()
        } else {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED } ?: true
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun networkUp(): Boolean {
        val n = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun trace(msg: String) = JellytipTrace.log("voice", "chat $msg")

    /**
     * Held: ask the recognizer not to end on silence at all (stopListening on key-up ends it). Many TV recognizers ignore these extras;
     * the restart-while-held loop is what guarantees "never cut off". Tap: end-of-speech after a few seconds of quiet.
     */
    private fun intent(): Intent {
        val hold = held && !tap
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (hold) HOLD_SILENCE_MS else 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (hold) HOLD_SILENCE_MS else 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, if (hold) HOLD_SILENCE_MS else 2000L)
        }
    }

    /** OK went down on the mic. [base] is what is already in the input box: the speech is appended to it. */
    fun press(base: String) {
        when (ChatVoiceText.onPress(active, held, tap)) {
            ChatVoiceText.OnPress.Ignore -> return

            ChatVoiceText.OnPress.TakeOver -> {
                // listening after a tap / a released sidebar hold: this press holds it now, and its release sends
                trace("press: takes over the tap-listen")
                held = true
                tap = false
                tookOver = true
                downAt = SystemClock.uptimeMillis()
                _ui.value = _ui.value.copy(tap = false)
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, MAX_HOLD_MS)
                return
            }

            ChatVoiceText.OnPress.Start -> Unit
        }
        if (!isAvailable) return fail(R.string.voice_error_client)
        if (!hasPermission) return fail(R.string.voice_error_permissions)
        if (!networkUp()) return fail(R.string.voice_error_network)
        if (!requestFocus()) return fail(R.string.voice_error_audio)
        trace("press: start")
        active = true
        held = true
        tap = false
        tookOver = false
        pressBase = ChatVoiceText.join(base, "")
        committed = pressBase
        partial = ""
        restarts = 0
        glitchUsed = false
        recreate = false
        level = 0f
        downAt = SystemClock.uptimeMillis()
        _ui.value = ChatVoiceUi(listening = true)
        handler.postDelayed(watchdog, MAX_HOLD_MS)
        begin()
    }

    /** OK came up (or focus left the mic). No-op unless a hold is in progress. */
    fun release() {
        val heldMs = SystemClock.uptimeMillis() - downAt
        when (ChatVoiceText.onRelease(active, held, heldMs, tookOver)) {
            ChatVoiceText.OnRelease.Ignore -> Unit

            ChatVoiceText.OnRelease.ToTap -> {
                trace("release after ${heldMs}ms: tap, listening on")
                held = false
                tap = true // the fallback: keep listening, it sends when the speaker stops
                _ui.value = _ui.value.copy(tap = true)
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, TAP_MAX_MS)
                // the window was started as a hold (no end on silence): start it again with the tap's end-of-speech
                val r = recognizer
                if (r != null && windowOpen) {
                    gen++ // the old window's callbacks are dropped
                    windowOpen = false
                    try {
                        r.cancel()
                    } catch (e: Exception) {
                        Timber.w(e, "Chat voice: cancel failed")
                        recreate = true
                    }
                    handler.removeCallbacks(restartRun)
                    handler.postDelayed(restartRun, RECREATE_DELAY_MS)
                }
            }

            ChatVoiceText.OnRelease.Stop -> {
                trace("release after ${heldMs}ms: stop, waiting for the last words (restarts=$restarts)")
                held = false
                finishTap()
            }
        }
    }

    /** A pointer click: a tap. */
    fun tapOnce(base: String) {
        press(base)
        release()
    }

    /** Stop listening and send what was heard (the recognizer may still deliver a final segment first). */
    private fun finishTap() {
        held = false
        tap = false
        handler.removeCallbacks(restartRun)
        val r = recognizer
        if (r == null || !windowOpen) {
            finish(send = true) // between windows: nothing is running, send what we have
        } else {
            handler.postDelayed(stopFallback, STOP_WAIT_MS)
            try {
                r.stopListening()
            } catch (e: Exception) {
                Timber.w(e, "stopListening failed")
                finish(send = true)
            }
        }
    }

    /** Leaving the screen / Start over: stop everything, send nothing. */
    fun cancel() {
        if (active) {
            trace("cancel")
            finish(send = false)
        }
        _ui.value = ChatVoiceUi()
    }

    /** The permission prompt was refused. */
    fun denied() {
        _ui.value = ChatVoiceUi(errorRes = R.string.voice_error_permissions)
    }

    /** The user typed or the text was cleared: a stale error goes away. */
    fun clearError() {
        if (_ui.value.errorRes != null) _ui.value = _ui.value.copy(errorRes = null)
    }

    private fun begin() {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        recreate = false
        startWindow(r)
    }

    /** The next window on the same recognizer (no destroy / create gap); a new recognizer only after a glitch or a failed start. */
    private fun restartWindow() {
        val r = recognizer
        if (r == null || recreate) {
            destroyRecognizer()
            begin()
        } else {
            startWindow(r)
        }
    }

    private fun startWindow(r: SpeechRecognizer) {
        gen++
        r.setRecognitionListener(listener(r, gen))
        try {
            r.startListening(intent())
            windowOpen = true
            trace("window ${restarts + 1} started (gen=$gen, ${if (held) "held" else if (tap) "tap" else "released"})")
        } catch (e: Exception) {
            Timber.e(e, "Chat voice: start failed")
            trace("start failed: ${e.javaClass.simpleName}")
            fail(R.string.voice_error_start_failed)
        }
    }

    private fun destroyRecognizer() {
        val r = recognizer
        recognizer = null // first: callbacks from cancel() must find no valid recognizer
        windowOpen = false
        if (r != null) {
            try {
                r.cancel()
                r.destroy()
            } catch (e: Exception) {
                Timber.w(e, "Chat voice: destroy failed")
            }
        }
    }

    private fun cleanup() {
        handler.removeCallbacks(restartRun)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(stopFallback)
        destroyRecognizer()
        abandonFocus()
        active = false
        held = false
        tap = false
        tookOver = false
        level = 0f
    }

    private fun finish(send: Boolean) {
        val text = ChatVoiceText.join(committed, partial)
        cleanup()
        _ui.value = ChatVoiceUi()
        if (!send) return
        trace("finish: ${text.length} chars after $restarts restarts")
        if (ChatVoiceText.heardNothing(pressBase, text)) {
            _ui.value = ChatVoiceUi(errorRes = R.string.voice_error_no_match) // typed text stays in the box, nothing is sent
        } else {
            onText(text)
            onFinal(text)
        }
    }

    /** A failure: stop, keep whatever was recognised in the input (not sent), say why. */
    private fun fail(res: Int) {
        val text = ChatVoiceText.join(committed, partial)
        cleanup()
        if (text.isNotBlank()) onText(text)
        _ui.value = ChatVoiceUi(errorRes = res)
    }

    /** A window ended; [added] = it heard words. */
    private fun ended(added: Boolean) {
        windowOpen = false
        when (ChatVoiceText.afterEnd(held, tap, restarts, added)) {
            ChatVoiceText.Next.Restart -> {
                restarts++
                handler.removeCallbacks(restartRun)
                handler.postDelayed(restartRun, if (recreate) RECREATE_DELAY_MS else RESTART_DELAY_MS)
            }

            ChatVoiceText.Next.Finish -> finish(send = true)
        }
    }

    private fun listener(
        mine: SpeechRecognizer,
        myGen: Int,
    ) = object : RecognitionListener {
        private fun valid() = recognizer === mine && gen == myGen

        override fun onReadyForSpeech(params: Bundle?) {
            if (valid()) trace("ready (gen=$myGen)")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!valid()) return
            level = ChatVoiceText.smoothLevel(level, rmsdB)
            val shown = ChatVoiceText.levelStep(level)
            if (shown != _ui.value.level) _ui.value = _ui.value.copy(level = shown)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!valid()) return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
            partial = p
            glitchUsed = ChatVoiceText.glitchUsedAfter(glitchUsed, glitch = false, resultArrived = true)
            onText(ChatVoiceText.join(committed, partial))
        }

        override fun onResults(results: Bundle?) {
            if (!valid()) return
            val seg = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: partial
            trace("window ${restarts + 1} result: ${seg.length} chars")
            committed = ChatVoiceText.join(committed, seg)
            glitchUsed = ChatVoiceText.glitchUsedAfter(glitchUsed, glitch = false, resultArrived = true)
            partial = ""
            onText(committed)
            ended(added = seg.isNotBlank())
        }

        override fun onError(error: Int) {
            if (!valid()) return
            Timber.w("Chat voice: recognizer error $error")
            val glitch = ChatVoiceText.isGlitchAfterRestart(error, held, tap, restarts, glitchUsed)
            trace("window ${restarts + 1} error $error${if (glitch) " (glitch, new recognizer)" else ""}")
            glitchUsed = ChatVoiceText.glitchUsedAfter(glitchUsed, glitch, resultArrived = false)
            if (glitch || ChatVoiceText.isQuietError(error)) {
                val added = partial.isNotBlank()
                committed = ChatVoiceText.join(committed, partial) // keep what the cut-off window had heard
                partial = ""
                if (glitch || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) recreate = true
                ended(added)
            } else {
                fail(errorRes(error))
            }
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

    private fun errorRes(error: Int): Int =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> R.string.voice_error_audio
            SpeechRecognizer.ERROR_CLIENT -> R.string.voice_error_client
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_error_permissions
            SpeechRecognizer.ERROR_NETWORK -> R.string.voice_error_network
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_error_network_timeout
            SpeechRecognizer.ERROR_SERVER -> R.string.voice_error_server
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> R.string.voice_error_busy
            else -> R.string.voice_error_unknown
        }

    private companion object {
        const val RESTART_DELAY_MS = 40L
        const val RECREATE_DELAY_MS = 150L
        const val STOP_WAIT_MS = 3000L
        const val MAX_HOLD_MS = 60_000L
        const val TAP_MAX_MS = 20_000L
        const val HOLD_SILENCE_MS = 30_000L
    }
}
