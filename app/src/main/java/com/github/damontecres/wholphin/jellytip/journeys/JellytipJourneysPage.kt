package com.github.damontecres.wholphin.jellytip.journeys

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID
import timber.log.Timber
import javax.inject.Inject

/*
 * Journeys (web #/journeys, 7cc13de9 + 1a1f4dca + f34fc72e): short ordered film paths grouped by type, and one journey's
 * page with watched ticks, a big Play next / Resume, and missing films greyed and skipped. Library only (no IPTV fill),
 * like the web. Entry: "Start a journey" on the Collections hub.
 */

private val dim = JellytipPalette.Vanilla.copy(alpha = 0.6f)

private val GROUPS = listOf("Finish a series", "Start with a director", "From a list")

sealed interface JourneyEntriesState {
    data object Loading : JourneyEntriesState

    data object Failed : JourneyEntriesState

    data class Loaded(
        val entries: List<JourneyEntry>,
    ) : JourneyEntriesState
}

data class JourneysState(
    /** The journeys page: null = loading. */
    val groups: JourneyGroups? = null,
    val groupsFailed: Boolean = false,
    /** One journey: null = loading. */
    val result: JourneyResult? = null,
    val entries: JourneyEntriesState = JourneyEntriesState.Loading,
    /** A Retry is out: the error UI (and its focused button) stays until the answer arrives. */
    val retrying: Boolean = false,
)

@HiltViewModel
class JellytipJourneysViewModel
    @Inject
    constructor(
        private val service: JellytipJourneysService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(JourneysState())
        val state: StateFlow<JourneysState> = _state
        private var groupsJob: Job? = null
        private var journeyJob: Job? = null
        private var entriesJob: Job? = null
        private var journeyId: String? = null

        fun loadGroups() {
            if (groupsJob?.isActive == true) return
            _state.update { it.copy(retrying = it.groupsFailed) }
            groupsJob =
                viewModelScope.launch {
                    val g = service.groups()
                    _state.update { it.copy(groups = g ?: JourneyGroups(emptyList(), emptyList(), emptyList()), groupsFailed = g == null, retrying = false) }
                }
        }

        fun loadJourney(
            id: String,
            retry: Boolean = false,
        ) {
            if (id == journeyId && !retry && _state.value.result != null) return
            journeyId = id
            journeyJob?.cancel()
            entriesJob?.cancel()
            // On Retry the error UI stays (its button keeps focus) until the answer arrives
            _state.update { if (retry) it.copy(retrying = true, entries = JourneyEntriesState.Loading) else it.copy(result = null, entries = JourneyEntriesState.Loading) }
            journeyJob =
                viewModelScope.launch {
                    val r = service.result(id)
                    _state.update { it.copy(result = r, retrying = false) }
                    if (r is JourneyResult.Ok) refreshEntries()
                }
        }

        /** Fresh watched state (on open, and on every resume: the page sits under the player). */
        fun refreshEntries(again: Boolean = false) {
            val def = (_state.value.result as? JourneyResult.Ok)?.def ?: return
            entriesJob?.cancel()
            entriesJob =
                viewModelScope.launch {
                    // The list (or the Failed line with its focused Retry) stays on screen while this runs
                    fetchEntries(def)
                    // Back from the player: its "stopped" report is sent without waiting, so ask once more a little later
                    if (again) {
                        kotlinx.coroutines.delay(AGAIN_MS)
                        fetchEntries(def)
                    }
                }
        }

        private suspend fun fetchEntries(def: JourneyDef) {
            val e =
                try {
                    JourneyEntriesState.Loaded(service.entries(def))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Journey progress failed")
                    if (_state.value.entries is JourneyEntriesState.Loaded) return else JourneyEntriesState.Failed
                }
            _state.update { it.copy(entries = e) }
        }

        fun back() = navigationManager.goBack()

        private companion object {
            const val AGAIN_MS = 1_500L
        }

        fun open(id: String) {
            JellytipTrailerControl.stop()
            navigationManager.navigateTo(Destination.JellytipJourney(id), "journeys card OK")
        }

        fun play(entry: JourneyEntry) {
            val uuid = entry.itemId?.let { runCatching { it.toUUID() }.getOrNull() } ?: return
            JellytipTrailerControl.stop()
            navigationManager.navigateTo(Destination.Playback(uuid, if (entry.inProgress) entry.positionTicks / 10_000 else 0L), "journey play next")
        }

        fun details(entry: JourneyEntry) {
            val uuid = entry.itemId?.let { runCatching { it.toUUID() }.getOrNull() } ?: return
            val kind = BaseItemKind.entries.firstOrNull { it.serialName == entry.type } ?: BaseItemKind.MOVIE
            navigationManager.navigateTo(Destination.MediaItem(uuid, kind), "journey film OK")
        }

        fun person(personId: String) {
            val uuid = runCatching { personId.toUUID() }.getOrNull() ?: return
            navigationManager.navigateTo(Destination.MediaItem(uuid, BaseItemKind.PERSON), "journey director films")
        }
    }

