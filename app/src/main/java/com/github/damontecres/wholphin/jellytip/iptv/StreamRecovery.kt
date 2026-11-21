package com.github.damontecres.wholphin.jellytip.iptv

import kotlin.math.max
import kotlin.math.min

/*
 * Graceful stream recovery (web lib/streamRecovery.ts, 80f3dd1b + review fixes 34edc0e1 efbf8366 28cbde25), for live
 * channels and IPTV films alike. A pure state machine: step() returns effects the player runs, it never talks to the
 * network. The single-connection guarantee lives here: an `Open` effect is only emitted once the old stream is
 * confirmed released (conn == CLOSED), and after a grace gap. Tested in StreamRecoveryTest.
 *
 *   playing -> stalled -> reconnecting (attempt k of max, backoff) -> switched (live TS -> HLS) -> failed
 */
object StreamRecovery {
    const val MAX_ATTEMPTS = 4
    const val SWITCH_ATTEMPTS = 2
    const val BACKOFF_BASE_MS = 1_000L
    const val BACKOFF_CAP_MS = 8_000L

    /** Continuous playback needed before earlier attempts are forgiven. */
    const val STABLE_MS = 20_000L

    /** "Keep waiting" silences stall escalation this long. */
    const val QUIET_MS = 60_000L

    /** Live: no picture moving this long = a stall; another 5 s of it = a fault (reconnect). */
    const val LIVE_STALL_MS = 10_000L
    const val LIVE_FAULT_MS = 15_000L

    /** VOD / catch-up: escalate only after this long without movement (it may just be loading). */
    const val VOD_IDLE_MS = 30_000L
    const val RETRY_DEBOUNCE_MS = 1_500L

    /** After releasing a stream, wait this long before opening another (provider-side grace). */
    const val GRACE_MS = 750L

    /** A 409 on a recovery open gets one more attempt after this long before it counts as blocked. */
    const val BUSY_RETRY_MS = 3_000L

    enum class Phase { PLAYING, STALLED, RECONNECTING, SWITCHED, FAILED, DENIED, BLOCKED, ENDED }

    enum class Conn { HELD, RELEASING, CLOSED, OPENING }

    /** Why a stream will not be retried by itself (manual Retry stays available). */
    enum class Denied { AUTH, DENIED, MISSING, NOCATCHUP, LINK, RATE }

    data class State(
        val phase: Phase = Phase.PLAYING,
        val live: Boolean,
        val attempt: Int = 0,
        val max: Int = MAX_ATTEMPTS,
        /** "ts" or "m3u8" (live only switches to HLS once TS attempts are spent). */
        val format: String = "ts",
        val switched: Boolean = false,
        val conn: Conn = Conn.HELD,
        val offline: Boolean = false,
        /** Recovered but not yet stable: a new fault keeps counting attempts instead of starting over. */
        val unstable: Boolean = false,
        val reason: Denied? = null,
        val busyRetried: Boolean = false,
        val lastRetryAt: Long? = null,
        /** A (re)open waiting for the release, the network, or the screen (ms delay). */
        val wantDelayMs: Long? = null,
        /** VOD / catch-up position to resume at (s); null for live (live edge). */
        val resumeAt: Long? = null,
    )

    sealed interface Event {
        data class Stall(
            val pos: Long,
        ) : Event

        data class Fault(
            val pos: Long,
            val canHls: Boolean,
        ) : Event

        data object Busy : Event

        data class RetryNow(
            val pos: Long,
            val now: Long,
        ) : Event

        data class Fatal(
            val reason: Denied,
        ) : Event

        data object Opened : Event

        data object Recovered : Event

        data object Stable : Event

        data object Released : Event

        data object Offline : Event

        data object Online : Event

        data object Cancel : Event
    }

    sealed interface Effect {
        data object Release : Effect

        data object CancelTimers : Effect

        data class Open(
            val delayMs: Long,
            val resumeAt: Long?,
            val format: String,
        ) : Effect

        data object Exit : Effect
    }

    data class Step(
        val state: State,
        val effects: List<Effect>,
    )

    fun backoffMs(attempt: Int): Long = min(BACKOFF_CAP_MS, BACKOFF_BASE_MS shl max(0, attempt - 1).coerceAtMost(20))

    private fun active(s: State) = s.phase == Phase.STALLED || s.phase == Phase.RECONNECTING || s.phase == Phase.SWITCHED

