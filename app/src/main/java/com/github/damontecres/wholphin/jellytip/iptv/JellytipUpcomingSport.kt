package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/*
 * Upcoming sport (~/ai/plans/jellytip-live-sport.md, "Upcoming sport"): Home row "Coming up in sport", a full screen
 * grouped by day (sidebar + Live TV), and an OK overlay with the channels (best first, with quality), Record and, once
 * it is on, Watch. GET /v1/iptv/sport/upcoming; when the route is missing nothing is shown.
 */

@HiltViewModel
class JellytipUpcomingSportViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: NavigationManager,
        val reminders: JellytipReminders,
    ) : ViewModel() {
        /** Message after Record ("Recording scheduled…" or the server's reason); null = none. */
        var recordResult by mutableStateOf<Pair<Boolean, String>?>(null)
            private set

        fun dismissResult() {
            recordResult = null
        }

        fun record(u: IptvUpcoming) {
            viewModelScope.launch {
                val r = service.record(u.channel.id, u.start)
                JellytipTrace.log("iptv", "upcoming record ${u.key} ok=${r.ok}")
                recordResult = if (r.ok) true to "Recording scheduled. It'll appear on Home." else false to (r.message ?: "Couldn't record it.")
                if (r.ok) service.recordings()
            }
        }

        /** Full screen on [ch]; Up/Down and failover go through the event's other channels. */
        fun watch(
            u: IptvUpcoming,
            ch: IptvChannel,
        ) = reminders.watch(u, ch)
    }

/** Upcoming sport for Home: the cached answer at once, then the fresh one; null = none or no route. */
@Composable
fun rememberUpcomingSport(viewModel: JellytipUpcomingSportViewModel = hiltViewModel()): List<IptvUpcoming>? {
    val initial = remember { viewModel.service.peekUpcoming()?.takeIf { it.isNotEmpty() } }
    val loaded by produceState(initial) {
        viewModel.service.upcoming()?.let { value = it.takeIf { l -> l.isNotEmpty() } }
    }
    return loaded
}

/** Home: "Coming up in sport", favourites first (server order). */
@Composable
fun JellytipUpcomingSportRow(
    items: List<IptvUpcoming>,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellytipUpcomingSportViewModel = hiltViewModel(),
) {
    var overlay by remember { mutableStateOf<IptvUpcoming?>(null) }
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemRowTitle("Coming up in sport")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(8.dp, 8.dp),
            modifier = modifier.jellytipRowBleed().fillMaxWidth(),
        ) {
            items(items, key = { it.key }) { u ->
                UpcomingTile(u, now, onClick = { overlay = u }, modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() })
            }
        }
    }
    UpcomingOverlays(overlay, now, onDismiss = { overlay = null }, viewModel)
}

/** "in 2h 10m", "in 25m"; a day or more away = the day and time. */
private fun countdownLabel(
    start: Long,
    now: Long,
): String {
    val mins = ((start - now + 59) / 60).coerceAtLeast(1)
    return when {
        mins >= 24 * 60 -> whenLabel(start)
        mins >= 60 -> "in ${mins / 60}h" + if (mins % 60 > 0) " ${mins % 60}m" else ""
        else -> "in ${mins}m"
    }
}

/**
 * Home: "Your reminders" (plan "Remind me"), right after Continue Watching. Countdown, STARTING SOON or LIVE on each
 * tile; OK on a live or soon one watches the best channel, otherwise opens the overlay (with Remove).
 */
@Composable
fun JellytipRemindersRow(
    items: List<IptvUpcoming>,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellytipUpcomingSportViewModel = hiltViewModel(),
) {
    var overlay by remember { mutableStateOf<IptvUpcoming?>(null) }
    val now by produceState(nowSec()) {
        while (true) {
            delay(15_000)
            value = nowSec()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemRowTitle("Your reminders")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(8.dp, 8.dp),
            modifier = modifier.jellytipRowBleed().fillMaxWidth(),
        ) {
            items(items.filter { it.stop > now }, key = { it.key }) { u ->
                UpcomingTile(
                    u,
                    now,
                    onClick = { if (u.isLive(now) || u.isSoon(now)) viewModel.reminders.watch(u) else overlay = u },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
                    countdown = true,
                )
            }
        }
    }
    UpcomingOverlays(overlay, now, onDismiss = { overlay = null }, viewModel, fromReminders = true)
}

/** Home's reminders (starts the 60 s poll); null = none, not loaded, or no route on this server. */
@Composable
fun rememberReminders(viewModel: JellytipUpcomingSportViewModel = hiltViewModel()): List<IptvUpcoming>? {
    LaunchedEffect(Unit) { viewModel.reminders.start() }
    val on by viewModel.reminders.available.collectAsState()
    val items by viewModel.reminders.items.collectAsState()
    return items?.takeIf { on == true && it.isNotEmpty() }
}

@Composable
private fun UpcomingOverlays(
    overlay: IptvUpcoming?,
    now: Long,
    onDismiss: () -> Unit,
    viewModel: JellytipUpcomingSportViewModel,
    fromReminders: Boolean = false,
) {
    overlay?.let { u -> UpcomingOverlay(u, now, onDismiss, viewModel, fromReminders) }
    viewModel.recordResult?.let { (ok, text) ->
        DialogPopup(
            showDialog = true,
            title = text,
            dialogItems =
                buildList {
                    if (ok) {
                        add(
                            DialogItem("View recordings", dismissOnClick = true) {
                                viewModel.dismissResult()
                                viewModel.navigationManager.navigateTo(Destination.JellytipRecordings, "upcoming record view")
                            },
                        )
                    }
                    add(DialogItem("OK", dismissOnClick = true) { viewModel.dismissResult() })
                },
            onDismissRequest = { viewModel.dismissResult() },
        )
    }
}

