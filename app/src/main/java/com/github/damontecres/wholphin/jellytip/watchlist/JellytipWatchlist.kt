package com.github.damontecres.wholphin.jellytip.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.playback.playable
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeHeader
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeHero
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTheme
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge
import com.github.damontecres.wholphin.jellytip.cards.libRef
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile
import com.github.damontecres.wholphin.jellytip.iptv.JellytipIptvVodViewModel
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.main.HomePageCardContent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import javax.inject.Inject

sealed interface WatchlistState {
    data object Loading : WatchlistState

    /** Could not load, or nothing on the list: the stock Favorites page takes over. */
    data object Fallback : WatchlistState

    data class Loaded(
        val rows: List<HomeRowLoadingState>,
    ) : WatchlistState
}

/**
 * The watchlist (Jellyfin favourites) as stacked rows like Home: Films, Shows, then Episodes / Collections / other
 * kinds only when they have something, each newest-added first. One request for everything; reloads on every return so
 * an item taken off the list disappears, keeping the old rows on screen meanwhile.
 */
@HiltViewModel
class JellytipWatchlistViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        val navigationManager: NavigationManager,
        private val myList: com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListService,
    ) : ViewModel() {
        private val _state = MutableStateFlow<WatchlistState>(WatchlistState.Loading)
        val state: StateFlow<WatchlistState> = _state

        /** IPTV favourites (films, shows) from `/v1/me/list`; empty while loading or when the server has no such route. */
        private val _iptv = MutableStateFlow<Pair<List<IptvVodItem>, List<IptvVodItem>>>(emptyList<IptvVodItem>() to emptyList())
        val iptv: StateFlow<Pair<List<IptvVodItem>, List<IptvVodItem>>> = _iptv

        private fun loadIptv() {
            viewModelScope.launch(Dispatchers.IO) {
                fun iptvOf(entries: List<com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListEntry>?) =
                    entries.orEmpty().filterIsInstance<com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListEntry.Iptv>().map { it.item }
                val films = myList.list("movie", MAX_IPTV)
                val shows = myList.list("series", MAX_IPTV)
                // A failed call keeps what is on screen
                if (films != null || shows != null) {
                    _iptv.value = (if (films != null) iptvOf(films) else _iptv.value.first) to (if (shows != null) iptvOf(shows) else _iptv.value.second)
                }
            }
        }

        fun load() {
            loadIptv()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val userId = serverRepository.currentUser?.id
                    val items =
                        GetItemsRequestHandler
                            .execute(
                                api,
                                GetItemsRequest(
                                    userId = userId,
                                    isFavorite = true,
                                    recursive = true,
                                    includeItemTypes = KINDS.map { it.first },
                                    sortBy = listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME),
                                    sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
                                    fields = HomeItemFields + ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                                    enableUserData = true,
                                    limit = MAX_ITEMS,
                                ),
                            ).content.items
                            .map { BaseItem(it, false) }
                    val rows =
                        KINDS.mapNotNull { (kind, label) ->
                            val ofKind = items.filter { it.type == kind }
                            if (ofKind.isEmpty()) {
                                null
                            } else {
                                HomeRowLoadingState.Success(
                                    StringStringProvider("$label (${ofKind.size})"),
                                    ofKind,
                                    HomeRowViewOptions(),
                                    showViewMore = false,
                                )
                            }
                        }
                    _state.value = if (rows.isEmpty()) WatchlistState.Fallback else WatchlistState.Loaded(rows)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Watchlist failed")
                    // A good list already on screen stays; only a first failure hands over to the stock page
                    if (_state.value !is WatchlistState.Loaded) _state.value = WatchlistState.Fallback
                }
            }
        }

        companion object {
            private const val MAX_ITEMS = 600
            private const val MAX_IPTV = 100

            /** Row order and labels. */
            private val KINDS =
                listOf(
                    BaseItemKind.MOVIE to "Films",
                    BaseItemKind.SERIES to "Shows",
                    BaseItemKind.EPISODE to "Episodes",
                    BaseItemKind.SEASON to "Seasons",
                    BaseItemKind.BOX_SET to "Collections",
                    BaseItemKind.VIDEO to "Videos",
                )
        }
    }

