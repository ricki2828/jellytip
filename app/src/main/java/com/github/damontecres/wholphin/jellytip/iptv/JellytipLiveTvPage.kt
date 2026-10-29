@file:OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LiveTvTab(
    val id: String,
    val name: String,
)

data class LiveTvState(
    /** null = still loading the tabs. */
    val tabs: List<LiveTvTab>? = null,
    val tab: String? = null,
    /** Channels shown for [tab] (dark event channels left out unless [showDark]); null = loading. */
    val channels: List<IptvChannel>? = null,
    val darkCount: Int = 0,
    val showDark: Boolean = false,
    /** Guide window start (epoch s, on a half hour); the window is [IptvRules.WINDOW_SECS] wide. */
    val windowStart: Long = IptvRules.windowStart(nowSec()),
    val message: String? = null,
    /** Recordings tab: null = loading. */
    val recordings: List<IptvRecording>? = null,
    val space: IptvSpace? = null,
)

internal fun nowSec() = System.currentTimeMillis() / 1000

@HiltViewModel
class JellytipLiveTvViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(LiveTvState())
        val state: StateFlow<LiveTvState> = _state

        /** Programmes per channel id, filled for the rows on screen only (plus now/next for the whole tab). */
        val epg = mutableStateMapOf<String, List<IptvProg>>()
        private val loaded = HashMap<String, LongRange>()
        private val inFlight = HashSet<String>()
        private var all: List<IptvChannel> = emptyList()
        private var nowNext: Map<String, IptvNowNext> = emptyMap()
        private var recent: List<IptvChannel> = emptyList()
        private var tabJob: Job? = null

        /** Last focused cell key, restored when the page comes back. */
        var lastFocusKey: String? = null

        init {
            viewModelScope.launch {
                val groups = service.groups()
                if (groups == null) {
                    _state.update { it.copy(tabs = emptyList(), channels = emptyList(), message = "Live TV isn't available right now.") }
                    return@launch
                }
                recent = service.recent()
                // The server's "Guide" (all opted channels) first, then Favourites / Recent, the groups in the
                // server's order, and Recordings last
                val default = groups.firstOrNull { it.isDefault }
                val tabs =
                    buildList {
                        default?.let { add(LiveTvTab(it.id, it.name.ifBlank { "Guide" })) }
                        groups.firstOrNull { it.id == JellytipIptvService.FAV }?.let { add(LiveTvTab(it.id, "Favourites")) }
                        if (recent.isNotEmpty()) add(LiveTvTab(JellytipIptvService.RECENT, "Recent"))
                        groups
                            .filter { it.id != JellytipIptvService.FAV && !it.isDefault && it.count > 0 }
                            .forEach { add(LiveTvTab(it.id, cleanGroupName(it.name))) }
                        add(LiveTvTab(RECORDINGS, "Recordings"))
                    }
                _state.update { it.copy(tabs = tabs) }
                loadRecordings()
                (default?.id ?: tabs.firstOrNull { it.id != RECORDINGS }?.id)?.let { selectTab(it, "first tab") }
                    ?: _state.update { it.copy(channels = emptyList(), message = "No channels are opted in yet (Settings › Live TV on the web).") }
            }
        }

        fun selectTab(
            id: String,
            reason: String,
        ) {
            if (id == _state.value.tab && _state.value.channels != null) return
            JellytipTrace.log("iptv", "guide tab $id ($reason)")
            tabJob?.cancel()
            _state.update { it.copy(tab = id, channels = null, darkCount = 0, showDark = false, message = null) }
            if (id == RECORDINGS) {
                _state.update { it.copy(channels = emptyList()) }
                loadRecordings()
                return
            }
            tabJob =
                viewModelScope.launch {
                    val list = if (id == JellytipIptvService.RECENT) recent else service.channels(id)
                    if (list == null) {
                        _state.update { it.copy(channels = emptyList(), message = "Couldn't load these channels.") }
                        return@launch
                    }
                    nowNext = if (id == JellytipIptvService.RECENT) service.epgNowFor(list.map { it.id }) else service.epgNow(id)
                    // Seed the guide with now/next so rows show something before the full window arrives
                    nowNext.forEach { (cid, nn) -> if (cid !in epg) epg[cid] = listOfNotNull(nn.now, nn.next) }
                    all = list
                    publish()
                }
        }

        private fun publish() {
            val s = _state.value
            val dark = all.filter { IptvRules.isDark(it.name, it.group, nowNext[it.id], it.event) }.map { it.id }.toSet()
            val shown = if (s.showDark) all else all.filter { it.id !in dark }
            _state.update {
                it.copy(
                    channels = shown,
                    darkCount = dark.size,
                    message = if (all.isEmpty()) "No channels in this group." else null,
                )
            }
        }

        fun toggleDark() {
            _state.update { it.copy(showDark = !it.showDark) }
            publish()
        }

        /** Window bounds: back a little (catch-up comes with recordings), ahead 3 days (what the server keeps). */
        fun minWindowStart(): Long {
            val days = all.maxOfOrNull { it.catchupDays }?.coerceAtMost(MAX_CATCHUP_DAYS) ?: 0
            return IptvRules.windowStart(nowSec()) - maxOf(BACK_SECS, days * 86_400L)
        }

        /** A past programme can be watched again when the channel keeps catch-up that far back. */
        fun canCatchUp(
            ch: IptvChannel,
            p: IptvProg,
        ): Boolean {
            val now = nowSec()
            if (p.title.isEmpty() || p.start >= now) return false
            return p.catchup || (ch.catchupDays > 0 && now - p.start <= ch.catchupDays * 86_400L)
        }

        fun loadRecordings() {
            viewModelScope.launch {
                val got = service.recordings()
                if (got == null) {
                    _state.update { it.copy(recordings = it.recordings ?: emptyList(), message = if (it.tab == RECORDINGS) "Couldn't load recordings." else it.message) }
                    return@launch
                }
                val (space, list) = got
                _state.update { it.copy(recordings = list, space = space) }
            }
        }

        /** True when a recording covers [p] on [ch] (the server pads start/stop around the programme). */
        fun isRecorded(
            ch: IptvChannel,
            p: IptvProg,
        ): Boolean =
            _state.value.recordings.orEmpty().any {
                it.channelId == ch.id && it.state != "cancelled" && it.state != "failed" && it.start <= p.start && it.stop >= p.stop
            }

        fun record(
            ch: IptvChannel,
            p: IptvProg,
        ) {
            viewModelScope.launch {
                val r = service.record(ch.id, p.start)
                _state.update { it.copy(message = if (r.ok) (r.message ?: "Recording “${p.title}”.") else r.message) }
                if (r.ok) loadRecordings()
            }
        }

        fun cancelRecording(r: IptvRecording) {
            viewModelScope.launch {
                val ok = service.cancelRecording(r.id)
                JellytipTrace.log("iptv", "cancel recording ${r.id} ok=$ok")
                _state.update { it.copy(message = if (ok) "Cancelled “${r.title}”." else "Couldn't cancel it.") }
                loadRecordings()
            }
        }

        fun deleteRecording(r: IptvRecording) {
            viewModelScope.launch {
                val ok = service.deleteRecording(r.id)
                JellytipTrace.log("iptv", "delete recording ${r.id} ok=$ok")
                _state.update { it.copy(message = if (ok) "Deleted “${r.title}”." else "Couldn't delete it.") }
                loadRecordings()
            }
        }

        fun maxWindowStart(): Long = IptvRules.windowStart(nowSec()) + AHEAD_SECS

        fun setWindowStart(ws: Long) {
            val clamped = ws.coerceIn(minWindowStart(), maxWindowStart())
            if (clamped != _state.value.windowStart) {
                JellytipTrace.log("iptv", "guide window ${clamped - nowSec()}s from now")
                _state.update { it.copy(windowStart = clamped) }
            }
        }

        /** Programmes for the rows on screen (and a few around) covering the window; only what is missing is asked. */
        fun ensureEpg(ids: List<String>) {
            val ws = _state.value.windowStart
            val need = ws..(ws + IptvRules.WINDOW_SECS)
            val missing =
                ids.filter { id ->
                    id !in inFlight && loaded[id]?.let { it.first <= need.first && it.last >= need.last } != true
                }
            if (missing.isEmpty()) return
            val from = ws - FETCH_BEFORE_SECS
            val to = ws + IptvRules.WINDOW_SECS + FETCH_AFTER_SECS
            inFlight += missing
            viewModelScope.launch {
                val got = service.epg(missing, from, to)
                inFlight -= missing.toSet()
                got ?: return@launch
                got.forEach { (id, progs) ->
                    epg[id] = IptvRules.tidy((epg[id].orEmpty() + progs).distinctBy { it.start })
                    val prev = loaded[id]
                    loaded[id] = if (prev == null) from..to else minOf(prev.first, from)..maxOf(prev.last, to)
                }
            }
        }

        fun toggleFavourite(ch: IptvChannel) {
            viewModelScope.launch {
                val ok = service.setFavourite(ch.id, !ch.favourite)
                if (!ok) {
                    _state.update { it.copy(message = "Couldn't change favourites.") }
                    return@launch
                }
                all = all.map { if (it.id == ch.id) it.copy(favourite = !it.favourite) else it }
                publish()
                _state.update { it.copy(message = if (!ch.favourite) "Added ${ch.name} to Favourites." else "Removed ${ch.name} from Favourites.") }
            }
        }

        companion object {
            const val RECORDINGS = "recordings"
            private const val BACK_SECS = 2 * 60 * 60L
            private const val MAX_CATCHUP_DAYS = 7
            private const val AHEAD_SECS = 3 * 24 * 60 * 60L - IptvRules.WINDOW_SECS
            private const val FETCH_BEFORE_SECS = 60 * 60L
            private const val FETCH_AFTER_SECS = 3 * 60 * 60L

            /** "|NZ| SPORTS ✪" → "NZ Sports"-ish: provider tags and stars out, the name kept readable. */
            fun cleanGroupName(raw: String): String {
                val tag = Regex("""^\s*\|([A-Z]{2,3})\|\s*""").find(raw)?.groupValues?.get(1)
                val body = raw.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "").replace("✪", "").trim()
                return listOfNotNull(tag, body.ifBlank { null }).joinToString(" ")
            }
        }
    }

