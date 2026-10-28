package com.github.damontecres.wholphin.ui.detail.series

import com.github.damontecres.wholphin.jellytip.RestoreFocusOnResume
import android.content.res.Resources
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.data.model.studioNames
import com.github.damontecres.wholphin.jellytip.JellytipButton
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBackground
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBlock
import com.github.damontecres.wholphin.jellytip.nav.JellytipBreadcrumb
import com.github.damontecres.wholphin.jellytip.nav.JellytipCrumb
import com.github.damontecres.wholphin.jellytip.JellytipDetailsViewModel
import com.github.damontecres.wholphin.jellytip.JellytipScoreRow
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.cards.ExtrasRow
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.PersonRow
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.components.ConfirmDialog
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.GenreText
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.Optional
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.PersonContextActions
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.ui.discover.DiscoverRow
import com.github.damontecres.wholphin.ui.discover.DiscoverRowData
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.UUID

@Composable
fun SeriesDetails(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: SeriesViewModel =
        hiltViewModel<SeriesViewModel, SeriesViewModel.Factory>(
            creationCallback = {
                it.create(destination.itemId, null, SeriesPageType.DETAILS)
            },
        ),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
    jellytipViewModel: JellytipDetailsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsState()
    val playlistState by playlistViewModel.playlistState.collectAsState()

    var overviewDialog by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    var showContextMenu by remember { mutableStateOf<ContextMenu?>(null) }

    var showWatchConfirmation by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf<Optional<UUID>>(Optional.absent()) }

    val contextActions =
        remember {
            ContextMenuActions(
                navigateTo = viewModel::navigateTo,
                onClickWatch = { itemId, watched ->
                    if (itemId == destination.itemId) {
                        // Confirm if marking whole series
                        showWatchConfirmation = true
                    } else {
                        viewModel.setWatched(itemId, watched, null)
                    }
                },
                onClickFavorite = { itemId, favorite ->
                    viewModel.setFavorite(itemId, favorite, null)
                },
                onClickAddPlaylist = { itemId ->
                    playlistViewModel.loadPlaylists()
                    showPlaylistDialog = Optional.present(itemId)
                },
                onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                onDeleteItem = viewModel::deleteItem,
                onChooseVersion = { item, source ->
                    viewModel.savePlayVersion(
                        item,
                        source.id!!.toUUID(),
                    )
                },
                onChooseTracks = { result ->
                    viewModel.saveTrackSelection(
                        result.item,
                        result.itemPlayback,
                        result.trackIndex,
                        result.streamType,
                    )
                },
                onShowOverview = { overviewDialog = ItemDetailsDialogInfo(it) },
                onClearChosenStreams = {},
                onChangeStill = jellytipViewModel::cycleStill,
            )
        }

    LifecycleResumeEffect(destination.itemId) {
        viewModel.refresh()

        onPauseOrDispose {
            viewModel.release()
        }
    }

    when (val st = state.series) {
        is DataLoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        DataLoadingState.Loading,
        DataLoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Success<BaseItem> -> {
            LifecycleResumeEffect(destination.itemId) {
                viewModel.onResumePage()

                onPauseOrDispose {}
            }
            val series = st.data
            val played = series.data.userData?.played ?: false
            SeriesDetailsContent(
                preferences = preferences,
                series = series,
                seasons = state.seasons,
                trailers = state.trailers,
                extras = state.extras,
                people = state.people,
                similar = state.similar,
                played = played,
                favorite = series.data.userData?.isFavorite ?: false,
                canDelete = state.canDeleteSeries,
                modifier = modifier,
                onClickItem = { index, item ->
                    viewModel.navigateTo(item.destination())
                },
                onClickPerson = {
                    viewModel.navigateTo(
                        Destination.MediaItem(
                            it.id,
                            BaseItemKind.PERSON,
                        ),
                    )
                },
                onLongClickItem = { index, season ->
                    showContextMenu =
                        ContextMenu.ForBaseItem(
                            fromLongClick = true,
                            item = season,
                            chosenStreams = null,
                            showGoTo = true,
                            showStreamChoices = false,
                            canDelete = viewModel.canDelete(season, preferences.appPreferences),
                            canRemoveContinueWatching = false,
                            canRemoveNextUp = false,
                            actions = contextActions,
                        )
                },
                overviewOnClick = {
                    overviewDialog = ItemDetailsDialogInfo(series)
                },
                playOnClick = { shuffle ->
                    if (shuffle) {
                        viewModel.navigateTo(
                            Destination.PlaybackList(
                                itemId = series.id,
                                shuffle = true,
                            ),
                        )
                    } else {
                        viewModel.playNextUp()
                    }
                },
                watchOnClick = { showWatchConfirmation = true },
                favoriteOnClick = {
                    val favorite = series.data.userData?.isFavorite ?: false
                    viewModel.setFavorite(series.id, !favorite, null)
                },
                trailerOnClick = {
                    TrailerService.onClick(context, it, viewModel::navigateTo)
                },
                onClickExtra = { _, extra ->
                    viewModel.navigateTo(extra.destination)
                },
                discoverSeries = state.discoverSeries,
                onClickDiscoverSeries = {
                    state.discoverSeries?.let {
                        viewModel.navigateTo(Destination.DiscoveredItem(it))
                    }
                },
                discovered = state.discovered,
                onClickDiscover = { index, item ->
                    viewModel.navigateTo(item.destination)
                },
                onShowContextMenu = {
                    showContextMenu = it
                },
                actions = contextActions,
            )
            if (showWatchConfirmation) {
                ConfirmDialog(
                    title = series.name ?: "",
                    body =
                        stringResource(if (played) R.string.mark_entire_series_as_unplayed else R.string.mark_entire_series_as_played),
                    onCancel = {
                        showWatchConfirmation = false
                    },
                    onConfirm = {
                        viewModel.setWatchedSeries(!played)
                        showWatchConfirmation = false
                    },
                )
            }
        }
    }
    showContextMenu?.let { contextMenu ->
        ContextMenuDialog(
            onDismissRequest = { showContextMenu = null },
            getMediaSource = viewModel.streamChoiceService::chooseSource,
            contextMenu = contextMenu,
            preferredSubtitleLanguage = null,
        )
    }
    overviewDialog?.let { info ->
        ItemDetailsDialog(
            info = info,
            showFilePath = false,
            onDismissRequest = { overviewDialog = null },
        )
    }
    showPlaylistDialog.compose { itemId ->
        PlaylistDialog(
            title = stringResource(R.string.add_to_playlist),
            state = playlistState,
            onDismissRequest = { showPlaylistDialog.makeAbsent() },
            onClick = {
                playlistViewModel.addToPlaylist(it.id, itemId)
                showPlaylistDialog.makeAbsent()
            },
            createEnabled = true,
            onCreatePlaylist = {
                playlistViewModel.createPlaylistAndAddItem(it, itemId)
                showPlaylistDialog.makeAbsent()
            },
            onSearch = playlistViewModel::loadPlaylists,
            elevation = 3.dp,
        )
    }
}

