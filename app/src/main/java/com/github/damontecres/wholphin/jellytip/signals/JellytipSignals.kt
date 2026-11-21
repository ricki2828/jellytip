package com.github.damontecres.wholphin.jellytip.signals

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONArray
import org.json.JSONObject

/*
 * Taste signals to the companion (web 4a830873 / 57decea1, lib/signals.ts; contract ~/ai/state/jellytip-taste-contract.md):
 * what the person plays, finishes, skips, dwells on and rates goes to `POST v1/me/signals` in batches, so For You learns
 * on the TV too. Ids and durations only. Every play-type event carries `ms` (the server ignores a play without one).
 */

data class TasteSignal(
    /** trailer | detail | play | finish | remind | record | thumbUp | thumbDown | mylist | skip */
    val type: String,
    /** ms since the epoch */
    val at: Long,
    /** movie | series */
    val kind: String,
    val tmdbId: Int? = null,
    val itemId: String? = null,
    val iptvId: String? = null,
    /** dwell or watch duration */
    val ms: Long? = null,
)

/** The title a signal is about. */
data class TasteTarget(
    val kind: String,
    val tmdbId: Int? = null,
    val itemId: String? = null,
    val iptvId: String? = null,
)

/** What is being watched. */
data class WatchMeta(
    val target: TasteTarget,
    /** Only a film leaving early counts as 'skip'. */
    val isFilm: Boolean,
    /** Started from a personal channel: a weaker signal, never finish or skip. */
    val channel: Boolean = false,
)

object TasteRules {
    const val BATCH_MAX = 50
    const val QUEUE_CAP = 200
    const val DETAIL_MIN_MS = 8_000L
    const val DETAIL_MAX_MS = 10 * 60_000L
    const val SKIP_UNDER_S = 120.0
    const val SKIP_MIN_PLAYED_S = 10.0
    const val SKIP_MIN_RUNTIME_S = 40 * 60.0
    const val FINISH_FRACTION = 0.9
    const val PLAY_MIN_S = 60.0
    const val CHANNEL_PLAY_MIN_S = 30.0

    private fun idOf(s: TasteSignal) =
        when {
            s.tmdbId != null -> "t${s.tmdbId}"
            s.itemId != null -> "i${s.itemId}"
            s.iptvId != null -> "v${s.iptvId}"
            else -> ""
        }

    /** Events with no id say nothing about taste. */
    fun valid(s: TasteSignal) = idOf(s).isNotEmpty() && (s.kind == "movie" || s.kind == "series")

    /** Same (type, title, minute) is one event (the server dedupes the same way). */
    fun dedupeKey(s: TasteSignal) = "${s.type}|${idOf(s)}|${s.at / 60_000}"

    private fun base(
        t: TasteTarget,
        type: String,
        at: Long,
        ms: Long?,
    ) = TasteSignal(type, at, t.kind, t.tmdbId, t.itemId, t.iptvId, ms)

    /** What a watch of [playedS] says ([runtimeS] 0 = unknown); null when too short to say anything. */
    fun watchSignal(
        meta: WatchMeta,
        playedS: Double,
        runtimeS: Double,
        ended: Boolean,
        at: Long,
    ): TasteSignal? {
        val ms = Math.round(maxOf(0.0, playedS) * 1000)
        if (meta.channel) return if (playedS >= CHANNEL_PLAY_MIN_S) base(meta.target, "play", at, ms) else null
        if (ended || (runtimeS > 0 && playedS >= runtimeS * FINISH_FRACTION)) {
            return if (playedS >= PLAY_MIN_S) base(meta.target, "finish", at, ms) else null
        }
        if (meta.isFilm && runtimeS >= SKIP_MIN_RUNTIME_S && playedS >= SKIP_MIN_PLAYED_S && playedS < SKIP_UNDER_S) {
            return base(meta.target, "skip", at, ms)
        }
        return if (playedS >= PLAY_MIN_S) base(meta.target, "play", at, ms) else null
    }

    fun detailSignal(
        t: TasteTarget,
        dwellMs: Long,
        at: Long,
    ): TasteSignal? = if (dwellMs >= DETAIL_MIN_MS) base(t, "detail", at, minOf(DETAIL_MAX_MS, dwellMs)) else null

    /** Thumbs, My list, reminders, recordings: a plain event (no duration). */
    fun event(
        t: TasteTarget,
        type: String,
        at: Long,
    ) = base(t, type, at, null)

