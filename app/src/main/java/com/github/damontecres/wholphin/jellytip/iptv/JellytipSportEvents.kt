package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.damontecres.wholphin.jellytip.JellytipApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/*
 * Sport interaction log (plan ~/ai/state/jellytip-sport-relevance-plan.md, "Update 21-Nov" (b); server iptv/sportevents.py):
 * what the sport surfaces showed and what the person did with it goes to `POST v1/me/sport/events` in batches of up to 50
 * (204). `shown` = a tile on screen for 1 s, once per fixture, surface and served list. Every 30 s and when a page goes,
 * fire-and-forget; 404 / 403 / 501 = no route on this server: off for this run. Never shows an error.
 */

data class SportEvent(
    /** One of [SportEventRules.TYPES]. */
    val type: String,
    val fid: String,
    /** One of [SportEventRules.SURFACES]. */
    val surface: String,
    /** Seconds since the epoch. */
    val at: Long,
    /** Where the tile sat (Game Centre row id, "hero"); [A-Za-z0-9_.:-]{1,40} or it is dropped. */
    val section: String? = null,
    /** 1-based place in its row. */
    val pos: Int? = null,
    /** The fixtures answer the tile came from. */
    val listId: String? = null,
    /** For `watch`: time watched. */
    val ms: Long? = null,
)

object SportEventRules {
    const val BATCH_MAX = 50
    const val QUEUE_CAP = 300
    const val SEEN_CAP = 2_000
    const val SHOWN_MIN_MS = 1_000L

    const val SHOWN = "shown"
    const val OPENED = "opened"
    const val WATCH = "watch"
    const val REMIND = "remind"
    const val RECORD = "record"
    const val NOT_INTERESTED = "not_interested"
    const val KICKOFF_OK = "kickoff_ok"
    const val KICKOFF_BACK = "kickoff_back"
    val TYPES = setOf(SHOWN, OPENED, WATCH, REMIND, RECORD, NOT_INTERESTED, KICKOFF_OK, KICKOFF_BACK)

    const val GAMECENTRE = "gamecentre"
    const val HOME_HERO = "home_hero"
    const val HOME_ROW = "home_row"
    const val KICKOFF_CARD = "kickoff_card"
    const val PLAYER_SWITCHER = "player_switcher"
    val SURFACES = setOf(GAMECENTRE, HOME_HERO, HOME_ROW, KICKOFF_CARD, PLAYER_SWITCHER)

    /** The server's shape for section / listId; anything else would be dropped there, so it is left out here. */
    private val WORD = Regex("^[A-Za-z0-9_.:-]{1,40}$")

    fun word(v: String?): String? = v?.takeIf { WORD.matches(it) }

    fun valid(e: SportEvent): Boolean = e.type in TYPES && e.surface in SURFACES && e.fid.isNotBlank() && e.fid.length <= 80 && '\n' !in e.fid

    /**
     * `shown`: once per fixture, surface and served list (a re-served list has a new id: shown again). Everything else:
     * once per type, fixture and surface a minute (the server's own bucket).
     */
    fun dedupeKey(e: SportEvent): String =
        if (e.type == SHOWN) "shown|${e.fid}|${e.surface}|${e.listId ?: ""}" else "${e.type}|${e.fid}|${e.surface}|${e.at / 60}"

    fun body(batch: List<SportEvent>): String =
        JSONObject()
            .put(
                "events",
                JSONArray().apply {
                    batch.forEach { e ->
                        put(
                            JSONObject().apply {
                                put("type", e.type)
                                put("fid", e.fid)
                                put("surface", e.surface)
                                put("at", e.at)
                                word(e.section)?.let { put("section", it) }
                                e.pos?.takeIf { it in 1..500 }?.let { put("pos", it) }
                                word(e.listId)?.let { put("listId", it) }
                                e.ms?.takeIf { it >= 0 }?.let { put("ms", it) }
                            },
                        )
                    }
                },
            ).toString()
}

/**
 * The queue. Duplicates (by [SportEventRules.dedupeKey]) are dropped; the keys outlive a send (a tile scrolled back into
 * view after a flush is not `shown` twice for the same list), bounded by [seenCap]. Oldest go first past [cap].
 */
class SportEventQueue(
    private val cap: Int = SportEventRules.QUEUE_CAP,
    private val seenCap: Int = SportEventRules.SEEN_CAP,
) {
    private val list = ArrayList<SportEvent>()
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun add(e: SportEvent): Boolean {
        if (!SportEventRules.valid(e)) return false
        if (!seen.add(SportEventRules.dedupeKey(e))) return false
        while (seen.size > seenCap) seen.remove(seen.first())
        list.add(e)
        while (list.size > cap) list.removeAt(0)
        return true
    }

    @Synchronized
    fun take(): List<SportEvent> = list.take(SportEventRules.BATCH_MAX)

    @Synchronized
    fun done(sent: List<SportEvent>) {
        val gone = sent.toSet()
        list.removeAll { it in gone }
    }

    @Synchronized
    fun size() = list.size

    @Synchronized
    fun clear() {
        list.clear()
        seen.clear()
    }
}

