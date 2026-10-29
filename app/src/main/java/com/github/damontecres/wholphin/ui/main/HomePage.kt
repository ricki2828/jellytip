package com.github.damontecres.wholphin.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import com.github.damontecres.wholphin.jellytip.JellytipSession
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.QuickDetailsData
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeHeader
import com.github.damontecres.wholphin.jellytip.JellytipInputGuard
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeHero
import com.github.damontecres.wholphin.jellytip.specials.JellytipHomeEntry
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecialFocus
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecialRow
import com.github.damontecres.wholphin.jellytip.specials.jellytipHomeEntries
import com.github.damontecres.wholphin.jellytip.specials.rememberJellytipSpecials
import com.github.damontecres.wholphin.jellytip.specials.rememberJellytipRows
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRowView
import com.github.damontecres.wholphin.jellytip.home.JellytipCarouselRows
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTheme
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.LocalJellytipHome
import com.github.damontecres.wholphin.jellytip.home.jellytipRowOptions
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.cards.BannerCard
import com.github.damontecres.wholphin.ui.cards.BannerCardWithTitle
import com.github.damontecres.wholphin.ui.cards.GenreCard
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.StudioCard
import com.github.damontecres.wholphin.ui.cards.ViewMoreCard
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.components.EpisodeName
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.FocusableItemRow
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.playback.scale
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ScrollToTopBringIntoViewSpec
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

