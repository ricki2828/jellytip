package com.github.damontecres.wholphin.jellytip.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The few services the hosted library menu needs (the ones Home's view model gives its menu). */
@HiltViewModel
class JellytipLibraryMenuViewModel
    @Inject
    constructor(
        val navigationManager: NavigationManager,
        private val favoriteWatchManager: FavoriteWatchManager,
    ) : ViewModel() {
        fun setWatched(
            id: UUID,
            played: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setWatched(id, played)
        }

        fun setFavorite(
            id: UUID,
            favorite: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setFavorite(id, favorite)
        }
    }

/**
 * The library tile's hold-OK menu on a page that has no Home view model: the SAME menu Home shows ([ContextMenu.ForBaseItem]
 * through [ContextMenuDialog]), with the actions these pages can reach (navigation, watched, My list, add to playlist,
 * media information).
 *
 * OMITTED (they need Home's own state): Delete, Remove from Continue Watching / Next Up, stream / version choices, change
 * still, subtitle tools. Their menu items are not shown (their flags are false / the actions are null).
 *
 * Use: `var menuItem by remember { mutableStateOf<BaseItem?>(null) }`, tile `onLongClick = { JellytipInputGuard.afterRelease { menuItem = item } }`,
 * and once on the page `JellytipLibraryMenuHost(menuItem) { menuItem = null }`. Plain values only (an item and a dismiss lambda).
 */
@Composable
fun JellytipLibraryMenuHost(
    item: BaseItem?,
    onDismiss: () -> Unit,
    viewModel: JellytipLibraryMenuViewModel = hiltViewModel(),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    var overview by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    var playlistFor by remember { mutableStateOf<UUID?>(null) }
    val playlistState by playlistViewModel.playlistState.collectAsState()
    if (item != null) {
        val menu =
            remember(item) {
                ContextMenu.ForBaseItem(
                    fromLongClick = true,
                    item = item,
                    chosenStreams = null,
                    showGoTo = true,
                    showStreamChoices = false,
                    canDelete = false,
                    canRemoveContinueWatching = false,
                    canRemoveNextUp = false,
                    actions =
                        ContextMenuActions(
                            navigateTo = { destination ->
                                JellytipTrailerControl.stop()
                                viewModel.navigationManager.navigateTo(destination)
                            },
                            onClickWatch = viewModel::setWatched,
                            onClickFavorite = viewModel::setFavorite,
                            onClickAddPlaylist = { id ->
                                playlistViewModel.loadPlaylists()
                                playlistFor = id
                            },
                            onSendMediaInfo = {},
                            onDeleteItem = {},
                            onChooseVersion = { _, _ -> },
                            onChooseTracks = {},
                            onShowOverview = { overview = ItemDetailsDialogInfo(it) },
                            onClearChosenStreams = {},
                        ),
                )
            }
        ContextMenuDialog(onDismissRequest = onDismiss, getMediaSource = null, contextMenu = menu, preferredSubtitleLanguage = null)
    }
    overview?.let { info ->
        ItemDetailsDialog(info = info, showFilePath = false, onDismissRequest = { overview = null })
    }
    playlistFor?.let { itemId ->
        PlaylistDialog(
            title = stringResource(R.string.add_to_playlist),
            state = playlistState,
            onDismissRequest = { playlistFor = null },
            onClick = {
                playlistViewModel.addToPlaylist(it.id, itemId)
                playlistFor = null
            },
            createEnabled = true,
            onCreatePlaylist = {
                playlistViewModel.createPlaylistAndAddItem(it, itemId)
                playlistFor = null
            },
            onSearch = playlistViewModel::loadPlaylists,
            elevation = 3.dp,
        )
    }
}

/** The state a page keeps for [JellytipLibraryMenuHost]: [open] from a tile's hold-OK, [Host] once on the page. */
class JellytipLibraryMenuState {
    var item by mutableStateOf<BaseItem?>(null)
    var used by mutableStateOf(false)

    /** Opens the menu after the hold-OK key is released (the key-up must not land on the first entry). */
    fun open(tile: BaseItem) {
        com.github.damontecres.wholphin.jellytip.JellytipInputGuard.afterRelease {
            used = true
            item = tile
        }
    }
}

@Composable
fun rememberJellytipLibraryMenu(): JellytipLibraryMenuState = remember { JellytipLibraryMenuState() }

/** Composes the hosted menu (call once per page / tile) once a tile has asked for it (before that nothing is built, so no view model is needed). */
@Composable
fun JellytipLibraryMenuHostOf(state: JellytipLibraryMenuState) {
    if (state.used) JellytipLibraryMenuHost(item = state.item, onDismiss = { state.item = null })
}
