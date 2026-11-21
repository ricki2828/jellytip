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
import androidx.compose.ui.focus.focusProperties
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
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // "This stream isn't working": Down opens it (and a failed stream shows it); Back closes it
    val canReport = target.vodId != null || target.episodeId != null
    var reportOpen by remember(target) { mutableStateOf(false) }
    var reportBusy by remember(target) { mutableStateOf(false) }
    val reportFocus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(playState is IptvPlayState.Failed) {
        if (playState is IptvPlayState.Failed && canReport) reportOpen = true
    }
    LaunchedEffect(reportOpen) {
        if (reportOpen) {
            kotlinx.coroutines.yield()
            reportFocus.tryRequestFocus()
        } else {
            focus.tryRequestFocus()
        }
    }
    androidx.activity.compose.BackHandler(enabled = reportOpen) {
        reportOpen = false
        controlsTick++
    }
    val notWorking: () -> Unit = {
        if (!reportBusy) {
            reportBusy = true
            scope.launch {
                val pos = playback.player?.currentPosition ?: 0L
                // Same film / episode on another copy: carry on from here
                val resume = if (playState is IptvPlayState.Playing) pos else target.resumeMs
                JellytipTrace.player("iptv", "user: stream isn't working ${target.label}")
                val next = IptvStreamReport.reportAndFindNext(target, pos, alreadyReported = playState is IptvPlayState.Failed)
                reportBusy = false
                android.widget.Toast.makeText(ctx, next.message, android.widget.Toast.LENGTH_LONG).show()
                // Back pressed while looking: this page is no longer on top, so don't pop whatever is
                val top = viewModel.navigationManager.backStack.lastOrNull() as? Destination.JellytipIptvPlay
                val stillHere = top != null && top.vodId == target.vodId && top.episodeId == target.episodeId
                if (!stillHere) return@launch
                when (next) {
                    is IptvStreamReport.Next.Vod -> {
                        reportOpen = false
                        viewModel.navigationManager.goBack()
                        viewModel.navigationManager.navigateTo(Destination.JellytipIptvPlay(title = target.label, vodId = next.id, resumeMs = resume), "stream isn't working: next copy")
                    }

                    is IptvStreamReport.Next.Episode -> {
                        reportOpen = false
                        viewModel.navigationManager.goBack()
                        viewModel.navigationManager.navigateTo(
                            Destination.JellytipIptvPlay(title = target.label, seriesId = next.seriesId, episodeId = next.episodeId, resumeMs = resume),
                            "stream isn't working: same episode, next copy",
                        )
                    }

                    is IptvStreamReport.Next.None -> {}
                }
            }
        }
    }

    // One owner per page: a copy swap (old page fading out while the new one opens) must not detach the new page
    val owner = remember { "item:" + java.util.UUID.randomUUID() }
    DisposableEffect(target) {
        playback.attach(owner)
        playback.setVolume(1f)
        playback.play(target, "open ${target.label}")
        onDispose {
            playback.saveProgress(target)
            playback.detach(owner)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Only this page's own stream: a page leaving behind a newer one (copy swap) must not stop it
        if (playback.state.value.target == target) {
            playback.saveProgress(target)
            playback.stop("app hidden (item)")
        }
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
                position = it.currentPosition
                duration = it.duration.takeIf { d -> d > 0 } ?: 0L
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
                // The report button has focus: its keys go to it (Back is the BackHandler above)
                if (reportOpen) return@onPreviewKeyEvent false
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
                        if (p != null && p.isCurrentMediaItemSeekable) {
                            val back = e.key == Key.DirectionLeft || e.key == Key.MediaRewind
                            val to = (p.currentPosition + if (back) -10_000 else 30_000).coerceAtLeast(0)
                            p.seekTo(to)
                            position = to
                        }
                        controlsTick++
                        true
                    }

                    Key.DirectionDown -> {
                        if (canReport) reportOpen = true
                        controlsTick++
                        true
                    }

                    Key.DirectionUp -> {
                        controlsTick++
                        true
                    }

                    else -> false
                }
            }.focusable(),
    ) {
        playback.player?.let { PlayerSurface(player = it, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = Modifier.fillMaxSize()) }
        val status =
            when (val s = playState) {
                is IptvPlayState.Opening -> "Opening ${target.label}…"
                is IptvPlayState.Full -> s.message
                is IptvPlayState.Failed -> s.message
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
        if (reportOpen) {
            androidx.tv.material3.Button(
                onClick = notWorking,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 32.dp, end = 48.dp)
                        .focusRequester(reportFocus)
                        .focusProperties {
                            // The only control on screen: arrows must not move focus to the (key-eating) page
                            up = androidx.compose.ui.focus.FocusRequester.Cancel
                            down = androidx.compose.ui.focus.FocusRequester.Cancel
                            left = androidx.compose.ui.focus.FocusRequester.Cancel
                            right = androidx.compose.ui.focus.FocusRequester.Cancel
                        },
            ) {
                Text(if (reportBusy) "Reporting…" else "This stream isn't working")
            }
        }
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
                    text = "OK  play / pause   ·   ◀ 10 s   ·   30 s ▶" + if (canReport) "   ·   ▼ not working?" else "",
                    color = JellytipPalette.Vanilla.copy(alpha = 0.45f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