@Composable
fun JellytipJourneysPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipJourneysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadGroups() }
    val g = state.groups
    val first = remember { FocusRequester() }
    val rows = g?.let { listOf(it.franchise, it.director, it.list) }.orEmpty()
    val firstRow = rows.indexOfFirst { it.isNotEmpty() }
    LaunchedEffect(firstRow) {
        if (firstRow >= 0) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 24.dp, top = 20.dp)) {
        Text("Journeys", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
        when {
            g == null -> Text("Loading…", color = dim, fontFamily = JellytipFontFamily)
            g.isEmpty -> {
                Text(if (state.groupsFailed) "Journeys aren't available right now." else "No journeys yet.", color = dim, fontFamily = JellytipFontFamily)
                if (state.groupsFailed) {
                    val retry = remember { FocusRequester() }
                    Button(if (state.retrying) "Retrying…" else "Retry", Modifier.focusRequester(retry)) { viewModel.loadGroups() }
                    LaunchedEffect(Unit) { retry.tryRequestFocus() }
                }
            }
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize().focusRestorer(first)) {
                    rows.forEachIndexed { i, list ->
                        if (list.isEmpty()) return@forEachIndexed
                        item(key = "g$i") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(GROUPS[i], color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 24.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                                    items(list, key = { it.id }) { s ->
                                        JourneyCard(s, modifier = if (i == firstRow && s == list.first()) Modifier.focusRequester(first) else Modifier) { viewModel.open(s.id) }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun JourneyCard(
    s: JourneySummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.07f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier.width(240.dp).height(96.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            s.kicker?.let { Text(it.uppercase(), fontFamily = JellytipFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = JellytipPalette.Jelly) }
            Text(s.title, fontFamily = JellytipFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun JellytipJourneyPage(
    id: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipJourneysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(id) { viewModel.loadJourney(id) }
    // Back from the player: the watched ticks and Play next move
    LifecycleResumeEffect(id) {
        viewModel.refreshEntries(again = true)
        onPauseOrDispose { }
    }
    val focus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 24.dp, top = 20.dp, end = 24.dp)) {
        when (val r = state.result) {
            null -> Text("Loading…", color = dim, fontFamily = JellytipFontFamily)
            JourneyResult.Error -> {
                Text("Couldn't load this journey.", color = dim, fontFamily = JellytipFontFamily)
                Button(if (state.retrying) "Retrying…" else "Retry", Modifier.focusRequester(focus)) { if (!state.retrying) viewModel.loadJourney(id, retry = true) }
                LaunchedEffect(Unit) { focus.tryRequestFocus() }
            }
            is JourneyResult.Short -> {
                Text("Not enough of their films in your library yet.", color = dim, fontFamily = JellytipFontFamily)
                Button("See their films", Modifier.focusRequester(focus)) { viewModel.person(r.personId) }
                LaunchedEffect(Unit) { focus.tryRequestFocus() }
            }
            JourneyResult.None -> {
                Text("This journey isn't available.", color = dim, fontFamily = JellytipFontFamily)
                // Something focusable, so focus never falls to the drawer
                Button("Back", Modifier.focusRequester(focus)) { viewModel.back() }
                LaunchedEffect(Unit) { focus.tryRequestFocus() }
            }
            is JourneyResult.Ok -> JourneyView(id, r.def, state.entries, viewModel)
        }
    }
}

@Composable
private fun JourneyView(
    id: String,
    d: JourneyDef,
    entriesState: JourneyEntriesState,
    viewModel: JellytipJourneysViewModel,
) {
    val entries = (entriesState as? JourneyEntriesState.Loaded)?.entries
    val next = entries?.let { Journeys.nextUp(it) }
    val p = entries?.let { Journeys.progress(it) }
    val playFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    // Land on Play next; with nothing left to play, on the first film; after a failed load, on Retry
    LaunchedEffect(entries != null, next != null, entriesState is JourneyEntriesState.Failed) {
        withFrameNanos { }
        when {
            next != null -> playFocus.tryRequestFocus()
            entries != null -> listFocus.tryRequestFocus()
            entriesState is JourneyEntriesState.Failed -> retryFocus.tryRequestFocus()
        }
    }
    Text(Journeys.journeyTitle(d), color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
    if (p != null) {
        val pct = if (p.playable > 0) p.watched.toFloat() / p.playable else 0f
        Box(Modifier.width(320.dp).height(6.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(pct).background(JellytipPalette.Jelly, RoundedCornerShape(3.dp)))
        }
        Text(
            "${p.watched} of ${p.playable} watched${if (p.playable < p.total) " · ${p.total - p.playable} not in your library" else ""}",
            color = dim,
            fontFamily = JellytipFontFamily,
            fontSize = 13.sp,
        )
    }
    if (next != null) {
        Button("${if (next.second) "Resume" else "Play next"}: ${next.first.title}", Modifier.focusRequester(playFocus), primary = true) { viewModel.play(next.first) }
    }
    when {
        entries != null && next == null && p != null && p.playable > 0 -> Text("All watched.", color = dim, fontFamily = JellytipFontFamily)
        entriesState is JourneyEntriesState.Loading -> Text("Loading your progress…", color = dim, fontFamily = JellytipFontFamily)
        entriesState is JourneyEntriesState.Failed ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Couldn't load your progress.", color = dim, fontFamily = JellytipFontFamily)
                Button("Retry", Modifier.focusRequester(retryFocus)) { viewModel.refreshEntries() }
            }
    }
    if (entries != null) {
        val firstPlayable = entries.indexOfFirst { it.playable }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
            modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(listFocus),
        ) {
            items(entries, key = { "$id:${it.n}" }) { e ->
                FilmRow(e, isNext = next?.first?.n == e.n, modifier = if (entries.indexOf(e) == firstPlayable) Modifier.focusRequester(listFocus) else Modifier) { viewModel.details(e) }
            }
        }
    }
}

@Composable
private fun FilmRow(
    e: JourneyEntry,
    isNext: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val title = e.title + (e.year?.let { " ($it)" } ?: "")
    val stateText = if (!e.playable) "Not in your library" else if (e.watched) "✓ Watched" else if (e.inProgress) "In progress" else if (isNext) "Next up" else ""
    val content: @Composable (Color) -> Unit = { c ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text("${e.n}", color = c.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 14.sp, modifier = Modifier.width(32.dp))
            Text(title, color = c, fontFamily = JellytipFontFamily, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(stateText, color = c.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
        }
    }
    if (!e.playable) {
        // Missing films: greyed and inert, but focusable so the list can scroll to them (a LazyColumn scrolls by focus)
        Surface(
            onClick = {},
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.03f),
                    focusedContainerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = JellytipPalette.Vanilla.copy(alpha = 0.4f),
                    focusedContentColor = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                ),
            modifier = Modifier.fillMaxWidth(),
        ) { content(androidx.tv.material3.LocalContentColor.current) }
        return
    }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (isNext) JellytipPalette.Jelly.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        content(androidx.tv.material3.LocalContentColor.current)
    }
}

@Composable
private fun Button(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) JellytipPalette.Jelly else Color.White.copy(alpha = 0.10f),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier,
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}
