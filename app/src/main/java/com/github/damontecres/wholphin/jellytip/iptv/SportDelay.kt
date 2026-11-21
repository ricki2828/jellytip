package com.github.damontecres.wholphin.jellytip.iptv

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Spoiler delay, alert gate and scorebug policy for the TV companion: a port of the web app's pure libs
 * (web/src/lib/delayMath.ts, alertPolicy.ts, companion.ts; plan ~/ai/plans/jellytip-sport-companion.md "Delay model").
 * No Android or player types here, so it is unit-tested (SportDelayTest). The server owns the timeline: event times are
 * its `wallclock` / `at` stamps and `updatedAt` per /live answer; the TV only decides when to show them.
 *
 *   reveal_at = event_time + delaySec + (liveEdge - playhead)
 */
object DelayMath {
    const val DEFAULT_DELAY_SEC = 30
    const val MAX_DELAY_SEC = 300

    /** A playhead further behind the live edge than this is a replay (catch-up, recording): nothing is revealed. */
    const val REPLAY_LAG_SEC = 120.0

    /** Once in replay, the playhead must come back inside this before live reveals resume (no flapping). */
    const val REPLAY_EXIT_LAG_SEC = 100.0

    fun clampDelay(
        n: Double?,
        fallback: Int = DEFAULT_DELAY_SEC,
    ): Int = if (n == null || n.isNaN() || n.isInfinite()) fallback else min(MAX_DELAY_SEC, max(0, n.roundToInt()))

    fun clampDelay(n: Int): Int = min(MAX_DELAY_SEC, max(0, n))

    /** Seconds the playhead sits behind the live edge (never negative). */
    fun lagSec(
        currentSec: Double,
        edgeSec: Double,
    ): Double = if (currentSec.isFinite() && edgeSec.isFinite()) max(0.0, edgeSec - currentSec) else 0.0

    /** Replay with hysteresis: enters past [REPLAY_LAG_SEC], leaves under [REPLAY_EXIT_LAG_SEC]; [forced] always wins. */
    fun replayState(
        prev: Boolean,
        lag: Double,
        forced: Boolean = false,
    ): Boolean {
        if (forced) return true
        return if (prev) lag >= REPLAY_EXIT_LAG_SEC else lag > REPLAY_LAG_SEC
    }

    fun revealAt(
        eventSec: Double,
        delaySec: Int,
        lag: Double,
    ): Double = eventSec + delaySec + lag

    /** Whether an event is revealed now. No event time = "is live reveal on at all". Replay reveals nothing. */
    fun isRevealed(
        eventSec: Double?,
        nowSec: Double,
        delaySec: Int,
        lag: Double,
        replay: Boolean,
    ): Boolean {
        if (replay) return false
        if (eventSec == null || !eventSec.isFinite()) return true
        return nowSec >= revealAt(eventSec, delaySec, lag)
    }

    /** "That just happened": the delay at which the latest key moment is revealed right now; null = nothing honest to anchor on. */
    fun calibratedDelay(
        latestEventSec: Double?,
        nowSec: Double,
        lag: Double,
    ): Int? {
        if (latestEventSec == null || !latestEventSec.isFinite()) return null
        val d = nowSec - latestEventSec - lag
        return if (d < -5 || d > MAX_DELAY_SEC) null else clampDelay(d)
    }

    /** The latest key-moment time seen so far, ignoring times from the future. */
    fun noteEvent(
        latest: Double?,
        atSec: Double,
        nowSec: Double,
    ): Double? {
        if (!atSec.isFinite() || atSec > nowSec + 5) return latest
        return if (latest == null || atSec > latest) atSec else latest
    }

    fun stepDelay(
        cur: Int,
        by: Int,
    ): Int = clampDelay(cur + by)

    /** A channel id is "pid:sid"; a bare id gets provider "_". */
    fun parseChannelKey(key: String): Pair<String, String> {
        val i = key.indexOf(':')
        return if (i < 0) "_" to key else key.substring(0, i) to key.substring(i + 1)
    }

    sealed interface Calibrate {
        data class Set(
            val sec: Int,
        ) : Calibrate

        data object None : Calibrate

        data object Replay : Calibrate
    }

    fun calibrateMessage(r: Calibrate): String =
        when (r) {
            is Calibrate.Set -> "Delay set to ${r.sec} s"
            Calibrate.Replay -> "Updates are off while you watch a replay"
            Calibrate.None -> "Nothing to line up yet. Press it right after something happens on screen."
        }
}

/**
 * One reveal clock per player: holds the channel's delay, the replay flag and the latest key moment. [lag] reads the
 * playhead's distance behind the live edge (seconds; 0 when the stream has no live window); [forcedReplay] is true for a
 * catch-up / recording. Not thread-safe: use from the main thread.
 */