/** The OK overlay: matchup and time as the title; channels best first (Watch when live), Record. */
@Composable
private fun UpcomingOverlay(
    u: IptvUpcoming,
    now: Long,
    onDismiss: () -> Unit,
    viewModel: JellytipUpcomingSportViewModel,
    fromReminders: Boolean = false,
) {
    val remindersOn by viewModel.reminders.available.collectAsState()
    val reminderList by viewModel.reminders.items.collectAsState()
    val live = u.isLive(now)
    val channels = listOf(u.channel) + u.alternateChannels
    val items =
        buildList {
            channels.forEach { ch ->
                add(
                    DialogItem(
                        headlineContent = {
                            Text(
                                (if (live) "Watch on " else "") + ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), ""),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { QualityChips(ch.quality) },
                        onClick = { if (live) viewModel.watch(u, ch) },
                        enabled = live,
                        dismissOnClick = true,
                    ),
                )
            }
            when {
                u.recordingId != null -> add(DialogItem("Recording scheduled", dismissOnClick = true) {})
                u.recordable && u.stop > now -> add(DialogItem("Record", dismissOnClick = true) { viewModel.record(u) })
            }
            // Remind me (plan "Remind me"): hidden until the server answers the reminders route (404 = never)
            if (remindersOn == true && u.stop > now) {
                val rid = viewModel.reminders.reminderIdFor(u, reminderList)
                when {
                    rid == null -> add(DialogItem("Remind me", dismissOnClick = true) { viewModel.reminders.add(u) })
                    fromReminders -> add(DialogItem("Remove reminder", dismissOnClick = true) { viewModel.reminders.remove(rid) })
                    else -> add(DialogItem("Reminder set ✓", dismissOnClick = true) { viewModel.reminders.remove(rid) })
                }
            }
        }
    val title =
        listOfNotNull(
            u.title,
            if (live) "On now" else whenLabel(u.start),
            u.league,
        ).joinToString("  ·  ")
    DialogPopup(showDialog = true, title = title, dialogItems = items, onDismissRequest = onDismiss)
}

@Composable
private fun UpcomingTile(
    u: IptvUpcoming,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    countdown: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = liveTileColors(u.art),
        border = liveTileBorder(u.art),
        modifier = modifier.size(300.dp, 84.dp).onFocusChanged { focused = it.isFocused },
    ) {
        SportArtBackdrop(u.art, focused)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                SportArtSlot(u.art, 40.dp) {
                    Box(Modifier.size(56.dp, 40.dp), contentAlignment = Alignment.Center) {
                        var failed by remember(u.channel.logo) { mutableStateOf(false) }
                        val logo = u.channel.logo
                        if (logo != null && !failed) {
                            AsyncImage(
                                model = if (logo.contains('?')) logo else "$logo?s=96",
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                onError = { failed = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(initials(u.channel.name), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (u.isLive(now)) {
                        KindPill("live")
                    } else if (countdown && u.isSoon(now)) {
                        SoonPill()
                    } else {
                        Text(
                            text = if (countdown) countdownLabel(u.start, now) else whenLabel(u.start),
                            color = Color.White.copy(alpha = 0.9f),
                            fontFamily = JellytipFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    if (u.recordingId != null) Text("●", color = JellytipPalette.Jelly, fontSize = 10.sp)
                    Text(
                        text = listOfNotNull(if (u.favourite) "★" else null, u.league).joinToString(" ").ifEmpty { u.channel.name },
                        fontFamily = JellytipFontFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    QualityChips(u.channel.quality)
                }
                Text(u.title, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                alsoOnText(u.alternateChannels.size)?.let {
                    Text(it, fontFamily = JellytipFontFamily, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        if (u.art?.hasBadge == true) CornerLogo(u.channel.logo, Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

/** "Today", "Tomorrow", else "Saturday 1 Nov". */
private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
    }
}

/** The "Upcoming sport" screen (sidebar, Live TV): a row per day, server order within the day. */
@Composable
fun JellytipUpcomingSportPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipUpcomingSportViewModel = hiltViewModel(),
) {
    // null = loading; empty = nothing coming up (or no route on this server)
    val items by produceState<List<IptvUpcoming>?>(viewModel.service.peekUpcoming()) {
        value = viewModel.service.upcoming(days = 3, limit = 120) ?: value ?: emptyList()
    }
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    var overlay by remember { mutableStateOf<IptvUpcoming?>(null) }
    val first = remember { FocusRequester() }
    val list = items
    LaunchedEffect(list?.isNotEmpty() == true) {
        if (list?.isNotEmpty() == true) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
    Column(modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Upcoming sport", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        when {
            list == null -> Text("Loading…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            list.isEmpty() -> Text("Nothing coming up in the next few days.", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            else -> {
                val zone = ZoneId.systemDefault()
                val days = remember(list) { list.groupBy { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate() }.toSortedMap().toList() }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                    items(days.size, key = { days[it].first.toString() }) { di ->
                        val (day, events) = days[di]
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ItemRowTitle("${dayLabel(day)} (${events.size})")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                items(events.size, key = { events[it].key }) { ei ->
                                    val u = events[ei]
                                    UpcomingTile(
                                        u,
                                        now,
                                        onClick = { overlay = u },
                                        modifier = if (di == 0 && ei == 0) Modifier.focusRequester(first) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    UpcomingOverlays(overlay, now, onDismiss = { overlay = null }, viewModel)
}
