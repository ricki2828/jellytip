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
        if (live) {
            "${item.title} is on now"
        } else {
            "${item.title} starts in ${((item.start - now + 59) / 60).coerceAtLeast(1)} min"
        }
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
            val got = service.reminders() ?: return
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
        ): String? = if (list != null) list.firstOrNull { it.key == u.key }?.reminderId else u.reminderId

        fun add(u: IptvUpcoming) {
            scope.launch {
                val id = service.addReminder(u)
                if (id != null) {
                    _items.value = (_items.value.orEmpty().filter { it.key != u.key } + u.copy(reminderId = id)).sortedBy { it.start }
                    refresh()
                }
            }
        }

        fun remove(id: String) {
            scope.launch {
                if (service.deleteReminder(id)) {
                    _items.value = _items.value?.filter { it.reminderId != id }
                    refresh()
                }
            }
        }

        /** Full screen on the reminder's best channel; Up/Down and failover go through the event's other channels. */
        fun watch(
            u: IptvUpcoming,
            ch: IptvChannel = u.channel,
        ) {
            JellytipTrailerControl.stop()
            val all = listOf(u.channel) + u.alternateChannels
            playback.lineup = all
            playback.alternates = all.associate { c -> c.id to all.filter { it.id != c.id } }
            playback.play(IptvTarget(ch.id, ch.name), "reminder watch")
            navigationManager.navigateTo(Destination.JellytipLiveTvPlayer(ch.id), "reminder watch ${ch.name}")
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