    /** A library item's target: films by their own id, episodes / seasons by their series. */
    fun libraryTarget(item: BaseItem): TasteTarget? {
        val ep = item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON
        if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.SERIES && !ep) return null
        val id = (if (ep) item.data.seriesId else item.id)?.toString()?.replace("-", "") ?: return null
        val tmdb =
            if (ep) {
                null
            } else {
                item.data.providerIds
                    ?.entries
                    ?.firstOrNull { it.key.equals("Tmdb", true) }
                    ?.value
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }
        return TasteTarget(if (item.type == BaseItemKind.MOVIE) "movie" else "series", tmdb, id)
    }

    fun body(batch: List<TasteSignal>): String =
        JSONObject()
            .put(
                "events",
                JSONArray().apply {
                    batch.forEach { s ->
                        put(
                            JSONObject().apply {
                                put("type", s.type)
                                put("at", s.at)
                                put("kind", s.kind)
                                s.tmdbId?.let { put("tmdbId", it) }
                                s.itemId?.let { put("itemId", it) }
                                s.iptvId?.let { put("iptvId", it) }
                                s.ms?.let { put("ms", it) }
                            },
                        )
                    }
                },
            ).toString()
}

/** Events waiting to go (deduped, capped: the oldest go first). */
class TasteQueue(
    private val cap: Int = TasteRules.QUEUE_CAP,
) {
    private val list = ArrayList<TasteSignal>()
    private val keys = HashSet<String>()

    @Synchronized
    fun add(s: TasteSignal): Boolean {
        if (!TasteRules.valid(s)) return false
        val k = TasteRules.dedupeKey(s)
        if (!keys.add(k)) return false
        list.add(s)
        while (list.size > cap) keys.remove(TasteRules.dedupeKey(list.removeAt(0)))
        return true
    }

    @Synchronized
    fun take(max: Int = TasteRules.BATCH_MAX): List<TasteSignal> = list.take(minOf(max, TasteRules.BATCH_MAX))

    @Synchronized
    fun done(sent: List<TasteSignal>) {
        val gone = sent.toSet()
        list.removeAll { it in gone }
    }

    @Synchronized
    fun size() = list.size

    @Synchronized
    fun clear() {
        list.clear()
        keys.clear()
    }
}

/** Real playing time: steps of normal playback only, never a seek (a jump of more than [maxStep] s). */
class PlayTracker {
    private var last: Double? = null
    var playedS = 0.0
        private set

    fun tick(
        t: Double,
        maxStep: Double = 2.5,
    ) {
        if (!t.isFinite()) return
        val d = last?.let { t - it } ?: 0.0
        if (d > 0 && d <= maxStep) playedS += d
        last = t
    }

    fun seeked() {
        last = null
    }

    fun reset() {
        last = null
        playedS = 0.0
    }
}

/** One title's watch, closed once (a finish is never followed by a second event). */
class WatchSession {
    private val tracker = PlayTracker()
    private var meta: WatchMeta? = null
    private var runtimeS = 0.0
    private var closed = false

    fun begin(
        meta: WatchMeta?,
        runtimeS: Double,
    ) {
        tracker.reset()
        this.meta = meta
        this.runtimeS = runtimeS
        closed = false
    }

    fun tick(
        t: Double,
        maxStep: Double = 2.5,
    ) {
        if (!closed) tracker.tick(t, maxStep)
    }

    fun seeked() = tracker.seeked()

    /** Runtime learned after start (IPTV: the player's duration); the first known value stays. */
    fun runtime(seconds: Double) {
        if (runtimeS <= 0 && seconds > 0) runtimeS = seconds
    }

    fun end(
        ended: Boolean,
        at: Long,
    ): TasteSignal? {
        if (closed) return null
        closed = true
        val m = meta ?: return null
        return TasteRules.watchSignal(m, tracker.playedS, runtimeS, ended, at)
    }
}

/**
 * The sender: events queue up and go every 30 s (and when a watch ends), 50 a call. 404 / 403 / 501 = the server has no
 * signals: off for this run. A different signed-in person starts a fresh queue. Never shows an error.
 */
object JellytipSignals {
    private const val FLUSH_MS = 30_000L
    private val queue = TasteQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var timer: Job? = null

    @Volatile private var off = false

    @Volatile private var owner = ""

    fun emit(s: TasteSignal?) {
        s ?: return
        val api = JellytipApi.shared ?: return
        if (off) return
        val who = api.currentUserKey()
        if (who.isEmpty()) return
        if (who != owner) {
            queue.clear()
            owner = who
        }
        if (queue.add(s) && timer == null) {
            timer =
                scope.launch {
                    while (true) {
                        delay(FLUSH_MS)
                        flush()
                    }
                }
        }
    }

    /** Send what is queued now (a watch just ended). */
    fun flushSoon() {
        scope.launch { flush() }
    }

    private suspend fun flush() =
        mutex.withLock {
            val api = JellytipApi.shared ?: return@withLock
            repeat(4) {
                if (off) {
                    queue.clear()
                    return@withLock
                }
                val batch = queue.take()
                if (batch.isEmpty()) return@withLock
                val code = api.userCall("v1/me/signals", TasteRules.body(batch))?.first ?: return@withLock // unreachable: keep
                queue.done(batch)
                when (code) {
                    404, 403, 501 -> {
                        off = true
                        queue.clear()
                        return@withLock
                    }
                    401 -> {
                        queue.clear()
                        return@withLock
                    }
                }
            }
        }
}