    /** Mutable working copy for one step. */
    private class W(
        var s: State,
    ) {
        val fx = ArrayList<Effect>()

        fun maybeOpen() {
            val d = s.wantDelayMs ?: return
            if (s.conn != Conn.CLOSED || s.offline || s.phase == Phase.FAILED || s.phase == Phase.ENDED || s.phase == Phase.BLOCKED) return
            fx += Effect.Open(d, if (s.live) null else s.resumeAt, s.format)
            s = s.copy(conn = Conn.OPENING, wantDelayMs = null)
        }

        fun release() {
            if (s.conn == Conn.HELD || s.conn == Conn.OPENING) {
                fx += Effect.Release
                s = s.copy(conn = Conn.RELEASING)
            }
        }

        /** Queue an open after [delayMs]; the old stream is released first. */
        fun reopen(delayMs: Long) {
            val hadStream = s.conn == Conn.HELD || s.conn == Conn.OPENING
            s = s.copy(wantDelayMs = if (hadStream) max(delayMs, GRACE_MS) else delayMs)
            release()
            maybeOpen()
        }

        fun stopAll(phase: Phase) {
            s = s.copy(phase = phase, wantDelayMs = null)
            fx += Effect.CancelTimers
            release()
        }

        fun nextAttempt(canHls: Boolean) {
            // Offline failures don't use up attempts: wait for the network and try the same one again
            if (s.offline) {
                s = s.copy(attempt = max(1, s.attempt), phase = if (s.switched) Phase.SWITCHED else Phase.RECONNECTING)
                return reopen(0)
            }
            if (s.attempt < s.max) {
                s = s.copy(attempt = s.attempt + 1, phase = if (s.switched) Phase.SWITCHED else Phase.RECONNECTING)
                return reopen(backoffMs(s.attempt))
            }
            if (!s.switched && s.live && s.format == "ts" && canHls) {
                s = s.copy(switched = true, format = "m3u8", attempt = 1, max = SWITCH_ATTEMPTS, phase = Phase.SWITCHED)
                return reopen(0)
            }
            stopAll(Phase.FAILED)
        }

        fun begin(e: Event.Fault) {
            val resume = if (s.live) null else if (s.phase == Phase.STALLED && s.resumeAt != null) s.resumeAt else e.pos.takeIf { it > 5 }
            s = s.copy(resumeAt = resume, attempt = 0, max = MAX_ATTEMPTS, switched = false)
            nextAttempt(e.canHls)
        }
    }

    fun step(
        prev: State,
        e: Event,
    ): Step {
        val w = W(prev)
        val s0 = prev
        if (s0.phase == Phase.ENDED || (s0.phase == Phase.BLOCKED && e !is Event.RetryNow)) {
            if (e == Event.Released && s0.conn == Conn.RELEASING) w.s = s0.copy(conn = Conn.CLOSED)
            return Step(w.s, w.fx)
        }
        when (e) {
            is Event.Stall ->
                if (s0.phase == Phase.PLAYING) {
                    w.s = s0.copy(phase = Phase.STALLED, resumeAt = if (!s0.live && e.pos > 5) e.pos else s0.resumeAt)
                }
            is Event.Fault ->
                when {
                    (s0.phase == Phase.PLAYING || s0.phase == Phase.STALLED) && s0.unstable -> {
                        w.s = s0.copy(resumeAt = if (!s0.live && e.pos > 5) e.pos else s0.resumeAt, unstable = false)
                        w.nextAttempt(e.canHls)
                    }
                    s0.phase == Phase.PLAYING || s0.phase == Phase.STALLED -> w.begin(e)
                    s0.phase == Phase.RECONNECTING || s0.phase == Phase.SWITCHED -> {
                        // a failure while an attempt is already being released / queued is the same failure
                        if (s0.conn != Conn.RELEASING && s0.conn != Conn.CLOSED) w.nextAttempt(e.canHls)
                    }
                    else -> {}
                }
            Event.Busy ->
                if (!s0.busyRetried && (s0.phase == Phase.RECONNECTING || s0.phase == Phase.SWITCHED)) {
                    w.s = s0.copy(busyRetried = true)
                    w.reopen(BUSY_RETRY_MS)
                } else {
                    w.stopAll(Phase.BLOCKED)
                }
            is Event.RetryNow -> {
                // mashing Retry must not flood /play: ignore while an open is in flight and within the debounce window
                if (s0.conn == Conn.OPENING || (s0.lastRetryAt != null && e.now - s0.lastRetryAt < RETRY_DEBOUNCE_MS)) return Step(s0, emptyList())
                w.s =
                    s0.copy(
                        lastRetryAt = e.now,
                        reason = null,
                        busyRetried = false,
                        resumeAt = if (s0.live) null else e.pos.takeIf { it > 5 } ?: s0.resumeAt,
                        attempt = 1,
                        max = MAX_ATTEMPTS,
                        switched = false,
                        unstable = false,
                        phase = Phase.RECONNECTING,
                        format = if (s0.live) "ts" else s0.format,
                    )
                w.fx += Effect.CancelTimers
                w.reopen(0)
            }
            Event.Opened -> if (s0.conn == Conn.OPENING) w.s = s0.copy(conn = Conn.HELD)
            Event.Recovered ->
                if (active(s0) && s0.conn == Conn.HELD) {
                    // attempts are only forgiven after STABLE_MS of continuous playback ("Stable")
                    w.s = s0.copy(phase = Phase.PLAYING, unstable = s0.attempt > 0, busyRetried = false, resumeAt = null)
                }
            Event.Stable -> if (s0.phase == Phase.PLAYING && s0.unstable) w.s = s0.copy(unstable = false, attempt = 0, max = MAX_ATTEMPTS)
            Event.Released ->
                // only the release we asked for counts; a stale one must never close a stream that is held
                if (s0.conn == Conn.RELEASING) {
                    w.s = s0.copy(conn = Conn.CLOSED)
                    w.maybeOpen()
                }
            Event.Offline -> {
                w.s = s0.copy(offline = true)
                w.fx += Effect.CancelTimers
                if (s0.wantDelayMs != null) {
                    w.s = w.s.copy(wantDelayMs = GRACE_MS)
                } else if (s0.conn == Conn.OPENING) {
                    w.s = w.s.copy(wantDelayMs = GRACE_MS)
                    w.release()
                }
            }
            Event.Online ->
                if (s0.offline) {
                    w.s = s0.copy(offline = false, wantDelayMs = s0.wantDelayMs?.let { GRACE_MS })
                    w.maybeOpen()
                }
            is Event.Fatal -> {
                w.s = s0.copy(reason = e.reason)
                w.stopAll(Phase.DENIED)
            }
            Event.Cancel -> {
                w.stopAll(Phase.ENDED)
                w.fx += Effect.Exit
            }
        }
        return Step(w.s, w.fx)
    }

