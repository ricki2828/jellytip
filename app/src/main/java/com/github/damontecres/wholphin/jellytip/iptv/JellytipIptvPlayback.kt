@file:OptIn(markerClass = [UnstableApi::class])

package com.github.damontecres.wholphin.jellytip.iptv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the IPTV player should show: a live channel, or a catch-up window of it. */
data class IptvTarget(
    val channelId: String,
    val label: String,
    val start: Long? = null,
    val stop: Long? = null,
    /** Set for a recording / IPTV film / series episode instead of a channel (then [channelId] is just an id for traces). */
    val recordingId: String? = null,
    val vodId: String? = null,
    val seriesId: String? = null,
    val episodeId: String? = null,
    /** Where to start a recording or film (ms). */
    val resumeMs: Long = 0,
) {
    val isLive get() = recordingId == null && vodId == null && episodeId == null && start == null

    /** The `item` key for POST /progress (recordings and IPTV films/episodes). */
    val progressItem: String?
        get() = recordingId?.let { "rec:$it" } ?: episodeId ?: vodId
}

sealed interface IptvPlayState {
    val target: IptvTarget?

    data object Idle : IptvPlayState {
        override val target: IptvTarget? = null
    }

    data class Opening(
        override val target: IptvTarget,
    ) : IptvPlayState

    data class Playing(
        override val target: IptvTarget,
    ) : IptvPlayState

    /** 409: the provider's connections are all taken; [message] says who holds it ("ricki28 watching Sky Sports …"). */
    data class Full(
        override val target: IptvTarget,
        val message: String,
    ) : IptvPlayState

    data class Failed(
        override val target: IptvTarget,
        val message: String,
    ) : IptvPlayState
}

/**
 * The one IPTV player of the app (guide preview and full screen draw the same ExoPlayer, so going full screen never
 * reconnects). Each stream uses the provider's one connection, so: a new target replaces the old stream (same `device`
 * = a zap on the server), 410 stale / 403 expired re-call /play once, and the stream is released as soon as no
 * Live TV screen is showing ([attach] / [detach]).
 */
