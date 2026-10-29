package com.github.damontecres.wholphin.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.jellytip.home.JellytipCarouselRows
import com.github.damontecres.wholphin.data.model.ServerUserConfig
import com.github.damontecres.wholphin.jellytip.cache.HomeCache
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DatePlayedService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.HomeDataService
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.LatestNextUpService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.collectLatestIn
import com.github.damontecres.wholphin.ui.combinePair
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.util.EmptyStringProvider
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val serverReportService: ServerReportService,
        private val navDrawerService: NavDrawerService,
        private val homeSettingsService: HomeSettingsService,
        private val homeDataService: HomeDataService,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val datePlayedService: DatePlayedService,
        private val backdropService: BackdropService,
        private val userPreferencesService: UserPreferencesService,
        private val mediaManagementService: MediaManagementService,
        private val latestNextUpService: LatestNextUpService,
        private val homeCache: HomeCache,
        private val jellytipRows: com.github.damontecres.wholphin.jellytip.rows.JellytipRowsService,
    ) : ViewModel() {
        private val _state = MutableStateFlow(HomeState.EMPTY)
        val state: StateFlow<HomeState> = _state

        private var dataLoadingJob: Job? = null

        init {
            datePlayedService.invalidateAll()
            serverRepository.currentUserDtoFlow
                .combinePair(homeSettingsService.currentSettings)
                .collectLatestIn(viewModelScope) { (userDto, settings) ->
                    Timber.v(
                        "Got new userDto & settings, userId=%s, settings?=%s",
                        userDto?.id,
                        settings != HomePageResolvedSettings.EMPTY,
                    )
//                    Timber.v("userDto=%s", userDto)
                    dataLoadingJob?.cancel()
                    if (userDto == null) {
                        Timber.d("UserDto is null")
                        _state.update { HomeState.EMPTY }
                        return@collectLatestIn
                    }
                    if (settings == HomePageResolvedSettings.EMPTY) {
                        Timber.d("Home settings are empty")
                        _state.update { HomeState.EMPTY }
                        return@collectLatestIn
                    }
                    if (userDto.id != settings.userId) {
                        Timber.d("User IDs don't match: %s vs %s", userDto?.id, settings.userId)
                        _state.update { HomeState.EMPTY }
                        return@collectLatestIn
                    }
                    if (state.value.settings.userId != settings.userId) {
                        Timber.d("User changed")
                        _state.update { HomeState.EMPTY }
                    }
                    dataLoadingJob =
                        viewModelScope.launchIO {
                            loadHomeRows(userDto, settings)
                        }
                }
        }

        fun init() {
            viewModelScope.launchIO {
                Timber.d("init HomeViewModel")
                serverRepository.currentUserDto?.let { userDto ->
                    if (dataLoadingJob?.isActive == false) {
                        val settings =
                            homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }
                        if (userDto.id == settings.userId) {
                            dataLoadingJob =
                                viewModelScope.launchIO {
                                    loadHomeRows(userDto, settings)
                                }
                        } else {
                            Timber.d(
                                "Init user IDs don't match: %s vs %s",
                                userDto.id,
                                settings.userId,
                            )
                        }
                    } else {
                        Timber.v("Data loading job is active")
                    }
                }
            }
        }

        suspend fun loadHomeRows(
            userDto: ServerUserConfig,
            settings: HomePageResolvedSettings,
        ) {
            Timber.i("Starting loadHomeRows")
            try {
                val preferences = userPreferencesService.getCurrent()
                val prefs = preferences.appPreferences.homePagePreferences

                val libraries =
                    navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)

                val state = state.value

                // Cold start: show the last home page straight away and refresh it in place
                val cached =
                    if (state.loadingState != LoadingState.Success) homeCache.restore(userDto.id, settings) else null

                // Refreshing if a load has already occurred and the rows haven't significantly changed
                val refresh =
                    cached != null || (state.loadingState == LoadingState.Success && state.settings == settings)
                Timber.v(
                    "refresh=%s, state.loadingState=%s, %s rows",
                    refresh,
                    state.loadingState,
                    settings.rows.size,
                )
                _state.update {
                    it.copy(
                        loadingState = if (refresh) LoadingState.Success else LoadingState.Loading,
                        refreshState = LoadingState.Loading,
                        settings = settings,
                        homeRows =
                            if (cached != null) {
                                cached
                            } else if (refresh) {
                                it.homeRows
                            } else {
                                List(settings.rows.size) {
                                    HomeRowLoadingState.Pending(EmptyStringProvider)
                                }
                            },
                    )
                }

                deferredRows.clear() // a newer load supersedes anything still waiting
                val semaphore = Semaphore(4)

                // Jellytip: with server rows (rows=1) the rows this app added from the old manifest are hidden on Home,
                // so don't spend requests on them. Today's answer is usually cached; otherwise wait briefly for it.
                val serverRows =
                    jellytipRows.peek(JELLYTIP_HOME_PAGE)
                        ?: kotlinx.coroutines.withTimeoutOrNull(SERVER_ROWS_WAIT_MS) { jellytipRows.load(JELLYTIP_HOME_PAGE) }
                val skipRow: (HomeRowConfig) -> Boolean = { config ->
                    serverRows != null && JellytipCarouselRows.isAppRow(config, serverRows.carouselIds)
                }

                // Continue Watching / Next Up change most: queue them first (the semaphore is FIFO)
                val fetchOrder =
                    settings.rows.indices.sortedBy { if (isWatchingRow(settings.rows[it].config)) 0 else 1 }
                val deferred =
                    fetchOrder
                        .map { settings.rows[it] }
                        .map { row ->
                            viewModelScope.async(WholphinDispatchers.IO) {
                                if (skipRow(row.config)) {
                                    return@async HomeRowLoadingState.Success(row.title, emptyList(), rowType = row.config)
                                }
                                semaphore.withPermit {
                                    Timber.v("Fetching row: %s", row)
                                    try {
                                        homeDataService.fetchDataForRow(
                                            row = row.config,
                                            scope = viewModelScope,
                                            prefs = prefs,
                                            userDto = userDto,
                                            libraries = libraries,
                                            limit = prefs.maxItemsPerRow,
                                            isRefresh = refresh,
                                        )
                                    } catch (ex: InvalidStatusException) {
                                        if (ex.status == 404) {
                                            Timber.w(ex, "404 on row %s", row)
                                            HomeRowLoadingState.Success(
                                                row.title,
                                                emptyList(),
                                            )
                                        } else {
                                            Timber.e(
                                                ex,
                                                "Error %s on row %s",
                                                ex.status,
                                                row,
                                            )
                                            HomeRowLoadingState.Error(
                                                row.title,
                                                exception = ex,
                                            )
                                        }
                                    } catch (ex: Exception) {
                                        Timber.e(ex, "Error on row %s", row)
                                        HomeRowLoadingState.Error(
                                            row.title,
                                            exception = ex,
                                        )
                                    }
                                }
                            }
                        }.withIndex()
                        .sortedBy { fetchOrder[it.index] }
                        .map { it.value }

                if (refresh) {
                    // Replace rows as they complete
                    val remaining = deferred.withIndex().toMutableList()
                    while (remaining.isNotEmpty()) {
                        val (rowIndex, rowData) =
                            select {
                                // "Return" the first remaining that is completed
                                remaining
                                    .forEach { (rowIndex, deferred) ->
                                        deferred.onAwait { rowIndex to it }
                                    }
                            }
                        Timber.v("Got row data index=%s", rowIndex)
                        remaining.removeIf { it.index == rowIndex }
                        if (shouldDeferRow(rowIndex, rowData)) {
                            // Would swap the item under the user's focus (e.g. Continue Watching reordered after playback)
                            deferredRows[rowIndex] = rowData
                            scheduleIdleApply()
                            continue
                        }
                        _state.update { state ->
                            val newRows =
                                state.homeRows.toMutableList().apply {
                                    // A failed refresh must not wipe a good cached row
                                    if (cached == null || rowData !is HomeRowLoadingState.Error || get(rowIndex) !is HomeRowLoadingState.Success) {
                                        set(rowIndex, rowData)
                                    }
                                }
                            state.copy(
                                homeRows = newRows,
                            )
                        }
                    }
                    _state.update {
                        it.copy(
                            loadingState = LoadingState.Success,
                            refreshState = LoadingState.Success,
                        )
                    }
                } else {
                    val rows = deferred.awaitAll()
                    Timber.v("Got all rows")
                    _state.update {
                        it.copy(
                            loadingState = LoadingState.Success,
                            refreshState = LoadingState.Success,
                            homeRows = rows,
                        )
                    }
                }
                Timber.d("Home page load complete")
                homeCache.onLoaded(userDto.id, settings, _state.value.homeRows)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Exception during home page loading")
                if (state.value.loadingState == LoadingState.Success) {
                    showToast(context, "Error refreshing home: ${ex.localizedMessage}")
                    _state.update { it.copy(refreshState = LoadingState.Error(ex)) }
                } else {
                    _state.update {
                        it.copy(loadingState = LoadingState.Error(ex))
                    }
                }
            }
        }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setWatched(itemId, played)
            withContext(WholphinDispatchers.Main) {
                init()
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
            withContext(WholphinDispatchers.Main) {
                init()
            }
        }

        /** Focus moved: prefetch images for the rows below. */
        fun onFocusPosition(position: RowColumn) {
            homeCache.onFocus(state.value.homeRows, position.row, position.column)
            if (position.row != focusedRow) applyDeferredRows() // focus left the row: its refresh can land now
            focusedRow = position.row
            focusedColumn = position.column
            scheduleIdleApply()
            focusedItemId = (state.value.homeRows.getOrNull(position.row) as? HomeRowLoadingState.Success)?.items?.getOrNull(position.column)?.id
        }

        // Focus-stable refresh: a refreshed row that would put a different item under the focused column waits until focus moves away
        private var focusedRow = -1
        private var focusedColumn = -1
        private var focusedItemId: UUID? = null
        private val deferredRows = java.util.concurrent.ConcurrentHashMap<Int, HomeRowLoadingState>()

        private fun shouldDeferRow(
            rowIndex: Int,
            rowData: HomeRowLoadingState,
        ): Boolean {
            val id = focusedItemId ?: return false
            if (rowIndex != focusedRow || rowData !is HomeRowLoadingState.Success) return false
            return rowData.items.getOrNull(focusedColumn)?.id != id
        }

        private var idleApplyJob: kotlinx.coroutines.Job? = null

        /** Deferred rows must not stay stale forever: land them after 60 s without focus movement, but never mid-trailer. */
        private fun scheduleIdleApply() {
            idleApplyJob?.cancel()
            if (deferredRows.isEmpty()) return
            idleApplyJob =
                viewModelScope.launch {
                    kotlinx.coroutines.delay(IDLE_APPLY_MS)
                    while (com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl.url != null) kotlinx.coroutines.delay(5_000)
                    applyDeferredRows()
                }
        }

        private fun applyDeferredRows() {
            if (deferredRows.isEmpty()) return
            val pending = deferredRows.toMap()
            deferredRows.clear()
            _state.update { st ->
                st.copy(homeRows = st.homeRows.toMutableList().apply { pending.forEach { (i, r) -> if (i in indices) set(i, r) } })
            }
        }

        /** An item was opened: remember which row it came from. */
        fun recordOpen(position: RowColumn) {
            homeCache.recordOpen(state.value.homeRows.getOrNull(position.row))
        }

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submit(item)
            }
        }

        fun deleteItem(
            position: RowColumn,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    val row = state.value.homeRows.getOrNull(position.row)
                    if (row is HomeRowLoadingState.Success) {
                        _state.update {
                            val newRow =
                                row.items.toMutableList().apply {
                                    removeAt(position.column)
                                }
                            it.copy(
                                homeRows =
                                    it.homeRows.toMutableList().apply {
                                        set(position.row, row.copy(items = newRow))
                                    },
                            )
                        }
                    }
                }
            }
        }

        fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        fun removeFromNextUp(item: BaseItem) {
            if (item.type == BaseItemKind.EPISODE) {
                viewModelScope.launchDefault {
                    serverRepository.currentUser?.id?.let { userId ->
                        latestNextUpService.removeFromNextUp(userId, item)
                        init()
                    }
                }
            } else {
                Timber.w("Item is not an episode %s", item.id)
            }
        }
    }

data class HomeState(
    val loadingState: LoadingState,
    val refreshState: LoadingState,
    val homeRows: List<HomeRowLoadingState>,
    val settings: HomePageResolvedSettings,
) {
    companion object {
        val EMPTY =
            HomeState(
                LoadingState.Pending,
                LoadingState.Pending,
                emptyList(),
                HomePageResolvedSettings.EMPTY,
            )
    }
}

/**
 * Whether a row is a "is watching" type
 */
private fun isWatchingRow(row: HomeRowConfig) =
    row is HomeRowConfig.ContinueWatching ||
        row is HomeRowConfig.NextUp ||
        row is HomeRowConfig.ContinueWatchingCombined

private const val IDLE_APPLY_MS = 60_000L

private const val JELLYTIP_HOME_PAGE = "home"
private const val SERVER_ROWS_WAIT_MS = 1_500L
