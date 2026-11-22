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
        val holders: List<IptvHolder> = emptyList(),
    ) : IptvPlayState

    data class Failed(
        override val target: IptvTarget,
        val message: String,
        /** Stream recovery gave up (or was refused): OK on the screen retries ([JellytipIptvPlayback.retry]). */
        val canRetry: Boolean = false,
        /** The failure already went to the failed-stream memory (so a user report would count it twice). */
        val reported: Boolean = false,
        /** Why it failed (a catch-up window only goes back to live for some: [CatchUpPolicy.fallsBack]). */
        val cause: FailCause = FailCause.OTHER,
    ) : IptvPlayState
}

/** Why a target failed. */
enum class FailCause {
    OTHER,

    /** The server: no catch-up for that channel / time (400 no_catchup). */
    NO_CATCHUP,

    /** Stream recovery gave up after its retries. */
    GAVE_UP,

    /** All the provider's connections are in use. */
    BLOCKED,
    AUTH,
    DENIED,
    MISSING,
    LINK,
    RATE,
    ;

    companion object {
        fun of(d: StreamRecovery.Denied?): FailCause =
            when (d) {
                StreamRecovery.Denied.AUTH -> AUTH
                StreamRecovery.Denied.DENIED -> DENIED
                StreamRecovery.Denied.MISSING -> MISSING
                StreamRecovery.Denied.NOCATCHUP -> NO_CATCHUP
                StreamRecovery.Denied.LINK -> LINK
                StreamRecovery.Denied.RATE -> RATE
                null -> OTHER
            }
    }
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
        private val screensaverService: com.github.damontecres.wholphin.services.ScreensaverService,
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
            fromKickOff: Boolean = false,
        ) {
            lineup = all.map { if (it === card) channel else it.channel }
            alternates =
                all.associate { o ->
                    val main = if (o === card) channel else o.channel
                    main.id to (listOf(o.channel) + o.alternates).filter { it.id != main.id }
                }
            // From kick-off: the programme on now, from its start (catch-up); the player shows it as such
            play(if (fromKickOff) IptvTarget(channel.id, card.title, card.start, card.stop) else IptvTarget(channel.id, channel.name), reason)
        }

        /** Channel the full-screen player was on when it closed: the guide lands on it (and keeps it playing). */
        var lastFullScreenChannel: String? = null

        private var job: Job? = null
        private var renewed = false
        private val owners = mutableSetOf<String>()
        private var releaseJob: Job? = null

        init {
            // Offline / online for stream recovery: a Wi-Fi drop waits for the network instead of spending attempts and
            // reporting a healthy channel as failing
            runCatching {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return@runCatching
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                cm?.registerDefaultNetworkCallback(
                    object : android.net.ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: android.net.Network) {
                            scope.launch {
                                netOffline = false
                                if (rec.offline) dispatch(StreamRecovery.Event.Online)
                            }
                        }

                        override fun onLost(network: android.net.Network) {
                            scope.launch {
                                JellytipTrace.player("iptv", "network lost")
                                netOffline = true
                                dispatch(StreamRecovery.Event.Offline)
                            }
                        }
                    },
                )
            }.onFailure { JellytipTrace.player("iptv", "no network callback: ${it.javaClass.simpleName}") }
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

        /** What was last sent to the screensaver service (null = nothing yet). */
        private var screenKept: Boolean? = null

        /**
         * Keep the screen on (Chromecast screensaver off, and the app's own screensaver paused) while the IPTV player
         * is meant to play: playing or buffering with play-when-ready. Ricki 31-Oct: "Chromecast screensaver comes on
         * in live tv". A rebuffer doesn't let go (clearing the flag after a long idle would start the screensaver at
         * once); pause, stop, end and errors do.
         */
        private fun updateKeepScreenOn(p: Player?) {
            val keep = p != null && p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
            if (keep == screenKept) return
            screenKept = keep
            JellytipTrace.player("iptv", "keep screen on: $keep")
            screensaverService.keepScreenOn(keep)
        }

        private val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAYER_ERROR)) {
                        updateKeepScreenOn(player)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val t = _state.value.target ?: return
                    JellytipTrace.player("iptv", "state=$playbackState ${t.channelId}")
                    // Live buffer Auto: a rebuffer while playing a live channel steps its level up (from the next open)
                    if (playbackState == Player.STATE_BUFFERING && _state.value is IptvPlayState.Playing && loadedTarget == t) {
                        LiveBuffer.stalled(context, t)?.let { lvl ->
                            JellytipTrace.player("iptv", "live buffer auto: ${t.channelId} -> ${lvl.label}")
                            android.widget.Toast
                                .makeText(context, "Live buffer: ${lvl.label} from the next time you open this channel", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                    // READY from the previous stream while the next one is still being asked for is not the new one playing
                    if (playbackState == Player.STATE_READY && _state.value is IptvPlayState.Opening && loadedTarget == t) {
                        // A resume on a direct stream ExoPlayer can't seek (it started at 0): the remux, from the saved position
                        val p = player
                        if (!isRemux && p != null && SkipPolicy.resumeViaRemux(film(t), p.isCurrentMediaItemSeekable, remuxAsked, openStartMs)) {
                            skipViaRemux(t, openStartMs, "resume via remux")
                            return
                        }
                        // A skip just started playing: if the person moved on meanwhile (presses during the conversion), one
                        // follow-up skip to where they are now (the probe is cached by now: only the seek is paid again)
                        val opened = skipOpenMs
                        if (isRemux && opened != null) {
                            skipOpenMs = null
                            skipTicker?.cancel()
                            val queued = pendingRemuxSeekMs
                            // (against what THIS open asked for: a recovery reopen already landed at the queued target)
                            if (queued != null && SkipPolicy.followUp(openStartMs, queued)) {
                                skipViaRemux(t, queued, "skip follow-up")
                                return
                            }
                        }
                        setState(IptvPlayState.Playing(t), "ready")
                        pendingRemuxSeekMs = null
                        if (recTarget == t) {
                            dispatch(StreamRecovery.Event.Recovered)
                            watchStalls(t)
                            stableJob?.cancel()
                            if (rec.unstable) {
                                stableJob =
                                    scope.launch {
                                        delay(StreamRecovery.STABLE_MS)
                                        if (_state.value is IptvPlayState.Playing && _state.value.target == t && recTarget == t) dispatch(StreamRecovery.Event.Stable)
                                    }
                            }
                        }
                        // Jellytip failed-stream memory: a minute of playback counts as a good play (clears the ding)
                        okJob?.cancel()
                        okJob =
                            scope.launch {
                                // Count only time the picture really moves (not paused, not stuck buffering)
                                var playedS = 0L
                                var lastPos = player?.currentPosition ?: 0L
                                while (playedS < IptvStreamFail.GOOD_PLAY_S) {
                                    kotlinx.coroutines.delay(OK_TICK_MS)
                                    val now = _state.value
                                    if (now !is IptvPlayState.Playing || now.target != t || loadedTarget != t) return@launch
                                    val p = player ?: return@launch
                                    val pos = p.currentPosition
                                    // HLS live windows keep the position roughly flat: playing is enough there
                                    val moved = p.isCurrentMediaItemLive || pos > lastPos
                                    if (p.isPlaying && moved) playedS += OK_TICK_MS / 1000
                                    lastPos = pos
                                }
                                IptvStreamFail.report(t, ok = true, posSec = playedS)
                            }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val t = _state.value.target ?: return
                    // A zap: the old stream's error arrives after the new target was asked for. It is not the new one failing.
                    if (loadedTarget != t) {
                        JellytipTrace.player("iptv", "stale error ${error.errorCodeName} from ${loadedTarget?.channelId} ignored (now ${t.channelId})")
                        return
                    }
                    val http = generateSequence(error as Throwable?) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    val code = http?.headerFields?.entries?.firstOrNull { it.key.equals("X-Iptv-Error", ignoreCase = true) }?.value?.firstOrNull()
                    JellytipTrace.player("iptv", "error ${error.errorCodeName} http=${http?.responseCode} code=$code ${t.channelId}")
                    // A container ExoPlayer can't parse (Portlandia S01E01, labelled mkv, 21-Nov): ask the server to convert it
                    // once, at the same position; if it still can't, say so plainly (no retry loop)
                    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !t.isLive && t.recordingId == null) {
                        if (!remuxAsked) {
                            remuxAsked = true
                            val at = positionMs().takeIf { it > 5_000 } ?: t.resumeMs
                            JellytipTrace.player("iptv", "container unsupported ${t.channelId}: server remux from ${at / 1000} s")
                            stopPlayer()
                            _note.value = "Converting…"
                            launchOpen(t, "server remux (container unsupported)", remux = true, seekMs = at)
                        } else {
                            JellytipTrace.player("iptv", "container unsupported ${t.channelId} even after asking for the remux")
                            reportFail(t, "decode")
                            _note.value = null
                            stopPlayer()
                            setState(IptvPlayState.Failed(t, "This file's format can't be played on the TV.", reported = true), "container unsupported")
                        }
                        return
                    }
                    val link = http?.let { IptvRules.linkFailure(it.responseCode, code) }
                    when {
                        (link == IptvRules.LinkFailure.RENEW || link == IptvRules.LinkFailure.FULL) && !renewed -> {
                            // Re-call /play once: a fresh link (renew), or the 409 answer naming who holds the connection
                            renewed = true
                            launchOpen(t, "renew after ${http?.responseCode}", rec.format, recovering = rec.conn == StreamRecovery.Conn.OPENING)
                        }
                        link == IptvRules.LinkFailure.FULL -> dispatch(StreamRecovery.Event.Busy)
                        link == IptvRules.LinkFailure.RENEW -> dispatch(StreamRecovery.Event.Fatal(StreamRecovery.Denied.LINK))
                        else -> {
                            val refusal = http?.let { StreamRecovery.permanentReason(it.responseCode, code) }
                            if (refusal != null) {
                                dispatch(StreamRecovery.Event.Fatal(refusal))
                            } else {
                                // A device decode fault at open is logged (never counted by the server), then retried
                                val why = IptvStreamFail.reasonFor(error.errorCode, http?.responseCode, code, _state.value is IptvPlayState.Playing)
                                if (why == "decode") reportFail(t, why)
                                dispatch(StreamRecovery.Event.Fault(posSec(), canHls(t)))
                            }
                        }
                    }
                }
            }

        /** Live buffer profile the current player was built with (its LoadControl can't change after build). */
        private var builtProfile: LiveBuffer.Profile = LiveBuffer.Profile.LOW

        private fun ensurePlayer(profile: LiveBuffer.Profile = builtProfile): ExoPlayer {
            val current = player
            if (current != null && profile != builtProfile) {
                // A different live buffer: rebuild at this open (the old stream is being replaced anyway)
                JellytipTrace.player("iptv", "live buffer ${builtProfile.label} -> ${profile.label}: rebuilding player")
                current.removeListener(listener)
                current.release()
                player = null
            }
            builtProfile = profile
            return buildPlayer(profile)
        }

        private fun buildPlayer(profile: LiveBuffer.Profile): ExoPlayer =
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
                // The server's remux sends nothing until ffmpeg has its first bytes: a seek on an mkv with no Cues took 23-38 s
                // (Celebrity Traitors S2E2, 22-Nov); the 15 s read timeout would cut every skip and loop. Remux items only.
                val remuxHttp =
                    DefaultHttpDataSource
                        .Factory()
                        .setUserAgent("Jellytip")
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                        .setReadTimeoutMs(REMUX_READ_TIMEOUT_MS)
                remuxSources = DefaultMediaSourceFactory(remuxHttp, extractors)
                // Live buffer profile (Low = the old small buffers: a zap shows the new channel in about a second)
                val load =
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, profile.startMs, profile.afterRebufferMs)
                        .setTargetBufferBytes(profile.targetBytes)
                        .build()
                ExoPlayer
                    .Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
                    .setLoadControl(load)
                    .build()
                    .also {
                        it.addListener(listener)
                        it.volume = volume
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
            resetRecovery(target)
            launchOpen(target, reason)
            guardOrphan(target)
        }

        // ---------- stream recovery (web lib/streamRecovery.ts; gap rows "Live player" / "IPTV films", 21-Nov) ----------

        /** Calm line for the screen while the stream recovers ("Reconnecting… (2 of 4)"); null = nothing to say. */
        private val _note = MutableStateFlow<String?>(null)
        val note: StateFlow<String?> = _note
        private var rec = StreamRecovery.State(live = true)
        private var recTarget: IptvTarget? = null
        private var recTimer: Job? = null
        private var watchJob: Job? = null
        private var stableJob: Job? = null

        /** The TV has no network (ConnectivityManager); a new viewing starts in that state. */
        @Volatile
        private var netOffline = false

        /** One failure report per viewing (the first), like the web's OutcomeSession. */
        private var failReported = false

        /** The server remux was asked for this viewing (a container ExoPlayer can't parse). */
        private var remuxAsked = false

        /** A remuxed stream begins here (ms into the item) and can't seek: [seekTo] reopens it. */
        private var remuxStartMs = 0L
        private var remuxDurationMs: Long? = null

        /** The current stream is the server's remux. */
        var isRemux = false
            private set

        /** Position in the item (ms), counting a remux's start offset. */
        fun positionMs(): Long = pendingRemuxSeekMs ?: ((if (isRemux) remuxStartMs else 0L) + (player?.currentPosition ?: 0L))

        /**
         * A remux seek asked for and not playing yet ("Converting…"): the position is that, so a second seek counts from
         * where the viewer asked to be, not from the old stream (t5k 21-Nov). Cleared when the new stream plays.
         */
        @Volatile
        private var pendingRemuxSeekMs: Long? = null

        /** Media sources for the server's remux (a long read timeout: see [buildPlayer]). */
        private var remuxSources: DefaultMediaSourceFactory? = null

        /** Where the current open meant to start (ms): a direct stream that turns out unseekable resumes there through the remux. */
        private var openStartMs = 0L

        /** The item's length when a skip began (the stopped player can't say), for saving progress mid-skip. */
        private var skipDurMs = 0L

        /**
         * The recording being played (its times and state), set by the page: a recording's length comes from it when the
         * file can't say (raw TS has no index; an in-progress one grows), so progress is never saved against half of it.
         */
        var recordingMeta: IptvRecording? = null

        /** Item length (ms); a remux's from the server, a recording's from its times, else the player's; 0 = unknown. */
        fun durationMs(): Long {
            if (isRemux) remuxDurationMs?.let { return it }
            val playerMs = player?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            val t = _state.value.target
            val meta = recordingMeta?.takeIf { t?.recordingId != null && it.id == t.recordingId }
            // Still recording: the file's length at open is stale (the old bug saved progress against half of it)
            if (meta != null) return (RecPlay.lengthSec(meta, System.currentTimeMillis() / 1000, if (meta.state == "recording") 0.0 else playerMs / 1000.0) * 1000).toLong()
            return playerMs
        }

        /** Seek a film / episode; a remux reopens from [ms] (the server starts the conversion there). */
        fun seekTo(ms: Long) {
            val t = _state.value.target ?: return
            val p = player
            when {
                isRemux -> skipViaRemux(t, ms, "remux seek")
                pendingRemuxSeekMs != null -> skipViaRemux(t, ms, "skip retarget") // (a press while a skip is under way: one new target)
                p != null && p.isCurrentMediaItemSeekable -> p.seekTo(ms)
                // A film / episode ExoPlayer can't seek (an mkv with no Cues, Celebrity Traitors S2E2 22-Nov): the server's
                // conversion starts at [ms] (ffmpeg seeks the file itself, ~10 s)
                SkipPolicy.viaRemux(film(t), false, p?.isCurrentMediaItemSeekable == true, remuxAsked) -> skipViaRemux(t, ms, "skip via remux")
                else -> p?.seekTo(ms)
            }
        }

        /** A seek can work here: the player can, the stream is the remux, or it's a film / episode the remux can start anywhere. */
        fun canSkip(): Boolean {
            val t = _state.value.target ?: return false
            val p = player ?: return false
            // Only while this target plays (or a skip is under way): not on Failed / Full, not while the previous stream is still up
            if (!(_state.value is IptvPlayState.Playing && loadedTarget == t) && pendingRemuxSeekMs == null) return false
            return p.isCurrentMediaItemSeekable || isRemux || SkipPolicy.viaRemux(film(t), false, false, remuxAsked)
        }

        private fun film(t: IptvTarget) = t.vodId != null || t.episodeId != null

        /**
         * Reopen through the remux at [ms] with the current frame held ("Skipping to 31:20…"): no stop / clear, so the picture
         * stays; the old stream's late READY / errors are ignored (loadedTarget cleared) and the server drops its provider
         * connection when the new one is asked for. A second seek meanwhile counts from [ms] ([pendingRemuxSeekMs]).
         */
        private fun skipViaRemux(
            t: IptvTarget,
            ms: Long,
            why: String,
        ) {
            // A skip is already converting (Ricki 22-Nov 17:22: a second press restarted a 30 s+ open, paying the probe and the
            // seek again): keep the open in flight, remember the new target; one follow-up skip once it plays (see READY)
            val s0 = _state.value
            if (skipOpenMs != null && s0 is IptvPlayState.Opening && s0.target == t) {
                JellytipTrace.player("iptv", "skip retarget ${t.channelId} to ${ms / 1000} s (queued behind the open at ${skipOpenMs!! / 1000} s)")
                pendingRemuxSeekMs = ms
                if (!recovering()) _note.value = SkipPolicy.note(ms, (System.currentTimeMillis() - skipStartedAt) / 1000)
                return
            }
            JellytipTrace.player("iptv", "$why ${t.channelId} to ${ms / 1000} s (frame held)")
            durationMs().takeIf { it > 0 }?.let { skipDurMs = it }
            _note.value = SkipPolicy.note(ms, 0)
            skipOpenMs = ms
            skipStartedAt = System.currentTimeMillis()
            startSkipTicker(t)
            pendingRemuxSeekMs = ms
            remuxAsked = true
            recTimer?.cancel() // (a pending recovery reopen must not undo the skip)
            loadedTarget = null
            // stop() without clearMediaItems(): the old stream's connection closes (a provider-direct one too, which the server
            // can't cut) and nothing replays it, while the surface keeps its last frame
            player?.stop()
            // Mid-recovery (a skip's remux open failed and is backing off): the reopen counts as the recovery's own, so the
            // machine sees Opened then Recovered and doesn't leave "Reconnecting…" over a playing picture
            val recovering = recTarget == t && rec.phase in setOf(StreamRecovery.Phase.RECONNECTING, StreamRecovery.Phase.SWITCHED)
            launchOpen(t, why, recovering = recovering, remux = true, seekMs = ms)
        }

        /** The skip being converted right now (ms), null when none; [skipStartedAt] when it was asked for. */
        private var skipOpenMs: Long? = null
        private var skipStartedAt = 0L
        private var skipTicker: kotlinx.coroutines.Job? = null

        /** "Skipping to 31:20… 12 s" while the skip converts: the person sees it is working, and how slow this file is. */
        private fun recovering(): Boolean =
            rec.phase in setOf(StreamRecovery.Phase.RECONNECTING, StreamRecovery.Phase.SWITCHED, StreamRecovery.Phase.STALLED)

        private fun startSkipTicker(t: IptvTarget) {
            skipTicker?.cancel()
            skipTicker =
                scope.launch {
                    while (true) {
                        delay(1_000)
                        val st = _state.value
                        val pend = pendingRemuxSeekMs
                        if (st !is IptvPlayState.Opening || st.target != t || pend == null || skipOpenMs == null) break
                        val secs = (System.currentTimeMillis() - skipStartedAt) / 1000
                        if (secs >= SkipPolicy.GIVE_UP_SEC && !recovering()) { // (recovery has its own attempt limit)
                            // Nothing after 90 s: an honest stop instead of an endless count (OK tries again at the target)
                            JellytipTrace.player("iptv", "skip gave up ${t.channelId} at ${pend / 1000} s after $secs s")
                            skipOpenMs = null
                            job?.cancel() // (the /play still in flight must not start playing behind the card)
                            okJob?.cancel()
                            recTimer?.cancel()
                            stopPlayer()
                            _note.value = null
                            setState(IptvPlayState.Failed(t, "Skipping in this file is taking too long.", canRetry = true), "skip too slow")
                            break
                        }
                        if (!recovering()) _note.value = SkipPolicy.note(pend, secs) // (recovery's own "Reconnecting…" wins)
                    }
                }
        }

        private fun resetRecovery(target: IptvTarget) {
            skipOpenMs = null
            skipTicker?.cancel()
            recTimer?.cancel()
            stableJob?.cancel()
            rec = StreamRecovery.State(live = target.isLive, offline = netOffline)
            recTarget = target
            failReported = false
            remuxAsked = false
            isRemux = false
            remuxStartMs = 0L
            remuxDurationMs = null
            pendingRemuxSeekMs = null
            skipDurMs = 0L
            _note.value = null
        }

        private fun reportFail(
            t: IptvTarget,
            reason: String?,
            posSec: Long? = null,
        ) {
            if (reason == null || failReported) return
            // Offline: our network, not the stream's fault (never ding a healthy channel)
            if (rec.offline) return
            // A device decode note is logged but doesn't use the one counted report of the viewing
            if (reason != "decode") failReported = true
            IptvStreamFail.report(t, ok = false, reason = reason, posSec = posSec)
        }

        private fun posSec(): Long = positionMs() / 1000

        private fun canHls(t: IptvTarget) = t.isLive && rec.format == "ts"

        /** Feed the recovery machine and run its effects (release before open, timers, the screen line). */
        private fun dispatch(e: StreamRecovery.Event) {
            val t = recTarget ?: return
            val before = rec
            val r = StreamRecovery.step(rec, e)
            rec = r.state
            if (before.phase != rec.phase || before.attempt != rec.attempt || before.format != rec.format) {
                JellytipTrace.player("iptv", "recovery ${t.channelId}: $e -> ${rec.phase} ${rec.attempt}/${rec.max} ${rec.format} conn=${rec.conn}")
            }
            var released = false
            for (fx in r.effects) {
                when (fx) {
                    StreamRecovery.Effect.Release -> {
                        job?.cancel()
                        okJob?.cancel()
                        stopPlayer()
                        released = true
                    }
                    StreamRecovery.Effect.CancelTimers -> recTimer?.cancel()
                    is StreamRecovery.Effect.Open -> {
                        recTimer?.cancel()
                        // The target keeps its identity (screens and stale-error checks compare it); the position goes apart
                        val seek = if (!t.isLive) fx.resumeAt?.times(1000) else null
                        recTimer =
                            scope.launch {
                                delay(fx.delayMs)
                                if (recTarget != t) return@launch
                                launchOpen(t, "recovery ${rec.attempt}/${rec.max} ${fx.format}", fx.format, recovering = true, seekMs = seek)
                            }
                    }
                    StreamRecovery.Effect.Exit -> {}
                }
            }
            val copy = StreamRecovery.copy(rec)
            when (rec.phase) {
                StreamRecovery.Phase.FAILED, StreamRecovery.Phase.DENIED, StreamRecovery.Phase.BLOCKED -> {
                    _note.value = null
                    if (before.phase != rec.phase) {
                        if (rec.phase == StreamRecovery.Phase.FAILED) reportFail(t, "recovery_gave_up", posSec())
                        if (rec.phase == StreamRecovery.Phase.DENIED) reportFail(t, StreamRecovery.deniedFailReason(rec.reason))
                        val msg = if (rec.phase == StreamRecovery.Phase.BLOCKED) "All connections are in use right now." else copy ?: "The stream stopped."
                        val cause =
                            when (rec.phase) {
                                StreamRecovery.Phase.BLOCKED -> FailCause.BLOCKED
                                StreamRecovery.Phase.FAILED -> FailCause.GAVE_UP
                                else -> FailCause.of(rec.reason)
                            }
                        setState(IptvPlayState.Failed(t, msg, canRetry = true, reported = failReported, cause = cause), "recovery ${rec.phase}")
                    }
                }
                StreamRecovery.Phase.RECONNECTING, StreamRecovery.Phase.SWITCHED -> {
                    _note.value = copy
                    if (_state.value !is IptvPlayState.Opening || _state.value.target != t) setState(IptvPlayState.Opening(t), "recovering")
                }
                StreamRecovery.Phase.STALLED -> _note.value = copy
                else -> _note.value = null
            }
            if (released) dispatch(StreamRecovery.Event.Released)
        }

        /** OK on the failed screen: try again now (debounced; never while an open is in flight). */
        fun retry() {
            val t = recTarget ?: return
            if (_state.value.target != t) return
            failReported = false
            renewed = false
            dispatch(StreamRecovery.Event.RetryNow(posSec(), System.currentTimeMillis()))
        }

        /**
         * While a stream is meant to play: buffering with no picture moving is a stall (the "buffering" line after 10 s
         * live), then a fault (reconnect) after 15 s live / 30 s for films and catch-up. The stall is reported to the
         * server once ("this stream fails" memory; gap row 369e5d81).
         */
        private fun watchStalls(t: IptvTarget) {
            watchJob?.cancel()
            watchJob =
                scope.launch {
                    var since = 0L
                    var stallSent = false
                    while (true) {
                        delay(1_000)
                        val s = _state.value
                        if (s.target != t || recTarget != t) return@launch
                        val p = player ?: continue
                        val buffering = s is IptvPlayState.Playing && loadedTarget == t && p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
                        if (!buffering) {
                            // Came back by itself: clear "Stream is buffering…"
                            if (rec.phase == StreamRecovery.Phase.STALLED && s is IptvPlayState.Playing) dispatch(StreamRecovery.Event.Recovered)
                            since = 0L
                            stallSent = false
                            continue
                        }
                        val now = System.currentTimeMillis()
                        if (since == 0L) since = now
                        val held = now - since
                        if (t.isLive && held >= StreamRecovery.LIVE_STALL_MS && !stallSent) {
                            stallSent = true
                            dispatch(StreamRecovery.Event.Stall(posSec()))
                        }
                        val faultAt = if (t.isLive) StreamRecovery.LIVE_FAULT_MS else StreamRecovery.VOD_IDLE_MS
                        if (held >= faultAt) {
                            JellytipTrace.player("iptv", "stall ${t.channelId}: no picture for ${held / 1000} s, reconnecting")
                            reportFail(t, "stall", posSec())
                            since = 0L
                            stallSent = false
                            dispatch(StreamRecovery.Event.Fault(posSec(), canHls(t)))
                        }
                    }
                }
        }

        private var orphanJob: Job? = null

        /**
         * Safety net (21-Nov bug: sound, no picture after a launch from Home): a stream that no screen claims within
         * [ORPHAN_MS] (no guide, full screen or film page attached) is stopped, never left playing out of sight.
         */
        private fun guardOrphan(target: IptvTarget) {
            orphanJob?.cancel()
            orphanJob =
                scope.launch {
                    delay(ORPHAN_MS)
                    val s = _state.value
                    if (owners.isEmpty() && s.target == target && s !is IptvPlayState.Idle) {
                        JellytipTrace.player("iptv", "orphan stream ${target.channelId}: no screen showing it, stopping")
                        stop("no screen showing it")
                    }
                }
        }

        private fun launchOpen(
            target: IptvTarget,
            reason: String,
            format: String = "ts",
            /** A recovery attempt: success / 409 / failure answer the recovery machine instead of ending here. */
            recovering: Boolean = false,
            /** Ask for the server remux (films / episodes); also kept for every reopen once asked. */
            remux: Boolean = remuxAsked,
            /** Where to start (ms) instead of [IptvTarget.resumeMs]; the target itself is never copied (its identity matters). */
            seekMs: Long? = null,
        ) {
            val startMs = seekMs ?: target.resumeMs
            openStartMs = startMs
            job?.cancel()
            okJob?.cancel()
            setState(IptvPlayState.Opening(target), reason)
            JellytipTrace.player("iptv", "open ${target.channelId} ${target.label} start=${target.start} format=$format ($reason)")
            job =
                scope.launch {
                    when (val r = service.playItem(target, format, remux = remux && !target.isLive, startSec = startMs / 1000)) {
                        is IptvPlayResult.Ok -> {
                            val profile = LiveBuffer.profileFor(context, target)
                            // Direct answers carry the provider's own absolute link (credentials inside: not traced)
                            val direct = r.answer.direct && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(r.answer.url)
                            val url = if (direct) r.answer.url else service.streamUrl(r.answer.url)
                            if (direct) {
                                JellytipTrace.player("iptv", "direct from provider ${target.channelId}")
                                android.widget.Toast
                                    .makeText(context, "Playing straight from your provider: other devices on this connection can't be seen.", android.widget.Toast.LENGTH_LONG)
                                    .show()
                            }
                            if (url == null) {
                                fail(target, "The server sent a stream link the app can't use.", null)
                                return@launch
                            }
                            val ts = r.answer.mime?.contains("mp2t", true) == true || url.substringBefore('?').endsWith(".ts")
                            val item =
                                MediaItem
                                    .Builder()
                                    .setUri(url)
                                    .apply { if (ts) setMimeType(MimeTypes.VIDEO_MP2T) }
                                    .apply {
                                        // Low (the default) keeps the stream's own hold-back; bigger profiles only ask for at least their distance
                                        if (target.isLive && profile != LiveBuffer.Profile.LOW) {
                                            setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinOffsetMs(profile.behindMs).build())
                                        }
                                    }.build()
                            val p = ensurePlayer(profile)
                            loadedTarget = target
                            isRemux = r.answer.remux
                            remuxStartMs = if (r.answer.remux) r.answer.startAt * 1000 else 0L
                            remuxDurationMs = if (r.answer.remux) r.answer.durationSec?.times(1000) else null
                            if (remux && !r.answer.remux) JellytipTrace.player("iptv", "remux asked but the server sent the file as is (${target.channelId})")
                            // A remux already begins at the asked position
                            val rs = remuxSources
                            when {
                                r.answer.remux && rs != null -> p.setMediaSource(rs.createMediaSource(item))
                                startMs > 0 && !r.answer.remux -> p.setMediaItem(item, startMs)
                                else -> p.setMediaItem(item)
                            }
                            p.playWhenReady = true
                            p.prepare()
                            if (recovering && recTarget?.channelId == target.channelId) dispatch(StreamRecovery.Event.Opened)
                        }

                        is IptvPlayResult.Full ->
                            if (recovering && recTarget?.channelId == target.channelId) {
                                dispatch(StreamRecovery.Event.Busy)
                            } else {
                            stopPlayer()
                            JellytipTrace.player("iptv", "full ${target.channelId}: ${r.message}")
                            _note.value = null
                            setState(IptvPlayState.Full(target, r.message, r.holders), "409")
                            }
                        is IptvPlayResult.Failed -> {
                            val refusal = StreamRecovery.permanentReason(r.status, r.code)
                            when {
                                recTarget?.channelId != target.channelId -> fail(target, r.message, IptvStreamFail.reasonForCode(r.code), cause = FailCause.of(refusal))
                                refusal != null -> {
                                    reportFail(target, IptvStreamFail.reasonForCode(r.code))
                                    dispatch(StreamRecovery.Event.Fatal(refusal))
                                }
                                // Network / server / provider hiccup: retry with backoff (the old screen said "Failed")
                                else -> dispatch(StreamRecovery.Event.Fault(posSec(), canHls(target)))
                            }
                        }
                    }
                }
        }

        private var okJob: kotlinx.coroutines.Job? = null

        private fun fail(
            target: IptvTarget,
            message: String,
            /** Failed-stream memory reason; null = not the stream's fault (nothing reported). */
            reason: String? = "open_failed",
            fromPlayer: Boolean = false,
            cause: FailCause = FailCause.OTHER,
        ) {
            okJob?.cancel()
            _note.value = null
            // Player errors are only the target's own when the player holds its stream (else: the previous channel's)
            val reported = reason != null && (!fromPlayer || loadedTarget == target)
            if (reported) IptvStreamFail.report(target, ok = false, reason = reason)
            stopPlayer()
            JellytipTrace.player("iptv", "failed ${target.channelId}: $message")
            setState(IptvPlayState.Failed(target, message, reported = reported, cause = cause), "failed")
        }

        /** The target whose stream the player holds (set with the media item); errors from any other are stale. */
        @Volatile
        private var loadedTarget: IptvTarget? = null

        /** The player holds [t]'s stream (its STATE_ENDED / READY are [t]'s, not the previous item's). */
        fun holds(t: IptvTarget): Boolean = loadedTarget == t

        /** Recovery is waiting for the network to come back (no stream is the stream's fault). */
        val offline: Boolean get() = rec.offline

        private fun stopPlayer() {
            loadedTarget = null
            player?.run {
                stop()
                clearMediaItems()
            }
            updateKeepScreenOn(null)
        }

        /**
         * "Stop recording & watch" (plan "Stop recording" pop-up): cancel [recordingId] (the file is kept), give the
         * panel ~3 s to free the slot, then play [target] again.
         */
        fun stopRecordingAndPlay(
            target: IptvTarget,
            recordingId: String,
        ) {
            job?.cancel()
            setState(IptvPlayState.Opening(target), "stop recording $recordingId")
            job =
                scope.launch {
                    val ok = service.cancelRecording(recordingId)
                    JellytipTrace.player("iptv", "stop recording $recordingId for ${target.channelId} ok=$ok")
                    if (!ok) {
                        fail(target, "Couldn't stop the recording.", null)
                        return@launch
                    }
                    delay(SLOT_FREE_WAIT_MS)
                    renewed = false
                    // Still full = the pop-up comes back with whoever holds it now
                    launchOpen(target, "after stopping recording $recordingId")
                }
        }

        /** Stop now and free the provider connection (closing the HTTP stream releases the slot). */
        fun stop(reason: String) {
            job?.cancel()
            recTimer?.cancel()
            watchJob?.cancel()
            stableJob?.cancel()
            recTarget = null
            _note.value = null
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
            player ?: return
            // Leaving mid-skip ("Skipping to 31:20…"): the target is where the person asked to be
            val pend = pendingRemuxSeekMs
            if (pend != null && _state.value.target == target && skipDurMs > 0) {
                val series = target.seriesId?.takeIf { target.episodeId != null }
                scope.launch { service.saveProgress(item, pend / 1000.0, skipDurMs / 1000.0, series) }
                return
            }
            // Only from a stream that is really playing: while recovering / failed the player holds nothing (position 0)
            // and would wipe the resume point
            if (_state.value !is IptvPlayState.Playing || _state.value.target != target || loadedTarget != target) return
            val pos = positionMs() / 1000.0
            val dur = durationMs().takeIf { it > 0 }?.div(1000.0) ?: return
            // Episodes name their series (pid:s:sid) so the server's Next Up works however the episode was opened
            val series = target.seriesId?.takeIf { target.episodeId != null }
            scope.launch { service.saveProgress(item, pos, dur, series) }
        }

        /** Kept so a rebuilt player (live buffer change) starts at the same volume (the guide preview is muted). */
        private var volume = 1f

        fun setVolume(volume: Float) {
            this.volume = volume
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
            /** Good-play check tick (failed-stream memory). */
            private const val OK_TICK_MS = 5_000L

            private const val HANDOVER_MS = 400L
            // Long enough for a cold first compose of the player page on the 32-bit GTV (else a from-kick-off launch
            // would be stopped and retuned as live)
            private const val ORPHAN_MS = 6_000L
            private const val SLOT_FREE_WAIT_MS = 3_000L
            private const val CONNECT_TIMEOUT_MS = 8_000
            private const val READ_TIMEOUT_MS = 15_000
            private const val REMUX_READ_TIMEOUT_MS = 75_000
        }
    }

