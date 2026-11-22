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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.github.damontecres.wholphin.jellytip.jellytipFocusRoom
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

        /** An IPTV film was opened from the path: on return the journey is rebuilt (IPTV watched state rides in it). */
        private var iptvOpened = false

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
            var def = (_state.value.result as? JourneyResult.Ok)?.def ?: return
            val rebuild = iptvOpened
            iptvOpened = false
            entriesJob?.cancel()
            entriesJob =
                viewModelScope.launch {
                    // Back from an IPTV film: its watched / progress state lives in the journey itself, so build it again
                    if (rebuild) {
                        journeyId?.let { id ->
                            service.forget(id)
                            (service.result(id) as? JourneyResult.Ok)?.let { ok ->
                                _state.update { it.copy(result = ok) }
                                def = ok.def
                            }
                        }
                    }
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

        /** What was opened from this page, so Back lands on it again (the page is rebuilt on return). */
        var lastJourney: String? = null
            private set
        var lastFilm: Int? = null
            private set

        fun open(id: String) {
            lastJourney = id
            JellytipTrailerControl.stop()
            navigationManager.navigateTo(Destination.JellytipJourney(id), "journeys card OK")
        }

        fun play(entry: JourneyEntry) {
            // On IPTV only: its details page (its own Play / Resume there; an IPTV title the library has opens the library page)
            entry.vod?.let {
                lastFilm = entry.n
                iptvOpened = true
                navigationManager.navigateTo(com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen.detailsFor(it), "journey iptv film")
                return
            }
            val uuid = entry.itemId?.let { runCatching { it.toUUID() }.getOrNull() } ?: return
            lastFilm = null // back from the player: Play next (which has moved on) takes focus
            JellytipTrailerControl.stop()
            navigationManager.navigateTo(Destination.Playback(uuid, if (entry.inProgress) entry.positionTicks / 10_000 else 0L), "journey play next")
        }

        fun details(entry: JourneyEntry) {
            entry.vod?.let {
                lastFilm = entry.n
                iptvOpened = true
                navigationManager.navigateTo(com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen.detailsFor(it), "journey iptv film OK")
                return
            }
            val uuid = entry.itemId?.let { runCatching { it.toUUID() }.getOrNull() } ?: return
            lastFilm = entry.n
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
    val restore = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val rows = g?.let { listOf(it.franchise, it.director, it.list) }.orEmpty()
    val firstRow = rows.indexOfFirst { it.isNotEmpty() }
    // Back from a journey: its card (group row scrolled in, card scrolled into its row), else the first card
    val back = viewModel.lastJourney?.let { id -> rows.indexOfFirst { l -> l.any { it.id == id } }.takeIf { it >= 0 }?.let { gi -> gi to rows[gi].indexOfFirst { it.id == id } } }
    LaunchedEffect(firstRow) {
        if (firstRow >= 0) {
            if (back != null) listState.scrollToItem(rows.take(back.first).count { it.isNotEmpty() })
            withFrameNanos { }
            if (back == null || !restore.tryRequestFocus()) first.tryRequestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(com.github.damontecres.wholphin.jellytip.JellytipLayout.TitleGap), modifier = modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageStart, top = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageTop)) {
        Text("Journeys", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageTitle)
        when {
            g == null -> com.github.damontecres.wholphin.jellytip.JellytipSkeleton(tileW = 240.dp, tileH = 96.dp, rows = 2, perRow = 3)
            g.isEmpty -> {
                Text(if (state.groupsFailed) "Journeys aren't available right now." else "No journeys yet.", color = dim, fontFamily = JellytipFontFamily)
                if (state.groupsFailed) {
                    val retry = remember { FocusRequester() }
                    Button(if (state.retrying) "Retrying…" else "Retry", Modifier.focusRequester(retry)) { viewModel.loadGroups() }
                    LaunchedEffect(Unit) { retry.tryRequestFocus() }
                }
            }
            else ->
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize().focusRestorer(first)) {
                    rows.forEachIndexed { i, list ->
                        if (list.isEmpty()) return@forEachIndexed
                        item(key = "g$i") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(GROUPS[i], color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                                val rowState = rememberLazyListState(initialFirstVisibleItemIndex = if (back?.first == i) back.second else 0)
                                LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = com.github.damontecres.wholphin.jellytip.JellytipLayout.FocusRoom, end = 24.dp), modifier = Modifier.jellytipFocusRoom().focusGroup().focusRestorer()) {
                                    items(list, key = { it.id }) { s ->
                                        // both may sit on one card: `first` must stay attached for the column's focusRestorer
                                        val m =
                                            Modifier
                                                .then(if (s.id == viewModel.lastJourney && i == back?.first) Modifier.focusRequester(restore) else Modifier)
                                                .then(if (i == firstRow && s == list.first()) Modifier.focusRequester(first) else Modifier)
                                        JourneyCard(s, modifier = m) { viewModel.open(s.id) }
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
internal fun JourneyCard(
    s: JourneySummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.07f),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        modifier = modifier.width(240.dp).height(96.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            s.kicker?.let { Text(it.uppercase(), fontFamily = JellytipFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = JellytipPalette.Jelly) }
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
    Column(verticalArrangement = Arrangement.spacedBy(com.github.damontecres.wholphin.jellytip.JellytipLayout.TitleGap), modifier = modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageStart, top = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageTop, end = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageEnd)) {
        when (val r = state.result) {
            null -> com.github.damontecres.wholphin.jellytip.JellytipSkeletonList(lines = 6)
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
    val filmFocus = remember { FocusRequester() }
    val filmList = rememberLazyListState()
    val backTo = entries?.indexOfFirst { it.n == viewModel.lastFilm && it.playable }?.takeIf { it >= 0 }
    // Back from a film's page: that film; else Play next; with nothing left to play, the first film; after a failed load, Retry
    LaunchedEffect(entries != null, next != null, entriesState is JourneyEntriesState.Failed) {
        if (backTo != null) filmList.scrollToItem(backTo)
        withFrameNanos { }
        when {
            backTo != null && filmFocus.tryRequestFocus() -> {}
            next != null -> playFocus.tryRequestFocus()
            entries != null -> listFocus.tryRequestFocus()
            entriesState is JourneyEntriesState.Failed -> retryFocus.tryRequestFocus()
        }
    }
    Text(Journeys.journeyTitle(d), color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageTitle)
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
        entriesState is JourneyEntriesState.Loading -> com.github.damontecres.wholphin.jellytip.JellytipSkeletonList(lines = 6)
        entriesState is JourneyEntriesState.Failed ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Couldn't load your progress.", color = dim, fontFamily = JellytipFontFamily)
                Button("Retry", Modifier.focusRequester(retryFocus)) { viewModel.refreshEntries() }
            }
    }
    if (entries != null) {
        // Nothing playable: the first (inert) row still takes focus, so it never falls to the drawer
        val firstPlayable = entries.indexOfFirst { it.playable }.coerceAtLeast(0)
        LazyColumn(
            state = filmList,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
            modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(listFocus),
        ) {
            items(entries, key = { "$id:${it.n}" }) { e ->
                val ix = entries.indexOf(e)
                val m = Modifier.then(if (ix == firstPlayable) Modifier.focusRequester(listFocus) else Modifier).then(if (ix == backTo) Modifier.focusRequester(filmFocus) else Modifier)
                FilmRow(e, isNext = next?.first?.n == e.n, modifier = m) { viewModel.details(e) }
            }
        }
    }
}

@Composable
internal fun FilmRow(
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
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (isNext) JellytipPalette.Jelly.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
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
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) JellytipPalette.Jelly else Color.White.copy(alpha = 0.10f),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = Color.White,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        modifier = modifier,
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}
