package com.github.damontecres.wholphin.jellytip.channels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/*
 * My Channels on the TV (round 3 item 10, mock-up 10; web screens/Channels.tsx). A list of always-on channels, numbered
 * from 101, each showing what the clock says is on, the episode/year and the time left; the right side shows the
 * focused channel's picture and what's next. Up/Down moves between channels without opening anything (one IPTV
 * connection: nothing streams until OK). OK joins mid-programme at the clock's offset; hold OK starts the programme
 * from the beginning. A library item is opened with the channel's next item queued after it (the existing "Play both"
 * queue); IPTV items open in the IPTV player at the offset.
 *
 * Not ported: zapping channels with Up/Down inside the player (it would collide with live-TV zapping) and chaining
 * past the queued next item; "Back to live" for a from-start play. See the lane report.
 */

data class ChannelRow(
    val def: ChannelDef,
    val number: Int,
    val sched: Sched,
)

@HiltViewModel
class JellytipMyChannelsViewModel
    @Inject
    constructor(
        private val service: JellytipChannelsService,
        private val images: ImageUrlService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        data class State(
            val loading: Boolean = true,
            val failed: Boolean = false,
            val defs: List<ChannelDef> = emptyList(),
            val pools: Map<String, List<ChanItem>> = emptyMap(),
        )

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        /** The channel last tuned: focus returns to it. */
        var lastId: String? = null

        fun load(fresh: Boolean = false) {
            viewModelScope.launch {
                val r = runCatching { service.all(fresh) }.onFailure { Timber.w(it, "my channels") }.getOrNull()
                _state.value =
                    if (r == null) {
                        _state.value.copy(loading = false, failed = _state.value.defs.isEmpty())
                    } else {
                        val pools = r.second.mapValues { (id, l) -> Channels.poolOf(l, Channels.channelById(id, r.first)) }
                        State(loading = false, defs = r.first.filter { Channels.channelViable(pools[it.id].orEmpty()) }, pools = pools)
                    }
            }
        }

        fun rows(nowMs: Long): List<ChannelRow> {
            val s = _state.value
            return s.defs.mapIndexed { i, d -> ChannelRow(d, 101 + i, Channels.schedule(s.pools[d.id].orEmpty(), d.id, nowMs, 3)) }
        }

        fun art(it: ChanItem?): String? {
            if (it == null) return null
            if (Channels.isIptvId(it.id)) return it.art
            val uuid = uuid(it.id) ?: return null
            return images.getItemImageUrl(uuid, ImageType.BACKDROP, maxWidth = 1280)
        }

        fun tune(
            row: ChannelRow,
            fromStart: Boolean,
        ) {
            // The list redraws every 15 s: take the clock again so the join offset is exact
            val sched = Channels.schedule(_state.value.pools[row.def.id].orEmpty(), row.def.id, System.currentTimeMillis(), 3)
            val t = (if (fromStart) Channels.fromStartTarget(sched.slots) else Channels.tuneTarget(sched.slots, sched.offsetS)) ?: return
            lastId = row.def.id
            JellytipTrailerControl.stop()
            JellytipTrace.log("channels", "tune ${row.def.id} ${t.id} at ${t.startS}s fromStart=$fromStart")
            val dest =
                when {
                    Channels.isIptvEpisodeId(t.id) && t.seriesId != null ->
                        Destination.JellytipIptvPlay(title = t.title, seriesId = t.seriesId, episodeId = t.id, resumeMs = t.startS * 1000)
                    Channels.isIptvId(t.id) -> Destination.JellytipIptvPlay(title = t.title, vodId = t.id, resumeMs = t.startS * 1000)
                    else -> {
                        val id = uuid(t.id) ?: return
                        val then = t.thenId?.takeIf { !Channels.isIptvId(it) }?.let { uuid(it)?.toString() }
                        Destination.Playback(id, t.startS * 1000, jellytipThenItemId = then)
                    }
                }
            navigationManager.navigateTo(dest, "my channels ${row.def.id}")
        }

        private fun uuid(hex: String): UUID? =
            runCatching {
                if (hex.length != 32) return null
                UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
            }.getOrNull()
    }