class RevealClock(
    private val lag: () -> Double,
    private val forcedReplay: () -> Boolean,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    var delaySec: Int = DelayMath.DEFAULT_DELAY_SEC
        private set
    private var replay = false
    private var latest: Double? = null

    /** A manual change beats a late server answer for this channel. */
    var touched = false
        private set

    fun reset(delay: Int) {
        delaySec = DelayMath.clampDelay(delay)
        latest = null
        touched = false
    }

    /** The server's saved value for this channel arrived; ignored after a manual change. */
    fun serverDelay(sec: Int) {
        if (!touched) delaySec = DelayMath.clampDelay(sec)
    }

    fun setDelay(sec: Int): Int {
        touched = true
        delaySec = DelayMath.clampDelay(sec)
        return delaySec
    }

    fun inReplay(): Boolean {
        replay = DelayMath.replayState(replay, lag(), forcedReplay())
        return replay
    }

    fun reveal(atSec: Double? = null): Boolean = DelayMath.isRevealed(atSec, now(), delaySec, lag(), inReplay())

    fun noteMoment(atSec: Double) {
        latest = DelayMath.noteEvent(latest, atSec, now())
    }

    fun canCalibrate(): Boolean = !inReplay() && DelayMath.calibratedDelay(latest, now(), lag()) != null

    fun calibrate(): DelayMath.Calibrate {
        if (inReplay()) return DelayMath.Calibrate.Replay
        val d = DelayMath.calibratedDelay(latest, now(), lag()) ?: return DelayMath.Calibrate.None
        return DelayMath.Calibrate.Set(setDelay(d))
    }

    /**
     * Whole seconds until something stamped [updatedAt] is released (0 = already); null = nothing is released at all
     * (replay) or no time. Bisects [reveal] like the web's holdSecs.
     */
    fun holdSecs(updatedAt: Double?): Int? {
        if (updatedAt == null || !updatedAt.isFinite()) return null
        if (reveal(updatedAt)) return 0
        val t = now()
        var lo = t - 1200
        if (!reveal(lo)) return null
        var hi = t + 10
        repeat(14) {
            val mid = (lo + hi) / 2
            if (reveal(mid)) lo = mid else hi = mid
        }
        return max(1, (updatedAt - lo).roundToInt())
    }
}

/** Which live alerts show, and when (web alertPolicy.ts): key events only, at most one per 30 s, never before the delay. */
object AlertPolicy {
    const val MIN_GAP_SEC = 30.0
    const val SHOW_MS = 4_000L
    const val EXPIRE_SEC = 120.0
    const val MAX_PENDING_SEC = 300.0 + 120.0 + EXPIRE_SEC
    const val QUEUE_MAX = 4
    const val FANTASY_SWING_MIN = 5.0

    enum class Mode { KEY, ALL, OFF }

    fun parseMode(v: String?): Mode =
        when (v) {
            "all" -> Mode.ALL
            "off" -> Mode.OFF
            else -> Mode.KEY
        }

    data class Candidate<T>(
        val id: String,
        val kind: String,
        /** Event time, epoch s. */
        val at: Double,
        val favourite: Boolean = false,
        val late: Boolean = false,
        val swing: Double? = null,
        val revealedAt: Double? = null,
        val payload: T,
    )

    private val ALWAYS = setOf("goal", "try", "touchdown", "wicket", "red_card")
    private val PRIORITY =
        mapOf(
            "goal" to 5, "try" to 5, "touchdown" to 5, "wicket" to 5, "red_card" to 5,
            "field_goal" to 4, "score" to 4, "lead_change" to 3, "clutch" to 3, "fantasy" to 2, "flip" to 1,
        )

    private fun prio(c: Candidate<*>) = PRIORITY[c.kind] ?: 0

    fun isKeyEvent(
        c: Candidate<*>,
        mode: Mode,
        blowout: Boolean = false,
    ): Boolean {
        if (mode == Mode.OFF) return false
        if (blowout && !c.favourite) return false
        if (mode == Mode.ALL) return true
        if (c.kind == "flip") return true
        if (c.kind in ALWAYS) return true
        if (c.kind == "lead_change") return c.late
        if (c.kind == "fantasy") return (c.swing ?: 0.0) >= FANTASY_SWING_MIN
        return c.favourite
    }

    data class Pick<T>(
        val show: Candidate<T>?,
        val pending: List<Candidate<T>>,
    )