/**
 * Jellytip watchlist: Home-style rows (Down/Up move between kinds at once, no tabs to scroll back to), titled 16:9
 * tiles with the untitled overlay, the hero/text block for the focused item, focus restored on return. [fallback] (the
 * stock Favorites page) is shown when the list cannot be loaded or is empty.
 */
@Composable
fun JellytipWatchlistPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: JellytipWatchlistViewModel = hiltViewModel(),
    fallback: @Composable () -> Unit,
) {
    LifecycleStartEffect(Unit) {
        viewModel.load()
        onStopOrDispose { }
    }
    val state by viewModel.state.collectAsState()
    val iptv by viewModel.iptv.collectAsState()
    val (iptvFilms, iptvShows) = iptv
    // IPTV favourites (plan "My list") join the library's Films / Shows rows (one tile per title); a kind the library has no row
    // for gets a row of its own with the same label
    val baseRows = (state as? WatchlistState.Loaded)?.rows.orEmpty()
    val merged = remember(baseRows, iptvFilms, iptvShows) { mergeWatchlist(baseRows, iptvFilms, iptvShows) }
    val iptvRows: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? =
        if (merged.iptvRows.isEmpty()) {
            null
        } else {
            {
                merged.iptvRows.forEach { (label, list) ->
                    item(key = "iptv-$label") {
                        com.github.damontecres.wholphin.jellytip.iptv.IptvVodRow(
                            title = "$label (${list.size})",
                            items = list,
                            poster = false,
                            heightDp = com.github.damontecres.wholphin.jellytip.home.jellytipRowOptions(HomeRowViewOptions()).heightDp,
                            onFocusTile = {},
                        )
                    }
                }
            }
        }
    val shown = if (state is WatchlistState.Fallback && iptvRows != null) WatchlistState.Loaded(emptyList()) else state
    when (val s = shown) {
        WatchlistState.Loading -> LoadingPage(modifier)
        WatchlistState.Fallback -> fallback()
        is WatchlistState.Loaded -> {
            var position by rememberPosition()
            val showLogos = preferences.appPreferences.interfacePreferences.showLogos
            JellytipHomeTheme {
                HomePageContent(
                    homeRows = if (state is WatchlistState.Loaded) merged.rows else s.rows,
                    iptvAppended = merged.appended,
                    position = position,
                    onFocusPosition = { position = it },
                    onClickItem = { clicked, item ->
                        position = clicked
                        JellytipTrailerControl.stop()
                        viewModel.navigationManager.navigateTo(item.destination(), "watchlist OK")
                    },
                    onLongClickItem = { clicked, item ->
                        position = clicked
                        JellytipTrailerControl.stop()
                        viewModel.navigationManager.navigateTo(item.destination(), "watchlist long-press")
                    },
                    onClickPlay = { _, item ->
                        JellytipTrailerControl.stop()
                        viewModel.navigationManager.navigateTo(
                            com.github.damontecres.wholphin.ui.nav.Destination
                                .Playback(item),
                        )
                    },
                    showClock = preferences.appPreferences.interfacePreferences.showClock,
                    onUpdateBackdrop = {},
                    showLogo = showLogos,
                    showViewMore = false,
                    modifier = modifier,
                    heroComposable = { focusedItem, hasFocus -> JellytipHomeHero(item = focusedItem, hasFocus = hasFocus) },
                    headerComposable = { focusedItem -> JellytipHomeHeader(item = focusedItem, showLogo = showLogos) },
                    extraRows = iptvRows,
                )
            }
        }
    }
}

/** The watchlist rows with IPTV favourites folded in (pure, unit tested). */
internal data class WatchlistMerged(
    /** The library rows; Films / Shows carry the combined count in their title when IPTV-only titles join them. */
    val rows: List<HomeRowLoadingState>,
    /** Row index -> IPTV-only titles drawn after that row's library tiles (no library twin, first of each title). */
    val appended: Map<Int, List<IptvVodItem>>,
    /** Kinds with no library row: label ("Films" / "Shows") -> the IPTV titles, drawn as a row of their own. */
    val iptvRows: List<Pair<String, List<IptvVodItem>>>,
)