private fun leftText(
    slot: Slot?,
    nowMs: Long,
): String {
    slot ?: return ""
    val mins = ((slot.endMs - nowMs) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "ending"
        mins < 60 -> "$mins min left"
        else -> "${mins / 60} h ${"%02d".format(mins % 60)} left"
    }
}

private fun hm(ms: Long) =
    java.time.Instant
        .ofEpochMilli(ms)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun JellytipMyChannelsPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipMyChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val rows = remember(state.defs, state.pools, now) { viewModel.rows(now) }
    var focused by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isEmpty()) return@LaunchedEffect
        val at = rows.indexOfFirst { it.def.id == viewModel.lastId }
        // The tuned row may be off screen (not composed): bring it in first, else fall back to the first row
        if (at >= 0) listState.scrollToItem(at)
        withFrameNanos { }
        if (at < 0 || !restore.tryRequestFocus()) first.tryRequestFocus()
    }
    val cur = rows.getOrNull(focused)
    Box(modifier.fillMaxSize().background(Color(0xFF14100C))) {
        // Focused channel's picture, right side, fading into the list
        val art = viewModel.art(cur?.sched?.slots?.firstOrNull()?.item)
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color(0xFF14100C), 0.5f to Color(0xFF14100C).copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.25f)),
                ),
            )
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(560.dp).fillMaxHeight().padding(start = 56.dp, top = 40.dp)) {
                Text("MY CHANNELS", color = JellytipPalette.Jelly, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 1.5.sp)
                Text("Always on, joined mid-programme", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp)
                Text(
                    "▲▼ change channel · OK join · hold OK from the start",
                    color = JellytipPalette.Vanilla.copy(alpha = 0.65f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                when {
                    state.loading && rows.isEmpty() -> Note("Tuning your channels…")
                    state.failed -> Note("Couldn't load your channels. Check the server and come back.")
                    rows.isEmpty() -> Note("Not enough in your library for any channel yet.")
                    else ->
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                            itemsIndexed(rows, key = { _, r -> r.def.id }) { i, r ->
                                ChannelTile(
                                    r,
                                    now,
                                    onClick = { viewModel.tune(r, fromStart = false) },
                                    onLongClick = { viewModel.tune(r, fromStart = true) },
                                    modifier =
                                        Modifier
                                            .onFocusChanged { if (it.isFocused) focused = i }
                                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                            .then(if (r.def.id == viewModel.lastId) Modifier.focusRequester(restore) else Modifier),
                                )
                            }
                        }
                }
            }
            // Up next, for the focused channel
            cur?.let { r ->
                Column(Modifier.fillMaxHeight().padding(start = 48.dp, end = 48.dp, bottom = 56.dp), verticalArrangement = Arrangement.Bottom) {
                    Text(r.def.blurb, color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 15.sp)
                    r.sched.slots.drop(1).take(2).forEach { s ->
                        Text(
                            "${hm(s.startMs)}  ${Channels.itemTitle(s.item)}${Channels.itemSub(s.item).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}",
                            color = JellytipPalette.Vanilla,
                            fontFamily = JellytipFontFamily,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 16.sp)
}

@Composable
internal fun ChannelTile(
    r: ChannelRow,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slot = r.sched.slots.firstOrNull()
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF1B1B1B),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp)),
            ),
        modifier = modifier.fillMaxWidth().height(84.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${r.number}", fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.width(76.dp))
            Column(Modifier.weight(1f)) {
                Text(r.def.name, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    slot?.let { s -> listOf(Channels.itemTitle(s.item), Channels.itemSub(s.item)).filter { it.isNotEmpty() }.joinToString(" · ") } ?: "",
                    fontFamily = JellytipFontFamily,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(leftText(slot, nowMs), fontFamily = JellytipFontFamily, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
