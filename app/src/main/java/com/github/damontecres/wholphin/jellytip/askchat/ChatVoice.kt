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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.roundToInt

/*
 * Press-to-talk for the TV chat: hold OK on the mic, speak, release to send. Wraps SpeechRecognizer directly (the shared VoiceInputManager,
 * which Search uses, ends on the first pause). While OK is held a recognizer that ends on silence / end-of-speech is restarted and its
 * text appended, so a pause never cuts the speaker off; release stops and sends. A short tap (< TAP_MS) is the fallback: it listens with
 * long-silence hints and sends when the recognizer finishes by itself (a second tap sends now).
 * The decisions are pure (ChatVoiceText, unit-tested); this class only wires them to the recognizer. Main thread only.
 */

/** What the page shows of the voice state. */
data class ChatVoiceUi(
    val listening: Boolean = false,
    /** 0..1 in quarter steps (so a level bar does not recompose the page ten times a second). */
    val level: Float = 0f,
    /** A string resource for the last failure, until the next press / the next typed character. */
    val errorRes: Int? = null,
)

/** The pure part: how segments join, tap vs hold, and what to do when the recognizer ends. */
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
     * The recognizer ended (a result, or a no-match / speech-timeout error). While OK is held on a hold press, restart it (up to
     * [MAX_RESTARTS] times in one hold); after release, in tap mode, or out of restarts, the utterance is over.
     */
    fun afterEnd(
        held: Boolean,
        tap: Boolean,
        restarts: Int,
    ): Next = if (held && !tap && restarts < MAX_RESTARTS) Next.Restart else Next.Finish

    /** Errors that just mean "that listening window had nothing / is busy": restart while held rather than fail. */
    fun isQuietError(error: Int): Boolean = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY

    /**
     * Some TV recognizers answer ERROR_CLIENT / ERROR_SERVER when a new recognizer starts right after the previous one was destroyed.
     * While held, the FIRST such error after each restart is quiet (restart again, counted toward the cap), not a failure.
     */
    fun isGlitchAfterRestart(
        error: Int,
        held: Boolean,
        tap: Boolean,
        restarts: Int,
        glitchUsed: Boolean,
    ): Boolean = held && !tap && restarts > 0 && !glitchUsed && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER)

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
    private var active = false
    private var held = false
    private var tap = false
    private var committed = ""
    private var pressBase = ""
    private var partial = ""
    private var restarts = 0
    private var glitchUsed = false // the tolerated post-restart ERROR_CLIENT / ERROR_SERVER is spent; refilled only by a result
    private var downAt = 0L

    private val restartRun = Runnable { if (active && held && !tap) begin() }
    private val watchdog = Runnable { finish(send = true) } // a missed key-up must not leave the mic open
    private val stopFallback = Runnable { finish(send = true) } // the recognizer never answered stopListening()

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) handler.post { if (active) finish(send = true) }
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

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Belt: many TV recognizers ignore these; the restart-while-held loop is what guarantees "never cut off"
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        }

    /** OK went down on the mic. [base] is what is already in the input box: the speech is appended to it. */
    fun press(base: String) {
        if (active) {
            if (tap) finishTap() // a second tap while a tap-listen is running: done, send now
            return
        }
        if (!isAvailable) return fail(R.string.voice_error_client)
        if (!hasPermission) return fail(R.string.voice_error_permissions)
        if (!networkUp()) return fail(R.string.voice_error_network)
        if (!requestFocus()) return fail(R.string.voice_error_audio)
        active = true
        held = true
        tap = false
        pressBase = ChatVoiceText.join(base, "")
        committed = pressBase
        partial = ""
        restarts = 0
        glitchUsed = false
        downAt = SystemClock.uptimeMillis()
        _ui.value = ChatVoiceUi(listening = true)
        handler.postDelayed(watchdog, MAX_HOLD_MS)
        begin()
    }

    /** OK came up (or the mic lost focus). No-op unless a hold is in progress. */
    fun release() {
        if (!active || !held) return
        held = false
        if (ChatVoiceText.isTap(SystemClock.uptimeMillis() - downAt)) {
            tap = true // the fallback: keep listening, the recognizer's own end-of-speech sends
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, TAP_MAX_MS)
            return
        }
        finishTap()
    }

    /** A pointer click: a tap. */
    fun tapOnce(base: String) {
        press(base)
        release()
    }

    /** Stop listening and send what was heard (the recognizer may still deliver a final segment first). */
    private fun finishTap() {
        val r = recognizer
        if (r == null) {
            finish(send = true) // between restarts: nothing is running, send what we have
        } else {
            handler.removeCallbacks(restartRun)
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
        if (active) finish(send = false)
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
        r.setRecognitionListener(listener(r))
        try {
            r.startListening(intent())
        } catch (e: Exception) {
            Timber.e(e, "Chat voice: start failed")
            fail(R.string.voice_error_start_failed)
        }
    }

    private fun destroyRecognizer() {
        val r = recognizer
        recognizer = null // first: callbacks from cancel() must find no valid recognizer
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
    }

    private fun finish(send: Boolean) {
        val text = ChatVoiceText.join(committed, partial)
        cleanup()
        _ui.value = ChatVoiceUi()
        if (!send) return
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

    private fun ended() {
        when (ChatVoiceText.afterEnd(held, tap, restarts)) {
            ChatVoiceText.Next.Restart -> {
                restarts++
                destroyRecognizer()
                handler.postDelayed(restartRun, RESTART_DELAY_MS)
            }

            ChatVoiceText.Next.Finish -> finish(send = true)
        }
    }

    private fun listener(mine: SpeechRecognizer) =
        object : RecognitionListener {
            private fun valid() = recognizer === mine

            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (!valid()) return
                val q = ChatVoiceText.quantizeLevel(rmsdB)
                if (q != _ui.value.level) _ui.value = _ui.value.copy(level = q)
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
                committed = ChatVoiceText.join(committed, seg)
                glitchUsed = ChatVoiceText.glitchUsedAfter(glitchUsed, glitch = false, resultArrived = true)
                partial = ""
                onText(committed)
                ended()
            }

            override fun onError(error: Int) {
                if (!valid()) return
                Timber.w("Chat voice: recognizer error $error")
                val glitch = ChatVoiceText.isGlitchAfterRestart(error, held, tap, restarts, glitchUsed)
                glitchUsed = ChatVoiceText.glitchUsedAfter(glitchUsed, glitch, resultArrived = false)
                if (glitch || ChatVoiceText.isQuietError(error)) {
                    committed = ChatVoiceText.join(committed, partial) // keep what the cut-off window had heard
                    partial = ""
                    ended()
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
        const val RESTART_DELAY_MS = 150L
        const val STOP_WAIT_MS = 3000L
        const val MAX_HOLD_MS = 60_000L
        const val TAP_MAX_MS = 20_000L
    }
}
