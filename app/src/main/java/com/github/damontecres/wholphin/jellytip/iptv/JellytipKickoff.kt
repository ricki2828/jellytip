package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.services.NavigationManager
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

/*
 * 1. Kick-off takeover, look 1B (~/ai/state/decision-briefs/jellytip-tv-20261121/mock/01b-kickoff-corner-card.html): a
 * quiet corner card over anything (film, browse, live) when a favourite team's game is about to start (2 min) and again
 * at kick-off. It never takes focus; while it shows OK = watch the best channel (the film's place is kept by its own
 * resume), Back = dismiss, everything else passes through. 15 s, then it leaves. Fixtures come from the same cached
 * /v1/iptv/sport/fixtures answer Game Centre uses; a fixture with a reminder is left to the reminder banner.
 */
data class KickoffCard(
    val due: KickoffPolicy.Due,
    /** When it went up (ms): a card set while the app was hidden is dropped, not shown late. */
    val shownAtMs: Long = System.currentTimeMillis(),
    /** Team colours from the match record (null = neutral). */
    val home: MatchTeam? = null,
    val away: MatchTeam? = null,
)

@Singleton
class JellytipKickoff
    @Inject
    constructor(
        private val service: JellytipIptvService,
        private val playback: JellytipIptvPlayback,
        private val navigationManager: NavigationManager,
        private val reminders: JellytipReminders,
    ) {
        /** Set by the live player while one of its own cards (catch me up, full time) wants OK: no takeover then. */
        @Volatile
        var quiet: Boolean = false

        /** The OK / Back press that started while the card was up (a press begun before it never acts on it). */
        private var downKey: Int? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val _card = MutableStateFlow<KickoffCard?>(null)
        val card: StateFlow<KickoffCard?> = _card
        private val nudged = HashSet<String>()
        private var loop: Job? = null

        /** Start watching fixtures (app root; idempotent). Stops for good on a server without the route. */
        fun start() {
            if (loop?.isActive == true) return
            loop =
                scope.launch {
                    var primed = false
                    while (true) {
                        val r = service.fixtures(maxAgeMs = 55_000)
                        if (r?.first == 404 || r?.first == 403) {
                            JellytipTrace.log("iptv", "kick-off takeover off (fixtures ${r.first})")
                            return@launch
                        }
                        val items = r?.second?.items
                        if (items != null) {
                            val now = nowSec()
                            if (!primed) {
                                // A game already under way when the app starts is not announced
                                primed = true
                                items.filter { it.status == "in" || it.kickoff <= now }.forEach { nudged += "${it.fid}:soon"; nudged += "${it.fid}:live" }
                            }
                            if (_card.value == null) check(items, now)
                        }
                        delay(TICK_MS)
                    }
                }
        }

        private suspend fun check(
            items: List<FxItem>,
            now: Long,
        ) {
            // The reminder banner owns the top of the screen (and OK/Back) while it shows
            if (quiet || reminders.nudge.value != null) return
            val playing = (playback.state.value as? IptvPlayState.Playing)?.target?.channelId
            val d = KickoffPolicy.due(items, now, nudged, playing) ?: return
            nudged += d.key
            if (d.phase == "live") nudged += "${d.fixture.fid}:soon"
            JellytipTrace.log("iptv", "kick-off takeover ${d.key} ${d.fixture.title}")
            val first = KickoffCard(d)
            _card.value = first
            // Team colours for the top bar (one match call; the card shows neutral until then)
            val m = runCatching { fetchMatch(d.fixture.companionId)?.second?.match }.getOrNull()
            if (m != null && _card.value?.due == d) _card.value = first.copy(home = m.home, away = m.away)
        }

        /** Dismiss by announcement key (the card is replaced once team colours arrive). */
        fun dismiss(key: String) {
            if (_card.value?.due?.key == key) _card.value = null
        }

        /** While the card shows: OK = watch, Back = dismiss (both swallowed, down and up); other keys pass. */
        fun onKey(e: android.view.KeyEvent): Boolean {
            val c = _card.value ?: return false
            if (quiet) return false
            val ok = e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == android.view.KeyEvent.KEYCODE_ENTER || e.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            val back = e.keyCode == android.view.KeyEvent.KEYCODE_BACK
            if (!ok && !back) return false
            if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                // Only a fresh press, begun after the card has been up a moment, belongs to the card
                if (e.repeatCount == 0 && System.currentTimeMillis() - c.shownAtMs >= KEY_GUARD_MS) downKey = e.keyCode
                return downKey == e.keyCode
            }
            if (e.action == android.view.KeyEvent.ACTION_UP) {
                if (downKey != e.keyCode) return false
                downKey = null
                _card.value = null
                if (ok) {
                    watch(c.due.fixture)
                } else {
                    JellytipTrace.log("iptv", "kick-off takeover dismissed ${c.due.key}")
                }
                return true
            }
            return false
        }

        private fun watch(f: FxItem) {
            val ch = SportFixtures.pickChannel(f, nowSec()).channel ?: return
            // Already full screen on a live channel: swap that page; anywhere else (film, browse) push the player, so
            // Back returns to the film where it stopped
            // On a live channel or a film: swap that page. The film's player is released when its page leaves (it
            // doesn't re-open on Back), so the film's place is kept by Jellyfin's resume point: its page offers Resume.
            val top = navigationManager.backStack.lastOrNull()
            val swap =
                top is com.github.damontecres.wholphin.ui.nav.Destination.JellytipLiveTvPlayer ||
                    top is com.github.damontecres.wholphin.ui.nav.Destination.Playback ||
                    top is com.github.damontecres.wholphin.ui.nav.Destination.PlaybackList
            launchFixture(playback, navigationManager, f, ch, fromStart = false, from = "kick-off", replaceTop = swap, forceMatch = true)
        }

        companion object {
            private const val TICK_MS = 20_000L
            private const val KEY_GUARD_MS = 400L
        }
    }