    /** One tick of the gate ([lastShownAt] = epoch s of the last alert, null = none). */
    fun <T> pick(
        pending: List<Candidate<T>>,
        lastShownAt: Double?,
        nowSec: Double,
        reveal: (Double) -> Boolean,
        mode: Mode,
        blowout: Boolean = false,
    ): Pick<T> {
        val seen = HashSet<String>()
        var keep =
            pending
                .map { if (it.revealedAt == null && reveal(it.at)) it.copy(revealedAt = nowSec) else it }
                .filter { c ->
                    val stale = c.revealedAt?.let { nowSec - it > EXPIRE_SEC } ?: false
                    if (c.id in seen || stale || nowSec - c.at > MAX_PENDING_SEC || !isKeyEvent(c, mode, blowout)) {
                        false
                    } else {
                        seen += c.id
                        true
                    }
                }
        if (keep.size > QUEUE_MAX) keep = keep.takeLast(QUEUE_MAX)
        if (lastShownAt != null && nowSec - lastShownAt < MIN_GAP_SEC) return Pick(null, keep)
        val show =
            keep
                .filter { reveal(it.at) }
                .sortedWith(compareByDescending<Candidate<T>> { prio(it) }.thenBy { it.at })
                .firstOrNull()
        return Pick(show, if (show != null) keep.filter { it !== show } else keep)
    }

    /** "Late" for a lead change: final stages by sport. Unknown layouts are never late. */
    fun isLateGame(
        layout: String?,
        period: String?,
        clock: String?,
    ): Boolean {
        val per = period?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: -1
        val mins = clock?.let { Regex("""^\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
        return when (layout) {
            "basketball", "nfl" -> per >= 4
            "football" -> per >= 2 && mins >= 75
            "rugby" -> per >= 2 && mins >= 65
            else -> false
        }
    }

    fun isBlowout(
        layout: String?,
        home: Int?,
        away: Int?,
        late: Boolean,
    ): Boolean {
        if (home == null || away == null || layout == null) return false
        val big = mapOf("basketball" to 25, "nfl" to 24, "football" to 4, "rugby" to 28)[layout] ?: return false
        return late && abs(home - away) >= big
    }
}

/** Scorebug and panel policy (web companion.ts). */
object CompanionPolicy {
    const val SCOREBUG_HIDE_MS = 8_000L
    const val MAX_RUN_ON_SEC = 20

    /** The pill shows for 8 s after a change, always when pinned, and never while the panel (with its score strip) is open. */
    fun scorebugVisible(
        nowMs: Long,
        changedAtMs: Long?,
        pinned: Boolean,
        panelOpen: Boolean,
    ): Boolean {
        if (panelOpen) return false
        if (pinned) return true
        return changedAtMs != null && nowMs - changedAtMs < SCOREBUG_HIDE_MS
    }

    /** Index of the newest snapshot the delay has released (-1 if none); oldest first, each asked with its own time. */
    fun newestRevealedIndex(
        updatedAts: List<Long?>,
        reveal: (Double?) -> Boolean,
    ): Int {
        for (i in updatedAts.indices.reversed()) if (reveal(updatedAts[i]?.toDouble())) return i
        return -1
    }

    /** Sport family for the running clock: nfl / nba count down, nrl / rugby / football count up. */
    fun clockSport(
        layout: String?,
        leagueKey: String?,
    ): String? {
        val k = leagueKey?.lowercase()
        return when {
            k == "nfl" || layout == "nfl" -> "nfl"
            k == "nba" || layout == "basketball" -> "nba"
            k == "nrl" -> "nrl"
            layout == "rugby" -> "rugby"
            layout == "football" -> "football"
            else -> null
        }
    }

    /** A running "mm:ss" clock moved on by [elapsedSec] since it was revealed; minute clocks and unknown sports stay as given. */
    fun tickClock(
        clock: String?,
        sport: String?,
        running: Boolean,
        elapsedSec: Double,
    ): String? {
        if (clock == null || !running || sport == null || !(elapsedSec >= 1)) return clock
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(clock.trim()) ?: return clock
        val down = sport == "nfl" || sport == "nba"
        val up = sport == "nrl" || sport == "rugby" || sport == "football"
        if (!down && !up) return clock
        val e = min(floor(elapsedSec).toInt(), MAX_RUN_ON_SEC)
        val total = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        val t = if (down) max(0, total - e) else total + e
        val mins = t / 60
        val mm = if (m.groupValues[1].length == 2) mins.toString().padStart(2, '0') else mins.toString()
        return "$mm:${(t % 60).toString().padStart(2, '0')}"
    }

    /** Stopped when the game is in a break / final / pre, or two snapshots 10+ s apart showed the same clock and period. */
    fun clockRunning(
        status: String?,
        prev: Triple<String?, String?, Long>?,
        cur: Triple<String?, String?, Long>,
    ): Boolean {
        if (status == "pre" || status == "post" || status == "break" || status == "final") return false
        if (prev != null && cur.third - prev.third >= 10_000 && prev.first == cur.first && prev.second == cur.second) return false
        return true
    }
}