@Composable
fun HomePage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LifecycleStartEffect(Unit) {
        viewModel.init()
        onStopOrDispose { }
    }
    val state by viewModel.state.collectAsState()
    val loading = state.loadingState
    val refreshing = state.refreshState
    val homeRows = state.homeRows
    val homeSettings = state.settings
    val rowConfigs = remember(homeSettings) { homeSettings.rows.map { it.config } }

    when (val state = loading) {
        is LoadingState.Error -> {
            ErrorMessage(state, modifier)
        }

        LoadingState.Loading,
        LoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            var showContextMenu by remember { mutableStateOf<ContextMenu?>(null) }
            var showPlaylistDialog by remember { mutableStateOf<UUID?>(null) }
            var overviewDialog by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }

            val playlistState by playlistViewModel.playlistState.collectAsState()
            var position by rememberPosition()

            val onFocusPosition =
                remember {
                    { it: RowColumn ->
                        position = it
                        viewModel.onFocusPosition(it)
                    }
                }
            val currentHomePrefs by rememberUpdatedState(preferences.appPreferences.homePagePreferences)
            val onClickItem =
                remember {
                    { clickedPosition: RowColumn, item: BaseItem ->
                        position = clickedPosition
                        // OK while the hero trailer plays: unmute, then full screen; otherwise open the item
                        if (!JellytipTrailerControl.onOk(item)) {
                            // Free the hero player/decoder before the main player or details page starts
                            JellytipTrailerControl.stop()
                            viewModel.recordOpen(clickedPosition)
                            if (currentHomePrefs.clickToPlay &&
                                homeRows.getOrNull(clickedPosition.row)?.isContinueWatchingNextUp == true
                            ) {
                                viewModel.navigationManager.navigateTo(Destination.Playback(item), "home OK (click-to-play row)")
                            } else {
                                viewModel.navigationManager.navigateTo(item.destination(), "home OK")
                            }
                        }
                    }
                }
            val onLongClickItem =
                remember {
                    longClick@{ clickedPosition: RowColumn, item: BaseItem ->
                        // row -1 = a server-row tile: keep the remembered row position as it is
                        if (clickedPosition.row >= 0) position = clickedPosition
                        if (JellytipTrailerControl.isPlayingFor(item)) {
                            // Long-press OK always opens details while the hero trailer plays
                            JellytipTrailerControl.stop()
                            // Open details only after OK is released: the key-up used to click Play and start the film
                            JellytipInputGuard.afterRelease { viewModel.navigationManager.navigateTo(item.destination(), "home long-press OK") }
                            return@longClick
                        }
                        val row =
                            (homeRows.getOrNull(clickedPosition.row) as? HomeRowLoadingState.Success)
                        val canRemoveContinueWatching =
                            row?.rowType is HomeRowConfig.ContinueWatching || row?.rowType is HomeRowConfig.ContinueWatchingCombined
                        val canRemoveNextUp =
                            row?.rowType is HomeRowConfig.NextUp || row?.rowType is HomeRowConfig.ContinueWatchingCombined
                        val menu =
                            ContextMenu.ForBaseItem(
                                fromLongClick = true,
                                item = item,
                                chosenStreams = null,
                                showGoTo = true,
                                showStreamChoices = false,
                                canDelete =
                                    viewModel.canDelete(
                                        item,
                                        preferences.appPreferences,
                                    ),
                                canRemoveContinueWatching = canRemoveContinueWatching,
                                canRemoveNextUp = canRemoveNextUp,
                                actions =
                                    ContextMenuActions(
                                        navigateTo = viewModel.navigationManager::navigateTo,
                                        onClickWatch = viewModel::setWatched,
                                        onClickFavorite = viewModel::setFavorite,
                                        onClickAddPlaylist = { itemId ->
                                            playlistViewModel.loadPlaylists()
                                            showPlaylistDialog = itemId
                                        },
                                        onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                                        onDeleteItem = {
                                            viewModel.deleteItem(clickedPosition, it)
                                        },
                                        onChooseVersion = { _, _ ->
                                            // Not supported on this page
                                        },
                                        onChooseTracks = {
                                            // Not supported on this page
                                        },
                                        onShowOverview = {
                                            overviewDialog = ItemDetailsDialogInfo(it)
                                        },
                                        onClearChosenStreams = {},
                                        onClickRemoveFromNextUp = viewModel::removeFromNextUp,
                                    ),
                            )
                        // Open only after OK is released so the key-up cannot land on the menu's first entry
                        JellytipInputGuard.afterRelease { showContextMenu = menu }
                    }
                }
            val onClickPlay =
                remember {
                    { _: RowColumn, item: BaseItem ->
                        viewModel.navigationManager.navigateTo(Destination.Playback(item), "home Play key")
                    }
                }
            val onClickViewMore =
                remember {
                    { _: RowColumn, row: HomeRowLoadingState.Success ->
                        viewModel.navigationManager.navigateTo(
                            Destination.MoreHomeRow(row.title, row.rowType!!, row.items.size),
                        )
                    }
                }

            val noBackdropUpdate = remember { { _: BaseItem -> } }
            val showLogos = preferences.appPreferences.interfacePreferences.showLogos
            JellytipHomeTheme {
                HomePageContent(
                    homeRows = homeRows,
                    position = position,
                    onFocusPosition = onFocusPosition,
                    onClickItem = onClickItem,
                    onLongClickItem = onLongClickItem,
                    onClickPlay = onClickPlay,
                    loadingState = refreshing,
                    showClock = preferences.appPreferences.interfacePreferences.showClock,
                    onUpdateBackdrop = noBackdropUpdate,
                    showLogo = showLogos,
                    showViewMore = true,
                    onClickViewMore = onClickViewMore,
                    modifier = modifier,
                    heroComposable = { focusedItem, hasFocus ->
                        JellytipHomeHero(item = focusedItem, hasFocus = hasFocus)
                    },
                    headerComposable = { focusedItem ->
                        JellytipHomeHeader(item = focusedItem, showLogo = showLogos)
                    },
                    specialsPage = "home",
                    rowConfigs = rowConfigs,
                    onLongClickExtraItem = { item -> onLongClickItem(RowColumn(-1, -1), item) },
                )
            }
            overviewDialog?.let { info ->
                ItemDetailsDialog(
                    info = info,
                    showFilePath = false,
                    onDismissRequest = { overviewDialog = null },
                )
            }
            showContextMenu?.let { contextMenu ->
                ContextMenuDialog(
                    onDismissRequest = { showContextMenu = null },
                    getMediaSource = null,
                    contextMenu = contextMenu,
                    preferredSubtitleLanguage = null,
                )
            }
            showPlaylistDialog?.let { itemId ->
                PlaylistDialog(
                    title = stringResource(R.string.add_to_playlist),
                    state = playlistState,
                    onDismissRequest = { showPlaylistDialog = null },
                    onClick = {
                        playlistViewModel.addToPlaylist(it.id, itemId)
                        showPlaylistDialog = null
                    },
                    createEnabled = true,
                    onCreatePlaylist = {
                        playlistViewModel.createPlaylistAndAddItem(it, itemId)
                        showPlaylistDialog = null
                    },
                    onSearch = playlistViewModel::loadPlaylists,
                    elevation = 3.dp,
                )
            }
        }
    }
}

