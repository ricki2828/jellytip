package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The top banner (plan "Remind me", in-app nudge): [live] = kick-off, else "starts in N min". */
data class ReminderNudge(
    val item: IptvUpcoming,
    val live: Boolean,
) {
    fun text(now: Long): String =
        (
            if (live) {
                "${item.title} is on now"
            } else {
                "${item.title} starts in ${((item.start - now + 59) / 60).coerceAtLeast(1)} min"
            }
        ) + if (item.noStream) " · no stream found yet" else ""
}

/**
 * The person's sport reminders (~/ai/plans/jellytip-live-sport.md, "Remind me"): GET /v1/iptv/reminders, polled every
 * 60 s only while any exist, plus the nudge when one turns `soon` and again at kick-off. Plain StateFlows, collected in
 * composition (snapshot rule: no lazily created snapshot state is read during composition). A 404 hides everything.
 */
@Singleton
class JellytipReminders
    @Inject
    constructor(
        private val service: JellytipIptvService,
        private val playback: JellytipIptvPlayback,
        private val navigationManager: NavigationManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** True once the server answered 200, false on 404 (older server); null = not known yet. */
        private val _available = MutableStateFlow<Boolean?>(null)
        val available: StateFlow<Boolean?> = _available

        /** Not yet ended, soonest first; null = not loaded. */
        private val _items = MutableStateFlow<List<IptvUpcoming>?>(null)
        val items: StateFlow<List<IptvUpcoming>?> = _items

        private val _nudge = MutableStateFlow<ReminderNudge?>(null)
        val nudge: StateFlow<ReminderNudge?> = _nudge

        private var loop: Job? = null
        private var lastFetch = 0L

        /** "<id>:soon" / "<id>:live" already nudged (or already past when first seen). */
        private val nudged = HashSet<String>()

        /** Whose reminders these are: another person's are cleared at a user switch, a late answer for them dropped. */
        private val own = UserOwnership.app()

        init {
            scope.launch { service.user.drop(1).collect { u -> switched(u == null) } }
        }

        private fun switched(signedOut: Boolean) {
            if (!own.mustClear(signedOut)) return
            JellytipTrace.log("iptv", "reminders: user switch, cleared")
            val wasRunning = loop?.isActive == true
            loop?.cancel()
            loop = null
            _available.value = null
            _items.value = null
            _nudge.value = null
            nudged.clear()
            lastFetch = 0L
            // Home started the poll: keep it going for the new person
            if (wasRunning && !signedOut) start()
        }

        /** Start loading and polling (Home calls it; idempotent). */
        fun start() {
            if (loop?.isActive == true) return
            loop =
                scope.launch {
                    refresh()
                    while (true) {
                        if (_available.value == false) return@launch
                        if (_available.value == null) {
                            // Unreachable so far: try again later
                            delay(POLL_MS)
                            refresh()
                            continue
                        }
                        if (_items.value.isNullOrEmpty()) {
                            // Nothing to poll: wait until one is added
                            _items.first { !it.isNullOrEmpty() }
                            continue
                        }
                        check()
                        delay(TICK_MS)
                        if (System.currentTimeMillis() - lastFetch >= POLL_MS) refresh()
                    }
                }
        }

        suspend fun refresh() {
            val who = own.start()
            val got = service.reminders() ?: return
            if (!own.accept(who)) return
            lastFetch = System.currentTimeMillis()
            when (got.first) {
                200 -> {
                    _available.value = true
                    val first = _items.value == null
                    _items.value = got.second
                    // Already soon or live when first seen this process: one nudge for the current phase only
                    if (first) {
                        val now = nowSec()
                        got.second.forEach { if (it.isLive(now)) nudged += "${it.reminderId}:soon" }
                    }
                }

                404 -> {
                    _available.value = false
                    _items.value = null
                }
            }
        }

        private fun check() {
            val now = nowSec()
            val list = _items.value.orEmpty().filter { it.stop > now }
            for (u in list) {
                val id = u.reminderId ?: u.key
                val phase =
                    when {
                        u.isLive(now) -> "live"
                        u.isSoon(now) -> "soon"
                        else -> null
                    } ?: continue
                if (nudged.add("$id:$phase")) {
                    val playing = playback.state.value
                    val ids = (listOf(u.channel) + u.alternateChannels).map { it.id }
                    // Already watching it: no nudge
                    if (playing is IptvPlayState.Playing && playing.target.channelId in ids) continue
                    if (phase == "live") nudged += "$id:soon"
                    JellytipTrace.log("iptv", "reminder nudge $phase ${u.key}")
                    _nudge.value = ReminderNudge(u, phase == "live")
                    return
                }
            }
        }

        /** While the banner shows: OK = Watch, Back = dismiss (both swallowed, down and up); other keys pass. */
        fun onKey(e: android.view.KeyEvent): Boolean {
            val n = _nudge.value ?: return false
            when (e.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (e.action == android.view.KeyEvent.ACTION_UP) {
                        _nudge.value = null
                        watch(n.item)
                    }
                    return true
                }

                android.view.KeyEvent.KEYCODE_BACK -> {
                    if (e.action == android.view.KeyEvent.ACTION_UP) _nudge.value = null
                    return true
                }
            }
            return false
        }

        fun dismissNudge(n: ReminderNudge) {
            if (_nudge.value == n) _nudge.value = null
        }

        /** The reminder id for [u]: the loaded list wins (it reflects adds and removes), else the item's own. */
        fun reminderIdFor(
            u: IptvUpcoming,
            list: List<IptvUpcoming>?,
        ): String? = if (list != null) list.firstOrNull { it.sameGame(u) }?.reminderId else u.reminderId

        fun add(u: IptvUpcoming) {
            scope.launch {
                val who = own.start()
                // Fixture path first when the game is known (web a9b66910): one row per game, whoever sets it
                val fixtureId = u.fid
                val matchId = u.matchId ?: u.matchInfo?.id
                val id =
                    (if (fixtureId != null || matchId != null) service.addFixtureReminder(fixtureId, matchId)?.takeIf { it.isNotBlank() } else null)
                        ?: service.addReminder(u)
                if (id != null && own.accept(who)) {
                    _items.value = (_items.value.orEmpty().filter { it.key != u.key } + u.copy(reminderId = id)).sortedBy { it.start }
                    refresh()
                }
            }
        }

        fun remove(
            id: String,
            /** True when at least one row was removed (called on the main thread). */
            onDone: (Boolean) -> Unit = {},
        ) {
            scope.launch {
                // Switching off removes every row of the same game (key, fixture id, match id), so no twin keeps
                // nudging (web f452ce07)
                val list = _items.value.orEmpty()
                val me = list.firstOrNull { it.reminderId == id }
                val ids = (listOf(id) + (me?.let { m -> list.filter { it.sameGame(m) }.mapNotNull { it.reminderId } } ?: emptyList())).distinct()
                val who = own.start()
                var anyOk = false
                for (x in ids) if (service.deleteReminder(x)) anyOk = true
                JellytipTrace.log("iptv", "reminder off ${ids.size} row(s) for ${me?.key ?: id}")
                if (anyOk && own.accept(who)) {
                    _items.value = _items.value?.filter { it.reminderId !in ids }
                    refresh()
                }
                onDone(anyOk)
            }
        }

        /** Full screen on the reminder's best channel; Up/Down and failover go through the event's other channels. */
        fun watch(
            u: IptvUpcoming,
            ch: IptvChannel = u.channel,
        ) {
            JellytipTrailerControl.stop()
            if (u.noStream) {
                // No channel linked yet: Game Centre (it shows the fixture, and a channel once one is found)
                navigationManager.navigateToChecked(Destination.JellytipUpcomingSport, "reminder without a stream ${u.key}")
                return
            }
            val all = listOf(u.channel) + u.alternateChannels
            // The page first: a blocked navigation must not leave a stream playing with no screen (sound, no picture)
            if (!navigationManager.navigateToChecked(Destination.JellytipLiveTvPlayer(ch.id), "reminder watch ${ch.name}")) return
            playback.lineup = all
            playback.alternates = all.associate { c -> c.id to all.filter { it.id != c.id } }
            playback.play(IptvTarget(ch.id, ch.name), "reminder watch")
        }

        companion object {
            private const val TICK_MS = 15_000L
            private const val POLL_MS = 60_000L
        }
    }

@dagger.hilt.android.lifecycle.HiltViewModel
class JellytipReminderBannerViewModel
    @Inject
    constructor(
        val reminders: JellytipReminders,
    ) : androidx.lifecycle.ViewModel()
