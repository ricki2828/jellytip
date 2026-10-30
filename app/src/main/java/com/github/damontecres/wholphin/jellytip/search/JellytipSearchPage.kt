package com.github.damontecres.wholphin.jellytip.search

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRowView
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
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
import org.jellyfin.sdk.model.api.ImageType
import javax.inject.Inject

data class JellytipSearchState(
    val query: String = "",
    val answer: SearchAnswer? = null,
    val searching: Boolean = false,
    val suggest: SearchSuggest? = null,
    /** null = asking the server; false = no 10x search (404): the app's own page is used. */
    val available: Boolean? = null,
    val failed: Boolean = false,
)

@HiltViewModel
class JellytipSearchViewModel
    @Inject
    constructor(
        val service: JellytipSearchService,
        val voiceInputManager: VoiceInputManager,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(JellytipSearchState(available = service.available))
        val state: StateFlow<JellytipSearchState> = _state
        private var job: Job? = null
        private var started = false

        fun start(initial: String) {
            if (started) return
            started = true
            viewModelScope.launch {
                val s = service.suggest()
                _state.update { it.copy(suggest = s ?: it.suggest, available = service.available ?: (s != null)) }
                if (initial.isNotBlank() && service.available == true) setQuery(initial, immediate = true)
            }
        }

        fun setQuery(
            q: String,
            immediate: Boolean = false,
        ) {
            _state.update { it.copy(query = q) }
            job?.cancel()
            if (q.trim().length < 2) {
                _state.update { it.copy(answer = null, searching = false, failed = false) }
                return
            }
            job =
                viewModelScope.launch {
                    if (!immediate) delay(DEBOUNCE_MS)
                    _state.update { it.copy(searching = true) }
                    val a = service.search(q.trim())
                    // A newer query took over while this one was out: drop the stale answer
                    if (_state.value.query.trim() != q.trim()) return@launch
                    if (service.available == false) {
                        _state.update { it.copy(available = false) }
                        return@launch
                    }
                    _state.update { it.copy(answer = a ?: it.answer, searching = false, failed = a == null) }
                    JellytipTrace.log("search", "q='${q.trim()}' groups=${a?.groups?.map { "${it.type}:${it.total}" }} pending=${a?.pending}")
                    // Discover (admins) can come a moment later: ask once more for just that group
                    if (a != null && a.pending.isNotEmpty()) {
                        delay(PENDING_MS)
                        val more = service.search(q.trim(), types = a.pending.joinToString(",")) ?: return@launch
                        _state.update { st ->
                            val cur = st.answer ?: return@update st
                            if (cur.q != a.q) return@update st
                            val known = cur.groups.map { it.type }.toSet()
                            st.copy(answer = cur.copy(groups = cur.groups + more.groups.filter { it.type !in known }, pending = emptyList()))
                        }
                    }
                }
        }

        fun removeChip(chip: SearchChip) {
            val a = _state.value.answer ?: return
            val q = SearchRules.removeChip(_state.value.query, a.text, chip, a.chips)
            JellytipTrace.log("search", "chip off ${chip.kind}:${chip.label} -> '$q'")
            setQuery(q, immediate = true)
        }

        /**
         * A ranked result was opened: log its 1-based position in `results` ([match] picks the entry). Nothing when
         * the answer has no `results` (older server) or the entry isn't in it.
         */
        fun clicked(match: (SearchResult) -> Boolean) {
            val a = _state.value.answer ?: return
            val results = a.results ?: return
            val i = results.indexOfFirst(match)
            if (i < 0) return
            service.logClick(a.q.ifBlank { _state.value.query.trim() }, results[i], i + 1)
        }

        /** By any id the rows know (library id with or without dashes, IPTV id, person id, collection or TMDB id). */
        fun clickedId(id: String) {
            val k = id.replace("-", "").lowercase()
            clicked { r ->
                listOfNotNull(r.id?.toString(), r.iptvId, r.personId?.toString(), r.collectionId?.toString(), r.tmdbId?.toString())
                    .any { it.replace("-", "").lowercase() == k }
            }
        }

        /** "Client calls on result open." */
        fun opened(reason: String) {
            val q = _state.value.query.trim()
            if (q.length < 2) return
            viewModelScope.launch { service.recordRecent(q) }
            JellytipTrace.log("search", "open result ($reason) for '$q'")
        }

        fun clearRecent() {
            viewModelScope.launch {
                service.clearRecent()
                _state.update { it.copy(suggest = it.suggest?.copy(recent = emptyList())) }
            }
        }

        companion object {
            private const val DEBOUNCE_MS = 150L
            private const val PENDING_MS = 300L
        }
    }

