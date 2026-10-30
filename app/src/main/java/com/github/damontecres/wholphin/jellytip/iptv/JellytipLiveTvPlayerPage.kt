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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import javax.inject.Inject

@HiltViewModel
class JellytipLiveTvPlayerViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: com.github.damontecres.wholphin.services.NavigationManager,
    ) : ViewModel()

private const val ZAP_SETTLE_MS = 700L
private const val BANNER_MS = 5_000L
private const val STALL_MS = 10_000L
private const val IDLE_RETUNE_MS = 600L

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

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
        if (bannerVisible) {
            val cu = current.takeIf { it.start != null && it.stop != null && it.channelId == channel.id }
            val shown = if (cu != null) IptvNowNext(IptvProg(cu.start!!, cu.stop!!, cu.label, null, true), null) else nowNext
            NowNextBanner(channel, shown, now, Modifier.align(Alignment.BottomStart), catchUp = cu != null)
            val hint =
                when {
                    cu != null -> "▶  Right: back to live"
                    nowNext?.now?.let { it.canStartOver && it.isOn(now) } == true -> "◀  From kick-off"
                    else -> null
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
