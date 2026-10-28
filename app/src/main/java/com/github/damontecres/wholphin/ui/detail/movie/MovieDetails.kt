package com.github.damontecres.wholphin.ui.detail.movie

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.github.damontecres.wholphin.jellytip.HeroScrollLock
import com.github.damontecres.wholphin.jellytip.BoxScopeDownHint
import androidx.compose.ui.Alignment
import com.github.damontecres.wholphin.jellytip.RestoreFocusOnResume
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.jellytip.JellytipButton
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBackground
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBlock
import com.github.damontecres.wholphin.jellytip.JellytipDetailsViewModel
import com.github.damontecres.wholphin.jellytip.subtitles.JellytipSubtitlesDialog
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.cards.ChapterRow
import com.github.damontecres.wholphin.ui.cards.ExtrasRow
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.PersonRow
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.Optional
import com.github.damontecres.wholphin.ui.components.PersonContextActions
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
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.UUID
import kotlin.time.Duration

@Composable
fun MovieDetails(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: MovieViewModel =
        hiltViewModel<MovieViewModel, MovieViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
    jellytipViewModel: JellytipDetailsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.init()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsState()

    var overviewDialog by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    var showContextMenu by remember { mutableStateOf<ContextMenu?>(null) }
    var showSubtitles by remember { mutableStateOf<BaseItem?>(null) }
    var showPlaylistDialog by remember { mutableStateOf<Optional<UUID>>(Optional.absent()) }
    val playlistState by playlistViewModel.playlistState.collectAsState()

    val userDto by viewModel.serverRepository.currentUserDtoFlow.collectAsState(null)
    val preferredSubtitleLanguage = userDto?.configuration?.subtitleLanguagePreference

    val contextActions =
        remember {
            ContextMenuActions(
                navigateTo = viewModel::navigateTo,
                onClickWatch = viewModel::setWatched,
                onClickFavorite = viewModel::setFavorite,
                onClickAddPlaylist = { itemId ->
                    playlistViewModel.loadPlaylists()
                    showPlaylistDialog.makePresent(itemId)
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
                onClearChosenStreams = { viewModel.clearChosenStreams(it) },
                onChangeStill = jellytipViewModel::cycleStill,
                onShowSubtitles = { showSubtitles = it },
            )
        }

    when (val s = state.loading) {
        is DataLoadingState.Error -> {
            ErrorMessage(s, modifier)
        }

        DataLoadingState.Loading,
        DataLoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Success -> {
            val unknownStr = stringResource(R.string.unknown)
            val movie by rememberUpdatedState(s.data)
            val chosenStreams by rememberUpdatedState(state.chosenStreams)
            LifecycleResumeEffect(destination.itemId) {
                viewModel.maybePlayThemeSong(destination.itemId)
                onPauseOrDispose {
                    viewModel.release()
                }
            }
            MovieDetailsContent(
                preferences = preferences,
                movie = movie,
                state = state,
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
                playOnClick = {
                    viewModel.navigateTo(
                        Destination.Playback(
                            movie.id,
                            it.inWholeMilliseconds,
                        ),
                    )
                },
                overviewOnClick = {
                    overviewDialog =
                        ItemDetailsDialogInfo(movie)
                },
                moreOnClick = {
                    showContextMenu =
                        ContextMenu.ForBaseItem(
                            fromLongClick = false,
                            item = movie,
                            chosenStreams = chosenStreams,
                            showGoTo = false,
                            showStreamChoices = true,
                            canDelete = state.canDelete,
                            canRemoveContinueWatching = false,
                            canRemoveNextUp = false,
                            actions = contextActions,
                        )
                },
                watchOnClick = {
                    viewModel.setWatched(movie.id, !movie.played)
                },
                favoriteOnClick = {
                    viewModel.setFavorite(movie.id, !movie.favorite)
                },
                onLongClickPerson = { index, person ->
                    showContextMenu =
                        ContextMenu.ForPerson(
                            fromLongClick = true,
                            person = person,
                            actions =
                                PersonContextActions(
                                    navigateTo = viewModel::navigateTo,
                                    onClickFavorite = viewModel::setFavorite,
                                ),
                        )
                },
                onLongClickSimilar = { _, similar ->
                    showContextMenu =
                        ContextMenu.ForBaseItem(
                            fromLongClick = true,
                            item = similar,
                            chosenStreams = null,
                            showGoTo = true,
                            showStreamChoices = false,
                            canDelete = false,
                            canRemoveContinueWatching = false,
                            canRemoveNextUp = false,
                            actions = contextActions,
                        )
                },
                trailerOnClick = {
                    TrailerService.onClick(context, it, viewModel::navigateTo)
                },
                onClickExtra = { index, extra ->
                    viewModel.navigateTo(extra.destination)
                },
                onClickDiscover = { index, item ->
                    viewModel.navigateTo(item.destination)
                },
                canDelete = state.canDelete,
                onConfirmDelete = { state.movie?.let { viewModel.deleteItem(it) } },
                onChooseVersion = { contextActions.onChooseVersion.invoke(movie, it) },
                modifier = modifier,
            )
        }
    }
    showContextMenu?.let { contextMenu ->
        ContextMenuDialog(
            onDismissRequest = { showContextMenu = null },
            getMediaSource = viewModel.streamChoiceService::chooseSource,
            contextMenu = contextMenu,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
        )
    }
    showSubtitles?.let { JellytipSubtitlesDialog(it, onDismissRequest = { showSubtitles = null }) }
    overviewDialog?.let { info ->
        ItemDetailsDialog(
            info = info,
            showFilePath =
                userDto
                    ?.policy
                    ?.isAdministrator == true,
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
private const val PEOPLE_ROW = HEADER_ROW + 1
private const val TRAILER_ROW = PEOPLE_ROW + 1
private const val CHAPTER_ROW = TRAILER_ROW + 1
private const val EXTRAS_ROW = CHAPTER_ROW + 1
private const val SIMILAR_ROW = EXTRAS_ROW + 1
private const val DISCOVER_ROW = SIMILAR_ROW + 1

@Composable
fun MovieDetailsContent(
    preferences: UserPreferences,
    movie: BaseItem,
    state: MovieState,
    playOnClick: (Duration) -> Unit,
    trailerOnClick: (Trailer) -> Unit,
    overviewOnClick: () -> Unit,
    watchOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    moreOnClick: () -> Unit,
    onClickItem: (Int, BaseItem) -> Unit,
    onClickPerson: (Person) -> Unit,
    onLongClickPerson: (Int, Person) -> Unit,
    onLongClickSimilar: (Int, BaseItem) -> Unit,
    onClickExtra: (Int, ExtrasItem) -> Unit,
    onClickDiscover: (Int, DiscoverItem) -> Unit,
    canDelete: Boolean,
    onConfirmDelete: () -> Unit,
    onChooseVersion: (MediaSourceInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var position by rememberInt(0)
    val focusRequesters = remember { List(DISCOVER_ROW + 1) { FocusRequester() } }
    val dto = movie.data
    val resumePosition = dto.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO

    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    // Always open hero-first: Play focused, list scrolled to top (never restore a saved cast/extras row).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
        focusRequesters[HEADER_ROW].tryRequestFocus()
    }
    var buttonRowFocused by remember { mutableStateOf(false) }
    var topFocused by remember { mutableStateOf(true) }
    // Sections are not composed at all until the user presses Down from the header, so moving along the
    // buttons can never scroll to, reveal or flash them.
    var sectionsActive by remember { mutableStateOf(false) }
    val hasBelow = state.people.isNotEmpty() || state.chapters.isNotEmpty() || state.extras.isNotEmpty() || state.similar.isNotEmpty() || state.discovered.isNotEmpty()
    LaunchedEffect(topFocused) { if (topFocused) sectionsActive = false }
    LaunchedEffect(sectionsActive) {
        if (sectionsActive) {
            // Rows only exist once sectionsActive composes them: wait for that, scroll to them, then focus
            withFrameNanos { }
            withFrameNanos { }
            if (listState.layoutInfo.totalItemsCount > 1) listState.animateScrollToItem(1)
            withFrameNanos { }
            var focused = false
            for (r in listOf(PEOPLE_ROW, CHAPTER_ROW, EXTRAS_ROW, SIMILAR_ROW, DISCOVER_ROW)) {
                if (focusRequesters[r].tryRequestFocus()) {
                    focused = true
                    break
                }
            }
            // Nothing took focus: fall back to the header so Down is not swallowed forever
            if (!focused) {
                sectionsActive = false
                listState.scrollToItem(0)
                focusRequesters[HEADER_ROW].tryRequestFocus()
            }
        }
    }
    val headerAlpha by animateFloatAsState(if (topFocused) 1f else 0f, tween(250), label = "jellytip-header")

    var pageHasFocus by remember { mutableStateOf(false) }
    RestoreFocusOnResume(focusRequesters[HEADER_ROW], pageHasFocus)
    Box(modifier = modifier.onFocusChanged { pageHasFocus = it.hasFocus }) {
        JellytipDetailsBackground(movie, state.trailers, buttonRowFocused, dimmed = !topFocused)
        LazyColumn(
                state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                JellytipDetailsBlock(
                    item = movie,
                    buttons =
                        buildList {
                            add(
                                JellytipButton(
                                    label =
                                        stringResource(
                                            if (resumePosition > Duration.ZERO) R.string.resume else R.string.play,
                                        ),
                                    primary = true,
                                    onClick = {
                                        position = HEADER_ROW
                                        playOnClick.invoke(resumePosition)
                                    },
                                ),
                            )
                            if (resumePosition > Duration.ZERO) {
                                add(
                                    JellytipButton(stringResource(R.string.restart), {
                                        position = HEADER_ROW
                                        playOnClick.invoke(Duration.ZERO)
                                    }),
                                )
                            }
                            add(
                                JellytipButton(
                                    stringResource(
                                        if (dto.userData?.isFavorite == true) R.string.remove_favorite else R.string.add_favorite,
                                    ),
                                    favoriteOnClick,
                                ),
                            )
                            add(JellytipButton(stringResource(R.string.jellytip_more), moreOnClick))
                        },
                    trailers = state.trailers,
                    onTrailer = {
                        position = TRAILER_ROW
                        trailerOnClick.invoke(it)
                    },
                    onOverview = overviewOnClick,
                    buttonRowModifier =
                        Modifier
                            .focusRequester(focusRequesters[HEADER_ROW])
                            .onPreviewKeyEvent {
                                if (hasBelow && it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                    // Already active: let the focus system move Down normally
                                    if (sectionsActive) {
                                        false
                                    } else {
                                        sectionsActive = true
                                        true
                                    }
                                } else {
                                    false
                                }
                            }
                            .onFocusChanged {
                                buttonRowFocused = it.hasFocus
                                if (it.hasFocus) position = HEADER_ROW
                            },
                    modifier = Modifier.fillParentMaxSize().graphicsLayer { alpha = headerAlpha },
                    onFocusWithin = { topFocused = it },
                )
            }
            (if (sectionsActive) state.people else emptyList()).letNotEmpty { people ->
                item {
                    PersonRow(
                        people = people,
                        onClick = {
                            position = PEOPLE_ROW
                            onClickPerson.invoke(it)
                        },
                        onLongClick = { index, person ->
                            position = PEOPLE_ROW
                            onLongClickPerson.invoke(index, person)
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequesters[PEOPLE_ROW]),
                    )
                }
            }
            (if (sectionsActive) state.chapters else emptyList()).letNotEmpty { chapters ->
                item {
                    ChapterRow(
                        chapters = chapters,
                        onClick = {
                            position = CHAPTER_ROW
                            playOnClick.invoke(it.position)
                        },
                        onLongClick = {},
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequesters[CHAPTER_ROW]),
                    )
                }
            }
            (if (sectionsActive) state.extras else emptyList()).letNotEmpty { extras ->
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
            (if (sectionsActive) state.similar else emptyList()).letNotEmpty { similar ->
                item {
                    val imageHeight =
                        remember(movie.type) {
                            if (movie.type == BaseItemKind.MOVIE) {
                                Cards.height2x3
                            } else {
                                Cards.heightEpisode
                            }
                        }
                    ItemRow(
                        title = stringResource(R.string.more_like_this),
                        items = similar,
                        onClickItem = { index, item ->
                            position = SIMILAR_ROW
                            onClickItem.invoke(index, item)
                        },
                        onLongClickItem = { index, similar ->
                            position = SIMILAR_ROW
                            onLongClickSimilar.invoke(index, similar)
                        },
                        cardContent = { index, item, mod, onClick, onLongClick ->
                            SeasonCard(
                                item = item,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                modifier = mod,
                                showImageOverlay = true,
                                imageHeight = imageHeight,
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
            (if (sectionsActive) state.discovered else emptyList()).letNotEmpty { discovered ->
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
        BoxScopeDownHint(
            visible = topFocused && hasBelow && !sectionsActive,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