/** What a flush does with the answer. */
enum class SportEventAnswer { SENT, KEEP, DROP, OFF }

object SportEventPolicy {
    /** null = unreachable: keep for the next flush. 429 = slow down: keep. 404 / 403 / 501 = no route: off. 401 = drop. */
    fun answer(code: Int?): SportEventAnswer =
        when (code) {
            null, 429 -> SportEventAnswer.KEEP
            404, 403, 501 -> SportEventAnswer.OFF
            in 200..299 -> SportEventAnswer.SENT
            else -> SportEventAnswer.DROP
        }
}

/** The sender (same shape as JellytipSignals): one queue per signed-in person, a 30 s timer, flush on page leave. */
object JellytipSportEvents {
    private const val FLUSH_MS = 30_000L
    private val queue = SportEventQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var timer: Job? = null

    @Volatile private var off = false

    @Volatile private var owner = ""

    fun log(
        type: String,
        f: FxItem,
        surface: String,
        section: String? = null,
        pos: Int? = null,
    ) = emit(SportEvent(type, f.fid, surface, System.currentTimeMillis() / 1000, section, pos, f.listId))

    /** For tiles that only know a fixture id (Home's "Coming up in sport", from the upcoming route: no list id). */
    fun logFid(
        type: String,
        fid: String?,
        surface: String,
        pos: Int? = null,
    ) {
        fid ?: return
        emit(SportEvent(type, fid, surface, System.currentTimeMillis() / 1000, pos = pos))
    }

    /**
     * For a channel picked in the player (the switcher's chips): the live (or about to start) fixture listing it, from
     * [items] (the cached fixtures); a guessed ("maybe") link or no fixture = nothing logged.
     */
    fun logChannel(
        type: String,
        channelId: String,
        surface: String,
        items: suspend () -> List<FxItem>?,
    ) {
        if (off) return
        scope.launch {
            val fx = runCatching { items() }.getOrNull() ?: return@launch
            val cm = SportFixtures.resolveChannelMatch(fx, channelId, System.currentTimeMillis() / 1000) ?: return@launch
            if (cm.maybe) return@launch
            cm.fixture?.let { log(type, it, surface) }
        }
    }

    fun emit(e: SportEvent) {
        val api = JellytipApi.shared ?: return
        if (off) return
        val who = api.currentUserKey()
        if (who.isEmpty()) return
        // One critical section: a person switching between the check and the add can't leak an event into the new queue
        synchronized(this) {
            if (who != owner) {
                queue.clear()
                owner = who
            }
            if (!queue.add(e) || timer != null) return
            timer =
                scope.launch {
                    while (true) {
                        delay(FLUSH_MS)
                        flush()
                    }
                }
        }
    }

    /** A page is going (or the app is hidden): send what is queued now. */
    fun flushSoon() {
        if (queue.size() > 0) scope.launch { flush() }
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
                val code = api.userCall("v1/me/sport/events", SportEventRules.body(batch))?.first
                when (SportEventPolicy.answer(code)) {
                    SportEventAnswer.SENT -> queue.done(batch)
                    SportEventAnswer.KEEP -> return@withLock
                    SportEventAnswer.DROP -> {
                        queue.done(batch)
                        return@withLock
                    }
                    SportEventAnswer.OFF -> {
                        off = true
                        queue.clear()
                        return@withLock
                    }
                }
            }
        }
}

/** `shown` once the tile has been at least half on screen for [SportEventRules.SHOWN_MIN_MS] (layout visibility, not composition). */
fun Modifier.sportShown(
    f: FxItem,
    surface: String,
    section: String? = null,
    pos: Int? = null,
): Modifier =
    onVisibilityChanged(minDurationMs = SportEventRules.SHOWN_MIN_MS, minFractionVisible = 0.5f) { visible ->
        if (visible) JellytipSportEvents.log(SportEventRules.SHOWN, f, surface, section, pos)
    }

/** The same for a tile that only has a fixture id. */
fun Modifier.sportShownFid(
    fid: String?,
    surface: String,
    pos: Int? = null,
): Modifier =
    if (fid == null) {
        this
    } else {
        onVisibilityChanged(minDurationMs = SportEventRules.SHOWN_MIN_MS, minFractionVisible = 0.5f) { visible ->
            if (visible) JellytipSportEvents.logFid(SportEventRules.SHOWN, fid, surface, pos)
        }
    }

/** Flush when the page leaves or the app is hidden. */
@Composable
fun SportEventsFlushOnLeave() {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { JellytipSportEvents.flushSoon() }
    DisposableEffect(Unit) { onDispose { JellytipSportEvents.flushSoon() } }
}