/** When a seek / resume goes through the server remux (pure; tested). */
object SkipPolicy {
    /** Resumes this close to the start just play from the top. */
    const val RESUME_MIN_MS = 5_000L

    /**
     * A film / episode whose stream the player can't seek, not already the remux, and the remux not already asked for (a server
     * that sent the file as is anyway would otherwise be asked again on every key / READY: an endless reopen loop).
     */
    fun viaRemux(
        film: Boolean,
        remux: Boolean,
        seekable: Boolean,
        remuxAsked: Boolean,
    ): Boolean = film && !remux && !seekable && !remuxAsked

    /** After a skip plays: another skip only when the target moved meaningfully while it converted. */
    const val FOLLOW_UP_MIN_MS = 10_000L

    /** A skip that hasn't played after this long stops with a message (OK tries again). */
    const val GIVE_UP_SEC = 90L

    fun followUp(
        openedMs: Long,
        targetMs: Long,
    ): Boolean = kotlin.math.abs(targetMs - openedMs) > FOLLOW_UP_MIN_MS

    /** What shows while a skip converts: the target, then the seconds, then plain words that this file is slow. */
    fun note(
        targetMs: Long,
        elapsedSec: Long,
    ): String {
        val to = "Skipping to ${IptvPlayControls.clock(targetMs)}…"
        return when {
            elapsedSec < 5 -> to
            elapsedSec < 20 -> "$to  $elapsedSec s"
            else -> "$to  This file is slow to skip ($elapsedSec s)"
        }
    }

    fun resumeViaRemux(
        film: Boolean,
        seekable: Boolean,
        remuxAsked: Boolean,
        startMs: Long,
    ): Boolean = viaRemux(film, false, seekable, remuxAsked) && startMs >= RESUME_MIN_MS
}
