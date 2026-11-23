package com.github.damontecres.wholphin.ui.components

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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.askchat.ChatVoiceText
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

private const val RMS_DB_MIN = -2.0f
private const val RMS_DB_MAX = 10.0f
private const val MAX_RESULTS = 1
private const val LISTENING_TIMEOUT_MS = 8000L
private const val RECOGNIZER_RECREATE_DELAY_MS = 150L

private val ERROR_TO_RESOURCE_MAP =
    mapOf(
        SpeechRecognizer.ERROR_AUDIO to R.string.voice_error_audio,
        SpeechRecognizer.ERROR_CLIENT to R.string.voice_error_client,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to R.string.voice_error_permissions,
        SpeechRecognizer.ERROR_NETWORK to R.string.voice_error_network,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT to R.string.voice_error_network_timeout,
        SpeechRecognizer.ERROR_NO_MATCH to R.string.voice_error_no_match,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY to R.string.voice_error_busy,
        SpeechRecognizer.ERROR_SERVER to R.string.voice_error_server,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT to R.string.voice_error_speech_timeout,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to R.string.voice_error_busy,
    )

private val RETRYABLE_ERRORS =
    setOf(
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    )

private fun normalizeRmsDb(rmsdB: Float) = ((rmsdB - RMS_DB_MIN) / (RMS_DB_MAX - RMS_DB_MIN)).coerceIn(0f, 1f)

sealed interface VoiceInputState {
    data object Idle : VoiceInputState

    /** Recognizer is being initialized, not yet ready for speech. */
    data object Starting : VoiceInputState

    data object Listening : VoiceInputState

    data object Processing : VoiceInputState

    data class Result(
        val text: String,
    ) : VoiceInputState

    data class Error(
        val messageResId: Int,
        val isRetryable: Boolean,
    ) : VoiceInputState
}