private val CHANNEL_W = 176.dp
private val ROW_H = 46.dp
private val RULER_H = 22.dp
private val PREVIEW_W = 224.dp
private val PREVIEW_H = 126.dp
private const val PREVIEW_REST_MS = 800L
private const val ON_NOW_REFRESH_MS = 2 * 60_000L

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

internal fun hhmm(epoch: Long): String = clock.format(Date(epoch * 1000))

private fun cellKey(
    channelId: String,
    start: Long,
) = "$channelId|$start"

/** Programmes of a row inside [ws, ws + window); a row with none gets one placeholder spanning the window. */
private fun cellsFor(
    progs: List<IptvProg>,
    ws: Long,
): List<IptvProg> {
    val we = ws + IptvRules.WINDOW_SECS
    val inside = progs.filter { it.stop > ws && it.start < we }
    return inside.ifEmpty { listOf(IptvProg(ws, we, "", null, false)) }
}

@Composable
fun JellytipLiveTvPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipLiveTvViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playback = viewModel.playback
    val playState by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    var pending by remember { mutableStateOf<String?>(null) }
    var focusedChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var focusedProg by remember { mutableStateOf<IptvProg?>(null) }
    var focusTime by remember { mutableStateOf(nowSec()) }
    var placed by remember { mutableStateOf(false) }
    var tabsFocused by remember { mutableStateOf(false) }
    var onNowFocused by remember { mutableStateOf(false) }
    val headerFocused = tabsFocused || onNowFocused
    // null = first answer pending: its height is kept so the guide below never jumps when it arrives
    val onNow by produceState(viewModel.service.peekOnNow()) {
        while (true) {
            value = viewModel.service.onNow()
            delay(ON_NOW_REFRESH_MS)
        }
    }
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }

    DisposableEffect(Unit) {
        playback.attach("guide")
        // The guide preview is muted (like home trailers); full screen has the sound
        playback.setVolume(0f)
        onDispose { playback.detach("guide") }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.stop("app hidden (guide)") }

    // Only the rows on screen (and a few around) ask for programmes; re-asked when the window moves
    LaunchedEffect(state.channels) {
        val chans = state.channels ?: return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            Triple(info.firstOrNull()?.index ?: 0, info.lastOrNull()?.index ?: 0, state.windowStart)
        }.distinctUntilChanged()
            .collectLatest { (first, last, _) ->
                delay(120) // settle while scrolling fast
                val from = (first - 4).coerceAtLeast(0)
                val to = (last + 6).coerceAtMost(chans.size - 1)
                if (to >= from) viewModel.ensureEpg(chans.subList(from, to + 1).map { it.id })
            }
    }

    // Focus is placed once per tab load: the channel full screen was on, else the last focused cell, else the first
    // channel's programme on now. Never while the tabs have focus, never again on reloads.
    LaunchedEffect(state.channels) {
        val chans = state.channels ?: return@LaunchedEffect
        if (chans.isEmpty() || placed || headerFocused) return@LaunchedEffect
        placed = true
        val back = playback.lastFullScreenChannel
        playback.lastFullScreenChannel = null
        val ci = chans.indexOfFirst { it.id == back }.takeIf { it >= 0 } ?: 0
        val ch = chans[ci]
        val restore = viewModel.lastFocusKey?.takeIf { back == null && it.substringBefore('|') in chans.map { c -> c.id } }
        if (ci > 0) listState.scrollToItem((ci - 2).coerceAtLeast(0))
        withFrameNanos { }
        val target =
            restore ?: cellKey(ch.id, IptvRules.progAt(cellsFor(viewModel.epg[ch.id].orEmpty(), state.windowStart), now)?.start ?: state.windowStart)
        JellytipTrace.focus("guide: place ${if (back != null) "full-screen channel $back" else if (restore != null) "restore" else "first channel"}")
        pending = target
    }

    // Preview after the focus rests on a channel (never while scrolling: each stream is the one connection)
    LaunchedEffect(focusedChannel?.id) {
        val ch = focusedChannel ?: return@LaunchedEffect
        delay(PREVIEW_REST_MS)
        playback.setVolume(0f)
        playback.play(IptvTarget(ch.id, ch.name), "guide preview (rested ${PREVIEW_REST_MS} ms)")
    }

    var actions by remember { mutableStateOf<Pair<IptvChannel, IptvProg>?>(null) }

    fun openFullScreen(
        ch: IptvChannel,
        target: IptvTarget = IptvTarget(ch.id, ch.name),
    ) {
        playback.lineup = state.channels.orEmpty()
        playback.play(target, if (target.start != null) "guide OK (catch-up)" else "guide OK")
        viewModel.navigationManager.navigateTo(Destination.JellytipLiveTvPlayer(ch.id), "guide OK on ${ch.name}")
    }

    /** OK on a programme: on now = watch live; over = catch-up where the channel keeps it; later = what to do with it. */
    fun onCellOk(
        ch: IptvChannel,
        p: IptvProg,
    ) {
        when {
            p.title.isEmpty() || p.isOn(now) -> openFullScreen(ch)
            p.stop <= now ->
                if (viewModel.canCatchUp(ch, p)) {
                    openFullScreen(ch, IptvTarget(ch.id, p.title, p.start, p.stop))
                } else {
                    actions = ch to p
                }
            else -> actions = ch to p
        }
    }

    /** D-pad inside the grid: moves by programme (left/right) and by channel at the same time (up/down). */
    fun onGridKey(
        ci: Int,
        ch: IptvChannel,
        p: IptvProg,
        key: Key,
    ): Boolean {
        val chans = state.channels ?: return false
        val ws = state.windowStart
        val we = ws + IptvRules.WINDOW_SECS
        val progs = viewModel.epg[ch.id].orEmpty()
        when (key) {
            Key.DirectionRight -> {
                val next = progs.firstOrNull { it.start >= p.stop && it.title.isNotEmpty() }
                if (next != null) {
                    var nws = ws
                    while (next.start > nws + IptvRules.WINDOW_SECS - IptvRules.HALF_HOUR && nws < viewModel.maxWindowStart()) nws += IptvRules.HALF_HOUR
                    viewModel.setWindowStart(nws)
                    focusTime = maxOf(next.start, viewModel.state.value.windowStart)
                    pending = cellKey(ch.id, next.start)
                } else if (we < viewModel.maxWindowStart() + IptvRules.WINDOW_SECS) {
                    val nws = ws + IptvRules.HALF_HOUR
                    viewModel.setWindowStart(nws)
                    focusTime = maxOf(focusTime, nws)
                    val c = IptvRules.progAt(cellsFor(progs, nws), focusTime)
                    pending = cellKey(ch.id, c?.start ?: nws)
                }
                return true
            }

            Key.DirectionLeft -> {
                val prev = progs.lastOrNull { it.stop <= p.start && it.title.isNotEmpty() }
                val min = viewModel.minWindowStart()
                if (prev != null && prev.stop > min) {
                    var nws = ws
                    while (prev.start < nws && nws > min) nws -= IptvRules.HALF_HOUR
                    viewModel.setWindowStart(nws)
                    focusTime = maxOf(prev.start, viewModel.state.value.windowStart)
                    pending = cellKey(ch.id, prev.start)
                    return true
                }
                if (ws > min) {
                    val nws = ws - IptvRules.HALF_HOUR
                    viewModel.setWindowStart(nws)
                    focusTime = nws
                    val c = IptvRules.progAt(cellsFor(progs, nws), focusTime)
                    pending = cellKey(ch.id, c?.start ?: nws)
                    return true
                }
                return false // the edge of the guide: Left goes on to the sidebar, as everywhere
            }

            Key.DirectionUp, Key.DirectionDown -> {
                val ti = ci + if (key == Key.DirectionUp) -1 else 1
                if (ti < 0) return false // up to the tabs
                if (ti >= chans.size) return state.darkCount == 0 // down to the "off-air event channels" chip
                val target = chans[ti]
                val c = IptvRules.progAt(cellsFor(viewModel.epg[target.id].orEmpty(), ws), focusTime)
                val visible = listState.layoutInfo.visibleItemsInfo
                val firstFull = visible.firstOrNull { it.offset >= 0 }?.index ?: 0
                val lastFull = visible.lastOrNull { it.offset + it.size <= listState.layoutInfo.viewportEndOffset }?.index ?: 0
                if (ti < firstFull || ti > lastFull) {
                    scope.launch { listState.scrollToItem(if (ti < firstFull) ti else (ti - (lastFull - firstFull)).coerceAtLeast(0)) }
                }
                pending = cellKey(target.id, c?.start ?: ws)
                return true
            }

            else -> return false
        }
    }

    Column(modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 24.dp, top = 16.dp, end = 16.dp)) {
        // Top: preview + what's focused
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.height(PREVIEW_H)) {
            LivePreview(playback, playState, focusedChannel, Modifier.size(PREVIEW_W, PREVIEW_H))
            FocusInfo(focusedChannel, focusedProg, now, state.message, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        // On now: live sport first, then the server's order; OK = full screen with sound
        val onNowList = onNow
        if (onNowList == null) {
            Spacer(Modifier.height(64.dp))
        } else if (onNowList.isNotEmpty()) {
            OnNowStrip(
                items = onNowList,
                now = now,
                onOpen = { o ->
                    playback.lineup = onNowList.map { it.channel }
                    playback.play(IptvTarget(o.channel.id, o.channel.name), "guide on-now OK")
                    viewModel.navigationManager.navigateTo(Destination.JellytipLiveTvPlayer(o.channel.id), "guide on-now OK ${o.channel.name}")
                },
                modifier = Modifier.onFocusChanged { onNowFocused = it.hasFocus },
            )
        }
        // Tabs: Favourites / Recent first, then the opted groups in the server's order
        val tabs = state.tabs
        if (tabs != null && tabs.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier.fillMaxWidth().focusGroup().onFocusChanged { tabsFocused = it.hasFocus },
            ) {
                items(tabs, key = { it.id }) { t ->
                    TabChip(t.name, t.id == state.tab) {
                        placed = false
                        viewModel.selectTab(t.id, "tab OK")
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (state.tab == JellytipLiveTvViewModel.RECORDINGS) {
            RecordingsList(
                state = state,
                onPlay = { r ->
                    val resume = ((r.positionSecs ?: 0.0) * 1000).toLong().takeIf { r.durationSecs == null || (r.positionSecs ?: 0.0) < r.durationSecs * 0.9 } ?: 0L
                    viewModel.navigationManager.navigateTo(
                        Destination.JellytipIptvPlay(title = r.title, recordingId = r.id, resumeMs = resume),
                        "recording OK ${r.id}",
                    )
                },
                onCancel = viewModel::cancelRecording,
                onDelete = viewModel::deleteRecording,
            )
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineW = maxWidth - CHANNEL_W
            val dpPerSec = timelineW / IptvRules.WINDOW_SECS.toFloat()
            val ws = state.windowStart
            Column(Modifier.fillMaxSize()) {
                TimeRuler(ws, dpPerSec, Modifier.padding(start = CHANNEL_W).height(RULER_H).fillMaxWidth())
                val chans = state.channels
                when {
                    chans == null -> Text("Loading the guide…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, modifier = Modifier.padding(12.dp))
                    chans.isEmpty() -> Text(state.message ?: "No channels here.", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, modifier = Modifier.padding(12.dp))
                    else ->
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(chans, key = { _, c -> c.id }) { ci, ch ->
                                    val cells = cellsFor(viewModel.epg[ch.id].orEmpty(), ws)
                                    Row(Modifier.height(ROW_H).fillMaxWidth()) {
                                        ChannelCell(ch, focusedChannel?.id == ch.id, Modifier.width(CHANNEL_W).fillMaxHeight())
                                        Box(Modifier.width(timelineW).fillMaxHeight().clip(RoundedCornerShape(0.dp))) {
                                            cells.forEach { p ->
                                                androidx.compose.runtime.key(ch.id, p.start) {
                                                val key = cellKey(ch.id, p.start)
                                                val req = requesters.getOrPut(key) { FocusRequester() }
                                                val x = ((maxOf(p.start, ws) - ws) * dpPerSec.value).dp
                                                val w = ((minOf(p.stop, ws + IptvRules.WINDOW_SECS) - maxOf(p.start, ws)) * dpPerSec.value).dp
                                                LaunchedEffect(pending == key) {
                                                    if (pending == key) {
                                                        withFrameNanos { }
                                                        if (req.tryRequestFocus()) pending = null
                                                    }
                                                }
                                                ProgCell(
                                                    prog = p,
                                                    now = now,
                                                    recorded = p.title.isNotEmpty() && viewModel.isRecorded(ch, p),
                                                    note = ch.guideNote,
                                                    catchUp = viewModel.canCatchUp(ch, p),
                                                    onClick = { onCellOk(ch, p) },
                                                    onLongClick = { actions = ch to p },
                                                    modifier =
                                                        Modifier
                                                            .offset(x = x)
                                                            .width((w - 2.dp).coerceAtLeast(2.dp))
                                                            .fillMaxHeight()
                                                            .padding(vertical = 2.dp)
                                                            .focusRequester(req)
                                                            .onFocusChanged {
                                                                if (it.isFocused) {
                                                                    focusedChannel = ch
                                                                    focusedProg = p.takeIf { pr -> pr.title.isNotEmpty() }
                                                                    viewModel.lastFocusKey = key
                                                                }
                                                            }.onPreviewKeyEvent { e ->
                                                                if (e.type != KeyEventType.KeyDown) {
                                                                    false
                                                                } else {
                                                                    onGridKey(ci, ch, p, e.key)
                                                                }
                                                            },
                                                )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (state.darkCount > 0) {
                                    item(key = "dark") {
                                        TabChip(
                                            if (state.showDark) "Hide ${state.darkCount} off-air event channels" else "${state.darkCount} event channels are off air · Show",
                                            false,
                                            Modifier.padding(top = 8.dp, start = 4.dp),
                                        ) { viewModel.toggleDark() }
                                    }
                                }
                            }
                            // Now line across the rows
                            if (now in ws until ws + IptvRules.WINDOW_SECS) {
                                Box(
                                    Modifier
                                        .offset(x = CHANNEL_W + ((now - ws) * dpPerSec.value).dp)
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(JellytipPalette.Jelly),
                                )
                            }
                        }
                }
            }
        }
    }
    ProgrammeActions(
        viewModel = viewModel,
        target = actions,
        now = now,
        onWatch = { ch, t -> openFullScreen(ch, t) },
        onDismiss = { actions = null },
    )
}

@Composable
private fun ProgrammeActions(
    viewModel: JellytipLiveTvViewModel,
    target: Pair<IptvChannel, IptvProg>?,
    now: Long,
    onWatch: (IptvChannel, IptvTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val (ch, p) = target ?: return
    val items =
        buildList {
            add(DialogItem("Watch ${ch.name} live", dismissOnClick = true) { onWatch(ch, IptvTarget(ch.id, ch.name)) })
            if (viewModel.canCatchUp(ch, p)) {
                add(DialogItem(if (p.isOn(now)) "Watch from the start" else "Watch “${p.title}”", dismissOnClick = true) { onWatch(ch, IptvTarget(ch.id, p.title, p.start, p.stop)) })
            }
            if (p.title.isNotEmpty() && p.stop > now && !viewModel.isRecorded(ch, p)) {
                add(DialogItem("Record “${p.title}”", dismissOnClick = true) { viewModel.record(ch, p) })
            }
            add(DialogItem(if (ch.favourite) "Remove from Favourites" else "Add to Favourites", dismissOnClick = true) { viewModel.toggleFavourite(ch) })
        }
    val subtitle = if (p.title.isNotEmpty()) "${p.title}  ·  ${hhmm(p.start)}–${hhmm(p.stop)}" else ch.name
    DialogPopup(
        showDialog = true,
        title = subtitle,
        dialogItems = items,
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun RecordingsList(
    state: LiveTvState,
    onPlay: (IptvRecording) -> Unit,
    onCancel: (IptvRecording) -> Unit,
    onDelete: (IptvRecording) -> Unit,
) {
    var menu by remember { mutableStateOf<IptvRecording?>(null) }
    Column(Modifier.fillMaxSize()) {
        state.space?.let { sp ->
            Text(
                text =
                    "Recordings use ${sp.usedGb.toInt()} of ${sp.capGb.toInt()} GB" +
                        (sp.freeGb?.let { " · ${it.toInt()} GB free on the server" } ?: "") +
                        (if (!sp.canRecord) " · can't record: ${sp.reason ?: "no space"}" else ""),
                color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                fontFamily = JellytipFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        val list = state.recordings
        when {
            list == null -> Text("Loading recordings…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, modifier = Modifier.padding(12.dp))
            list.isEmpty() -> Text("Nothing recorded yet. Hold OK on a programme in the guide to record it.", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, modifier = Modifier.padding(12.dp))
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { r ->
                        val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(r.start * 1000))
                        val stateText =
                            when (r.state) {
                                "scheduled" -> r.error ?: "Scheduled"
                                "recording" -> "Recording now"
                                "done" -> if (r.watched) "Watched" else "Ready"
                                "failed" -> "Failed" + (r.error?.let { ": $it" } ?: "")
                                else -> "Cancelled"
                            }
                        Surface(
                            onClick = { if (r.playable) onPlay(r) else menu = r },
                            onLongClick = { menu = r },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors =
                                ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.06f),
                                    focusedContainerColor = Color.White,
                                    contentColor = JellytipPalette.Vanilla,
                                    focusedContentColor = Color.Black,
                                ),
                            modifier = Modifier.fillMaxWidth().height(ROW_H),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            ) {
                                if (r.state == "recording") Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(JellytipPalette.Jelly))
                                Text(r.title, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${r.channel.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")}  ·  $day ${hhmm(r.start)}–${hhmm(r.stop)}", fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1)
                                Text(stateText, fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(180.dp))
                            }
                        }
                    }
                }
        }
    }
    menu?.let { r ->
        val items =
            buildList {
                if (r.playable) add(DialogItem("Play", dismissOnClick = true) { onPlay(r) })
                if (r.upcoming) add(DialogItem(if (r.state == "recording") "Stop recording" else "Cancel recording", dismissOnClick = true) { onCancel(r) })
                if (!r.upcoming) add(DialogItem("Delete", dismissOnClick = true) { onDelete(r) })
            }
        DialogPopup(showDialog = true, title = r.title, dialogItems = items, onDismissRequest = { menu = null })
    }
}

@Composable
private fun LivePreview(
    playback: JellytipIptvPlayback,
    playState: IptvPlayState,
    focused: IptvChannel?,
    modifier: Modifier,
) {
    val player = playback.player
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF141414))) {
        if (player != null && (playState is IptvPlayState.Playing || playState is IptvPlayState.Opening)) {
            PlayerSurface(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = Modifier.fillMaxSize())
        }
        val text =
            when (playState) {
                is IptvPlayState.Opening -> "Tuning ${playState.target.label}…"
                is IptvPlayState.Full -> playState.message
                is IptvPlayState.Failed -> playState.message
                is IptvPlayState.Playing -> null
                IptvPlayState.Idle -> focused?.let { "Preview starts when you stop on a channel" }
            }
        text?.let {
            Text(
                text = it,
                color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
                fontFamily = JellytipFontFamily,
                fontSize = 12.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(10.dp),
            )
        }
    }
}

@Composable
private fun FocusInfo(
    ch: IptvChannel?,
    prog: IptvProg?,
    now: Long,
    message: String?,
    modifier: Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        Text(
            text = listOfNotNull(ch?.num?.toString(), ch?.name).joinToString("  "),
            color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
            fontFamily = JellytipFontFamily,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(
            text = prog?.title ?: ch?.let { it.guideNote ?: "No guide information" } ?: "",
            color = JellytipPalette.Vanilla,
            fontFamily = JellytipFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        prog?.let { p ->
            val left = if (p.isOn(now)) "  ·  ${(p.stop - now) / 60} min left" else ""
            Text(
                text = "${hhmm(p.start)}–${hhmm(p.stop)}$left",
                color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                fontFamily = JellytipFontFamily,
                fontSize = 13.sp,
            )
            p.desc?.let {
                Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.75f), fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            text = message ?: "OK  watch full screen   ·   hold OK  favourite",
            color = JellytipPalette.Vanilla.copy(alpha = 0.45f),
            fontFamily = JellytipFontFamily,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimeRuler(
    ws: Long,
    dpPerSec: Dp,
    modifier: Modifier,
) {
    Box(modifier) {
        var t = ws
        while (t < ws + IptvRules.WINDOW_SECS) {
            Text(
                text = hhmm(t),
                color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                fontFamily = JellytipFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.offset(x = ((t - ws) * dpPerSec.value).dp + 4.dp),
            )
            t += IptvRules.HALF_HOUR
        }
    }
}

@Composable
private fun ChannelCell(
    ch: IptvChannel,
    focusedRow: Boolean,
    modifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(end = 8.dp),
    ) {
        Box(Modifier.size(44.dp, 30.dp), contentAlignment = Alignment.Center) {
            var failed by remember(ch.logo) { mutableStateOf(false) }
            if (ch.logo != null && !failed) {
                AsyncImage(
                    // Small logo size (the server scales to fit 96 px)
                    model = if (ch.logo.contains('?')) ch.logo else "${ch.logo}?s=96",
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = initials(ch.name),
                    color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            text = (if (ch.favourite) "★ " else "") + ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), ""),
            color = JellytipPalette.Vanilla.copy(alpha = if (focusedRow) 1f else 0.75f),
            fontFamily = JellytipFontFamily,
            fontWeight = if (focusedRow) FontWeight.Medium else FontWeight.Normal,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun initials(name: String): String =
    name
        .replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2)
        .joinToString("") { it.first().uppercase() }

@Composable
private fun ProgCell(
    prog: IptvProg,
    now: Long,
    recorded: Boolean,
    catchUp: Boolean,
    note: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val onNow = prog.isOn(now)
    val past = prog.stop <= now
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = if (onNow) 0.12f else if (past) 0.03f else 0.07f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla.copy(alpha = if (past) 0.5f else 0.9f),
                focusedContentColor = Color.Black,
            ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp),
        ) {
            if (recorded) Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(JellytipPalette.Jelly))
            Text(
                text = (if (catchUp) "↺ " else "") + prog.title.ifEmpty { note ?: "No guide information" },
                fontFamily = JellytipFontFamily,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) JellytipPalette.Vanilla.copy(alpha = 0.18f) else Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier,
    ) {
        Text(
            text = label,
            fontFamily = JellytipFontFamily,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
}