internal fun mergeWatchlist(
    rows: List<HomeRowLoadingState>,
    iptvFilms: List<IptvVodItem>,
    iptvShows: List<IptvVodItem>,
): WatchlistMerged {
    val out = rows.toMutableList()
    val appended = LinkedHashMap<Int, List<IptvVodItem>>()
    val own = ArrayList<Pair<String, List<IptvVodItem>>>()
    listOf(Triple(BaseItemKind.MOVIE, "Films", iptvFilms), Triple(BaseItemKind.SERIES, "Shows", iptvShows)).forEach { (kind, label, list) ->
        if (list.isEmpty()) return@forEach
        val idx = out.indexOfFirst { r -> r is HomeRowLoadingState.Success && r.items.firstOrNull { it != null }?.type == kind }
        val row = out.getOrNull(idx) as? HomeRowLoadingState.Success
        if (row == null) {
            val only = JellytipSourceMerge.iptvOnly(emptyList(), list)
            if (only.isNotEmpty()) own += label to only
        } else {
            val extra = JellytipSourceMerge.iptvOnly(row.items.filterNotNull().map { it.libRef() }, list)
            if (extra.isNotEmpty()) {
                out[idx] = row.copy(title = StringStringProvider("$label (${row.items.size + extra.size})"))
                appended[idx] = extra
            }
        }
    }
    return WatchlistMerged(out, appended, own)
}

private sealed interface WatchlistTile {
    data class Lib(
        val item: BaseItem,
        val index: Int,
    ) : WatchlistTile

    data class Vod(
        val item: IptvVodItem,
    ) : WatchlistTile
}

/**
 * A watchlist row of library tiles followed by the IPTV-only titles of the same kind: one row, one tile per title. Library
 * tiles behave as in the plain row (they drive the hero); IPTV tiles open the title.
 */
@Composable
fun JellytipWatchlistMixedRow(
    title: String,
    library: List<BaseItem?>,
    iptv: List<IptvVodItem>,
    viewOptions: HomeRowViewOptions,
    onClickLibrary: (Int, BaseItem) -> Unit,
    onLongClickLibrary: (Int, BaseItem) -> Unit,
    onFocusLibrary: (Int) -> Unit,
    onFocusIptv: () -> Unit,
    modifier: Modifier,
    viewModel: JellytipIptvVodViewModel = hiltViewModel(),
    /** The plain row's Play key (media play / play-pause on a playable library tile). */
    onPlay: ((BaseItem) -> Unit)? = null,
    /** Row index in the page: the first tile gets the plain row's `row-<n>-tile0` test tag. */
    rowIndex: Int = 0,
) {
    val tiles =
        remember(library, iptv) {
            library.mapIndexedNotNull { i, item -> item?.let { WatchlistTile.Lib(it, i) } } + iptv.map { WatchlistTile.Vod(it) }
        }
    val h = viewOptions.heightDp.dp
    ItemRow(
        title = title,
        items = tiles,
        onClickItem = { _, t ->
            when (t) {
                is WatchlistTile.Lib -> onClickLibrary(t.index, t.item)
                is WatchlistTile.Vod -> {
                    JellytipTrailerControl.stop()
                    viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(t.item.id, t.item.title), "watchlist IPTV OK")
                }
            }
        },
        onLongClickItem = { _, t ->
            when (t) {
                is WatchlistTile.Lib -> onLongClickLibrary(t.index, t.item)
                is WatchlistTile.Vod -> {
                    JellytipTrailerControl.stop()
                    viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(t.item.id, t.item.title), "watchlist IPTV long-press")
                }
            }
        },
        horizontalPadding = viewOptions.spacing.dp,
        modifier = modifier,
        cardContent = { index, t, cardModifier, onClick, onLongClick ->
            when (t) {
                is WatchlistTile.Lib ->
                    HomePageCardContent(
                        index = index,
                        item = t.item,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        viewOptions = viewOptions,
                        modifier =
                            cardModifier
                                .then(if (index == 0) Modifier.testTag("row-$rowIndex-tile0") else Modifier)
                                .onFocusChanged { if (it.isFocused) onFocusLibrary(t.index) }
                                .onKeyEvent { event ->
                                    if (onPlay != null && isPlayKeyUp(event) && t.item.type.playable) {
                                        onPlay(t.item)
                                        true
                                    } else {
                                        false
                                    }
                                },
                    )

                is WatchlistTile.Vod ->
                    IptvVodTile(
                        item = t.item,
                        width = h * 16f / 9f,
                        height = h,
                        poster = false,
                        onClick = onClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusIptv() },
                    )

                null -> androidx.compose.foundation.layout.Box(Modifier)
            }
        },
    )
}
