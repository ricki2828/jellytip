package com.github.damontecres.wholphin.jellytip.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.jellytip.specials.Caption
import com.github.damontecres.wholphin.jellytip.specials.SpecialCard
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import javax.inject.Inject

/** One Discover row on the browse page; [entries] null = still loading (the row keeps its height). */
data class DiscoverBrowseRow(
    val key: String,
    val title: String,
    val entries: List<JellytipDiscoverEntry>?,
)

data class DiscoverBrowseState(
    val media: String = "movie",
    val genres: List<Pair<Int, String>> = emptyList(),
    /** Which media [genres] belong to; the old chips stay until the new ones arrive (no layout jump). */
    val genresMedia: String? = null,
    val genre: Int? = null,
    val query: String = "",
    val rows: List<DiscoverBrowseRow> = emptyList(),
    /** False when the companion refused (not an admin) or is unreachable. */
    val available: Boolean = true,
)

/** Admin-only Discover (sidebar): Trending / Popular / Upcoming / New releases for films or TV, genres, search. */
@HiltViewModel
class JellytipDiscoverBrowseViewModel
    @Inject
    constructor(
        private val service: JellytipDiscoverService,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DiscoverBrowseState())
        val state: StateFlow<DiscoverBrowseState> = _state
        private var job: Job? = null
        private var searchJob: Job? = null

        init {
            reload()
        }

        fun setMedia(media: String) {
            if (media == _state.value.media) return
            _state.update { it.copy(media = media, genre = null) }
            reload()
        }

        fun setGenre(genre: Int?) {
            if (genre == _state.value.genre) return
            _state.update { it.copy(genre = genre) }
            reload()
        }

        fun setQuery(q: String) {
            _state.update { it.copy(query = q) }
            searchJob?.cancel()
            searchJob =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    reload()
                }
        }

        private fun reload() {
            job?.cancel()
            val s = _state.value
            val rows =
                when {
                    s.query.trim().length >= 2 -> listOf("search" to "Results for “${s.query.trim()}”")
                    s.genre != null -> listOf("genre" to (s.genres.firstOrNull { it.first == s.genre }?.second ?: "Genre"))
                    else -> SECTIONS
                }
            _state.update { st -> st.copy(rows = rows.map { (k, t) -> DiscoverBrowseRow("${s.media}:$k:${s.genre}:${s.query}", t, null) }) }
            job =
                viewModelScope.launch {
                    if (_state.value.genresMedia != s.media) {
                        val g = service.genres(s.media)
                        if (g.isNotEmpty()) _state.update { it.copy(genres = g, genresMedia = s.media) }
                    }
                    var any = false
                    rows.forEachIndexed { i, (section, _) ->
                        val entries =
                            when (section) {
                                "search" -> service.search(s.query)
                                "genre" -> service.section("genre", s.media, s.genre)
                                else -> service.section(section, s.media)
                            }
                        if (entries != null) any = true
                        _state.update { st -> st.copy(rows = st.rows.mapIndexed { j, r -> if (j == i) r.copy(entries = entries.orEmpty()) else r }) }
                    }
                    _state.update { it.copy(available = any) }
                }
        }

        companion object {
            private const val SEARCH_DEBOUNCE_MS = 500L
            private val SECTIONS =
                listOf("trending" to "Trending", "popular" to "Popular", "upcoming" to "Upcoming", "new" to "New releases")
        }
    }

private val TILE_W = 120.dp
private val TILE_H = 180.dp