val HomeRowLoadingState?.isContinueWatchingNextUp: Boolean
    get() =
        (this as? HomeRowLoadingState.Success).let { row ->
            row?.rowType is HomeRowConfig.ContinueWatching ||
                row?.rowType is HomeRowConfig.NextUp ||
                row?.rowType is HomeRowConfig.ContinueWatchingCombined
        }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePageContent(
    homeRows: List<HomeRowLoadingState>,
    position: RowColumn,
    onFocusPosition: (RowColumn) -> Unit,
    onClickItem: (RowColumn, BaseItem) -> Unit,
    onLongClickItem: (RowColumn, BaseItem) -> Unit,
    onClickPlay: (RowColumn, BaseItem) -> Unit,
    showClock: Boolean,
    onUpdateBackdrop: (BaseItem) -> Unit,
    showLogo: Boolean,
    showViewMore: Boolean,
    modifier: Modifier = Modifier,
    loadingState: LoadingState? = null,
    listState: LazyListState = rememberLazyListState(),
    takeFocus: Boolean = true,
    showEmptyRows: Boolean = false,
    headerComposable: @Composable (focusedItem: BaseItem?) -> Unit = { focusedItem ->
        HomePageHeader(
            item = focusedItem,
            showLogo = showLogo,
            modifier = HeaderUtils.modifier,
        )
    },
    onClickViewMore: (RowColumn, HomeRowLoadingState.Success) -> Unit = { _, _ -> },
    heroComposable: (@Composable (focusedItem: BaseItem?, hasFocus: Boolean) -> Unit)? = null,
    extraRows: (LazyListScope.() -> Unit)? = null,
    /** Jellytip: companion page (`home`, `movies`, `shows`) for server rows and specials; null = none. */
    specialsPage: String? = null,
    /** Rows before the first "normal" row (Continue Watching / Next Up); null = detect from the row types. */
    specialsLeadingRows: Int? = null,
    /** The configs behind [homeRows] (Home): rows the app added from the old manifest are hidden once server rows arrive. */
    rowConfigs: List<HomeRowConfig>? = null,
    /** Long press on a server-row tile (context menu); null = open details. */
    onLongClickExtraItem: ((BaseItem) -> Unit)? = null,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val rowFocusedItem =
        remember(homeRows, position) {
            (homeRows.getOrNull(position.row) as? HomeRowLoadingState.Success)?.items?.getOrNull(
                position.column,
            )
        }
    // Jellytip specials: rows of their own between the normal rows; a focused special tile drives the hero instead
    val liveSpecials = specialsPage?.let { rememberJellytipSpecials(it) }
    // Server rows (rows=1 manifest) replace the old carousel rows; old manifest / unavailable = today's rows
    val liveServerRows = specialsPage?.let { rememberJellytipRows(it) }
    // What is on screen. Starts with today's cached copies (disk), so specials/rows are in place on the first frame;
    // a late answer is taken only if it does not move the focused row, otherwise it waits for the next visit.
    var specials by remember { mutableStateOf(liveSpecials) }
    var serverRows by remember { mutableStateOf(liveServerRows) }
    val hiddenRows =
        remember(serverRows, rowConfigs) {
            if (serverRows == null || rowConfigs == null) {
                emptySet()
            } else {
                rowConfigs.indices.filter { JellytipCarouselRows.isAppRow(rowConfigs[it], serverRows.carouselIds) }.toSet()
            }
        }
    val leadingRows =
        specialsLeadingRows
            ?: rowConfigs?.let { c -> c.indexOfFirst { !JellytipCarouselRows.isLeadingRow(it) }.let { if (it < 0) c.size else it } }
    var specialFocus by remember { mutableStateOf<JellytipSpecialFocus?>(null) }
    var savedSpecialId by rememberSaveable { mutableStateOf<String?>(null) }
    val specialRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val entries =
        remember(homeRows, specials, leadingRows, serverRows, hiddenRows) {
            jellytipHomeEntries(homeRows, specials?.specials.orEmpty(), leadingRows, serverRows?.rows.orEmpty(), hiddenRows)
        }
    val focusedEntry =
        remember(entries, specialFocus?.id, position.row) {
            val sid = specialFocus?.id
            (if (sid != null) entries.indexOfFirst { it.key == sid } else -1).takeIf { it >= 0 }
                ?: entries.indexOfFirst { it is JellytipHomeEntry.Row && it.index == position.row }
        }
    val focusedItem = if (specialFocus != null) specialFocus?.item else rowFocusedItem
    val headerItem = if (specialFocus?.showHeader == false) null else focusedItem

    val focusedKey = entries.getOrNull(focusedEntry)?.key
    val currentFocusedKey by rememberUpdatedState(focusedKey)
    val currentHasFocus by rememberUpdatedState(hasFocus)
    LaunchedEffect(liveSpecials, liveServerRows) {
        if (liveSpecials === specials && liveServerRows === serverRows) return@LaunchedEffect
        val key = currentFocusedKey
        val candidate =
            jellytipHomeEntries(
                homeRows,
                liveSpecials?.specials.orEmpty(),
                leadingRows,
                liveServerRows?.rows.orEmpty(),
                if (liveServerRows == null || rowConfigs == null) {
                    emptySet()
                } else {
                    rowConfigs.indices.filter { JellytipCarouselRows.isAppRow(rowConfigs[it], liveServerRows.carouselIds) }.toSet()
                },
            )
        val moves = currentHasFocus && key != null && candidate.indexOfFirst { it.key == key } != entries.indexOfFirst { it.key == key }
        if (moves) {
            JellytipTrace.layout("deferred late specials/rows: would move focused $key")
        } else {
            specials = liveSpecials
            serverRows = liveServerRows
        }
    }
    // Trace any list change that moves the focused entry (index and px), to find "jumping around" while browsing
    var lastFocusedIndex by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(entries) {
        val key = currentFocusedKey ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it.key == key }
        val before = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
        val previous = lastFocusedIndex
        if (previous != null && previous.first == key && previous.second != index) {
            withFrameNanos { }
            val after = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
            JellytipTrace.layout("focused $key moved ${previous.second}->$index px ${before ?: "?"}->${after ?: "?"} entries=${entries.size}")
        }
        lastFocusedIndex = key to index
    }
    // The hero/text block follow focus 150 ms behind, so fast scrolling does not reload the hero on every tile
    var heroItem by remember { mutableStateOf(focusedItem) }
    var heroHeaderItem by remember { mutableStateOf(headerItem) }
    LaunchedEffect(focusedItem, headerItem) {
        delay(HERO_DEBOUNCE_MS)
        heroItem = focusedItem
        heroHeaderItem = headerItem
        JellytipTrace.focus("${focusedKey ?: "-"} ${focusedItem?.name ?: "-"}")
    }

    val rowFocusRequesters = remember(homeRows.size) { List(homeRows.size) { FocusRequester() } }
    var firstFocused by remember { mutableStateOf(false) }

    val currentPosition by rememberUpdatedState(position)
    val currentOnFocusPosition by rememberUpdatedState(onFocusPosition)
    val currentOnClickPlay by rememberUpdatedState(onClickPlay)

    if (takeFocus) {
        LaunchedEffect(homeRows) {
            // Back on Home after opening something from a special: focus that special again
            val special = savedSpecialId?.let { specialRequesters[it] }
            if (!firstFocused && special != null) {
                withFrameNanos { }
                if (special.tryRequestFocus()) firstFocused = true
            }
            if (!firstFocused && homeRows.isNotEmpty()) {
                if (position.row >= 0) {
                    val index = position.row.coerceIn(0, rowFocusRequesters.lastIndex)
                    rowFocusRequesters.getOrNull(index)?.tryRequestFocus()
                    firstFocused = true
                } else {
                    // Waiting for the first home row to load, then focus on it
                    homeRows
                        .indexOfFirstOrNull { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }
                        ?.let {
                            rowFocusRequesters[it].tryRequestFocus()
                            firstFocused = true
                            delay(50)
                            listState.scrollToItem(it)
                        }
                }
            }
        }
    }
    // After the cold-start intro, make sure the first home row (not the nav rail) holds focus
    val introTick by JellytipSession.introDoneTick.collectAsState()
    LaunchedEffect(introTick, homeRows) {
        // One-shot: consumed only once a real row exists, so later visits to Home keep their restored focus
        if (introTick > 0 && takeFocus && JellytipSession.introFocusPending) {
            withFrameNanos { }
            val row = homeRows.indexOfFirstOrNull { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }
            if (row != null) {
                JellytipSession.introFocusPending = false
                rowFocusRequesters.getOrNull(row)?.tryRequestFocus()
            }
        }
    }
    LaunchedEffect(onUpdateBackdrop, focusedItem) {
        focusedItem?.let { onUpdateBackdrop.invoke(it) }
    }
    Box(modifier = modifier.onFocusChanged { hasFocus = it.hasFocus }) {
        heroComposable?.invoke(heroItem, hasFocus)
        Column(
            modifier =
                Modifier
                    .focusProperties {
                        onEnter = {
                            val special = (specialFocus?.id ?: savedSpecialId)?.let { specialRequesters[it] }
                            if (special == null || !special.tryRequestFocus()) {
                                rowFocusRequesters.getOrNull(currentPosition.row)?.tryRequestFocus()
                            }
                        }
                    }.fillMaxSize(),
        ) {
            headerComposable.invoke(heroHeaderItem)

            val density = LocalDensity.current
            val spaceAbovePx =
                remember(density) {
                    with(density) {
                        // The size of the row titles & spacing
                        50.dp.toPx()
                    }
                }
            val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides ScrollToTopBringIntoViewSpec(spaceAbovePx),
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    contentPadding =
                        PaddingValues(
                            bottom = Cards.height2x3,
                        ),
                    modifier =
                        Modifier
                            .focusRestorer(),
                ) {
                    items(count = entries.size, key = { entries[it].key }) { entryIndex ->
                        val entry = entries[entryIndex]
                        // Jellytip: rows above the focused one fade out completely so no clipped sliver is left at the top
                        val aboveFocus = LocalJellytipHome.current && entryIndex < focusedEntry
                        val rowAlpha by animateFloatAsState(if (aboveFocus) 0f else 1f, tween(150), label = "jellytip-row-above")
                        val rowModifier =
                            Modifier
                                .animateItem(placementSpec = null)
                                .graphicsLayer { alpha = rowAlpha }
                                .padding(bottom = 8.dp)
                        if (entry is JellytipHomeEntry.Server) {
                            val id = entry.key
                            CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                                JellytipServerRowView(
                                    row = entry.row,
                                    onFocusItem = { item, showHeader ->
                                        specialFocus = JellytipSpecialFocus(id, item, showHeader)
                                        savedSpecialId = id
                                    },
                                    onLongClickItem = onLongClickExtraItem,
                                    focusModifier =
                                        Modifier
                                            .focusGroup()
                                            .focusRequester(specialRequesters.getOrPut(id) { FocusRequester() }),
                                    modifier = rowModifier,
                                )
                            }
                            return@items
                        }
                        if (entry is JellytipHomeEntry.Special) {
                            val page = specials ?: return@items
                            val id = entry.key
                            CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                                JellytipSpecialRow(
                                    special = entry.special,
                                    page = page,
                                    onFocusItem = { item, showHeader ->
                                        specialFocus = JellytipSpecialFocus(id, item, showHeader)
                                        savedSpecialId = id
                                    },
                                    focusModifier =
                                        Modifier
                                            .focusGroup()
                                            .focusRequester(specialRequesters.getOrPut(id) { FocusRequester() }),
                                    modifier = rowModifier,
                                )
                            }
                            return@items
                        }
                        val rowIndex = (entry as JellytipHomeEntry.Row).index
                        val row = homeRows[rowIndex]
                        CompositionLocalProvider(
                            LocalBringIntoViewSpec provides defaultBringIntoViewSpec,
                        ) {
                            when (val r = row) {
                                is HomeRowLoadingState.Loading,
                                is HomeRowLoadingState.Pending,
                                -> {
                                    if (LocalJellytipHome.current) {
                                        // Jellytip: reserve the loaded row's height so nothing below moves when it fills in
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = rowModifier) {
                                            ItemRowTitle(r.title.getString())
                                            Spacer(Modifier.height(jellytipRowOptions(HomeRowViewOptions()).heightDp.dp + 16.dp))
                                        }
                                    } else {
                                        FocusableItemRow(
                                            title = r.title.getString(),
                                            subtitle = stringResource(R.string.loading),
                                            modifier = rowModifier,
                                        )
                                    }
                                }

                                is HomeRowLoadingState.Error -> {
                                    FocusableItemRow(
                                        title = r.title.getString(),
                                        subtitle = r.localizedMessage,
                                        isError = true,
                                        modifier = rowModifier,
                                    )
                                }

                                is HomeRowLoadingState.Success -> {
                                    if (row.items.isNotEmpty()) {
                                        val viewOptions =
                                            if (LocalJellytipHome.current) {
                                                jellytipRowOptions(row.viewOptions)
                                            } else {
                                                row.viewOptions
                                            }
                                        ItemRow(
                                            title = row.title.getString(),
                                            items = row.items,
                                            onClickItem =
                                                remember(rowIndex, onClickItem) {
                                                    { index, item ->
                                                        onClickItem.invoke(
                                                            RowColumn(
                                                                rowIndex,
                                                                index,
                                                            ),
                                                            item,
                                                        )
                                                    }
                                                },
                                            onLongClickItem =
                                                remember(rowIndex, onLongClickItem) {
                                                    { index, item ->
                                                        onLongClickItem.invoke(
                                                            RowColumn(rowIndex, index),
                                                            item,
                                                        )
                                                    }
                                                },
                                            modifier =
                                                rowModifier
                                                    .fillMaxWidth()
                                                    .focusGroup()
                                                    .focusRequester(rowFocusRequesters[rowIndex]),
                                            horizontalPadding = viewOptions.spacing.dp,
                                            cardContent = { index, item, cardModifier, onClick, onLongClick ->
                                                val onFocus =
                                                    remember(rowIndex, index) {
                                                        { isFocused: Boolean ->
                                                            if (isFocused) {
                                                                specialFocus = null
                                                                savedSpecialId = null
                                                                currentOnFocusPosition(
                                                                    RowColumn(
                                                                        rowIndex,
                                                                        index,
                                                                    ),
                                                                )
                                                            }
                                                        }
                                                    }
                                                val onKey =
                                                    remember(item) {
                                                        { event: KeyEvent ->
                                                            if (isPlayKeyUp(event) && item?.type?.playable == true) {
                                                                Timber.v("Clicked play on ${item.id}")
                                                                currentOnClickPlay(
                                                                    currentPosition,
                                                                    item,
                                                                )
                                                                true
                                                            } else {
                                                                false
                                                            }
                                                        }
                                                    }
                                                HomePageCardContent(
                                                    index = index,
                                                    item = item,
                                                    onClick = onClick,
                                                    onLongClick = onLongClick,
                                                    viewOptions = viewOptions,
                                                    modifier =
                                                        cardModifier
                                                            .onFocusChanged { onFocus(it.isFocused) }
                                                            .onKeyEvent { onKey(it) },
                                                )
                                            },
                                            showViewMore = showViewMore && row.showViewMore,
                                            viewMoreCardContent = { mod ->
                                                HomePageViewMoreCard(
                                                    isEpisode = row.items.last()?.type == BaseItemKind.EPISODE,
                                                    onClick = {
                                                        onClickViewMore.invoke(
                                                            RowColumn(
                                                                rowIndex,
                                                                r.items.size,
                                                            ),
                                                            r,
                                                        )
                                                    },
                                                    onLongClick = {},
                                                    viewOptions = viewOptions,
                                                    modifier =
                                                        mod.onFocusChanged {
                                                            if (it.isFocused) {
                                                                specialFocus = null
                                                                savedSpecialId = null
                                                                currentOnFocusPosition.invoke(
                                                                    RowColumn(
                                                                        rowIndex,
                                                                        r.items.size,
                                                                    ),
                                                                )
                                                            }
                                                        },
                                                )
                                            },
                                        )
                                    } else if (showEmptyRows) {
                                        FocusableItemRow(
                                            title = r.title.getString(),
                                            subtitle = stringResource(R.string.no_results),
                                            modifier = rowModifier,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    extraRows?.invoke(this)
                }
            }
        }
        when (loadingState) {
            LoadingState.Pending,
            LoadingState.Loading,
            -> {
                Box(
                    modifier =
                        Modifier
                            .padding(if (showClock) 40.dp else 20.dp)
                            .size(40.dp)
                            .align(Alignment.TopEnd),
                ) {
                    CircularProgress(Modifier.fillMaxSize())
                }
            }

            else -> {}
        }
    }
}