@Singleton
class VoiceInputManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : Closeable {
        private val handler = Handler(Looper.getMainLooper())
        private val mainDispatcher = provideMainDispatcher()
        private val scope = CoroutineScope(mainDispatcher + SupervisorJob())
        private val mutex = Mutex()

        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val audioFocusListener =
            AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Timber.d("Permanent audio focus loss. Stopping listening.")
                        stopListening()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Timber.d("Transient audio focus loss. Ignoring to allow SpeechRecognizer to work.")
                    }

                    else -> {
                        Timber.d("Audio focus change: $focusChange")
                    }
                }
            }

        private val audioFocusRequest: AudioFocusRequest? by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setOnAudioFocusChangeListener(audioFocusListener, handler)
                    .build()
            } else {
                null
            }
        }

        @Suppress("DEPRECATION")
        private fun requestAudioFocusCompat(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
                    ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                )
            }

        @Suppress("DEPRECATION")
        private fun abandonAudioFocusCompat() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        }

        private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
        val state: StateFlow<VoiceInputState> = _state.asStateFlow()

        private val _soundLevel = MutableStateFlow(0f)
        val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

        private val _partialResult = MutableStateFlow("")
        val partialResult: StateFlow<String> = _partialResult.asStateFlow()

        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val hasPermission: Boolean
            get() =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED

        private var recognizer: SpeechRecognizer? = null
        private var timeoutJob: Job? = null

        /*
         * Jellytip (23-Nov): one search can span several recognizer windows. A window that ends with words (a pause) starts the next one
         * on the same recognizer and its words are appended; the first window that hears nothing new ends the search with everything
         * heard. Main thread (recognizer callbacks).
         */
        private var committed = ""
        private var windowPartial = ""
        private var restarts = 0

        private fun trace(msg: String) = JellytipTrace.log("voice", "search $msg")

        /** The window ended; [heard] = what it added. True = another window was started (the search goes on). */
        private fun continueAfter(
            rec: SpeechRecognizer,
            heard: String,
        ): Boolean {
            committed = ChatVoiceText.join(committed, heard)
            windowPartial = ""
            if (ChatVoiceText.afterEnd(held = false, tap = true, restarts = restarts, added = heard.isNotBlank()) != ChatVoiceText.Next.Restart) return false
            restarts++
            trace("window ${restarts + 1} (heard ${committed.length} chars so far)")
            handler.post {
                _partialResult.value = committed
                if (recognizer !== rec) return@post
                // listening again; the window timeout is re-armed so a window that never answers still ends the search
                _state.value = VoiceInputState.Listening
                startTimeout()
                try {
                    rec.startListening(recognitionIntent)
                } catch (e: Exception) {
                    Timber.w(e, "Voice restart failed")
                    finishWith(committed)
                }
            }
            return true
        }

        /** End the search with [text] (blank: no match). */
        private fun finishWith(text: String) {
            trace("finish: ${text.length} chars after $restarts restarts")
            cancelTimeout()
            destroyRecognizer()
            handler.post {
                _state.value =
                    if (text.isNotBlank()) {
                        VoiceInputState.Result(text)
                    } else {
                        VoiceInputState.Error(messageResId = R.string.voice_error_no_match, isRetryable = true)
                    }
                _soundLevel.value = 0f
                _partialResult.value = ""
            }
        }

        private fun provideMainDispatcher(): CoroutineDispatcher =
            try {
                WholphinDispatchers.Main.immediate
            } catch (_: IllegalStateException) {
                // Fallback for unit tests where Main dispatcher is not installed
                handler.asCoroutineDispatcher()
            }

        private val recognitionIntent by lazy {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                // Jellytip (23-Nov): a short pause must not end the search (many TV recognizers ignore these; the restart below is the fix)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            }
        }

        private fun isNetworkAvailable(): Boolean {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        fun startListening() {
            scope.launch {
                mutex.withLock {
                    val currentState = _state.value
                    if (currentState is VoiceInputState.Starting || currentState is VoiceInputState.Listening) {
                        return@withLock
                    }

                    val hadRecognizer = recognizer != null
                    if (hadRecognizer) {
                        destroyRecognizer()
                    }

                    if (!isNetworkAvailable()) {
                        handler.post {
                            _state.value =
                                VoiceInputState.Error(
                                    messageResId = R.string.voice_error_network,
                                    isRetryable = true,
                                )
                        }
                        return@withLock
                    }

                    val focusResult = requestAudioFocusCompat()
                    if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        handler.post {
                            _state.value =
                                VoiceInputState.Error(
                                    messageResId = R.string.voice_error_audio,
                                    isRetryable = true,
                                )
                        }
                        return@withLock
                    }

                    cancelTimeout()
                    committed = ""
                    windowPartial = ""
                    restarts = 0
                    trace("start")
                    handler.post {
                        _partialResult.value = ""
                        _soundLevel.value = 0f
                        _state.value = VoiceInputState.Starting
                    }

                    // Give the OS time to release the mic before recreating when replacing an old recognizer
                    if (hadRecognizer) {
                        delay(RECOGNIZER_RECREATE_DELAY_MS)
                    }

                    val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = newRecognizer
                    newRecognizer.setRecognitionListener(createRecognitionListener(newRecognizer))

                    try {
                        newRecognizer.startListening(recognitionIntent)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start speech recognition")
                        destroyRecognizer()
                        cancelTimeout()
                        handler.post {
                            _state.value =
                                VoiceInputState.Error(
                                    messageResId = R.string.voice_error_start_failed,
                                    isRetryable = true,
                                )
                        }
                    }
                }
            }
        }

        fun stopListening() {
            scope.launch {
                mutex.withLock {
                    cancelTimeout()
                    close()
                }
            }
        }

        private fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        private fun startTimeout() {
            cancelTimeout()
            timeoutJob =
                scope.launch {
                    delay(LISTENING_TIMEOUT_MS)
                    mutex.withLock {
                        if (_state.value is VoiceInputState.Listening && recognizer != null) {
                            val partial = ChatVoiceText.join(committed, windowPartial)
                            destroyRecognizer()
                            handler.post {
                                _soundLevel.value = 0f
                                _partialResult.value = ""
                                _state.value =
                                    if (partial.isNotBlank()) {
                                        VoiceInputState.Result(partial)
                                    } else {
                                        VoiceInputState.Error(
                                            messageResId = R.string.voice_error_timeout,
                                            isRetryable = true,
                                        )
                                    }
                            }
                        }
                    }
                }
        }

        fun acknowledge() {
            handler.post { _state.value = VoiceInputState.Idle }
        }

        fun onPermissionGranted() = startListening()

        fun onPermissionDenied() {
            Timber.w("RECORD_AUDIO permission denied")
            handler.post {
                _state.value =
                    VoiceInputState.Error(
                        messageResId = R.string.voice_error_permissions,
                        isRetryable = false,
                    )
            }
        }

        private fun destroyRecognizer() {
            abandonAudioFocusCompat()
            // Null out FIRST to invalidate callbacks before cancel() can trigger them
            val rec = recognizer
            recognizer = null
            rec?.let {
                try {
                    it.cancel()
                    it.destroy()
                } catch (e: Exception) {
                    Timber.w(e, "Error destroying speech recognizer")
                }
            }
        }

        override fun close() {
            destroyRecognizer()
            handler.post {
                _soundLevel.value = 0f
                _partialResult.value = ""
                _state.value = VoiceInputState.Idle
            }
        }

        private fun createRecognitionListener(activeRecognizer: SpeechRecognizer) =
            object : RecognitionListener {
                // Guard against callbacks from zombie recognizers
                private fun isValid() = recognizer === activeRecognizer

                override fun onReadyForSpeech(params: Bundle?) {
                    if (!isValid()) return
                    handler.post { _state.value = VoiceInputState.Listening }
                    startTimeout()
                }

                override fun onBeginningOfSpeech() {
                    if (!isValid()) return
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (!isValid()) return
                    handler.post { _soundLevel.value = normalizeRmsDb(rmsdB) }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (!isValid()) return
                    cancelTimeout()
                    handler.post { _state.value = VoiceInputState.Processing }
                }

                override fun onError(error: Int) {
                    if (!isValid()) return
                    Timber.e("Voice recognition error code: $error")
                    trace("window ${restarts + 1} error $error")
                    cancelTimeout()
                    // A quiet window (no match / speech timeout): the words it had go on, and if it heard any the search goes on too
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        val heard = windowPartial
                        if (continueAfter(activeRecognizer, heard)) return
                        if (committed.isNotBlank()) return finishWith(committed)
                    } else if (ChatVoiceText.join(committed, windowPartial).isNotBlank()) {
                        // a real failure after words were heard: search what was heard rather than lose it
                        return finishWith(ChatVoiceText.join(committed, windowPartial))
                    }
                    destroyRecognizer()

                    if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                        handler.post {
                            _state.value =
                                VoiceInputState.Error(
                                    messageResId = ERROR_TO_RESOURCE_MAP[error] ?: R.string.voice_error_unknown,
                                    isRetryable = false,
                                )
                            _soundLevel.value = 0f
                            _partialResult.value = ""
                        }
                        return
                    }
                    handler.post {
                        _state.value =
                            VoiceInputState.Error(
                                messageResId = ERROR_TO_RESOURCE_MAP[error] ?: R.string.voice_error_unknown,
                                isRetryable = error in RETRYABLE_ERRORS,
                            )
                        _soundLevel.value = 0f
                        _partialResult.value = ""
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!isValid()) return
                    cancelTimeout()
                    val window =
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() } ?: windowPartial
                    trace("window ${restarts + 1} result: ${window.length} chars")
                    if (continueAfter(activeRecognizer, window)) return
                    val spokenText = committed
                    handler.post {
                        _state.value =
                            if (spokenText.isNotBlank()) {
                                VoiceInputState.Result(spokenText)
                            } else {
                                VoiceInputState.Error(
                                    messageResId = R.string.voice_error_no_match,
                                    isRetryable = true,
                                )
                            }
                        _soundLevel.value = 0f
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!isValid()) return
                    partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            windowPartial = it
                            val shown = ChatVoiceText.join(committed, it)
                            handler.post { _partialResult.value = shown }
                        }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            }
    }