private const val HEADER_ROW = 0
private const val SEASONS_ROW = HEADER_ROW + 1
private const val PEOPLE_ROW = SEASONS_ROW + 1
private const val TRAILER_ROW = PEOPLE_ROW + 1
private const val EXTRAS_ROW = TRAILER_ROW + 1
private const val SIMILAR_ROW = EXTRAS_ROW + 1
private const val DISCOVER_ROW = SIMILAR_ROW + 1

@Composable
fun SeriesDetailsContent(
    preferences: UserPreferences,
    series: BaseItem,
    seasons: List<BaseItem?>,
    similar: List<BaseItem>,
    trailers: List<Trailer>,
    extras: List<ExtrasItem>,
    people: List<Person>,
    discovered: List<DiscoverItem>,
    played: Boolean,
    favorite: Boolean,
    canDelete: Boolean,
    onClickItem: (Int, BaseItem) -> Unit,
    onClickPerson: (Person) -> Unit,
    onLongClickItem: (Int, BaseItem) -> Unit,
    overviewOnClick: () -> Unit,
    playOnClick: (Boolean) -> Unit,
    watchOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    trailerOnClick: (Trailer) -> Unit,
    onClickExtra: (Int, ExtrasItem) -> Unit,
    onShowContextMenu: (ContextMenu) -> Unit,
    actions: ContextMenuActions,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
    discoverSeries: DiscoverItem?,
    onClickDiscoverSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    var position by rememberInt()
    val focusRequesters = remember { List(DISCOVER_ROW + 1) { FocusRequester() } }
    val playFocusRequester = remember { FocusRequester() }
    // Always open hero-first: Play focused, list scrolled to top (never restore a saved cast/extras row).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
        focusRequesters[HEADER_ROW].tryRequestFocus()
    }
    var buttonRowFocused by remember { mutableStateOf(false) }
    var topFocused by remember { mutableStateOf(true) }
    val headerAlpha by animateFloatAsState(if (topFocused) 1f else 0f, tween(250), label = "jellytip-header")

    var pageHasFocus by remember { mutableStateOf(false) }
    RestoreFocusOnResume(focusRequesters[HEADER_ROW], pageHasFocus)
    Box(
        modifier = modifier.onFocusChanged { pageHasFocus = it.hasFocus },
    ) {
        JellytipDetailsBackground(series, trailers, buttonRowFocused, dimmed = !topFocused)
        JellytipBreadcrumb(
            crumbs = listOf(JellytipCrumb(series.name ?: "", {})),
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 64.dp, top = 24.dp)
                    .graphicsLayer { alpha = headerAlpha },
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier,
            ) {
                item {
                    JellytipDetailsBlock(
                        item = series,
                        buttons =
                            buildList {
                                add(
                                    JellytipButton(stringResource(R.string.play), primary = true, onClick = {
                                        position = HEADER_ROW
                                        playOnClick.invoke(false)
                                    }),
                                )
                                add(
                                    JellytipButton(stringResource(R.string.shuffle), {
                                        position = HEADER_ROW
                                        playOnClick.invoke(true)
                                    }),
                                )
                                add(
                                    JellytipButton(
                                        stringResource(if (favorite) R.string.remove_favorite else R.string.add_favorite),
                                        favoriteOnClick,
                                    ),
                                )
                                if (discoverSeries != null) {
                                    add(JellytipButton(stringResource(R.string.discover), onClickDiscoverSeries))
                                }
                                add(
                                    JellytipButton(stringResource(R.string.jellytip_more), {
                                        onShowContextMenu(
                                            ContextMenu.ForBaseItem(
                                                fromLongClick = false,
                                                item = series,
                                                chosenStreams = null,
                                                showGoTo = false,
                                                showStreamChoices = false,
                                                canDelete = canDelete,
                                                canRemoveContinueWatching = false,
                                                canRemoveNextUp = false,
                                                actions = actions,
                                            ),
                                        )
                                    }),
                                )
                            },
                        trailers = trailers,
                        onTrailer = {
                            position = TRAILER_ROW
                            trailerOnClick.invoke(it)
                        },
                        onOverview = overviewOnClick,
                        buttonRowModifier =
                            Modifier
                                .focusRequester(focusRequesters[HEADER_ROW])
                                .onFocusChanged {
                                    buttonRowFocused = it.hasFocus
                                    if (it.hasFocus) position = HEADER_ROW
                                },
                        modifier = Modifier.fillParentMaxSize().graphicsLayer { alpha = headerAlpha },
                    onFocusWithin = { topFocused = it },
                    )
                }
                item {
                    ItemRow(
                        title = stringResource(R.string.tv_seasons) + " (${seasons.size})",
                        items = seasons,
                        onClickItem = { index, item ->
                            position = SEASONS_ROW
                            onClickItem.invoke(index, item)
                        },
                        onLongClickItem = { index, item ->
                            position = SEASONS_ROW
                            onLongClickItem.invoke(index, item)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequesters[SEASONS_ROW]),
                        cardContent = @Composable { index, item, mod, onClick, onLongClick ->
                            SeasonCard(
                                item = item,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                imageHeight = Cards.height2x3,
                                imageWidth = Dp.Unspecified,
                                showImageOverlay = true,
                                modifier = mod,
                            )
                        },
                    )
                }
                if (people.isNotEmpty()) {
                    item {
                        PersonRow(
                            people = people,
                            onClick = {
                                position = PEOPLE_ROW
                                onClickPerson.invoke(it)
                            },
                            onLongClick = { index, person ->
                                position = PEOPLE_ROW
                                onShowContextMenu.invoke(
                                    ContextMenu.ForPerson(
                                        fromLongClick = true,
                                        person = person,
                                        actions =
                                            PersonContextActions(
                                                navigateTo = actions.navigateTo,
                                                onClickFavorite = actions.onClickFavorite,
                                            ),
                                    ),
                                )
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequesters[PEOPLE_ROW]),
                        )
                    }
                }
                if (extras.isNotEmpty()) {
                    item {
                        ExtrasRow(
                            extras = extras,
                            onClickItem = { index, item ->
                                position = EXTRAS_ROW
                                onClickExtra.invoke(index, item)
                            },
                            onLongClickItem = { _, _ -> },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequesters[EXTRAS_ROW]),
                        )
                    }
                }
                if (similar.isNotEmpty()) {
                    item {
                        ItemRow(
                            title = stringResource(R.string.more_like_this),
                            items = similar,
                            onClickItem = { index, item ->
                                position = SIMILAR_ROW
                                onClickItem.invoke(index, item)
                            },
                            onLongClickItem = { index, item ->
                                position = SIMILAR_ROW
                                onShowContextMenu.invoke(
                                    ContextMenu.ForBaseItem(
                                        fromLongClick = true,
                                        item = item,
                                        chosenStreams = null,
                                        showGoTo = true,
                                        showStreamChoices = false,
                                        canDelete = false,
                                        canRemoveContinueWatching = false,
                                        canRemoveNextUp = false,
                                        actions = actions,
                                    ),
                                )
                            },
                            cardContent = { index, item, mod, onClick, onLongClick ->
                                SeasonCard(
                                    item = item,
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                    modifier = mod,
                                    showImageOverlay = true,
                                    imageHeight = Cards.height2x3,
                                    imageWidth = Dp.Unspecified,
                                )
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequesters[SIMILAR_ROW]),
                        )
                    }
                }
                if (discovered.isNotEmpty()) {
                    item {
                        DiscoverRow(
                            row =
                                DiscoverRowData(
                                    ResStringProvider(R.string.discover),
                                    DataLoadingState.Success(discovered),
                                    type = DiscoverRequestType.UNKNOWN,
                                ),
                            onClickItem = { index: Int, item: DiscoverItem ->
                                position = DISCOVER_ROW
                                onClickDiscover.invoke(index, item)
                            },
                            onLongClickItem = { _, _ -> },
                            onCardFocus = {},
                            focusRequester = focusRequesters[DISCOVER_ROW],
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailsHeader(
    series: BaseItem,
    showLogo: Boolean,
    overviewOnClick: () -> Unit,
    bringIntoViewRequester: BringIntoViewRequester,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val dto = series.data
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        TitleOrLogo(
            item = series,
            showLogo = showLogo,
            modifier =
                Modifier
                    .fillMaxWidth(.75f)
                    .padding(start = HeaderUtils.startPadding),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(.60f),
        ) {
            QuickDetails(
                series.ui.quickDetails,
                null,
                Modifier.padding(start = HeaderUtils.startPadding),
            )
            dto.studios?.let {
                val studios = remember { series.studioNames }
                GenreText(
                    studios,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = HeaderUtils.startPadding),
                )
            }
            dto.genres?.letNotEmpty {
                GenreText(it, Modifier.padding(start = HeaderUtils.startPadding, bottom = 4.dp))
            }
            JellytipScoreRow(series)
            dto.overview?.let { overview ->
                OverviewText(
                    overview = overview,
                    maxLines = 3,
                    onClick = overviewOnClick,
                    textBoxHeight = Dp.Unspecified,
                    modifier =
                        Modifier.onFocusChanged {
                            if (it.isFocused) {
                                scope.launch(ExceptionHandler()) {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                )
            }
        }
    }
}

fun buildDialogForSeason(
    resources: Resources,
    s: BaseItem,
    canDelete: Boolean,
    onClickItem: (BaseItem) -> Unit,
    markPlayed: (Boolean) -> Unit,
    onClickPlay: (Boolean) -> Unit,
    onClickDelete: (BaseItem) -> Unit,
): DialogParams {
    val items =
        buildList {
            add(
                DialogItem(resources.getString(R.string.go_to), Icons.Default.PlayArrow) {
                    onClickItem.invoke(s)
                },
            )
            if (s.data.userData?.played == true) {
                add(
                    DialogItem(resources.getString(R.string.mark_unwatched), R.string.fa_eye) {
                        markPlayed.invoke(false)
                    },
                )
            } else {
                add(
                    DialogItem(resources.getString(R.string.mark_watched), R.string.fa_eye_slash) {
                        markPlayed.invoke(true)
                    },
                )
            }
            add(
                DialogItem(
                    resources.getString(R.string.play),
                    Icons.Default.PlayArrow,
                ) {
                    onClickPlay.invoke(false)
                },
            )
            add(
                DialogItem(
                    resources.getString(R.string.shuffle),
                    R.string.fa_shuffle,
                ) {
                    onClickPlay.invoke(true)
                },
            )
            if (canDelete) {
                add(
                    DialogItem(
                        resources.getString(R.string.delete),
                        Icons.Default.Delete,
                        iconColor = Color.Red.copy(alpha = .8f),
                    ) {
                        onClickDelete.invoke(s)
                    },
                )
            }
        }
    return DialogParams(
        title = s.name ?: resources.getString(R.string.tv_season),
        fromLongClick = true,
        items = items,
    )
}
