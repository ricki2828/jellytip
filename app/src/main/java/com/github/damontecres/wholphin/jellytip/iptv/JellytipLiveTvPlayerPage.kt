@file:OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class JellytipLiveTvPlayerViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: com.github.damontecres.wholphin.services.NavigationManager,
        val reminders: JellytipReminders,
    ) : ViewModel()

private const val ZAP_SETTLE_MS = 700L
private const val BANNER_MS = 5_000L
private const val STALL_MS = 10_000L
private const val FLIP_POLL_MS = 30_000L
private const val LIVE_OPEN_POLL_MS = 15_000L
private const val FANTASY_POLL_MS = 60_000L
private const val FLIP_TOAST_MS = 6_000L
private const val TOAST_GAP_MS = 250L
private const val MAX_QUEUED_TOASTS = 3
private const val PRE_CARD_MS = 10_000L
private const val IDLE_RETUNE_MS = 600L
private val TOAST_MINE = Color(0xFF22C55E)
private val TOAST_OPP = Color(0xFFE5534B)

/**
 * A top-right player toast, a Broadcast lower third (plan "In-match visual direction"): a colour edge (the team for a
 * flip or moment, green for your player, red for your opponent's), an icon block, then a kicker, the main line in
 * condensed caps and a sub line.
 */
data class PlayerToast(
    val main: String,
    val kicker: String? = null,
    val sub: String? = null,
    val edge: Color = Broadcast.Gold,
    val kickerColor: Color = Broadcast.Gold,
    val icon: androidx.compose.ui.graphics.vector.ImageVector = BroadcastIcons.Odds,
)

/**
 * An IPTV channel full screen. Up/Down zap through the guide tab it was opened from: the banner follows at once, the
 * stream is only asked for once the choice settles (each stream is the provider's one connection). OK shows the
 * now/next banner. Back returns to the guide, which lands on (and keeps playing) this channel.
 */
