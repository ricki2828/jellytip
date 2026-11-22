@file:OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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

/**
 * A recording, IPTV film or series episode full screen. OK / Up / Down show one overlay (title, progress, [Play/Pause]
 * [Audio & subtitles] [More]); Left / Right seek while it's hidden (hold to go faster) and move between its buttons while
 * it's shown; Back hides it, else leaves. Resume point saved every 30 s and on leaving; the stream is released when the
 * page goes.
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
    val watch = remember(target) { com.github.damontecres.wholphin.jellytip.signals.WatchSession() }
    // Ramped seeking (web b662f0bc): a pending target that grows while Left/Right are pressed or held, seeked once settled
    var pendingSeek by remember(target) { mutableStateOf<SeekRamp.State?>(null) }
    var seekHeld by remember(target) { mutableStateOf(false) }
    // The overlay's scrub bar has focus (Left / Right seek there, Ricki 22-Nov)
    var scrubFocused by remember(target) { mutableStateOf(false) }
    var lastSeekRefusedAt by remember(target) { androidx.compose.runtime.mutableLongStateOf(0L) }
    val playingThis = (playState as? IptvPlayState.Playing)?.target == target
    LaunchedEffect(playingThis) {
        if (playingThis) {
            delay(3_000) // (a container's seek index can arrive a moment after READY)
            val p = playback.player
            JellytipTrace.player("iptv", "seekable ${target.channelId}: exo=${p?.isCurrentMediaItemSeekable} remux=${playback.isRemux} dur=${playback.durationMs() / 1000} s")
        }
    }
    LaunchedEffect(pendingSeek != null) {
        while (pendingSeek != null) {
            delay(100)
            val s = pendingSeek ?: break
            if (SeekRamp.commitDue(s, System.currentTimeMillis(), seekHeld)) {
                val to = (s.target * 1000).toLong()
                JellytipTrace.player("iptv", "seek ${target.channelId} ${(to - playback.positionMs()) / 1000} s -> ${to / 1000} s (${if (playback.isRemux) "remux" else "direct"})")
                playback.seekTo(to)
                watch.seeked()
                position = to
                pendingSeek = null
            }
        }
    }
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

    // A seek key on a stream that can't seek (Ricki 22-Nov, Celebrity Traitors S2E2: Right x15, nothing happened and nothing in
    // the trace): say so, and trace why, once per burst (presses within 3 s count as one)
    fun seekRefused() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSeekRefusedAt > 3_000) {
            val p = playback.player
            JellytipTrace.player(
                "iptv",
                "seek ignored ${target.channelId}: not seekable (exo=${p?.isCurrentMediaItemSeekable} remux=${playback.isRemux} dur=${playback.durationMs() / 1000} s)",
            )
            android.widget.Toast.makeText(context, "This stream can't be skipped through yet", android.widget.Toast.LENGTH_SHORT).show()
        }
        lastSeekRefusedAt = nowMs
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // One owner per page: a copy swap (old page fading out while the new one opens) must not detach the new page
    val owner = remember { "item:" + java.util.UUID.randomUUID() }
    // Taste signals (web 4a830873): this IPTV film / episode's real playing time; closed when the page goes or it ends
    DisposableEffect(target) {
        val t =
            target.vodId?.let { com.github.damontecres.wholphin.jellytip.signals.TasteTarget("movie", iptvId = it) }
                ?: target.seriesId?.let { com.github.damontecres.wholphin.jellytip.signals.TasteTarget("series", iptvId = it) }
        watch.begin(t?.let { com.github.damontecres.wholphin.jellytip.signals.WatchMeta(it, isFilm = target.vodId != null) }, 0.0)
        onDispose {
            com.github.damontecres.wholphin.jellytip.signals.JellytipSignals
                .emit(watch.end(false, System.currentTimeMillis()))
            com.github.damontecres.wholphin.jellytip.signals.JellytipSignals
                .flushSoon()
        }
    }
    // Audio & subtitles (Up): the menu, and the person's languages asked for on open (put back when the page goes)
    var trackMenu by remember(target) { mutableStateOf(false) }
    // The overlay (lane B, Ricki 21-Nov: one overlay like the library player): its buttons, the More list
    var overlay by remember { mutableStateOf(false) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var moreOpen by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    // The OK that opened the overlay: its key-up must not also press the Play/Pause button it focused
    var swallowOkUp by remember { mutableStateOf(false) }
    val player0 = playback.player
    DisposableEffect(target, player0) {
        val p = player0
        val me = com.github.damontecres.wholphin.jellytip.me.JellytipMe.prefs.value
        val before = p?.let { IptvTracks.applyPrefs(it, me?.audioLangs.orEmpty(), owner) }
        // Put back only while still this page's (a newer page, e.g. a copy swap, has set its own by then)
        onDispose {
            if (p != null && before != null && IptvTracks.appliedBy == owner) {
                p.trackSelectionParameters = before
                IptvTracks.appliedBy = null
            }
        }
    }
    // Subtitles (Ricki 22-Nov, Celebrity Traitors: "subtitles don't work"): the menu picked a text track but this page drew no
    // cues. The stream's cues for THIS page's player, drawn over the picture below; cleared when the target or player changes.
    // Only once the player holds this page's own stream: while it opens, the previous stream (Live TV) still plays and must
    // not caption the "Opening…" screen.
    var subtitleCues by remember(target, player0) {
        mutableStateOf(if (playback.holds(target)) player0?.currentCues?.cues.orEmpty() else emptyList())
    }
    DisposableEffect(target, player0) {
        val p = player0
        val listener =
            object : androidx.media3.common.Player.Listener {
                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                    subtitleCues = if (playback.state.value.target == target && playback.holds(target)) cueGroup.cues else emptyList()
                }
            }
        p?.addListener(listener)
        onDispose {
            p?.removeListener(listener)
            subtitleCues = emptyList()
        }
    }
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
    // The overlay hides itself after 5 s without a key (never while one of its menus is open)
    LaunchedEffect(overlay, overlayTick, trackMenu, moreOpen, recMenu) {
        if (overlay && !trackMenu && !moreOpen && !recMenu) {
            delay(IptvPlayControls.HIDE_MS)
            overlay = false
        }
    }
    LaunchedEffect(overlay) {
        if (overlay) {
            androidx.compose.runtime.withFrameNanos { }
            playFocus.tryRequestFocus()
        } else {
            scrubFocused = false
            swallowOkUp = false
            focus.tryRequestFocus()
        }
    }
    // A failed stream: hide the overlay so OK is "try again" again (the status card says so)
    LaunchedEffect(playState is IptvPlayState.Failed) {
        if (playState is IptvPlayState.Failed) overlay = false
    }
    // Back: hides the overlay; with it hidden the page's own Back leaves the player
    androidx.activity.compose.BackHandler(enabled = overlay) { overlay = false }
    fun jumpToLive() {
        val m = recMeta ?: return
        if (!inProgress || m.channelId.isBlank()) return
        JellytipTrace.player("iptv", "recording ${m.id}: jump to live ${m.channelId}")
        playback.saveProgress(target)
        val ch = IptvChannel(m.channelId, m.channelId.substringBefore(':'), null, m.channel, m.logo, "", null, 0, false)
        playback.lineup = listOf(ch)
        playback.alternates = emptyMap()
        viewModel.navigationManager.replaceTop(Destination.JellytipLiveTvPlayer(ch.id))
        playback.play(IptvTarget(ch.id, ch.name), "recording: jump to live")
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
                if (it.isPlaying && playback.state.value.target == target) {
                    watch.runtime(duration / 1000.0)
                    watch.tick(it.currentPosition / 1000.0, 1.5)
                }
                if (it.playbackState == androidx.media3.common.Player.STATE_ENDED && playback.state.value.target == target) {
                    com.github.damontecres.wholphin.jellytip.signals.JellytipSignals
                        .emit(watch.end(true, System.currentTimeMillis()))
                }
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
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (e.type == KeyEventType.KeyUp && isOk && swallowOkUp) {
                    swallowOkUp = false
                    return@onPreviewKeyEvent true
                }
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                    seekHeld = false
                    return@onPreviewKeyEvent pendingSeek != null
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                overlayTick++
                val p = playback.player
                val seekable = playback.canSkip()
                // Remote media keys act directly, overlay or not
                when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (p != null) {
                            p.playWhenReady = !p.playWhenReady
                            playing = p.playWhenReady
                            JellytipTrace.player("iptv", "${if (p.playWhenReady) "play" else "pause"} ${target.label}")
                        }
                        controlsTick++
                        return@onPreviewKeyEvent true
                    }

                    Key.MediaRewind, Key.MediaFastForward -> {
                        if (!seekable && e.nativeKeyEvent.repeatCount == 0) seekRefused()
                        if (seekable) {
                            val back = e.key == Key.MediaRewind
                            val to = (playback.positionMs() + if (back) -10_000 else 30_000).coerceAtLeast(0)
                            JellytipTrace.player("iptv", "seek ${target.channelId} ${if (back) -10 else 30} s -> ${to / 1000} s (media key, ${if (playback.isRemux) "remux" else "direct"})")
                            playback.seekTo(to)
                            watch.seeked()
                            position = to
                        }
                        controlsTick++
                        return@onPreviewKeyEvent true
                    }

                    else -> {}
                }
                val dir =
                    when {
                        isOk -> IptvPlayControls.Dir.OK
                        e.key == Key.DirectionUp -> IptvPlayControls.Dir.UP
                        e.key == Key.DirectionDown -> IptvPlayControls.Dir.DOWN
                        e.key == Key.DirectionLeft -> IptvPlayControls.Dir.LEFT
                        e.key == Key.DirectionRight -> IptvPlayControls.Dir.RIGHT
                        else -> IptvPlayControls.Dir.OTHER
                    }
                // The overlay's buttons have focus: the D-pad and OK are theirs (Back is the BackHandler); on the scrub bar
                // Left / Right seek as below
                if (overlay && !IptvPlayControls.scrubSeeks(overlay, scrubFocused, dir)) return@onPreviewKeyEvent false
                // Stream recovery gave up: OK tries again at the same position
                val failedNow = playState as? IptvPlayState.Failed
                if (failedNow != null && failedNow.canRetry && isOk) {
                    if (e.nativeKeyEvent.repeatCount == 0) playback.retry()
                    return@onPreviewKeyEvent true
                }
                when (IptvPlayControls.hidden(dir)) {
                    IptvPlayControls.Hidden.SHOW -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            swallowOkUp = isOk
                            overlay = true
                        }
                        true
                    }

                    IptvPlayControls.Hidden.SEEK -> {
                        // Ramped seek (lane B) on t5j's remux-aware position/seek; the progress strip shows, not the buttons
                        if (!seekable && e.nativeKeyEvent.repeatCount == 0) seekRefused()
                        if (seekable) {
                            seekHeld = true
                            pendingSeek =
                                SeekRamp.advance(
                                    pendingSeek,
                                    if (e.key == Key.DirectionLeft) -1 else 1,
                                    System.currentTimeMillis(),
                                    e.nativeKeyEvent.repeatCount > 0,
                                    playback.positionMs() / 1000.0,
                                    (playback.durationMs().takeIf { d -> d > 0 } ?: Long.MAX_VALUE / 2) / 1000.0,
                                )
                        }
                        controlsTick++
                        true
                    }

                    IptvPlayControls.Hidden.NONE -> false
                }
            }.focusable(),
    ) {
        playback.player?.let { PlayerSurface(player = it, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = Modifier.fillMaxSize()) }
        // Text and bitmap (PGS / DVB) cues alike, in the person's system caption style and size
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                androidx.media3.ui.SubtitleView(it).apply {
                    setUserDefaultStyle()
                    setUserDefaultTextSize()
                }
            },
            update = { it.setCues(subtitleCues) },
            modifier = Modifier.fillMaxSize(),
        )
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
        val tp = playback.player
        if (trackMenu && tp != null) {
            IptvTrackMenu(tp) {
                trackMenu = false
                overlayTick++
                if (overlay) playFocus.tryRequestFocus() else focus.tryRequestFocus()
            }
        }
        if (overlay || controlsVisible) {
            val tp2 = playback.player
            IptvPlayOverlay(
                title = target.label,
                shownMs = pendingSeek?.let { (it.target * 1000).toLong() } ?: position,
                durationMs = duration,
                seeking = pendingSeek != null,
                playing = playing,
                nowMs = System.currentTimeMillis(),
                buttons = overlay,
                subtitlesOn = tp2?.currentTracks?.groups?.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected } == true,
                tracksEnabled = tp2 != null && playState is IptvPlayState.Playing,
                playFocus = playFocus,
                onPlayPause = {
                    tp2?.let {
                        it.playWhenReady = !it.playWhenReady
                        playing = it.playWhenReady
                        JellytipTrace.player("iptv", "${if (it.playWhenReady) "play" else "pause"} ${target.label} (overlay)")
                    }
                    overlayTick++
                },
                onTracks = { trackMenu = true },
                onMore = { moreOpen = true },
                onInteraction = { overlayTick++ },
                modifier = Modifier.align(Alignment.BottomStart),
                scrubbable = playback.canSkip() && duration > 0,
                onScrubFocus = { scrubFocused = it },
            )
        }
    }

    if (moreOpen) {
        val p = playback.player
        val items =
            IptvPlayControls.more(
                seekable = playback.canSkip(),
                recording = target.recordingId != null,
                inProgressWithChannel = inProgress && recMeta?.channelId?.isNotBlank() == true,
            )
        val close = {
            moreOpen = false
            overlayTick++
            if (overlay) playFocus.tryRequestFocus() else focus.tryRequestFocus()
        }
        com.github.damontecres.wholphin.jellytip.JellytipDialog(
            showDialog = true,
            title = target.label,
            dialogItems =
                if (items.isEmpty()) {
                    listOf(com.github.damontecres.wholphin.ui.components.DialogItem("Nothing else for this stream", dismissOnClick = true) {})
                } else {
                    items.map { m ->
                        com.github.damontecres.wholphin.ui.components.DialogItem(m.label, dismissOnClick = true) {
                            JellytipTrace.player("iptv", "more: ${m.name} ${target.label}")
                            when (m) {
                                IptvPlayControls.More.START_OVER -> {
                                    playback.seekTo(0)
                                    watch.seeked()
                                    position = 0
                                }

                                IptvPlayControls.More.JUMP_TO_LIVE -> jumpToLive()

                                IptvPlayControls.More.DELETE_RECORDING -> {
                                    recConfirm = true
                                    recMenu = true
                                }
                            }
                        }
                    }
                },
            onDismissRequest = { close() },
        )
    }

    if (recMenu) {
        val id = target.recordingId
        com.github.damontecres.wholphin.jellytip.JellytipDialog(
            showDialog = true,
            title = if (recConfirm) "Delete this recording for good?" else target.label,
            dialogItems =
                listOf(
                    com.github.damontecres.wholphin.ui.components.DialogItem(if (recConfirm) "Delete for good" else "Delete recording", dismissOnClick = false) {
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
                                } else {
                                    // Not deleted: back to watching it, never a black page
                                    playback.play(target, "delete failed: resume")
                                }
                            }
                        }
                    },
                ),
            onDismissRequest = {
                recMenu = false
                recConfirm = false
                overlayTick++
                if (overlay) playFocus.tryRequestFocus() else focus.tryRequestFocus()
            },
            // The first pick only arms "Delete for good": the dialog must stay (DialogPopup dismisses before onClick)
            dismissOnClick = false,
        )
    }
}