@Singleton
class JellytipIptvPlayback
    @Inject
    constructor(
        private val service: JellytipIptvService,
        @param:ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val _state = MutableStateFlow<IptvPlayState>(IptvPlayState.Idle)
        val state: StateFlow<IptvPlayState> = _state

        /** The ExoPlayer once created (Compose state so surfaces appear when it exists). */
        var player by mutableStateOf<ExoPlayer?>(null)
            private set

        /** Channels of the guide tab that opened full screen, so Up/Down there zaps through the same list. */
        var lineup: List<IptvChannel> = emptyList()

        /**
         * Other channels showing the same event, per lineup channel id, best first: full screen falls to the next one when
         * a stream fails (live-sport plan §4). Empty = no fallback.
         */
        var alternates: Map<String, List<IptvChannel>> = emptyMap()

        /** OK on an "on now" card (Home row, guide strip): lineup = the cards, [channel] = the card's channel or an alternate. */
        fun openOnNow(
            card: IptvOnNow,
            all: List<IptvOnNow>,
            channel: IptvChannel,
            reason: String,
        ) {
            lineup = all.map { if (it === card) channel else it.channel }
            alternates =
                all.associate { o ->
                    val main = if (o === card) channel else o.channel
                    main.id to (listOf(o.channel) + o.alternates).filter { it.id != main.id }
                }
            play(IptvTarget(channel.id, channel.name), reason)
        }

        /** Channel the full-screen player was on when it closed: the guide lands on it (and keeps it playing). */
        var lastFullScreenChannel: String? = null

        private var job: Job? = null
        private var renewed = false
        private val owners = mutableSetOf<String>()
        private var releaseJob: Job? = null

        init {
            // Stop the stream whenever the app is hidden, even if no Live TV page is composed (a page that never
            // drew, or a stuck screen, must not keep the provider's one connection and the audio going)
            (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityStopped(activity: android.app.Activity) {
                        if (!activity.isChangingConfigurations && _state.value !is IptvPlayState.Idle) {
                            stop("app hidden (activity stopped)")
                        }
                    }

                    override fun onActivityCreated(
                        activity: android.app.Activity,
                        savedInstanceState: android.os.Bundle?,
                    ) {}

                    override fun onActivityStarted(activity: android.app.Activity) {}

                    override fun onActivityResumed(activity: android.app.Activity) {}

                    override fun onActivityPaused(activity: android.app.Activity) {}

                    override fun onActivitySaveInstanceState(
                        activity: android.app.Activity,
                        outState: android.os.Bundle,
                    ) {}

                    override fun onActivityDestroyed(activity: android.app.Activity) {}
                },
            )
        }

        private val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val t = _state.value.target ?: return
                    JellytipTrace.player("iptv", "state=$playbackState ${t.channelId}")
                    if (playbackState == Player.STATE_READY && _state.value is IptvPlayState.Opening) {
                        setState(IptvPlayState.Playing(t), "ready")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val t = _state.value.target ?: return
                    val http = generateSequence(error as Throwable?) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    val code = http?.headerFields?.entries?.firstOrNull { it.key.equals("X-Iptv-Error", ignoreCase = true) }?.value?.firstOrNull()
                    JellytipTrace.player("iptv", "error ${error.errorCodeName} http=${http?.responseCode} code=$code ${t.channelId}")
                    when (http?.let { IptvRules.linkFailure(it.responseCode, code) }) {
                        IptvRules.LinkFailure.RENEW, IptvRules.LinkFailure.FULL -> {
                            // Re-call /play: a fresh link (renew), or the 409 answer naming who holds the connection
                            if (!renewed) {
                                renewed = true
                                launchOpen(t, "renew after ${http?.responseCode}")
                                return
                            }
                            fail(t, IptvRules.failureText(code, "The stream stopped."))
                        }

                        else -> fail(t, IptvRules.failureText(code, "The stream stopped (${error.errorCodeName})."))
                    }
                }
            }

        private fun ensurePlayer(): ExoPlayer =
            player ?: run {
                val extractors =
                    DefaultExtractorsFactory().setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                    )
                val http =
                    DefaultHttpDataSource
                        .Factory()
                        .setUserAgent("Jellytip")
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                        .setReadTimeoutMs(READ_TIMEOUT_MS)
                // Small buffers: a zap should show the new channel in about a second
                val load =
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(2_000, 20_000, 700, 1_500)
                        .build()
                ExoPlayer
                    .Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
                    .setLoadControl(load)
                    .build()
                    .also {
                        it.addListener(listener)
                        player = it
                        JellytipTrace.player("iptv", "player created")
                    }
            }

        /** Show [target]. Same target already opening/playing = nothing to do (focus coming back, full screen opening). */
        fun play(
            target: IptvTarget,
            reason: String,
        ) {
            val s = _state.value
            if (s.target == target && (s is IptvPlayState.Opening || s is IptvPlayState.Playing)) {
                JellytipTrace.player("iptv", "play ${target.channelId} skipped: already ${describe(s)} ($reason)")
                return
            }
            renewed = false
            launchOpen(target, reason)
        }

        private fun launchOpen(
            target: IptvTarget,
            reason: String,
        ) {
            job?.cancel()
            setState(IptvPlayState.Opening(target), reason)
            JellytipTrace.player("iptv", "open ${target.channelId} ${target.label} start=${target.start} ($reason)")
            job =
                scope.launch {
                    when (val r = service.playItem(target)) {
                        is IptvPlayResult.Ok -> {
                            val url = service.streamUrl(r.answer.url)
                            if (url == null) {
                                fail(target, "The server sent a stream link the app can't use.")
                                return@launch
                            }
                            val ts = r.answer.mime?.contains("mp2t", true) == true || url.substringBefore('?').endsWith(".ts")
                            val item =
                                MediaItem
                                    .Builder()
                                    .setUri(url)
                                    .apply { if (ts) setMimeType(MimeTypes.VIDEO_MP2T) }
                                    .build()
                            val p = ensurePlayer()
                            if (target.resumeMs > 0) p.setMediaItem(item, target.resumeMs) else p.setMediaItem(item)
                            p.playWhenReady = true
                            p.prepare()
                        }

                        is IptvPlayResult.Full -> {
                            stopPlayer()
                            JellytipTrace.player("iptv", "full ${target.channelId}: ${r.message}")
                            setState(IptvPlayState.Full(target, r.message), "409")
                        }

                        is IptvPlayResult.Failed -> fail(target, r.message)
                    }
                }
        }

        private fun fail(
            target: IptvTarget,
            message: String,
        ) {
            stopPlayer()
            JellytipTrace.player("iptv", "failed ${target.channelId}: $message")
            setState(IptvPlayState.Failed(target, message), "failed")
        }

        private fun stopPlayer() {
            player?.run {
                stop()
                clearMediaItems()
            }
        }

        /** Stop now and free the provider connection (closing the HTTP stream releases the slot). */
        fun stop(reason: String) {
            job?.cancel()
            if (_state.value !is IptvPlayState.Idle) JellytipTrace.player("iptv", "stop ($reason)")
            stopPlayer()
            setState(IptvPlayState.Idle, reason)
        }

        /** Every state change goes through here, so `[player] iptv state` traces show the whole story. */
        private fun setState(
            next: IptvPlayState,
            reason: String,
        ) {
            val prev = _state.value
            _state.value = next
            if (prev != next) JellytipTrace.player("iptv", "state ${describe(prev)} -> ${describe(next)} ($reason)")
        }

        private fun describe(s: IptvPlayState): String =
            when (s) {
                IptvPlayState.Idle -> "Idle"
                is IptvPlayState.Opening -> "Opening(${s.target.channelId})"
                is IptvPlayState.Playing -> "Playing(${s.target.channelId})"
                is IptvPlayState.Full -> "Full(${s.target.channelId})"
                is IptvPlayState.Failed -> "Failed(${s.target.channelId})"
            }

        /** Save [target]'s resume point (recordings, films, episodes) from where the player is now. */
        fun saveProgress(target: IptvTarget) {
            val item = target.progressItem ?: return
            val p = player ?: return
            if (_state.value.target != target) return
            val pos = p.currentPosition / 1000.0
            val dur = p.duration.takeIf { it > 0 }?.div(1000.0) ?: return
            scope.launch { service.saveProgress(item, pos, dur) }
        }

        fun setVolume(volume: Float) {
            player?.volume = volume
        }

        /** A Live TV screen is showing (guide, full screen). */
        fun attach(owner: String) {
            owners += owner
            if (releaseJob?.isActive == true) JellytipTrace.player("iptv", "release cancelled by $owner")
            releaseJob?.cancel()
            JellytipTrace.player("iptv", "attach $owner owners=$owners ${describe(_state.value)}")
        }

        /**
         * A Live TV screen left. When none is left after a short handover (guide -> full screen swaps owners within a
         * frame or two), the stream stops and the player is released.
         */
        fun detach(owner: String) {
            owners -= owner
            JellytipTrace.player("iptv", "detach $owner owners=$owners ${describe(_state.value)}")
            if (owners.isNotEmpty()) return
            releaseJob?.cancel()
            releaseJob =
                scope.launch {
                    delay(HANDOVER_MS)
                    if (owners.isEmpty()) {
                        stop("left Live TV")
                        player?.let {
                            it.removeListener(listener)
                            it.release()
                        }
                        player = null
                        JellytipTrace.player("iptv", "player released")
                    }
                }
        }

        companion object {
            private const val HANDOVER_MS = 400L
            private const val CONNECT_TIMEOUT_MS = 8_000
            private const val READ_TIMEOUT_MS = 15_000
        }
    }