/**
 * Search: the companion's 10x search when the server has it, else [fallback] (the app's own page). Voice: the mic
 * button, the remote's Search key, and Google TV's mic (ACTION_SEARCH arrives as [initialQuery]).
 */
@Composable
fun JellytipSearchPage(
    initialQuery: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipSearchViewModel = hiltViewModel(),
    fallback: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.start(initialQuery) }
    when (state.available) {
        false -> fallback()
        null -> Box(modifier.fillMaxSize().background(JellytipPalette.Night))
        true -> SearchContent(state, viewModel, modifier)
    }
}

@Composable
private fun SearchContent(
    state: JellytipSearchState,
    viewModel: JellytipSearchViewModel,
    modifier: Modifier,
) {
    val boxFocus = remember { FocusRequester() }
    var typing by remember { mutableStateOf(false) }
    var resultsTouched by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { boxFocus.tryRequestFocus() }
    DisposableEffect(Unit) {
        onDispose { if (resultsTouched) viewModel.opened("left page from results") }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxSize()
                .background(JellytipPalette.Night)
                .padding(start = 24.dp, top = 20.dp, end = 16.dp)
                .onPreviewKeyEvent { e ->
                    // The remote's Search / mic key: listen right away
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.Search || e.key == Key.VoiceAssist) && viewModel.voiceInputManager.isAvailable) {
                        JellytipTrace.log("search", "voice key")
                        viewModel.voiceInputManager.startListening()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoiceSearchButton(
                onSpeechResult = { spoken ->
                    JellytipTrace.log("search", "voice result '$spoken'")
                    viewModel.setQuery(spoken, immediate = true)
                },
                voiceInputManager = viewModel.voiceInputManager,
            )
            SearchEditTextBox(
                value = state.query,
                onValueChange = {
                    typing = true
                    viewModel.setQuery(it)
                },
                onSearchClick = { viewModel.setQuery(state.query, immediate = true) },
                readOnly = !typing,
                modifier =
                    Modifier
                        .width(480.dp)
                        .focusRequester(boxFocus)
                        .onFocusChanged { if (!it.isFocused) typing = false }
                        .onPreviewKeyEvent { event ->
                            val ok = event.key == Key.DirectionCenter || event.key == Key.Enter
                            if (event.type == KeyEventType.KeyUp && ok && !typing) {
                                typing = true
                                keyboard?.show()
                                true
                            } else {
                                false
                            }
                        },
            )
            if (state.searching) {
                Text("Searching…", color = JellytipPalette.Vanilla.copy(alpha = 0.5f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
            }
        }
        val answer = state.answer?.takeIf { state.query.trim().length >= 2 }
        if (answer != null && answer.chips.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                items(answer.chips, key = { "${it.kind}:${it.value}" }) { chip ->
                    Chip("${chip.label}  ✕") { viewModel.removeChip(chip) }
                }
            }
        }
        when {
            answer == null -> Suggestions(state, viewModel)
            answer.isEmpty -> Text("Nothing found for “${state.query.trim()}”.", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, modifier = Modifier.padding(8.dp))
            else ->
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    modifier = Modifier.fillMaxSize().focusRestorer().onFocusChanged { if (it.hasFocus) resultsTouched = true },
                ) {
                    if (answer.results != null) {
                        // Newer server: the top hit, one ranked row of films and shows, then people, collections, not available
                        answer.top?.let { top -> item(key = "${answer.q}:top") { TopHit(top, viewModel) } }
                        answer.rankedGroups.forEach { g -> item(key = "${answer.q}:r:${g.type}") { ResultGroup(answer.q, g, viewModel) } }
                    } else {
                        SearchRules.displayOrder(answer.groups).forEach { g -> item(key = "${answer.q}:${g.type}") { ResultGroup(answer.q, g, viewModel) } }
                    }
                }
        }
    }
}

@Composable
private fun Suggestions(
    state: JellytipSearchState,
    viewModel: JellytipSearchViewModel,
) {
    val s = state.suggest ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.recent.isNotEmpty()) {
            ItemRowTitle("Recent")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                items(s.recent) { q -> Chip(q) { viewModel.setQuery(q, immediate = true) } }
                item { Chip("Clear") { viewModel.clearRecent() } }
            }
        }
        if (s.suggested.isNotEmpty()) {
            ItemRowTitle("Try")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                items(s.suggested) { q -> Chip(q) { viewModel.setQuery(q, immediate = true) } }
            }
        }
    }
}

private fun groupTitle(g: SearchGroup): String {
    val name =
        when (g.type) {
            "films" -> "Films"
            "shows" -> "Shows"
            "people" -> "People"
            "collections" -> "Collections"
            "iptv" -> "On your IPTV"
            "results" -> "Results"
            "discover" -> "Not in the library"
            "lists" -> "Letterboxd lists"
            else -> g.type
        }
    return name
}