@Composable
fun HomePageHeader(
    item: BaseItem?,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    val isEpisode = item?.type == BaseItemKind.EPISODE
    val title =
        remember(item?.id) {
            when (item?.type) {
                BaseItemKind.EPISODE,
                BaseItemKind.SEASON,
                -> item.data.seriesName

                else -> item?.title
            }
        }
    val subtitle =
        remember(item?.id) {
            when (item?.type) {
                BaseItemKind.SEASON,
                BaseItemKind.EPISODE,
                -> item.name

                else -> null
            }
        }
    HomePageHeader(
        title = title,
        subtitle = subtitle,
        overview = item?.data?.overview,
        overviewTwoLines = isEpisode,
        quickDetails = item?.ui?.quickDetails,
        timeRemaining = item?.timeRemainingOrRuntime,
        endsAt = item?.data?.endDate,
        showLogo = showLogo,
        logoImageUrl = rememberLogoUrl(item),
        modifier = modifier,
    )
}

@Composable
fun HomePageHeader(
    title: String?,
    subtitle: String?,
    overview: String?,
    overviewTwoLines: Boolean,
    quickDetails: QuickDetailsData?,
    timeRemaining: Duration?,
    endsAt: DateTime?,
    showLogo: Boolean,
    logoImageUrl: String?,
    modifier: Modifier = Modifier,
) {
    // Jellytip: the shared small header block (22 px text, logo within 380x150, one-line overview)
    val sizes = rememberJellytipSizes()
    val textSize = sizes.sp(JellytipHomeTokens.TEXT_PX)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        TitleOrLogo(
            title = title,
            logoImageUrl = logoImageUrl,
            showLogo = showLogo,
            modifier =
                Modifier
                    .widthIn(max = sizes.px(JellytipHomeTokens.LOGO_MAX_W_PX))
                    .heightIn(max = sizes.px(JellytipHomeTokens.LOGO_MAX_H_PX)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.width(sizes.px(JellytipHomeTokens.TEXT_BLOCK_W_PX)),
        ) {
            if (subtitle != null) {
                EpisodeName(subtitle)
            }
            QuickDetails(
                quickDetails,
                timeRemaining,
                endsAt = endsAt,
                textStyle = MaterialTheme.typography.titleSmall.copy(fontSize = textSize),
            )
            if (overview.isNotNullOrBlank()) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = textSize),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun HomePageCardContent(
    index: Int,
    item: BaseItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    viewOptions: HomeRowViewOptions,
    modifier: Modifier,
) {
    when (item?.type) {
        BaseItemKind.GENRE -> {
            GenreCard(
                genreId = item.id,
                name = item.name,
                imageUrl = item.imageUrlOverride,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.height(viewOptions.heightDp.dp),
            )
        }

        BaseItemKind.STUDIO -> {
            StudioCard(
                studioId = item.id,
                name = item.name,
                imageUrl = item.imageUrlOverride,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.height(viewOptions.heightDp.dp),
            )
        }

        else -> {
            val jellytipHome = LocalJellytipHome.current
            val imageType =
                remember(item, viewOptions, jellytipHome) {
                    if (item?.type == BaseItemKind.EPISODE) {
                        viewOptions.episodeImageType.imageType
                    } else if (jellytipHome && viewOptions.imageType == ViewOptionImageType.THUMB) {
                        ImageType.BACKDROP
                    } else {
                        viewOptions.imageType.imageType
                    }
                }
            val ratio =
                remember(item, viewOptions) {
                    if (item?.type == BaseItemKind.EPISODE) {
                        viewOptions.episodeAspectRatio.ratio
                    } else {
                        viewOptions.aspectRatio.ratio
                    }
                }
            val scale =
                remember(item, viewOptions) {
                    if (item?.type == BaseItemKind.EPISODE) {
                        viewOptions.episodeContentScale.scale
                    } else {
                        viewOptions.contentScale.scale
                    }
                }
            if (viewOptions.showTitles) {
                BannerCardWithTitle(
                    title = item?.title,
                    subtitle = item?.subtitle,
                    item = item,
                    aspectRatio = ratio,
                    imageType = imageType,
                    imageContentScale = scale,
                    cornerText = item?.ui?.episodeUnplayedCornerText,
                    played = item?.data?.userData?.played ?: false,
                    favorite = item?.favorite ?: false,
                    playPercent =
                        item?.data?.userData?.playedPercentage
                            ?: 0.0,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = modifier,
                    cardHeight = viewOptions.heightDp.dp,
                    useSeriesForPrimary = viewOptions.useSeries,
                )
            } else {
                BannerCard(
                    name = item?.data?.seriesName ?: item?.name,
                    item = item,
                    aspectRatio = ratio,
                    imageType = imageType,
                    imageContentScale = scale,
                    cornerText = item?.ui?.episodeUnplayedCornerText,
                    played = item?.data?.userData?.played ?: false,
                    favorite = item?.favorite ?: false,
                    playPercent =
                        item?.data?.userData?.playedPercentage
                            ?: 0.0,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = modifier,
                    interactionSource = null,
                    cardHeight = viewOptions.heightDp.dp,
                    useSeriesForPrimary = viewOptions.useSeries,
                )
            }
        }
    }
}

@Composable
fun HomePageViewMoreCard(
    isEpisode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    viewOptions: HomeRowViewOptions,
    modifier: Modifier,
) {
    val aspectRatio =
        remember(isEpisode, viewOptions) {
            if (isEpisode) {
                viewOptions.episodeAspectRatio
            } else {
                viewOptions.aspectRatio
            }
        }
    ViewMoreCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        aspectRatio = aspectRatio,
        size = DpSize(height = viewOptions.heightDp.dp, width = Dp.Unspecified),
        showTitle = viewOptions.showTitles,
    )
}

/** Jellytip: hero/text block lag behind focus by this much (fast scrolling skips the tiles in between). */
private const val HERO_DEBOUNCE_MS = 150L