@Composable
fun JellytipLiveTvPlayerPage(
    channelId: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipLiveTvPlayerViewModel = hiltViewModel(),
) {
    val playback = viewModel.playback
    val playState by playback.state.collectAsState()
    val lineup =
        remember(channelId) {
            playback.lineup.takeIf { l -> l.any { it.id == channelId } }
                ?: listOf(IptvChannel(channelId, channelId.substringBefore(':'), null, channelId, null, "", null, 0, false))
        }
    val startIndex = remember(channelId) { lineup.indexOfFirst { it.id == channelId }.coerceAtLeast(0) }
    var index by remember { mutableIntStateOf(startIndex) }
    // What the guide asked for (live, or a catch-up programme of this channel); zapping away switches to live
    val opened = remember(channelId) { playback.state.value.target?.takeIf { it.channelId == channelId } }
    var current by remember { mutableStateOf(opened ?: IptvTarget(lineup[startIndex].id, lineup[startIndex].name)) }
    var bannerTick by remember { mutableIntStateOf(1) }
    var bannerVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    // Same-event alternate in use after the lineup channel's stream failed (plan §4); null = the lineup channel
    var fallback by remember { mutableStateOf<IptvChannel?>(null) }
    val tried = remember { mutableSetOf<String>() }
    // Epoch s when this page opened: a catch-up ending after it was a programme still on (kick-off)
    val startedAt = remember { nowSec() - 60 }
    val channel = fallback ?: lineup[index]

    DisposableEffect(Unit) {
        playback.attach("full")
        playback.setVolume(1f)
        onDispose {
            playback.lastFullScreenChannel = (fallback ?: lineup[index]).id
            playback.detach("full")
        }
    }
    // App hidden = JellytipIptvPlayback's activity callback stops the stream; no nav-entry ON_STOP stop here (it also
    // fires on Back, while the guide is meant to keep this channel playing)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.play(current, "app shown (full screen)") }
    // Never sit on "Tuning" with nothing asked for: if the stream is stopped while this page is showing (by anything),
    // ask for the current channel again
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playState is IptvPlayState.Idle) {
        if (playState !is IptvPlayState.Idle) return@LaunchedEffect
        delay(IDLE_RETUNE_MS)
        if (playback.state.value !is IptvPlayState.Idle || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        JellytipTrace.player("iptv", "full screen idle while shown: retune ${current.channelId}")
        playback.play(current, "full screen retune after stop")
    }

    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    // Zap: open the stream once the choice has settled
    LaunchedEffect(index) {
        val ch = lineup[index]
        fallback = null
        tried.clear()
        tried += ch.id
        val target = if (index == startIndex && opened != null) opened else IptvTarget(ch.id, ch.name)
        current = target
        if (playback.state.value.target != target) delay(ZAP_SETTLE_MS)
        playback.play(target, "full screen settle")
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    /** Same event on its next-best channel (each alternate once per zap), with a toast; false = none left. */
    fun failover(why: String): Boolean {
        val base = lineup[index]
        val next = playback.alternates[base.id].orEmpty().firstOrNull { it.id !in tried } ?: return false
        tried += next.id
        JellytipTrace.player("iptv", "full screen ${current.channelId} $why: alternate ${next.id} ${next.name}")
        fallback = next
        val target = IptvTarget(next.id, next.name)
        current = target
        bannerTick++
        playback.play(target, "alternate after $why")
        val name = next.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
        android.widget.Toast.makeText(context, "Switched to $name", android.widget.Toast.LENGTH_SHORT).show()
        return true
    }
    // The stream failed: try the same event on its next-best channel
    LaunchedEffect(playState) {
        val failed = playState as? IptvPlayState.Failed ?: return@LaunchedEffect
        if (failed.target != current || !current.isLive) return@LaunchedEffect
        failover("failed")
    }
    // From kick-off: the catch-up ends where the button was pressed (server: stop = press time); then back to live.
    // A programme opened while it was still on (tile "From kick-off") counts as kick-off too.
    LaunchedEffect(current) {
        val t = current
        if (t.isLive || t.start == null || (t.stop ?: 0L) < startedAt) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val p = playback.player ?: continue
            if (playback.state.value.target == t && p.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                JellytipTrace.player("iptv", "kick-off caught up ${t.channelId}: back to live")
                current = IptvTarget(t.channelId, channel.name)
                playback.play(current, "kick-off ended: back to live")
                bannerTick++
                android.widget.Toast.makeText(context, "Back to live", android.widget.Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
        }
    }
    // A live stream that stalls (buffering while meant to play) for 10 s is treated like a failure (plan "8h Block 3")
    LaunchedEffect(current) {
        if (!current.isLive) return@LaunchedEffect
        var stalledSince = 0L
        while (true) {
            delay(1_000)
            val p = playback.player ?: continue
            val playing = playback.state.value is IptvPlayState.Playing && playback.state.value.target == current
            if (playing && p.playWhenReady && p.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                if (stalledSince == 0L) stalledSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - stalledSince >= STALL_MS) {
                    if (failover("stalled 10 s")) return@LaunchedEffect
                    stalledSince = System.currentTimeMillis() // nothing to switch to: keep waiting
                }
            } else {
                stalledSince = 0L
            }
        }
    }
    LaunchedEffect(bannerTick) {
        bannerVisible = true
        delay(BANNER_MS)
        bannerVisible = false
    }
    val nowNext by produceState<IptvNowNext?>(null, channel.id) {
        value = null
        value = viewModel.service.epgNowFor(listOf(channel.id))[channel.id]
    }
    val now by produceState(nowSec(), channel.id) {
        while (true) {
            value = nowSec()
            delay(15_000)
        }
    }

    // Pre-game (plan "Pre-game state"): the channel is on but the event hasn't started. A card bottom-left says when;
    // it hides after 10 s, any key brings it back, OK on it offers Remind me / other channels; "Started" at startsAt.
    val preProg = nowNext?.now?.takeIf { it.phase == "pre" && (it.startsAt ?: it.start) > now }
    val preStartsAt = preProg?.let { it.startsAt ?: it.start }
    var preCardTick by remember { mutableIntStateOf(0) }
    var preCardVisible by remember { mutableStateOf(false) }
    var preMenu by remember { mutableStateOf(false) }
    var matchModal by remember { mutableStateOf<IptvMatch?>(null) }
    // In-event panel (plan "In-event panel"): Right on a live key match opens it over the right of the video
    var panelOpen by remember { mutableStateOf(false) }
    var panelTab by remember { mutableIntStateOf(0) }
    var shownPanelMatch by remember { mutableStateOf<IptvMatch?>(null) }
    // Long-press OK while the panel is open: its menu (Docked / Floating, Match info)
    var panelMenu by remember { mutableStateOf(false) }
    var lastToast by remember { mutableStateOf<PlayerToast?>(null) }
    val panelMatch = nowNext?.now?.match?.takeIf { current.isLive && !LivePanelRoute.missing && !MatchHidden.hides(it.id) }
    LaunchedEffect(panelMatch?.id) { if (panelMatch == null) panelOpen = false }
    val me by com.github.damontecres.wholphin.jellytip.me.JellytipMe.prefs.collectAsState()
    val spoilerNotice = (nowNext?.now?.kind == "replay" || nowNext?.now?.kind == "highlights") && me?.sportSpoilers != true
    androidx.activity.compose.BackHandler(enabled = panelOpen) { panelOpen = false }
    // Top-right toasts shared by market flips and fantasy scoring (plan "fantasy alerts"): one at a time, 6 s each, at
    // most 3 waiting (the oldest waiting one is dropped)
    var flipToast by remember { mutableStateOf<PlayerToast?>(null) }
    val toastQueue = remember { androidx.compose.runtime.mutableStateListOf<PlayerToast>() }
    fun enqueueToast(t: PlayerToast) {
        if (toastQueue.size >= MAX_QUEUED_TOASTS) toastQueue.removeAt(0)
        toastQueue.add(t)
    }
    LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.snapshotFlow { toastQueue.isNotEmpty() }.first { it }
            val next = toastQueue.removeAt(0)
            flipToast = next
            delay(FLIP_TOAST_MS)
            flipToast = null
            delay(TOAST_GAP_MS) // let it slide out before the next one
        }
    }
    // Fantasy (plan "Fantasy live scoring for NFL"): while an NFL match plays (not a replay), /v1/fantasy/live every
    // 60 s feeds the panel's Fantasy tab, the banner chip and scoring toasts; nothing without a linked account
    val fantasy by JellytipFantasy.live.collectAsState()
    val nflMatch = nowNext?.now?.match?.takeIf { current.isLive && isNfl(it) && !spoilerNotice }
    LaunchedEffect(nflMatch?.id) {
        if (nflMatch == null) return@LaunchedEffect
        while (true) {
            JellytipFantasy.refresh(FANTASY_POLL_MS - 5_000)
            delay(FANTASY_POLL_MS)
        }
    }
    // Chat only once a /live answer carries a `chat` block (no Reddit app: none at all)
    var liveHasChat by remember(panelMatch?.id) { mutableStateOf(false) }
    val liveTabs = if (liveHasChat) LIVE_TABS else LIVE_TABS - "Chat"
    val panelTabs = if (nflMatch != null && fantasy != null) liveTabs + "Fantasy" else liveTabs
    LaunchedEffect(panelTabs.size) { if (panelTab >= panelTabs.size) panelTab = 0 }
    // RedZone (plan "RedZone / whip-around mode"): the Fantasy tab is the default
    val fantasyIndex = panelTabs.indexOf("Fantasy")
    LaunchedEffect(panelMatch?.id, fantasyIndex) { if (panelMatch?.isMulti == true && fantasyIndex >= 0) panelTab = fantasyIndex }
    // Scoring events: toasts like the flip one (fantasy.alerts); events already there when watching starts are skipped
    val seenEvents = remember { HashSet<String>() }
    val eventsPrimed = remember { booleanArrayOf(false) }
    LaunchedEffect(fantasy, nflMatch?.id) {
        val f = fantasy ?: return@LaunchedEffect
        val fresh = f.events.filter { seenEvents.add("${it.at}|${it.league}|${it.name}") }
        if (!eventsPrimed[0]) {
            eventsPrimed[0] = true
            return@LaunchedEffect
        }
        if (nflMatch == null || me?.fantasyAlerts == false) return@LaunchedEffect
        // Both sides (green accent for yours, red for the opponent's), with the play when the server has it:
        // "🏈 +6.4 Gibbs · TD catch, 34 yds (CCP) · you lead 93.8 – 79.2"
        fresh.forEach { e ->
            val league = f.leagues.firstOrNull { it.name == e.league || it.id == e.league }
            val sign = if (e.delta >= 0) "+" else ""
            val opp = e.side == "opp"
            val lead =
                league?.let { l ->
                    val mine = l.mePoints ?: 0.0
                    val theirs = l.oppPoints ?: 0.0
                    (if (mine >= theirs) "You lead " else "You trail ") + l.scoreLine
                }
            val leagueName = league?.name ?: e.league
            val color = if (opp) TOAST_OPP else TOAST_MINE
            enqueueToast(
                PlayerToast(
                    main = "$sign${pts(e.delta)} ${e.name}" + (e.reason?.let { " · $it" } ?: ""),
                    kicker = (if (opp) "Opponent's player" else "Your player") + (leagueName?.let { " · $it" } ?: ""),
                    sub = lead,
                    edge = color,
                    kickerColor = color,
                    icon = BroadcastIcons.Ball,
                ),
            )
        }
    }
    // One /live poll for the panel, flips and moments (plan "Live panel: pop, context, performance"): 15 s full while
    // the panel is open, 30 s light (`?fields=market,moments,score`) while it is closed; parsed off the main thread,
    // unchanged rows keep their instances. A new market.flip.at -> a flip toast (sport.flipAlerts); a new moment -> the
    // strip flashes while the panel is open, a toast while it is closed.
    val liveId = panelMatch?.id?.takeIf { !spoilerNotice }
    val panelIsOpen = panelOpen && panelMatch != null
    var liveData by remember(liveId) { mutableStateOf<MatchLive?>(null) }
    var liveFlash by remember(liveId) { mutableStateOf<LiveFlash?>(null) }
    val liveSeen = remember(liveId) { LiveSeen() }
    LaunchedEffect(liveId, panelIsOpen) {
        val id = liveId ?: return@LaunchedEffect
        val m = panelMatch ?: return@LaunchedEffect
        while (true) {
            val got = fetchLive(id, light = !panelIsOpen)
            if (got?.first == 404) {
                LivePanelRoute.missing = true
                panelOpen = false
                return@LaunchedEffect
            }
            if (MatchHidden.hides(id)) {
                panelOpen = false
                return@LaunchedEffect
            }
            val d = got?.second
            if (d != null) {
                if (panelIsOpen) {
                    liveData = d.reuse(liveData)
                    liveHasChat = d.hasChat
                }
                // Flip toast (the first answer counts too, as before)
                val mk = d.market
                val at = mk?.flipAt
                if (mk != null && at != null && at != liveSeen.flipAt) {
                    liveSeen.flipAt = at
                    if (me?.sportFlipAlerts != false) {
                        val team = if (mk.flipTo == "away") m.away else m.home
                        val name = team?.short ?: team?.name ?: mk.flipTo ?: "New"
                        val pal = matchPalette(m)
                        enqueueToast(
                            PlayerToast(
                                main = "$name now favourites" + (mk.flipProb?.let { " · ${pct(it)}" } ?: ""),
                                kicker = "${mk.source?.replaceFirstChar { it.uppercase() } ?: "Market"} flip",
                                sub = mk.volume?.let { v -> "${if (v >= 1_000_000) String.format(java.util.Locale.US, "$%.1fM", v / 1_000_000) else String.format(java.util.Locale.US, "$%.0fK", v / 1_000)} traded" },
                                edge = if (pal.known) pal.side(mk.flipTo) else Broadcast.Gold,
                            ),
                        )
                    }
                    JellytipTrace.player("iptv", "market flip ${m.id} -> ${mk.flipTo}")
                }
                // Moments: the server's, else a lead change / NFL touchdown or field goal from the score
                liveSeen.newMoments(d, m).forEach { mo ->
                    JellytipTrace.player("iptv", "moment ${m.id} ${mo.kind ?: mo.label}")
                    if (panelOpen) {
                        liveFlash = LiveFlash((liveFlash?.seq ?: 0) + 1, mo)
                    } else if (me?.sportFlipAlerts != false) {
                        val team = if (mo.team == "away") m.away else if (mo.team == "home") m.home else null
                        val score = if (d.scoreHome != null && d.scoreAway != null) " · ${m.home?.short ?: m.home?.name} ${d.scoreHome} – ${d.scoreAway} ${m.away?.short ?: m.away?.name}" else ""
                        val pal = matchPalette(m)
                        enqueueToast(
                            PlayerToast(
                                main = mo.label,
                                kicker = team?.let { it.short ?: it.name } ?: "Live",
                                sub = mo.detail ?: score.removePrefix(" · ").ifBlank { null },
                                edge = if (pal.known && mo.team != null) pal.side(mo.team) else Broadcast.Live,
                                icon = BroadcastIcons.Ball,
                            ),
                        )
                    }
                }
            }
            delay(if (panelIsOpen) LIVE_OPEN_POLL_MS else FLIP_POLL_MS)
        }
    }
    // Perf trace: the player with the panel open is measured on its own (perfByDest "…+panel")
    DisposableEffect(panelIsOpen) {
        com.github.damontecres.wholphin.jellytip.debug.JellytipPerf.subState = if (panelIsOpen) "panel" else null
        onDispose { com.github.damontecres.wholphin.jellytip.debug.JellytipPerf.subState = null }
    }
    LaunchedEffect(preStartsAt, channel.id) {
        if (preStartsAt != null) preCardTick++
    }
    LaunchedEffect(preCardTick) {
        if (preCardTick == 0 || preStartsAt == null) return@LaunchedEffect
        preCardVisible = true
        delay(PRE_CARD_MS)
        preCardVisible = false
    }
    LaunchedEffect(preStartsAt, channel.id) {
        val at = preStartsAt ?: return@LaunchedEffect
        delay(((at - nowSec()) * 1000).coerceAtLeast(0))
        preCardVisible = false
        android.widget.Toast.makeText(context, "Started", android.widget.Toast.LENGTH_SHORT).show()
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // The side panel takes Left (close) and Up/Down (tabs) while it is open; the video keeps playing
                if (panelOpen) {
                    when (e.key) {
                        Key.DirectionLeft -> {
                            panelOpen = false
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            panelTab = (panelTab - 1 + panelTabs.size) % panelTabs.size
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionDown -> {
                            panelTab = (panelTab + 1) % panelTabs.size
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> return@onPreviewKeyEvent true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (e.nativeKeyEvent.repeatCount > 0 && !panelMenu) panelMenu = true
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                // Match info (plan "Match info"): the remote's Info key, or holding OK, on a programme with a fixture
                val matchNow = nowNext?.now?.match?.takeIf { !MatchInfoRoute.missing && !MatchHidden.hides(it.id) }
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (matchNow != null && (e.key == Key.Info || (isOk && e.nativeKeyEvent.repeatCount > 0))) {
                    matchModal = matchNow
                    return@onPreviewKeyEvent true
                }
                if (preStartsAt != null) {
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok && preCardVisible) {
                        preMenu = true
                        return@onPreviewKeyEvent true
                    }
                    preCardTick++
                }
                when (e.key) {
                    Key.DirectionUp, Key.DirectionDown, Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown -> {
                        if (lineup.size > 1) {
                            val step = if (e.key == Key.DirectionUp || e.key == Key.ChannelDown || e.key == Key.PageUp) -1 else 1
                            index = Math.floorMod(index + step, lineup.size)
                            JellytipTrace.log("iptv", "zap ${if (step < 0) "up" else "down"} to ${lineup[index].name}")
                        }
                        bannerTick++
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (bannerVisible) bannerVisible = false else bannerTick++
                        true
                    }

                    // From kick-off: restart what is on now (catch-up from its start); Right goes back to live
                    Key.DirectionLeft, Key.MediaRewind -> {
                        val prog = nowNext?.now?.takeIf { it.canStartOver && it.isOn(nowSec()) }
                        if (current.isLive && prog != null) {
                            JellytipTrace.player("iptv", "from kick-off ${channel.id} ${prog.title}")
                            current = IptvTarget(channel.id, prog.title, prog.start, prog.stop)
                            playback.play(current, "from kick-off")
                            bannerTick++
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionRight, Key.MediaFastForward -> {
                        if (!current.isLive && current.channelId == channel.id) {
                            current = IptvTarget(channel.id, channel.name)
                            playback.play(current, "back to live")
                            bannerTick++
                            true
                        } else if (e.key == Key.DirectionRight && panelMatch != null && matchModal == null && !preMenu) {
                            // Live: Right opens the in-event panel (Right was unused here)
                            panelOpen = true
                            JellytipTrace.player("iptv", "live panel ${panelMatch.id}")
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }.focusable(),
    ) {
        val player = playback.player
        if (player != null) {
            PlayerSurface(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = Modifier.fillMaxSize())
        }
        val status =
            when (val s = playState) {
                is IptvPlayState.Opening -> if (s.target.channelId == channel.id) "Tuning ${channel.name}…" else null
                is IptvPlayState.Full -> s.message
                is IptvPlayState.Failed -> s.message
                IptvPlayState.Idle -> "Tuning ${channel.name}…"
                is IptvPlayState.Playing -> null
            }
        status?.let {
            Text(
                text = it,
                color = JellytipPalette.Vanilla,
                fontFamily = JellytipFontFamily,
                fontSize = 18.sp,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        StreamBusyPopup(playState, playback)
        // Slides in from the right (260 ms, decelerate) and out (200 ms, accelerate); kept composed until it is off screen
        val open = panelOpen && panelMatch != null
        LaunchedEffect(open, panelMatch?.id) { if (open) shownPanelMatch = panelMatch }
        // Read in graphicsLayer only (the page must not recompose every animation frame)
        val panelProgress = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (open) 1f else 0f,
            animationSpec =
                if (open) {
                    androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
                } else {
                    androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutLinearInEasing)
                },
            label = "live-panel",
        )
        val panelShown by remember { derivedStateOf { panelProgress.value > 0f } }
        val pm = if (open) panelMatch else shownPanelMatch
        if (pm != null && panelShown) {
            JellytipLivePanel(
                pm,
                tab = panelTab,
                spoilerNotice = spoilerNotice,
                data = liveData,
                tabs = panelTabs,
                fantasy = fantasy,
                flash = liveFlash,
                floating = me?.sportPanelStyle == "floating",
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.32f),
                progress = { panelProgress.value },
            )
        }
        if (panelMenu) {
            val floating = me?.sportPanelStyle == "floating"
            val pm = panelMatch
            com.github.damontecres.wholphin.ui.components.DialogPopup(
                showDialog = true,
                title = "Live panel",
                dialogItems =
                    listOfNotNull(
                        com.github.damontecres.wholphin.ui.components.DialogItem(if (floating) "Docked to the edge" else "Docked to the edge  ✓", dismissOnClick = true) {
                            com.github.damontecres.wholphin.jellytip.me.JellytipMe.setPanelStyle("docked")
                        },
                        com.github.damontecres.wholphin.ui.components.DialogItem(if (floating) "Floating  ✓" else "Floating", dismissOnClick = true) {
                            com.github.damontecres.wholphin.jellytip.me.JellytipMe.setPanelStyle("floating")
                        },
                        matchInfoItem(pm) { matchModal = it },
                    ),
                onDismissRequest = { panelMenu = false },
            )
        }
        // Toast: a Broadcast lower third, top right; slides in from the right (240 ms), never takes focus; the queue
        // above times it
        val toast = flipToast
        val toastIn = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (toast != null) 1f else 0f,
            animationSpec =
                androidx.compose.animation.core.tween(
                    if (toast != null) 240 else 200,
                    easing = if (toast != null) androidx.compose.animation.core.LinearOutSlowInEasing else androidx.compose.animation.core.FastOutLinearInEasing,
                ),
            label = "player-toast",
        )
        LaunchedEffect(toast) { if (toast != null) lastToast = toast }
        val shownToast = toast ?: lastToast
        val toastShown by remember { derivedStateOf { toastIn.value > 0f } }
        if (toastShown && shownToast != null) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = 24.dp)
                        .graphicsLayer {
                            translationX = (1f - toastIn.value) * (size.width + 24.dp.toPx())
                            alpha = toastIn.value
                        }.width(330.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        .background(Broadcast.Base.copy(alpha = 0.96f)),
            ) {
                Box(Modifier.width(8.dp).fillMaxHeight().background(shownToast.edge))
                Box(Modifier.width(36.dp).fillMaxHeight().background(Broadcast.Chip), contentAlignment = Alignment.Center) {
                    androidx.tv.material3.Icon(shownToast.icon, contentDescription = null, tint = shownToast.kickerColor, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    shownToast.kicker?.let { Cond(it, 12.sp, shownToast.kickerColor, tracking = 0.08f) }
                    Text(
                        shownToast.main.uppercase(java.util.Locale.ROOT),
                        color = Color.White,
                        fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    shownToast.sub?.let { BodyText(it, 12.sp, maxLines = 1) }
                }
            }
        }
        matchModal?.let { m ->
            JellytipMatchModal(
                m,
                onWatch = { ch ->
                    matchModal = null
                    if (ch.id != channel.id) {
                        tried += ch.id
                        fallback = ch
                        current = IptvTarget(ch.id, ch.name)
                        playback.play(current, "match info watch")
                        bannerTick++
                    }
                },
                onDismiss = { matchModal = null },
            )
        }
        if (preCardVisible && preStartsAt != null) {
            // Broadcast pre-game card: gold tag, the start in condensed caps, the hint in Barlow
            val pm = nowNext?.now?.match
            val pal = remember(pm?.id) { matchPalette(pm) }
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 48.dp, bottom = if (bannerVisible) 150.dp else 40.dp)
                        .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        .background(pal.wash, alpha = 0.96f),
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(Broadcast.Gold))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cond("Pre-game", 13.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 7.dp, vertical = 1.dp), tracking = 0.06f)
                        pm?.matchup?.let { Cond(it, 17.sp, pal.onHomeWash) }
                    }
                    Cond("Starts ${hhmm(preStartsAt)} · ${startsLabel(preStartsAt, now).substringAfter("· ")}", 20.sp, Color.White)
                    BodyText("OK: Remind me · Other channels", 12.sp, Broadcast.Secondary)
                }
            }
        }
        if (preMenu && preProg != null && preStartsAt != null) {
            val remindersOn by viewModel.reminders.available.collectAsState()
            val others = playback.alternates[lineup[index].id].orEmpty().filter { it.id != channel.id }
            val items =
                buildList {
                    if (remindersOn == true) {
                        add(
                            com.github.damontecres.wholphin.ui.components.DialogItem("Remind me", dismissOnClick = true) {
                                viewModel.reminders.add(
                                    IptvUpcoming(
                                        key = "${channel.id}@$preStartsAt",
                                        title = preProg.title,
                                        league = null,
                                        favourite = false,
                                        start = preStartsAt,
                                        stop = preProg.stop,
                                        kind = preProg.kind,
                                        channel = channel,
                                        alternateChannels = others,
                                        recordable = false,
                                        recordingId = null,
                                    ),
                                )
                                android.widget.Toast.makeText(context, "Reminder set", android.widget.Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    others.forEach { ch ->
                        val name = ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
                        add(
                            com.github.damontecres.wholphin.ui.components.DialogItem("Watch on $name", dismissOnClick = true) {
                                tried += ch.id
                                fallback = ch
                                current = IptvTarget(ch.id, ch.name)
                                playback.play(current, "pre-game other channel")
                                bannerTick++
                            },
                        )
                    }
                }
            if (items.isEmpty()) {
                preMenu = false
            } else {
                com.github.damontecres.wholphin.ui.components.DialogPopup(showDialog = true, title = preProg.title, dialogItems = items, onDismissRequest = { preMenu = false })
            }
        }
        if (bannerVisible) {
            val cu = current.takeIf { it.start != null && it.stop != null && it.channelId == channel.id }
            val shown = if (cu != null) IptvNowNext(IptvProg(cu.start!!, cu.stop!!, cu.label, null, true), null) else nowNext
            NowNextBanner(channel, shown, now, Modifier.align(Alignment.BottomStart), catchUp = cu != null)
            val hint =
                when {
                    cu != null -> "▶  Right: back to live"
                    else -> {
                        val kick = nowNext?.now?.let { it.canStartOver && it.isOn(now) } == true
                        val info = nowNext?.now?.match?.let { !MatchHidden.hides(it.id) } == true && !MatchInfoRoute.missing
                        listOfNotNull(
                            if (kick) "◀  From kick-off" else null,
                            if (panelMatch != null) "→  Live stats" else null,
                            if (info) "Hold OK: match info" else null,
                        ).joinToString("   ·   ").ifEmpty { null }
                    }
                }
            // Fantasy chip "🏈 CCP 87.4 – 79.2" (the league with starters in this game, else the first)
            JellytipFantasy.leagueFor(fantasy, nflMatch?.id)?.takeIf { nflMatch != null }?.let { l ->
                Text(
                    "🏈  ${l.short} ${l.scoreLine}",
                    color = Color.White,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 32.dp, start = 48.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            hint?.let {
                Text(
                    it,
                    color = JellytipPalette.Vanilla.copy(alpha = 0.85f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 14.sp,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 32.dp, end = 48.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NowNextBanner(
    ch: IptvChannel,
    nn: IptvNowNext?,
    now: Long,
    modifier: Modifier,
    catchUp: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp, 64.dp), contentAlignment = Alignment.Center) {
                var failed by remember(ch.logo) { mutableStateOf(false) }
                if (ch.logo != null && !failed) {
                    AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.fillMaxSize())
                } else {
                    Text(initials(ch.name), color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                val p = nn?.now
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!catchUp) KindPill(p?.kind)
                    if (ch.sport == true || p?.sport == true) SportMark(JellytipPalette.Vanilla.copy(alpha = 0.75f), 14.dp)
                    Text(
                        text = listOfNotNull(ch.num?.toString(), ch.name, if (catchUp) "Catch-up" else null).joinToString("  "),
                        color = JellytipPalette.Vanilla.copy(alpha = 0.75f),
                        fontFamily = JellytipFontFamily,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                    QualityChips(ch.quality)
                }
                Text(
                    text = p?.title ?: "No guide information",
                    color = JellytipPalette.Vanilla,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${hhmm(p.start)}–${hhmm(p.stop)}", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
                        val frac = if (catchUp) 0f else ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (!catchUp) Box(Modifier.width(220.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(JellytipPalette.Jelly))
                        }
                    }
                }
                nn?.next?.let { n ->
                    Text(
                        text = "Next  ${hhmm(n.start)}  ${n.title}",
                        color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                        fontFamily = JellytipFontFamily,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** What the live poll has already shown: the last flip, moments (server or inferred from the score); the first answer primes. */
private class LiveSeen {
    var flipAt: String? = null
    private var primed = false
    private val moments = HashSet<String>()
    private var lastHome: Int? = null
    private var lastAway: Int? = null

    fun newMoments(
        d: MatchLive,
        m: IptvMatch,
    ): List<LiveMoment> {
        val first = !primed
        primed = true
        val out = ArrayList<LiveMoment>()
        if (d.hasMoments) {
            d.moments.forEach { mo -> if (moments.add("${mo.at}|${mo.label}") && !first) out += mo }
        }
        val h = d.scoreHome?.toIntOrNull()
        val a = d.scoreAway?.toIntOrNull()
        if (h != null && a != null) {
            val ph = lastHome
            val pa = lastAway
            // No `moments` from this server: a lead change, or an NFL touchdown / field goal, from the score
            if (!d.hasMoments && !first && ph != null && pa != null && (h != ph || a != pa)) {
                val before = Integer.signum(ph - pa)
                val now = Integer.signum(h - a)
                val team = if (h > ph) "home" else if (a > pa) "away" else null
                val delta = maxOf(h - ph, a - pa)
                when {
                    before != 0 && now != 0 && before != now -> out += LiveMoment("score:$h-$a", "lead_change", team, "LEAD CHANGE")
                    isNfl(m) && delta in 6..8 -> out += LiveMoment("score:$h-$a", "touchdown", team, "TOUCHDOWN")
                    isNfl(m) && delta == 3 -> out += LiveMoment("score:$h-$a", "field_goal", team, "FIELD GOAL")
                }
            }
            lastHome = h
            lastAway = a
        }
        return out
    }
}