@Composable
private fun ResultGroup(
    q: String,
    g: SearchGroup,
    viewModel: JellytipSearchViewModel,
) {
    when (g.type) {
        "films", "shows" ->
            if (g.ids.isNotEmpty()) {
                // Library titles draw exactly like a server row (titled art, badges, OK = details)
                val row =
                    remember(q, g.type, g.ids) {
                        JellytipServerRow(
                            id = "search:${g.type}:${q.hashCode()}",
                            source = "search",
                            family = "search",
                            title = groupTitle(g),
                            layout = "standard",
                            mediaType = if (g.type == "shows") "tv" else "movie",
                            itemIds = g.ids,
                            collection = null,
                        )
                    }
                JellytipServerRowView(
                    row = row,
                    onFocusItem = { _, _ -> },
                    onLongClickItem = null,
                    focusModifier = Modifier.focusGroup(),
                )
            }

        "people" -> PeopleRow(g, viewModel)

        "results" -> {
            // Films and shows in rank order, library and IPTV mixed (IPTV tiles carry the cue)
            val row =
                remember(q, g.ids, g.iptv, g.order) {
                    JellytipServerRow(
                        id = "search:results:${q.hashCode()}",
                        source = "search",
                        family = "search",
                        title = groupTitle(g),
                        layout = "standard",
                        mediaType = "movie",
                        itemIds = g.ids,
                        collection = null,
                        iptvItems = g.iptv.map { it.toVodItem() },
                        order = g.order,
                    )
                }
            JellytipServerRowView(
                row = row,
                onFocusItem = { _, _ -> },
                onLongClickItem = null,
                focusModifier = Modifier.focusGroup(),
                onOpened = { id -> viewModel.clickedId(id) },
            )
        }

        else ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ItemRowTitle(groupTitle(g))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(),
                ) {
                    items(g.collections, key = { "c${it.tmdbId}" }) { c ->
                        TextCard(c.name, "${c.inLibrary} of ${c.total} in the library") {
                            viewModel.opened("collection")
                            viewModel.clicked { it.kind == "collection" && it.collectionId == c.tmdbId }
                            viewModel.navigationManager.navigateTo(Destination.JellytipCollection(c.tmdbId, c.name), "search collection OK")
                        }
                    }
                    items(g.lists, key = { "l${it.slug}" }) { l ->
                        TextCard(l.name, "${l.count} films · Letterboxd") {
                            viewModel.opened("list")
                            viewModel.navigationManager.navigateTo(Destination.JellytipList(l.slug, l.name), "search list OK")
                        }
                    }
                    items(g.iptv, key = { "i${it.id}" }) { i ->
                        PosterCard(i.poster, listOfNotNull(i.name, i.year?.toString()).joinToString(" · ")) {
                            viewModel.opened("iptv")
                            viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(i.id, i.name), "search iptv OK")
                        }
                    }
                    items(g.discover, key = { "d${it.key}" }) { t ->
                        PosterCard(t.posterUrl, listOfNotNull(t.title, t.year?.toString()).joinToString(" · ")) {
                            viewModel.opened("discover")
                            viewModel.clicked { it.source == "discover" && it.tmdbId == t.tmdbId }
                            viewModel.navigationManager.navigateTo(Destination.JellytipDiscover(t), "search discover OK")
                        }
                    }
                }
            }
    }
}