@dagger.hilt.android.lifecycle.HiltViewModel
class JellytipKickoffViewModel
    @Inject
    constructor(
        val kickoff: JellytipKickoff,
    ) : androidx.lifecycle.ViewModel()

@Composable
private fun CrestDot(
    team: MatchTeam?,
    short: String?,
    logo: String?,
    color: Color,
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(color).border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        var failed by remember(logo) { mutableStateOf(false) }
        val l = logo ?: team?.logo
        if (l != null && !failed) {
            AsyncImage(model = l, contentDescription = null, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(32.dp))
        } else {
            Cond((short ?: team?.short ?: team?.name ?: "").take(3), 14.sp, Color.White)
        }
    }
}

/** The 1B corner card, top right; mounted at the app root over every screen. */
@Composable
fun JellytipKickoffCard(
    kickoff: JellytipKickoff,
    modifier: Modifier = Modifier,
) {
    val c by kickoff.card.collectAsState()
    val card = c ?: return
    LaunchedEffect(card.due.key) {
        // Measured from when it went up: a card set while the app was hidden goes at once
        delay((card.shownAtMs + KickoffPolicy.SHOW_MS - System.currentTimeMillis()).coerceAtLeast(0))
        kickoff.dismiss(card.due.key)
    }
    val f = card.due.fixture
    val pal = remember(card.home, card.away) { matchPalette(IptvMatch(f.companionId, f.league, f.leagueKey, card.home, card.away, f.kickoff, null, f.status, false, null)) }
    val left by produceState(KickoffPolicy.SHOW_MS / 1000, card.due.key) {
        while (value > 0) {
            delay(1_000)
            value--
        }
    }
    val now by produceState(nowSec(), card.due.key) {
        while (true) {
            delay(5_000)
            value = nowSec()
        }
    }
    Box(modifier.fillMaxSize().padding(top = 36.dp, end = 48.dp), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.widthIn(min = 360.dp, max = 520.dp).clip(RoundedCornerShape(10.dp)).background(Broadcast.Base.copy(alpha = 0.96f))) {
            Row(Modifier.fillMaxWidth().height(6.dp)) {
                Box(Modifier.weight(1f).fillMaxSize().background(if (pal.known) pal.home else Broadcast.Gold))
                Box(Modifier.weight(1f).fillMaxSize().background(if (pal.known) pal.away else Broadcast.Gold))
            }
            Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CrestDot(card.home, f.homeShort, f.homeLogo, if (pal.known) pal.home else Broadcast.Chip)
                    Cond(f.title, 24.sp, Color.White, modifier = Modifier.weight(1f, fill = false))
                    if (f.away.isNotBlank()) CrestDot(card.away, f.awayShort, f.awayLogo, if (pal.known) pal.away else Broadcast.Chip)
                }
                Cond(KickoffPolicy.headline(card.due, now), 22.sp, Broadcast.Gold)
                BodyText("OK to watch · leaves in $left s", 16.sp, Broadcast.Secondary, maxLines = 1)
            }
        }
    }
}
