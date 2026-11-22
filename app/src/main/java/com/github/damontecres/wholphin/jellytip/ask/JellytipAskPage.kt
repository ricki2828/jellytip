package com.github.damontecres.wholphin.jellytip.ask

import com.github.damontecres.wholphin.jellytip.cards.JellytipLibraryMenuHostOf
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen
import com.github.damontecres.wholphin.jellytip.cards.iptvRef
import com.github.damontecres.wholphin.jellytip.cards.libRef
import com.github.damontecres.wholphin.jellytip.cards.rememberJellytipLibraryMenu
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile
import org.json.JSONObject
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.specials.PosterCard
import com.github.damontecres.wholphin.jellytip.specials.SpecialTokens
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import timber.log.Timber
import javax.inject.Inject

/*
 * Ask (web #/ask, 69fc43f6 + f126b5d5): "something funny under 90 minutes" -> removable chips -> up to 24 library picks.
 * Deterministic (AskParse), no LLM, no companion route: one Jellyfin Items call per reading, like the web. Library only:
 * IPTV and requests are not searched here. Entry: "Not sure? Ask" on Search. "Narrow it down" (web askchips 3cc845f7) offers
 * refine chips drawn from what was found: people shared by the picks, genres to keep / leave out, decades, kind, length,
 * unwatched; a tapped one is a removable chip like the others.
 */

sealed interface AskResult {
    data object Idle : AskResult

    data object Loading : AskResult

    data object Error : AskResult

    data class Done(
        val picks: AskPicks<BaseItem>,
        /** The fetched titles that pass every check: what the refine chips are drawn from. */
        val kept: List<BaseItem> = emptyList(),
        /** The shown picks with their people (a second, small request; empty until it answers or when it fails). */
        val withPeople: List<BaseItem> = emptyList(),
        /** IPTV titles that fit (after the library picks; twins of a library pick dropped); empty until they answer. */
        val iptv: List<IptvVodItem> = emptyList(),
        /** What the IPTV half checked / couldn't apply; null = no IPTV searched. */
        val iptvNote: String? = null,
    ) : AskResult {
        /** One grid: the library picks, then the IPTV ones (appended, so nothing moves under focus when they arrive). */
        val tiles: List<AskTile> get() = picks.items.map { AskTile.Lib(it) } + iptv.map { AskTile.Vod(it) }
    }

/** A tile of Ask's grid: a library title or an IPTV one (source-neutral: same tile look, no source mark). */
sealed interface AskTile {
    val key: String

    data class Lib(
        val item: BaseItem,
    ) : AskTile {
        override val key get() = JellytipSourceMerge.libKey(item.id.toString())
    }

    data class Vod(
        val item: IptvVodItem,
    ) : AskTile {
        override val key get() = JellytipSourceMerge.iptvKey(item.id)
    }
}
}

data class AskState(
    val text: String = "",
    /** The text that was asked (the reading comes from this, not from what is being typed). */
    val asked: String = "",
    val reading: AskReading = AskReading(emptyList(), emptyList(), emptyList()),
    val removed: List<String> = emptyList(),
    /** Refine chips he tapped (after the reading's own; removable like them). */
    val added: List<AskChip> = emptyList(),
    val shuffle: Boolean = false,
    val result: AskResult = AskResult.Idle,
) {
    val chips: List<AskChip>
        get() {
            val read = AskParse.activeChips(reading, removed)
            return read + added.filter { a -> a.id !in removed && read.none { it.id == a.id } }
        }

    /** Ways to narrow what was found (empty until results are in). */
    val refine: List<AskChip>
        get() {
            val r = result as? AskResult.Done ?: return emptyList()
            return AskParse.refineOptions(r.kept, r.withPeople, chips) { it.data.askItem() }
        }
    val hasAsk: Boolean get() = asked.isNotEmpty()
}

