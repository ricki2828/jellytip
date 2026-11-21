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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.launch
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay

private const val CONTROLS_MS = 4_000L
private const val SAVE_EVERY_MS = 30_000L

private fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
}

/**
 * A recording, IPTV film or series episode full screen: OK play/pause, Left/Right seek 10 s back / 30 s on, resume
 * point saved every 30 s and on leaving. The stream is released as soon as the page goes.
 */
@Composable
fun JellytipIptvPlayPage(
    target: IptvTarget,
    modifier: Modifier = Modifier,
    viewModel: JellytipLiveTvPlayerViewModel = hiltViewModel(),
) {
    val playback = viewModel.playback
    val playState by playback.state.collectAsState()
    val focus = remember { FocusRequester() }
    var controlsTick by remember { mutableIntStateOf(1) }
    var controlsVisible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    // A recording's own times (length when the file can't say; still recording = jump to live), refreshed each minute
    // while it records (gap row fb816e09 6f62ba3e)
    var recMeta by remember { mutableStateOf<IptvRecording?>(null) }
    var recMenu by remember { mutableStateOf(false) }
    var recConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(target.recordingId) {
        val id = target.recordingId ?: return@LaunchedEffect
        while (true) {
            val m = viewModel.service.peekRecordings()?.firstOrNull { it.id == id }?.takeIf { it.state != "recording" }
                ?: viewModel.service.recordings()?.second?.firstOrNull { it.id == id }
            recMeta = m
            playback.recordingMeta = m
            if (m?.state != "recording") return@LaunchedEffect
            delay(60_000)
        }
    }
    DisposableEffect(Unit) { onDispose { playback.recordingMeta = null } }
    val inProgress = recMeta?.state == "recording"
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(target) {
        playback.attach("item")
        playback.setVolume(1f)
        playback.play(target, "open ${target.label}")
        onDispose {
            playback.saveProgress(target)
            playback.detach("item")
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        playback.saveProgress(target)
        playback.stop("app hidden (item)")
    }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    LaunchedEffect(controlsTick) {
        controlsVisible = true
        delay(CONTROLS_MS)
        controlsVisible = false
    }
    LaunchedEffect(target) {
        var sinceSave = 0L
        while (true) {
            delay(500)
            playback.player?.let {
                // A server remux begins at its start offset: the item's position, not the stream's
                position = playback.positionMs()
                duration = playback.durationMs()
                playing = it.playWhenReady
                // "Play both" from a double feature: this IPTV film ended, start the other half
                if (it.playbackState == androidx.media3.common.Player.STATE_ENDED && target.vodId != null) {
                    val next =
                        com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill
                            .takeThenAfterIptv(target.vodId)
                    if (next != null) {
                        JellytipTrace.player("iptv", "play both: next after ${target.label}")
                        viewModel.navigationManager.goBack()
                        when (next) {
                            is com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.LibHalf ->
                                viewModel.navigationManager.navigateTo(Destination.Playback(next.id, 0L), "play both second half")

                            is com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.IptvHalf ->
                                viewModel.navigationManager.navigateTo(
                                    Destination.JellytipIptvPlay(title = next.item.title, vodId = next.item.id),
                                    "play both second half",
                                )
                        }
                        return@LaunchedEffect
                    }
                }
            }
            sinceSave += 500
            if (sinceSave >= SAVE_EVERY_MS) {
                sinceSave = 0
                playback.saveProgress(target)
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Stream recovery gave up: OK tries again at the same position
                val failedNow = playState as? IptvPlayState.Failed
                if (failedNow != null && failedNow.canRetry && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    if (e.nativeKeyEvent.repeatCount == 0) playback.retry()
                    return@onPreviewKeyEvent true
                }
                val p = playback.player
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (p != null) {
                            p.playWhenReady = !p.playWhenReady
                            JellytipTrace.player("iptv", "${if (p.playWhenReady) "play" else "pause"} ${target.label}")
                        }
                        controlsTick++
                        true
                    }

                    Key.DirectionLeft, Key.DirectionRight, Key.MediaRewind, Key.MediaFastForward -> {
                        if (p != null && (p.isCurrentMediaItemSeekable || playback.isRemux)) {
                            val back = e.key == Key.DirectionLeft || e.key == Key.MediaRewind
                            val to = (playback.positionMs() + if (back) -10_000 else 30_000).coerceAtLeast(0)
                            playback.seekTo(to)
                            position = to
                        }
                        controlsTick++
                        true
                    }

                    // Recording still in progress: Up jumps to the channel live
                    Key.DirectionUp -> {
                        val m = recMeta
                        if (m != null && inProgress && m.channelId.isNotBlank()) {
                            JellytipTrace.player("iptv", "recording ${m.id}: jump to live ${m.channelId}")
                            playback.saveProgress(target)
                            val ch = IptvChannel(m.channelId, m.channelId.substringBefore(':'), null, m.channel, m.logo, "", null, 0, false)
                            playback.lineup = listOf(ch)
                            playback.alternates = emptyMap()
                            viewModel.navigationManager.replaceTop(Destination.JellytipLiveTvPlayer(ch.id))
                            playback.play(IptvTarget(ch.id, ch.name), "recording: jump to live")
                        }
                        controlsTick++
                        true
                    }

                    // A recording: Down opens its menu (Delete recording)
                    Key.DirectionDown -> {
                        if (target.recordingId != null) recMenu = true
                        controlsTick++
                        true
                    }

                    else -> false
                }
            }.focusable(),
    ) {
        playback.player?.let { PlayerSurface(player = it, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = Modifier.fillMaxSize()) }
        val recoveryNote by playback.note.collectAsState()
        val status =
            recoveryNote ?: when (val s = playState) {
                is IptvPlayState.Opening -> "Opening ${target.label}…"
                is IptvPlayState.Full -> s.message
                is IptvPlayState.Failed -> if (s.canRetry) "${s.message}\nOK: try again" else s.message
                else -> null
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
        if (controlsVisible) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
            ) {
                Text(
                    text = target.label,
                    color = JellytipPalette.Vanilla,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (playing) "▶" else "❚❚", color = JellytipPalette.Vanilla, fontSize = 14.sp)
                    Text(clockText(position), color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
                    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(JellytipPalette.Jelly))
                    }
                    Text(if (duration > 0) clockText(duration) else "--:--", color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
                }
                Text(
                    text =
                        "OK  play / pause   ·   ◀ 10 s   ·   30 s ▶" +
                            (if (inProgress) "   ·   ▲ live" else "") + (if (target.recordingId != null) "   ·   ▼ delete" else ""),
                    color = JellytipPalette.Vanilla.copy(alpha = 0.45f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 11.sp,
                )
            }
        }
    }

    if (recMenu) {
        val id = target.recordingId
        com.github.damontecres.wholphin.ui.components.DialogPopup(
            showDialog = true,
            title = if (recConfirm) "Delete this recording for good?" else target.label,
            dialogItems =
                listOf(
                    com.github.damontecres.wholphin.ui.components.DialogItem(if (recConfirm) "Delete for good" else "Delete recording", dismissOnClick = recConfirm) {
                        if (!recConfirm) {
                            recConfirm = true
                        } else if (id != null) {
                            recMenu = false
                            recConfirm = false
                            playback.stop("delete recording")
                            scope.launch {
                                val ok = viewModel.service.deleteRecording(id)
                                JellytipTrace.player("iptv", "recording $id deleted ok=$ok")
                                android.widget.Toast.makeText(context, if (ok) "Recording deleted" else "Couldn't delete it", android.widget.Toast.LENGTH_SHORT).show()
                                if (ok) {
                                    viewModel.service.recordings()
                                    viewModel.navigationManager.goBack()
                                }
                            }
                        }
                    },
                ),
            onDismissRequest = {
                recMenu = false
                recConfirm = false
            },
        )
    }
}