@Composable
private fun PeopleRow(
    g: SearchGroup,
    viewModel: JellytipSearchViewModel,
) {
    val images = LocalImageUrlService.current
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ItemRowTitle(groupTitle(g))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(),
        ) {
            items(g.people, key = { it.personId.toString() }) { p ->
                val url =
                    remember(p.personId, p.imageTag) {
                        with(density) {
                            images.getItemImageUrl(itemId = p.personId, imageType = ImageType.PRIMARY, fillWidth = 120.dp.roundToPx(), fillHeight = 180.dp.roundToPx(), tag = p.imageTag)
                        }
                    }
                PosterCard(url, "${p.name}" + if (p.filmCount > 0) " · ${p.filmCount}" else "") {
                    viewModel.opened("person")
                    viewModel.clicked { it.personId == p.personId }
                    viewModel.navigationManager.navigateTo(Destination.MediaItem(p.personId, BaseItemKind.PERSON), "search person OK")
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    url: String?,
    caption: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(120.dp)) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF141414)),
            border =
                ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp)),
                ),
            modifier = Modifier.size(120.dp, 180.dp),
        ) {
            var failed by remember(url) { mutableStateOf(false) }
            if (url != null && !failed) {
                AsyncImage(model = url, contentDescription = caption, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
            } else {
                Text(caption, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
        }
        Text(caption, color = JellytipPalette.Vanilla.copy(alpha = 0.75f), fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TextCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.07f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = Modifier.size(240.dp, 135.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(title, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Chip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = JellytipPalette.Vanilla.copy(alpha = 0.12f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
    }
}

private fun SearchIptv.toVodItem() =
    com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem(
        id = id,
        kind = kind,
        tmdbId = tmdbId,
        title = name,
        year = year,
        quality = quality,
        poster = poster,
        backdrop = backdrop,
        overview = "",
        rating = rating,
        providerName = null,
        progress = null,
        tech = tech,
    )

/** The best hit as a wide card: its art, title, facts (kind · year · seasons · rating · where), OK opens it. */
@Composable
private fun TopHit(
    r: SearchResult,
    viewModel: JellytipSearchViewModel,
) {
    val images = LocalImageUrlService.current
    val density = LocalDensity.current
    val image =
        remember(r.key, density) {
            with(density) {
                val w = TOP_W.roundToPx()
                val h = TOP_H.roundToPx()
                when {
                    r.kind == "person" && r.personId != null -> images.getItemImageUrl(itemId = r.personId, imageType = ImageType.PRIMARY, fillWidth = h * 2 / 3, fillHeight = h, tag = r.imageTag)
                    r.source == "library" && r.id != null -> images.getItemImageUrl(itemId = r.id, imageType = ImageType.BACKDROP, fillWidth = w, fillHeight = h)
                    r.source == "discover" -> r.posterUrl
                    else -> r.backdrop ?: r.poster
                }
            }
        }
    val facts =
        listOfNotNull(
            when (r.kind) {
                "series" -> "Series"
                "person" -> r.role?.replaceFirstChar { it.uppercase() } ?: "Person"
                "collection" -> "Collection"
                else -> "Film"
            },
            r.year?.toString(),
            r.seasons?.let { if (it == 1) "1 season" else "$it seasons" },
            r.filmCount.takeIf { r.kind == "person" && it > 0 }?.let { "$it films" },
            r.total.takeIf { r.kind == "collection" && it > 0 }?.let { "${r.inLibrary} of $it in the library" },
            r.rating?.let { String.format(java.util.Locale.US, "★ %.1f", it) },
            when (r.source) {
                "iptv" -> "via IPTV"
                "discover" -> "Not in the library"
                else -> null
            },
        ).joinToString("  ·  ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp)) {
        ItemRowTitle("Top hit")
        Surface(
            onClick = { openResult(r, viewModel) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF141414)),
            border =
                ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp)),
                ),
            modifier = Modifier.size(TOP_W, TOP_H),
        ) {
            var failed by remember(image) { mutableStateOf(false) }
            if (image != null && !failed) {
                AsyncImage(
                    model = image,
                    contentDescription = r.title,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = if (r.kind == "person") Modifier.align(Alignment.CenterEnd).size(TOP_H * 2f / 3f, TOP_H) else Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.85f),
                            0.7f to Color.Black.copy(alpha = 0.2f),
                        ),
                    ),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).width(TOP_W * 0.7f),
            ) {
                Text(r.title, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.source == "iptv") com.github.damontecres.wholphin.jellytip.iptv.IptvCornerMark(r.quality.firstOrNull()?.uppercase())
                    Text(facts, color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun openResult(
    r: SearchResult,
    viewModel: JellytipSearchViewModel,
) {
    viewModel.opened("top ${r.kind}")
    viewModel.clicked { it.key == r.key }
    val nav = viewModel.navigationManager
    when {
        r.kind == "person" && r.personId != null -> nav.navigateTo(Destination.MediaItem(r.personId, BaseItemKind.PERSON), "search top person")
        r.kind == "collection" && r.collectionId != null -> nav.navigateTo(Destination.JellytipCollection(r.collectionId, r.title), "search top collection")
        r.source == "library" && r.id != null ->
            nav.navigateTo(Destination.MediaItem(r.id, if (r.kind == "series") BaseItemKind.SERIES else BaseItemKind.MOVIE), "search top library")
        r.source == "iptv" && r.iptvId != null -> nav.navigateTo(Destination.JellytipIptvDetails(r.iptvId, r.title), "search top iptv")
        r.source == "discover" && r.tmdbId != null ->
            nav.navigateTo(
                Destination.JellytipDiscover(
                    com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle(
                        tmdbId = r.tmdbId,
                        mediaType = if (r.kind == "series") "tv" else "movie",
                        title = r.title,
                        year = r.year,
                        posterUrl = r.posterUrl,
                    ),
                ),
                "search top discover",
            )
    }
}

private val TOP_W = 520.dp
private val TOP_H = 190.dp