@HiltViewModel
class JellytipAskViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        val voiceInputManager: VoiceInputManager,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(AskState())
        val state: StateFlow<AskState> = _state
        private var job: Job? = null

        /** Bumped per load: a late answer (people, IPTV) of an older load never lands on a newer one. */
        private var loadSeq = 0

        /** Fold a late answer into the current result, only if it is still [seq]'s. */
        private fun patch(
            seq: Int,
            f: (AskResult.Done) -> AskResult.Done,
        ) = _state.update { st -> (st.result as? AskResult.Done)?.takeIf { seq == loadSeq }?.let { st.copy(result = f(it)) } ?: st }

        fun setText(v: String) = _state.update { it.copy(text = v) }

        fun submit(v: String) {
            val q = v.trim()
            JellytipTrace.log("ask", "ask '$q'")
            _state.update { it.copy(text = v, asked = q, reading = AskParse.parse(q), removed = emptyList(), added = emptyList(), shuffle = false) }
            load()
        }

        fun remove(id: String) {
            _state.update { it.copy(removed = AskParse.removeChip(it.removed, id)) }
            load()
        }

        fun addRefine(c: AskChip) {
            JellytipTrace.log("ask", "refine + ${c.id}")
            // Something he removed earlier comes back as asked, not as a no-op
            _state.update { it.copy(added = it.added + c, removed = it.removed - c.id) }
            load()
        }

        fun putBack() {
            _state.update { it.copy(removed = emptyList()) }
            load()
        }

        fun shuffle() {
            if (_state.value.result == AskResult.Loading) return
            _state.update { it.copy(shuffle = true) }
            load()
        }

        fun retry() = load()

        private fun load() {
            job?.cancel()
            val s = _state.value
            val chips = s.chips
            if (chips.isEmpty()) {
                _state.update { it.copy(result = AskResult.Idle) }
                return
            }
            _state.update { it.copy(result = AskResult.Loading) }
            val user = serverRepository.currentUser?.id
            val seq = ++loadSeq
            job =
                viewModelScope.launch {
                    val result =
                        try {
                            // Provider ids: the TMDB id the IPTV half's twins are dropped by
                            val req = AskParse.buildQuery(chips, shuffle = s.shuffle, refine = true).toGetItemsRequest(user, extraFields = listOf(ItemFields.PROVIDER_IDS))
                            val page = api.itemsApi.getItems(req).content
                            val items = page.items.map { BaseItem(it, false) }
                            val picks = AskParse.pickFrom(items, page.totalRecordCount, chips, s.shuffle) { it.data.askItem() }
                            AskResult.Done(picks, kept = AskParse.filterItems(items, chips) { it.data.askItem() })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Ask query failed")
                            AskResult.Error
                        }
                    // A different person signed in while this was out: never show them another's picks
                    val stillMine = serverRepository.currentUser?.id == user
                    _state.update { it.copy(result = if (stillMine) result else AskResult.Idle) }
                    val done = result as? AskResult.Done ?: return@launch
                    if (!stillMine) return@launch
                    // IPTV alongside (source-neutral): its own request, never an error for the page
                    launch { loadIptv(seq, chips, s.shuffle, done.picks.items) }
                    // People for the shown picks only (director / actor refine chips); a failure just means none
                    if (done.picks.items.size < 2) return@launch
                    val withPeople =
                        try {
                            api.itemsApi
                                .getItems(
                                    GetItemsRequest(
                                        userId = user,
                                        ids = done.picks.items.map { it.id },
                                        fields = listOf(ItemFields.PEOPLE),
                                        limit = done.picks.items.size,
                                    ),
                                ).content.items
                                .map { BaseItem(it, false) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Ask people failed")
                            return@launch
                        }
                    patch(seq) { it.copy(withPeople = withPeople) }
                }
        }

        /**
         * IPTV titles for [chips] (GET v1/iptv/vod per kind, AskVod): only for people with IPTV; kept by AskVod.vodKeep, twins
         * of a library pick dropped (JellytipSourceMerge), shuffled when the library half is, at most PICKS. Failures, 403 /
         * 404 or no IPTV = library only (never the page's error).
         */
        private suspend fun loadIptv(
            seq: Int,
            chips: List<AskChip>,
            shuffle: Boolean,
            library: List<BaseItem>,
        ) {
            if (com.github.damontecres.wholphin.jellytip.iptv.JellytipIptvState.hasIptv.value != true) return
            val queries = AskVod.vodQueries(chips)
            val note = AskVod.vodNote(chips)
            if (queries.isEmpty()) {
                patch(seq) { it.copy(iptvNote = note) }
                return
            }
            val api = com.github.damontecres.wholphin.jellytip.JellytipApi.shared ?: return
            var fetched = 0
            val kept = mutableListOf<IptvVodItem>()
            try {
                val base = api.companionBase().also { IptvVodParse.imageBase = it }
                for (q in queries) {
                    val qs = q.params.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
                    val (code, body) = api.userCall("v1/iptv/vod?$qs") ?: continue
                    if (code != 200) continue
                    val items = IptvVodParse.items(JSONObject(body).optJSONArray("items"), base)
                    fetched += items.size
                    kept += items.filter { AskVod.vodKeep(it.vodView(), chips) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Ask IPTV failed")
                return
            }
            val pool = if (shuffle) kept.shuffled() else kept
            // One tile per title: an IPTV copy of a library pick goes (the library tile wins)
            val merged =
                JellytipSourceMerge
                    .merge(library.map { JellytipSourceMerge.Entry<IptvVodItem?>(null, lib = it.libRef()) } + pool.map { JellytipSourceMerge.Entry<IptvVodItem?>(it, iptv = it.iptvRef()) })
                    .entries
                    .filterNotNull()
            val shown = merged.take(AskParse.PICKS)
            JellytipTrace.log("ask", "iptv fetched=$fetched kept=${kept.size} shown=${shown.size}")
            val checked = "IPTV: ${shown.size} more from the top $fetched by rating" + (if (kept.size > shown.size) " (${kept.size} fit)" else "") + "."
            patch(seq) { it.copy(iptv = shown, iptvNote = listOfNotNull(checked.takeIf { fetched > 0 }, note).joinToString(" ").ifBlank { null }) }
        }

        /** The pick last opened: Back from its page lands on it again (the grid is rebuilt on return). */
        var lastOpened: String? = null
            private set

        fun openVod(item: IptvVodItem) {
            lastOpened = JellytipSourceMerge.iptvKey(item.id)
            JellytipTrailerControl.stop()
            // An IPTV title the library also has opens the library page (source-neutral)
            navigationManager.navigateTo(JellytipTitleOpen.detailsFor(item), "ask iptv pick OK")
        }

        fun open(item: BaseItem) {
            lastOpened = JellytipSourceMerge.libKey(item.id.toString())
            JellytipTrailerControl.stop()
            navigationManager.navigateTo(item.destination(), "ask pick OK")
        }
    }

private val dim = JellytipPalette.Vanilla.copy(alpha = 0.6f)

@Composable
fun JellytipAskPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipAskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val boxFocus = remember { FocusRequester() }
    var typing by remember { mutableStateOf(false) }
    // Focus to restore after the focused control goes away: chip index (-1 = none)
    var refocusChip by remember { mutableIntStateOf(-1) }
    val chips = state.chips
    val chipFocus = remember(chips.size) { List(chips.size + 1) { FocusRequester() } }
    val gridState = rememberLazyGridState()
    val restore = remember { FocusRequester() }
    // First composition (also on Back from a pick's page): the pick that was opened, else the text box
    // Hold OK on a library pick: the library title menu (IPTV tiles bring their own)
    val menu = rememberJellytipLibraryMenu()
    LaunchedEffect(Unit) {
        val tiles = (state.result as? AskResult.Done)?.tiles.orEmpty()
        val at = viewModel.lastOpened?.let { key -> tiles.indexOfFirst { it.key == key } } ?: -1
        if (at >= 0) {
            gridState.scrollToItem(at)
            withFrameNanos { }
            if (restore.tryRequestFocus()) return@LaunchedEffect
        }
        boxFocus.tryRequestFocus()
    }
    LaunchedEffect(chips.map { it.id }, state.asked, state.reading) {
        val i = refocusChip
        if (i < 0) return@LaunchedEffect
        refocusChip = -1
        withFrameNanos { }
        // The removed chip's neighbour, else "Put back" (last requester), else the text box
        val ok =
            when {
                chips.isNotEmpty() -> chipFocus[i.coerceIn(0, chips.size - 1)].tryRequestFocus()
                state.removed.isNotEmpty() -> chipFocus[chips.size].tryRequestFocus()
                else -> false
            }
        if (!ok) boxFocus.tryRequestFocus()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxSize()
                .background(JellytipPalette.Night)
                .padding(start = JellytipLayout.PageStart, top = JellytipLayout.PageTop, end = JellytipLayout.PageEnd)
                .onPreviewKeyEvent { e ->
                    // The remote's Search / mic key: listen right away
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.Search || e.key == Key.VoiceAssist) && viewModel.voiceInputManager.isAvailable) {
                        viewModel.voiceInputManager.startListening()
                        true
                    } else {
                        false
                    }
                },
    ) {
        Text("Ask", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageTitle)
        Text("Say what you feel like. Searches your library only, not IPTV or requests.", color = dim, fontFamily = JellytipFontFamily, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoiceSearchButton(
                onSpeechResult = { spoken ->
                    JellytipTrace.log("ask", "voice result '$spoken'")
                    // The mic key works from anywhere: whatever had focus may go with the new reading
                    refocusChip = 0
                    viewModel.submit(spoken)
                },
                voiceInputManager = viewModel.voiceInputManager,
            )
            SearchEditTextBox(
                value = state.text,
                onValueChange = {
                    typing = true
                    viewModel.setText(it)
                },
                onSearchClick = { viewModel.submit(state.text) },
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
            AskButton("Ask", primary = true) { viewModel.submit(state.text) }
            AskButton("Chat", primary = false) { viewModel.navigationManager.navigateTo(com.github.damontecres.wholphin.ui.nav.Destination.JellytipAskChat, "ask -> chat") }
        }

        if (!state.hasAsk) {
            Text("Try", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                itemsIndexed(AskParse.EXAMPLES) { _, q ->
                    AskChipButton(q, on = false) {
                        // The examples go away once something is asked: land on the first chip it was read as
                        refocusChip = 0
                        viewModel.submit(q)
                    }
                }
            }
        } else {
            if (chips.isNotEmpty() || state.reading.chips.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                    itemsIndexed(chips, key = { _, c -> c.id }) { i, c ->
                        AskChipButton("${c.label}  ✕", on = true, modifier = Modifier.focusRequester(chipFocus[i])) {
                            refocusChip = i
                            viewModel.remove(c.id)
                        }
                    }
                    if (state.removed.isNotEmpty()) {
                        item(key = "put-back") {
                            AskChipButton("Put back", on = false, modifier = Modifier.focusRequester(chipFocus[chips.size])) {
                                refocusChip = 0
                                viewModel.putBack()
                            }
                        }
                    }
                }
            } else {
                Text("Couldn't read a mood, length, decade or “unwatched” from that. Try one of the examples.", color = dim, fontFamily = JellytipFontFamily, fontSize = 14.sp)
            }
            if (state.reading.ignored.isNotEmpty()) {
                Text("ignored: ${state.reading.ignored.joinToString(", ")}", color = dim, fontFamily = JellytipFontFamily, fontSize = 12.sp)
            }
            (state.reading.notes + AskParse.appliedNotes(chips)).forEach { n ->
                Text(n, color = dim, fontFamily = JellytipFontFamily, fontSize = 12.sp)
            }
            if (chips.isEmpty() && state.reading.chips.isNotEmpty()) {
                Text("Every filter is removed. Put one back, or ask again.", color = dim, fontFamily = JellytipFontFamily, fontSize = 14.sp)
            }
        }

        val r = state.result
        if (r != AskResult.Idle) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // One button in one slot for Error / Loading / Done ("Try again" or "Shuffle"): the focused node is never
                // disposed, so focus can't fall to the drawer when a load fails or finishes. It stays enabled while loading.
                AskButton(if (r == AskResult.Error) "Try again" else "Shuffle", primary = false) {
                    if (r == AskResult.Error) viewModel.retry() else viewModel.shuffle()
                }
                Text(
                    when (r) {
                        AskResult.Error -> "Couldn't reach your library."
                        is AskResult.Done -> listOfNotNull(if (r.tiles.isEmpty()) "Nothing fits." else null, r.picks.note, r.iptvNote).joinToString(" ")
                        else -> "Looking…"
                    },
                    color = dim,
                    fontFamily = JellytipFontFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        val refine = state.refine
        if (refine.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Narrow it down", color = dim, fontFamily = JellytipFontFamily, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                    items(refine.size, key = { refine[it].id }) { i ->
                        val c = refine[i]
                        AskChipButton("+ ${c.label}", on = false) {
                            // The tapped chip leaves this row: land on the reading's chips (the new one is last)
                            refocusChip = chips.size
                            viewModel.addRefine(c)
                        }
                    }
                }
            }
        }
        if (r is AskResult.Done && r.tiles.isNotEmpty()) {
            AskPicksGrid(
                tiles = r.tiles,
                gridState = gridState,
                restoreKey = viewModel.lastOpened,
                restore = restore,
                onOpenLibrary = viewModel::open,
                onOpenIptv = viewModel::openVod,
                onHoldLibrary = { menu.open(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        JellytipLibraryMenuHostOf(menu)
    }
}

/**
 * Ask's picks: library posters then IPTV ones, the same tile look (source-neutral: IptvVodTile draws like PosterCard, no
 * source mark). Keys are JellytipSourceMerge's library / IPTV keys; [restoreKey] = the tile focus returns to.
 */
@Composable
internal fun AskPicksGrid(
    tiles: List<AskTile>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    restoreKey: String?,
    restore: FocusRequester,
    onOpenLibrary: (BaseItem) -> Unit,
    onOpenIptv: (IptvVodItem) -> Unit,
    onHoldLibrary: (BaseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(SpecialTokens.POSTER_W + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 48.dp),
        state = gridState,
        modifier = modifier.focusGroup().focusRestorer(),
    ) {
        items(tiles.size, key = { tiles[it].key }) { i ->
            val tile = tiles[i]
            val m = if (tile.key == restoreKey) Modifier.focusRequester(restore) else Modifier
            when (tile) {
                is AskTile.Lib -> PosterCard(tile.item, onClick = { onOpenLibrary(tile.item) }, onLongClick = { onHoldLibrary(tile.item) }, modifier = m)
                is AskTile.Vod -> IptvVodTile(tile.item, SpecialTokens.POSTER_W, SpecialTokens.POSTER_H, poster = true, onClick = { onOpenIptv(tile.item) }, modifier = m)
            }
        }
    }
}

@Composable
internal fun AskChipButton(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (on) JellytipPalette.Jelly.copy(alpha = 0.35f) else JellytipPalette.Vanilla.copy(alpha = 0.12f),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        modifier = modifier,
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
    }
}

@Composable
internal fun AskButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) JellytipPalette.Jelly else JellytipPalette.Vanilla.copy(alpha = 0.12f),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = if (primary) Color.White else JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        modifier = modifier,
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(label, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}
