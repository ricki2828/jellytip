package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Reminders page rows (web RemindersPage.tsx, 8efc5458; gap row "Team reminders ... and a Reminders page"). */
object RemindersPageLogic {
    /** Game reminders not yet ended, one row per game (twins set from different screens fold), soonest first. */
    fun upcoming(
        list: List<IptvUpcoming>,
        now: Long,
    ): List<IptvUpcoming> {
        val out = ArrayList<IptvUpcoming>()
        for (u in list.filter { it.stop > now && it.reminderId != null }.sortedBy { it.start }) {
            if (out.none { it.sameGame(u) }) out += u
        }
        return out
    }

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    fun whenText(
        start: Long,
        zone: ZoneId,
    ): String = WHEN.format(Instant.ofEpochSecond(start).atZone(zone))

    fun subtitle(
        u: IptvUpcoming,
        now: Long,
        zone: ZoneId,
    ): String =
        listOfNotNull(
            u.league?.takeIf { it.isNotBlank() },
            if (u.isLive(now)) "On now" else whenText(u.startsAt ?: u.start, zone),
            if (u.noStream) "no stream yet" else null,
        ).joinToString(" · ")
}

@HiltViewModel
class JellytipRemindersPageViewModel
    @Inject
    constructor(
        val reminders: JellytipReminders,
        val teams: JellytipTeamReminders,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        fun load() {
            viewModelScope.launch { reminders.refresh() }
            teams.refresh()
        }
    }

@Composable
fun JellytipRemindersPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipRemindersPageViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    val games by viewModel.reminders.items.collectAsState()
    val teamList by viewModel.teams.items.collectAsState()
    val teamsAvailable by viewModel.teams.available.collectAsState()
    val gamesAvailable by viewModel.reminders.available.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.load()
        // The button is a lazy item: retry until it is attached
        repeat(10) {
            delay(50)
            if (first.tryRequestFocus()) return@LaunchedEffect
        }
    }
    // A removed row had focus: back to the top button
    val refocus = {
        scope.launch {
            listState.scrollToItem(0)
            delay(50)
            first.tryRequestFocus()
        }
    }
    val now = nowSec()
    val zone = ZoneId.systemDefault()
    val gameList = games?.let { RemindersPageLogic.upcoming(it, now) }
    Column(modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        Text("Reminders", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        Text(
            message ?: "OK on a reminder removes it.",
            color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
            fontFamily = JellytipFontFamily,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Button(
                    onClick = { viewModel.navigationManager.navigateTo(Destination.JellytipUpcomingSport, "reminders: upcoming sport") },
                    modifier = Modifier.focusRequester(first),
                ) { Text("Upcoming sport") }
            }
            if (teamsAvailable != false) {
                item { Head("Team reminders") }
                val tl = teamList
                when {
                    tl == null -> item { Hint("Loading…") }
                    tl.isEmpty() -> item { Hint("None yet. In Upcoming sport, OK on a game offers 'Remind me for every <team> game'.") }
                }
                items(tl.orEmpty(), key = { "t:${it.id}" }) { r ->
                    Row2("Every ${r.teamName} game" + (r.league?.let { " · $it" } ?: ""), if (busy == "t:${r.id}") "Saving…" else "OK to stop") {
                        if (busy != null) return@Row2
                        busy = "t:${r.id}"
                        viewModel.teams.toggle(TeamSide(r.teamId, r.teamName, null, r.sport, r.league), turnOn = false) { err ->
                            busy = null
                            message = err ?: "Stopped reminders for ${r.teamName}"
                            if (err == null) refocus()
                        }
                    }
                }
            }
            item { Head("Game reminders") }
            when {
                gamesAvailable == false -> item { Hint("Reminders aren't available on this server.") }
                gameList == null -> item { Hint("Loading…") }
                gameList.isEmpty() -> item { Hint("No game reminders set.") }
            }
            items(gameList.orEmpty(), key = { "g:${it.reminderId}" }) { u ->
                val id = u.reminderId ?: return@items
                Row2(u.title, if (busy == "g:$id") "Saving…" else RemindersPageLogic.subtitle(u, now, zone)) {
                    if (busy != null) return@Row2
                    busy = "g:$id"
                    viewModel.reminders.remove(id) { ok ->
                        busy = null
                        message = if (ok) "Reminder removed: ${u.title}" else "Couldn't remove the reminder."
                        if (ok) refocus()
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = JellytipPalette.Vanilla.copy(alpha = 0.55f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