    /** Calm line for the screen; null while playing normally. Never an error code. */
    fun copy(s: State): String? =
        when (s.phase) {
            Phase.STALLED -> "Stream is buffering…"
            Phase.RECONNECTING, Phase.SWITCHED ->
                when {
                    s.offline -> "You're offline. Waiting for a connection…"
                    s.phase == Phase.SWITCHED && s.attempt <= 1 && s.conn != Conn.HELD -> "Switched to a backup stream"
                    else -> "Reconnecting… (${s.attempt} of ${s.max})"
                }
            Phase.DENIED ->
                when (s.reason) {
                    Denied.AUTH -> "The provider refused the login. Check it in Settings → Live TV / IPTV."
                    Denied.DENIED -> "This stream isn't available to this account."
                    Denied.MISSING -> "This stream couldn't be found."
                    Denied.NOCATCHUP -> "This channel has no catch-up for that time."
                    Denied.LINK -> "The stream link is no longer valid. Retry to get a fresh one."
                    Denied.RATE -> "The provider is limiting connections; try again in a minute."
                    null -> "This stream can't be played right now."
                }
            Phase.FAILED -> if (s.live) "This channel isn't responding. Try another channel or retry." else "This stream isn't responding. Try again or go back."
            else -> null
        }

    /** Final refusals: never put on the backoff. null = not a permanent refusal. */
    fun permanentReason(
        status: Int,
        code: String?,
    ): Denied? =
        when {
            code == "provider_auth" -> Denied.AUTH
            code == "no_catchup" -> Denied.NOCATCHUP
            code == "bad_token" || code == "expired" || code == "stale" || status == 410 -> Denied.LINK
            status == 429 -> Denied.RATE
            status == 401 || status == 403 -> Denied.DENIED
            status == 404 -> Denied.MISSING
            else -> null
        }

    /** The failed-stream memory reason for a denial (only the stream's own fault is reported). */
    fun deniedFailReason(r: Denied?): String? =
        when (r) {
            Denied.MISSING -> "open_failed"
            // A bare 401/403 is sign-in / permission (ours), never the stream's fault
            else -> null
        }

    /**
     * Live stall: show "buffering" only while meant to play and not within the quiet window. The [lastMoveMs] is the
     * last time the picture moved (or the stream was opened).
     */
    fun shouldSurfaceStall(
        nowMs: Long,
        lastMoveMs: Long,
        thresholdMs: Long,
        paused: Boolean,
        quietUntilMs: Long,
    ): Boolean = !paused && nowMs - lastMoveMs >= thresholdMs && nowMs > quietUntilMs
}
