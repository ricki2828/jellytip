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
    ) : ViewModel()

private const val ZAP_SETTLE_MS = 700L
private const val BANNER_MS = 5_000L

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
    var index by remember { mutableIntStateOf(lineup.indexOfFirst { it.id == channelId }.coerceAtLeast(0)) }
    var bannerTick by remember { mutableIntStateOf(1) }
    var bannerVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val channel = lineup[index]

    DisposableEffect(Unit) {
        playback.attach("full")
        playback.setVolume(1f)
        onDispose {
            playback.lastFullScreenChannel = lineup[index].id
            playback.detach("full")
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.stop("app hidden (full screen)") }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val ch = lineup[index]
        playback.play(IptvTarget(ch.id, ch.name), "app shown (full screen)")
    }

    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    // Zap: open the stream once the choice has settled
    LaunchedEffect(index) {
        val ch = lineup[index]
        if (playState.target?.channelId != ch.id) delay(ZAP_SETTLE_MS)
        playback.play(IptvTarget(ch.id, ch.name), "full screen settle")
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
        if (bannerVisible) {
            NowNextBanner(channel, nowNext, now, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun NowNextBanner(
    ch: IptvChannel,
    nn: IptvNowNext?,
    now: Long,
    modifier: Modifier,
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
                Text(
                    text = listOfNotNull(ch.num?.toString(), ch.name).joinToString("  "),
                    color = JellytipPalette.Vanilla.copy(alpha = 0.75f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                val p = nn?.now
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
                        val frac = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(Modifier.width(220.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
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