@Composable
fun JellytipDiscoverBrowsePage(
    modifier: Modifier = Modifier,
    viewModel: JellytipDiscoverBrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sizes = rememberJellytipSizes()
    val listState = rememberLazyListState()
    // Last focused tile, restored when coming back from a title's page
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val firstTile = remember { FocusRequester() }
    // Focus is placed once, when the page first has tiles (or on return, the tile last opened). Reloads after a
    // Films/TV, genre or search change never move it: it stays on the chip or search box that caused them.
    var placed by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    val loaded = state.rows.any { !it.entries.isNullOrEmpty() }
    LaunchedEffect(loaded) {
        if (!loaded || placed) return@LaunchedEffect
        placed = true
        if (headerFocused) return@LaunchedEffect
        withFrameNanos { }
        val back = lastFocused?.let { requesters[it] }
        if (back != null && back.tryRequestFocus()) {
            JellytipTrace.focus("discover: restore $lastFocused (page shown again)")
        } else if (firstTile.tryRequestFocus()) {
            JellytipTrace.focus("discover: first tile (page loaded)")
        }
    }
    val open: (JellytipDiscoverEntry) -> Unit = { e ->
        val id = e.jellyfinId
        if (e.inLibrary && id != null) {
            viewModel.navigationManager.navigateTo(
                Destination.MediaItem(id, if (e.title.isTv) BaseItemKind.SERIES else BaseItemKind.MOVIE),
                "discover OK (in library)",
            )
        } else {
            viewModel.navigationManager.navigateTo(Destination.JellytipDiscover(e.title), "discover OK")
        }
    }
    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, top = 20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.onFocusChanged { headerFocused = it.hasFocus },
            ) {
                Text(
                    text = "Discover",
                    color = JellytipPalette.Vanilla,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = sizes.sp(JellytipHomeTokens.TITLE_PX),
                )
                Chip("Films", state.media == "movie") { viewModel.setMedia("movie") }
                Chip("TV", state.media == "tv") { viewModel.setMedia("tv") }
                var active by remember { mutableStateOf(false) }
                val keyboard = LocalSoftwareKeyboardController.current
                SearchEditTextBox(
                    value = state.query,
                    onValueChange = {
                        active = true
                        viewModel.setQuery(it)
                    },
                    onSearchClick = { viewModel.setQuery(state.query) },
                    readOnly = !active,
                    modifier =
                        Modifier
                            .width(360.dp)
                            .onFocusChanged { if (!it.isFocused) active = false }
                            .onPreviewKeyEvent { event ->
                                // As on the Search page: OK on the box starts typing (opens the keyboard)
                                val ok = event.key == Key.DirectionCenter || event.key == Key.Enter
                                if (event.type == KeyEventType.KeyUp && ok && !active) {
                                    active = true
                                    keyboard?.show()
                                    true
                                } else {
                                    false
                                }
                            },
                )
            }
            if (state.genres.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 2.dp),
                    modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer().onFocusChanged { headerFocused = it.hasFocus },
                ) {
                    item { Chip("All genres", state.genre == null) { viewModel.setGenre(null) } }
                    items(state.genres, key = { it.first }) { (id, name) -> Chip(name, state.genre == id) { viewModel.setGenre(id) } }
                }
            }
            if (!state.available && state.rows.all { it.entries != null }) {
                Caption("Discover is not available right now (admins only).", alpha = 0.8f, px = JellytipHomeTokens.TEXT_PX)
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                state.rows.forEachIndexed { rowIndex, row ->
                    item(key = row.key) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ItemRowTitle(row.title)
                            val entries = row.entries
                            if (entries == null) {
                                Box(Modifier.height(TILE_H + 40.dp))
                            } else if (entries.isEmpty()) {
                                Caption("Nothing here right now.", alpha = 0.6f, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(),
                                ) {
                                    items(entries, key = { it.title.key }) { e ->
                                        val key = "${row.key}|${e.title.key}"
                                        val req = requesters.getOrPut(key) { FocusRequester() }
                                        val first = rowIndex == 0 && e === entries.first()
                                        DiscoverBrowseTile(
                                            entry = e,
                                            onClick = { open(e) },
                                            modifier =
                                                Modifier
                                                    .focusRequester(req)
                                                    .then(if (first) Modifier.focusRequester(firstTile) else Modifier)
                                                    .onFocusChanged { if (it.isFocused) lastFocused = key },
                                        )
                                    }
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
private fun DiscoverBrowseTile(
    entry: JellytipDiscoverEntry,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val t = entry.title
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(TILE_W)) {
        SpecialCard(width = TILE_W, height = TILE_H, onClick = onClick, onLongClick = onClick, modifier = modifier) {
            if (t.posterUrl != null) {
                AsyncImage(model = t.posterUrl, contentDescription = t.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Caption(t.title, alpha = 0.9f, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            entry.badge?.let { b ->
                Text(
                    text = b,
                    color = Color.White,
                    fontFamily = JellytipFontFamily,
                    fontSize = rememberJellytipSizes().sp(15),
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .background(
                                if (entry.inLibrary) Color.Black.copy(alpha = 0.7f) else JellytipPalette.Jelly.copy(alpha = 0.85f),
                                RoundedCornerShape(4.dp),
                            ).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = t.title + (t.year?.let { " ($it)" } ?: ""),
            color = JellytipPalette.Vanilla.copy(alpha = 0.75f),
            fontFamily = JellytipFontFamily,
            fontSize = rememberJellytipSizes().sp(16),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val sizes = rememberJellytipSizes()
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors =
            androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = if (selected) JellytipPalette.Vanilla.copy(alpha = 0.18f) else Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
    ) {
        Text(
            text = label,
            fontFamily = JellytipFontFamily,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = sizes.sp(JellytipHomeTokens.CARD_SUBTITLE_PX),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
}
