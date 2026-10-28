package com.github.damontecres.wholphin.ui.detail.episode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipButton
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBackground
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBlock
import com.github.damontecres.wholphin.jellytip.subtitles.JellytipSubtitlesDialog
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.RequestOrRestoreFocus
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.Optional
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.util.DataLoadingState
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.UUID
import kotlin.time.Duration

@Composable
fun EpisodeDetails(
    preferences: UserPreferences,
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: EpisodeViewModel =
        hiltViewModel<EpisodeViewModel, EpisodeViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
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
    val canDelete by viewModel.canDelete.collectAsState()

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
                onShowOverview = { overviewDialog = ItemDetailsDialogInfo(it) },
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
                onClearChosenStreams = { viewModel.clearChosenStreams(it) },
                onShowSubtitles = { showSubtitles = it },
            )
        }

    when (val st = state.episode) {
        is DataLoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        DataLoadingState.Loading,
        DataLoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        is DataLoadingState.Success<BaseItem> -> {
            val ep = st.data
            LifecycleResumeEffect(ep) {
                ep.data.seriesId?.let { seriesId ->
                    viewModel.maybePlayThemeSong(seriesId)
                }
                onPauseOrDispose {
                    viewModel.release()
                }
            }
            EpisodeDetailsContent(
                preferences = preferences,
                ep = ep,
                chosenStreams = state.chosenStreams,
                playOnClick = {
                    viewModel.navigateTo(
                        Destination.Playback(
                            ep.id,
                            it.inWholeMilliseconds,
                        ),
                    )
                },
                overviewOnClick = {
                    overviewDialog =
                        ItemDetailsDialogInfo(ep)
                },
                moreOnClick = {
                    showContextMenu =
                        ContextMenu.ForBaseItem(
                            fromLongClick = false,
                            item = ep,
                            chosenStreams = state.chosenStreams,
                            showGoTo = false,
                            showStreamChoices = true,
                            canDelete = canDelete,
                            canRemoveContinueWatching = false,
                            canRemoveNextUp = false,
                            actions = contextActions,
                        )
                },
                watchOnClick = {
                    viewModel.setWatched(ep.id, !ep.played)
                },
                favoriteOnClick = {
                    viewModel.setFavorite(ep.id, !ep.favorite)
                },
                canDelete = canDelete,
                onConfirmDelete = { viewModel.deleteItem(ep) },
                onChooseVersion = { contextActions.onChooseVersion.invoke(ep, it) },
                modifier = modifier,
            )
        }
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
    showContextMenu?.let { contextMenu ->
        ContextMenuDialog(
            onDismissRequest = { showContextMenu = null },
            getMediaSource = viewModel.streamChoiceService::chooseSource,
            contextMenu = contextMenu,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
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

@Composable
fun EpisodeDetailsContent(
    preferences: UserPreferences,
    ep: BaseItem,
    chosenStreams: ChosenStreams?,
    playOnClick: (Duration) -> Unit,
    overviewOnClick: () -> Unit,
    watchOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    moreOnClick: () -> Unit,
    canDelete: Boolean,
    onConfirmDelete: () -> Unit,
    onChooseVersion: (MediaSourceInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var position by rememberInt(0)
    val focusRequesters = remember { List(1) { FocusRequester() } }
    val dto = ep.data
    val resumePosition = dto.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO
    var buttonRowFocused by remember { mutableStateOf(false) }

    RequestOrRestoreFocus(focusRequesters.getOrNull(position))
    Box(modifier = modifier) {
        JellytipDetailsBackground(ep, emptyList(), buttonRowFocused)
        JellytipDetailsBlock(
            item = ep,
            subtitle = ep.ui.episodeCornerText?.let { "$it \u00B7 ${ep.name ?: ""}" } ?: ep.name,
            buttons =
                buildList {
                    add(
                        JellytipButton(
                            label = stringResource(if (resumePosition > Duration.ZERO) R.string.resume else R.string.play),
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
                            stringResource(if (dto.userData?.isFavorite == true) R.string.remove_favorite else R.string.add_favorite),
                            favoriteOnClick,
                        ),
                    )
                    add(JellytipButton(stringResource(R.string.jellytip_more), moreOnClick))
                },
            trailers = emptyList(),
            onTrailer = {},
            onOverview = overviewOnClick,
            buttonRowModifier =
                Modifier
                    .focusRequester(focusRequesters[HEADER_ROW])
                    .onFocusChanged { buttonRowFocused = it.hasFocus },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
