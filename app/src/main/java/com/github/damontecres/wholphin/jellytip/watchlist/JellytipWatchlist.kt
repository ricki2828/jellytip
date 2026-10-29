package com.github.damontecres.wholphin.jellytip.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeHeader
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeHero
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTheme
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.HomeItemFields
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
    ) : ViewModel() {
        private val _state = MutableStateFlow<WatchlistState>(WatchlistState.Loading)
        val state: StateFlow<WatchlistState> = _state

        fun load() {
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
    when (val s = state) {
        WatchlistState.Loading -> LoadingPage(modifier)
        WatchlistState.Fallback -> fallback()
        is WatchlistState.Loaded -> {
            var position by rememberPosition()
            val showLogos = preferences.appPreferences.interfacePreferences.showLogos
            JellytipHomeTheme {
                HomePageContent(
                    homeRows = s.rows,
                    position = position,
                    onFocusPosition = { position = it },
                    onClickItem = { clicked, item ->
                        position = clicked
                        if (!JellytipTrailerControl.onOk(item)) {
                            JellytipTrailerControl.stop()
                            viewModel.navigationManager.navigateTo(item.destination())
                        }
                    },
                    onLongClickItem = { clicked, item ->
                        position = clicked
                        JellytipTrailerControl.stop()
                        viewModel.navigationManager.navigateTo(item.destination())
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
                )
            }
        }
    }
}
