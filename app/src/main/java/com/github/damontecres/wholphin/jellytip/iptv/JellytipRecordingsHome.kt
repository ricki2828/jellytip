package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/*
 * Recordings on Home (~/ai/plans/jellytip-live-sport.md, "Recordings prominent"): a row right after Continue Watching
 * whenever the person has any, from GET /v1/iptv/recordings. Recording now (● REC + progress), ready and unwatched
 * (NEW), scheduled (time chip), watched. OK plays a ready one; on a scheduled one it opens a small sheet with Cancel.
 */

@HiltViewModel
class JellytipRecordingsHomeViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        /** Bumped after a cancel so the row reloads at once. */
        val reload = MutableStateFlow(0)

        fun play(r: IptvRecording) {
            JellytipTrailerControl.stop()
            val pos = r.positionSecs ?: 0.0
            val resume = (pos * 1000).toLong().takeIf { r.durationSecs == null || pos < r.durationSecs * 0.9 } ?: 0L
            navigationManager.navigateTo(Destination.JellytipIptvPlay(title = r.title, recordingId = r.id, resumeMs = resume), "home recording OK ${r.id}")
        }

        fun cancel(r: IptvRecording) {
            viewModelScope.launch {
                val ok = service.cancelRecording(r.id)
                JellytipTrace.log("iptv", "home cancel recording ${r.id} ok=$ok")
                reload.value++
            }
        }
    }

/**
 * Home's recordings, ordered for the row; null = none (or no IPTV). Refreshes every 60 s while Home shows, but only while
 * something is scheduled or recording.
 */
@Composable
fun rememberHomeRecordings(viewModel: JellytipRecordingsHomeViewModel = hiltViewModel()): List<IptvRecording>? {
    val initial = remember { viewModel.service.peekRecordings()?.let(::homeRecordings)?.takeIf { it.isNotEmpty() } }
    val tick by viewModel.reload.collectAsState()
    val loaded by produceState(initial, tick) {
        while (true) {
            val got = viewModel.service.recordings()?.second ?: break
            val list = homeRecordings(got)
            value = list.takeIf { it.isNotEmpty() }
            if (list.none { it.upcoming }) break
            delay(REFRESH_MS)
        }
    }
    return loaded
}

private const val REFRESH_MS = 60_000L

@Composable
fun JellytipRecordingsRow(
    items: List<IptvRecording>,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellytipRecordingsHomeViewModel = hiltViewModel(),
) {
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    var sheet by remember { mutableStateOf<IptvRecording?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemRowTitle("Recordings")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(8.dp, 8.dp),
            modifier = modifier.jellytipRowBleed().fillMaxWidth(),
        ) {
            items(items, key = { it.id }) { r ->
                RecordingCard(
                    r,
                    now,
                    onClick = { if (r.playable && r.state != "recording") viewModel.play(r) else sheet = r },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
                )
            }
        }
    }
    sheet?.let { r ->
        val options =
            buildList {
                addAll(recordingSheetItems(r, onPlay = { viewModel.play(r) }, onCancel = { viewModel.cancel(r) }))
                add(
                    DialogItem("All recordings", dismissOnClick = true) {
                        viewModel.navigationManager.navigateTo(Destination.JellytipRecordings, "home recording sheet")
                    },
                )
            }
        DialogPopup(showDialog = true, title = recordingSheetTitle(r, "${r.title}  ·  ${whenLabel(r.start)}"), dialogItems = options, onDismissRequest = { sheet = null })
    }
}

@Composable
private fun RecordingCard(
    r: IptvRecording,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.07f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier.size(300.dp, 84.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(Modifier.size(56.dp, 40.dp), contentAlignment = Alignment.Center) {
                var failed by remember(r.logo) { mutableStateOf(false) }
                val logo = r.logo
                if (logo != null && !failed) {
                    AsyncImage(
                        model = if (logo.contains('?')) logo else "$logo?s=96",
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onError = { failed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(initials(r.channel.ifBlank { r.title }), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        r.state == "recording" -> Chip("● REC", Color.White, JellytipPalette.Jelly)
                        r.readyNew -> Chip("NEW", Color.Black, JellytipPalette.Vanilla)
                        r.state == "scheduled" -> Chip(whenLabel(r.start), Color.White.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.45f))
                    }
                    Text(
                        text = listOfNotNull(r.channel.takeIf { it.isNotBlank() }, durationLabel(r)).joinToString("  ·  "),
                        fontFamily = JellytipFontFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(r.title, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.desc?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontFamily = JellytipFontFamily, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp))
                }
                val frac =
                    when {
                        r.state == "recording" -> ((now - r.start).toFloat() / (r.stop - r.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        r.durationSecs != null && r.durationSecs > 0 && (r.positionSecs ?: 0.0) > 0 ->
                            ((r.positionSecs ?: 0.0) / r.durationSecs).toFloat().coerceIn(0f, 1f)
                        else -> null
                    }
                if (frac != null) {
                    Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.Gray.copy(alpha = 0.4f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(JellytipPalette.Jelly))
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    color: Color,
    background: Color,
) {
    Text(
        text = text,
        color = color,
        fontFamily = JellytipFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        modifier = Modifier.background(background, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private fun durationLabel(r: IptvRecording): String? {
    val min = ((r.stop - r.start) / 60).toInt()
    if (min <= 0) return null
    return if (min >= 60) "${min / 60}h ${min % 60}m".replace(" 0m", "") else "$min min"
}

/** "Today 12:14", "Tomorrow 08:00", or "Sat 19:30" within the week, else "12 Nov 19:30". */
internal fun whenLabel(epoch: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochSecond(epoch).atZone(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(zone), at.toLocalDate())
    val time = hhmm(epoch)
    return when (days) {
        0L -> "Today $time"
        1L -> "Tomorrow $time"
        -1L -> "Yesterday $time"
        in 2L..6L -> at.format(java.time.format.DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + " " + time
        else -> at.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) + " " + time
    }
}
