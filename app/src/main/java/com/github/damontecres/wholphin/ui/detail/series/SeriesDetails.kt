package com.github.damontecres.wholphin.ui.detail.series

import androidx.compose.runtime.withFrameNanos
import org.jellyfin.sdk.model.extensions.ticks
import androidx.compose.ui.platform.LocalDensity
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.JellytipRowFocusScope
import com.github.damontecres.wholphin.jellytip.jellytipFocusRoom
import com.github.damontecres.wholphin.jellytip.details.JellytipDetailsScaffold
import com.github.damontecres.wholphin.jellytip.details.JellytipDetailsSection
import com.github.damontecres.wholphin.jellytip.details.JellytipEpisodeTile
import com.github.damontecres.wholphin.jellytip.details.JellytipEpisodeTileHeight
import com.github.damontecres.wholphin.jellytip.details.IptvSeasonPosition
import com.github.damontecres.wholphin.jellytip.details.SplitLibEp
import com.github.damontecres.wholphin.jellytip.details.splitEpisodes
import com.github.damontecres.wholphin.jellytip.details.splitNextUp
import com.github.damontecres.wholphin.jellytip.details.LibrarySeasonRef
import com.github.damontecres.wholphin.jellytip.details.SeasonSource
import com.github.damontecres.wholphin.jellytip.details.episodeSourceMark
import com.github.damontecres.wholphin.jellytip.details.mergeSeasons
import com.github.damontecres.wholphin.jellytip.details.seasonTiles
import com.github.damontecres.wholphin.jellytip.details.selectedMergedIndex
import com.github.damontecres.wholphin.jellytip.details.seriesIsSplit
import com.github.damontecres.wholphin.jellytip.details.splitSourceBadge
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeasons
import com.github.damontecres.wholphin.jellytip.iptv.IptvStreamFail
import com.github.damontecres.wholphin.jellytip.details.myListLabel
import com.github.damontecres.wholphin.jellytip.details.rememberJellytipDetailsScaffoldState
import com.github.damontecres.wholphin.jellytip.details.seasonChipLabel
import com.github.damontecres.wholphin.jellytip.details.seriesPlayLabel
import com.github.damontecres.wholphin.jellytip.details.seriesStatusLine
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import org.jellyfin.sdk.model.api.ImageType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.github.damontecres.wholphin.jellytip.HeroScrollLock
import com.github.damontecres.wholphin.jellytip.BoxScopeDownHint
import androidx.compose.ui.Alignment
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.ui.ifElse
import kotlinx.coroutines.flow.update
import com.github.damontecres.wholphin.jellytip.nav.JellytipSeasonChips
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.cards.BannerCardWithTitle
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
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
import com.github.damontecres.wholphin.jellytip.JellytipDetailsPullQuote
import com.github.damontecres.wholphin.jellytip.nav.JellytipBreadcrumb
import com.github.damontecres.wholphin.jellytip.nav.JellytipCrumb
import com.github.damontecres.wholphin.jellytip.JellytipDetailsViewModel
import com.github.damontecres.wholphin.jellytip.JellytipScoreRow
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipReviews
import com.github.damontecres.wholphin.jellytip.JellytipReviewsRow
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
                it.create(destination.itemId, destination.openAt, SeriesPageType.DETAILS)
            },
        ),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
    jellytipViewModel: JellytipDetailsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsState()
    val position by viewModel.position.collectAsState()
    val playTarget by viewModel.playTarget.collectAsState()
    val playlistState by playlistViewModel.playlistState.collectAsState()

    var overviewDialog by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    var showContextMenu by remember { mutableStateOf<ContextMenu?>(null) }

    var showWatchConfirmation by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf<Optional<UUID>>(Optional.absent()) }
    // The episode (id, index in its season's list) whose context menu was opened last: lets a watched / favourite change refresh its tile
    var menuEpisode by remember { mutableStateOf<Pair<UUID, Int>?>(null) }

    val contextActions =
        remember {
            ContextMenuActions(
                navigateTo = { dest ->
                    // Playing from an episode's menu: Back lands on the episode row, like playing from the tile
                    if (dest is Destination.Playback) viewModel.returnToEpisodes = true
                    viewModel.navigateTo(dest)
                },
                onClickWatch = { itemId, watched ->
                    if (itemId == destination.itemId) {
                        // Confirm if marking whole series
                        showWatchConfirmation = true
                    } else {
                        viewModel.setWatched(itemId, watched, menuEpisode?.takeIf { it.first == itemId }?.second)
                    }
                },
                onClickFavorite = { itemId, favorite ->
                    viewModel.setFavorite(itemId, favorite, menuEpisode?.takeIf { it.first == itemId }?.second)
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
            // Shows of the page: the first one uses the cached IPTV copy, every later one (back from playback) asks again
            var shows by remember(destination.itemId) { mutableIntStateOf(0) }
            LifecycleResumeEffect(destination.itemId) {
                viewModel.onResumePage()
                shows++

                onPauseOrDispose {}
            }
            val series = st.data
            val played = series.data.userData?.played ?: false
            // The person's IPTV copy of this series: asked once per page (10-minute cache); a late answer just appears in the season list
            val iptvDetail by produceState<com.github.damontecres.wholphin.jellytip.iptv.IptvVodDetail?>(IptvSeasons.cachedDetail(series), series.id, shows) {
                val fresh = shows > 1
                val got = com.github.damontecres.wholphin.jellytip.iptv.IptvSeasons.detailFor(series, fresh = fresh)
                // A fresh ask that came back empty keeps what the page shows (a failed ask must not drop the IPTV seasons)
                if (got != null || !fresh) value = got
            }
            // A split series: next up over library + IPTV needs every library episode's watched state (asked again on each show)
            val hasIptvSeasons = iptvDetail?.seasons?.isNotEmpty() == true
            LaunchedEffect(hasIptvSeasons, shows) { if (hasIptvSeasons) viewModel.loadAllEpisodes() }
            val allLibraryEpisodes by viewModel.allEpisodes.collectAsState()
            val openEpisodeDetails: (BaseItem) -> Unit = {
                viewModel.returnToEpisodes = true
                // The old Overview page, named explicitly: BaseItem.destination() now points an episode at this series page
                val seasonId = it.data.seasonId
                viewModel.navigateTo(
                    if (seasonId != null) {
                        Destination.SeriesOverview(
                            series.id,
                            BaseItemKind.SERIES,
                            SeasonEpisodeIds(seasonId, it.data.parentIndexNumber, it.id, it.indexNumber),
                        )
                    } else {
                        it.destination()
                    },
                )
            }
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
                episodes = state.episodes,
                nextUpId = state.nextUpId,
                seasonIndex = position.seasonTabIndex,
                initialEpisodeIndex = position.episodeRowIndex,
                onChangeSeason = { index ->
                    if (index != position.seasonTabIndex) {
                        state.seasons.getOrNull(index)?.let { season ->
                            viewModel.loadEpisodes(season.id, season.indexNumber)
                            viewModel.position.update { SeriesOverviewPosition(index, 0) }
                        }
                    }
                },
                // The old Overview page for an episode: only the "Details" item of an episode's menu opens it now
                onClickEpisode = openEpisodeDetails,
                resolved = playTarget,
                allLibraryEpisodes = allLibraryEpisodes,
                iptv = iptvDetail,
                onPlayIptvEpisode = { d, e ->
                    // Same destination and arguments the old IPTV strips used; Back lands on the episode row like a library tile
                    val resume = e.progress?.takeIf { p -> p.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L
                    com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
                        .log("iptv", "series page: IPTV episode E${e.episode} ${e.id}")
                    viewModel.returnToEpisodes = true
                    viewModel.navigateTo(
                        Destination.JellytipIptvPlay(title = "${series.name ?: d.item.title} \u00B7 ${e.title}", seriesId = d.item.id, episodeId = e.id, resumeMs = resume),
                    )
                },
                iptvPosition = viewModel.iptvPosition,
                onIptvPosition = { viewModel.iptvPosition = it },
                onPlayEpisode = { ep ->
                    // The episode's own resume position, the same call the Overview page's tile makes
                    val resumeMs = (ep.data.userData?.playbackPositionTicks ?: 0L).ticks.inWholeMilliseconds
                    viewModel.navigateTo(Destination.Playback(ep.id, resumeMs))
                },
                onEpisodeSelected = { index ->
                    viewModel.position.update { it.copy(episodeRowIndex = index) }
                    viewModel.returnToEpisodes = true
                },
                onLongClickEpisode = { index, ep ->
                    menuEpisode = ep.id to index
                    viewModel.position.update { it.copy(episodeRowIndex = index) }
                    showContextMenu =
                        ContextMenu.ForBaseItem(
                            fromLongClick = true,
                            item = ep,
                            chosenStreams = null,
                            showGoTo = false,
                            showStreamChoices = true,
                            canDelete = viewModel.canDelete(ep, preferences.appPreferences),
                            canRemoveContinueWatching = false,
                            canRemoveNextUp = false,
                            actions = contextActions,
                            leadingItems = listOf(DialogItem("Details", dismissOnClick = true) { openEpisodeDetails(ep) }),
                        )
                },
                consumeReturnToEpisodes = { viewModel.returnToEpisodes.also { viewModel.returnToEpisodes = false } },
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

private const val PEOPLE_ROW = 1
private const val TRAILER_ROW = 2

/** Key of the first lower section: the page returns here (`openAt`) after playback or the fallback Overview page. */
private const val EPISODES_SECTION = "episodes"

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
    episodes: EpisodeList,
    nextUpId: UUID?,
    seasonIndex: Int,
    initialEpisodeIndex: Int,
    onChangeSeason: (Int) -> Unit,
    /** The old Overview page for an episode (kept one release as a fallback; the page's "Details" menu item calls it). Episode tiles no longer do. */
    onClickEpisode: (BaseItem) -> Unit,
    consumeReturnToEpisodes: () -> Boolean,
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
    /** The episode the primary button plays and names (see SeriesViewModel.playTarget); null until known. */
    resolved: BaseItem? = null,
    /** Plays an episode at its own resume position (the primary button and an episode tile's OK). */
    onPlayEpisode: (BaseItem) -> Unit = {},
    /** An episode tile's OK, called with the tile's list index just before [onPlayEpisode]: the page remembers where to land on Back. */
    onEpisodeSelected: (Int) -> Unit = {},
    /** Hold OK on an episode tile: the page opens that episode's context menu. */
    onLongClickEpisode: (Int, BaseItem) -> Unit = { _, _ -> },
    /** Every library episode with its watched state (split series only; null = not asked / not answered). */
    allLibraryEpisodes: List<BaseItem>? = null,
    /** The person's IPTV copy of this series (null = none, or not answered yet): its seasons and episodes merge into the page. */
    iptv: com.github.damontecres.wholphin.jellytip.iptv.IptvVodDetail? = null,
    /** OK on an IPTV episode tile: play it through IPTV. The page that owns navigation also sets the return-to-episodes flag. */
    onPlayIptvEpisode: (com.github.damontecres.wholphin.jellytip.iptv.IptvVodDetail, com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode) -> Unit = { _, _ -> },
    /** Where the page last was on the IPTV side (the IPTV-only season picked, the IPTV episode played); read once when the page opens. */
    iptvPosition: IptvSeasonPosition = IptvSeasonPosition(),
    /** Called whenever that position changes, so the page's owner can keep it across playback. */
    onIptvPosition: (IptvSeasonPosition) -> Unit = {},
) {
    var position by rememberInt(0)
    val playWord = stringResource(R.string.play)
    val resumeWord = stringResource(R.string.resume)
    val discoverLabel = stringResource(R.string.discover)

    // Back from playback / the fallback Overview page: open on the episode row (read once; the flag is consumed)
    val returned = remember { consumeReturnToEpisodes() }
    val scaffoldState = rememberJellytipDetailsScaffoldState(if (returned) EPISODES_SECTION else null)
    // True while the page was opened on the episode row and has not been left for the hero since: focus goes to the tile
    var landOnTile by remember { mutableStateOf(returned) }
    var episodesHadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(scaffoldState.sectionsActive) {
        if (!scaffoldState.sectionsActive) {
            landOnTile = false
            episodesHadFocus = false
        }
    }
    // Rows are frozen (no data swapped in under focus) once the lower sections are open and focus has settled there
    val rowsFrozen = scaffoldState.sectionsActive && (!landOnTile || episodesHadFocus)

    // Jellytip taste signals: details dwell (8 s or more) when the page goes or moves to another title
    androidx.compose.runtime.DisposableEffect(series.id) {
        val since = System.currentTimeMillis()
        onDispose {
            com.github.damontecres.wholphin.jellytip.signals.TasteRules
                .libraryTarget(series)
                ?.let { com.github.damontecres.wholphin.jellytip.signals.TasteRules.detailSignal(it, System.currentTimeMillis() - since, System.currentTimeMillis()) }
                ?.let(com.github.damontecres.wholphin.jellytip.signals.JellytipSignals::emit)
        }
    }
    // Fetched with the page but only composed below the fold (sectionsActive), so it never pops in over the buttons
    val reviews by produceState<JellytipReviews?>(null, series.id) { value = JellytipApi.shared?.getReviews(series) }
    val hasReviews = reviews?.reviews?.isNotEmpty() == true
    // Jellytip: companion "More like this" (library + IPTV mix); null = Jellyfin's similar list below
    val jtSimilarLoaded by produceState<com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow?>(null, series.id) {
        value = com.github.damontecres.wholphin.jellytip.similar.JellytipSimilar.queryFor(series)?.let {
            com.github.damontecres.wholphin.jellytip.similar.JellytipSimilar.row(it)
        }
    }
    // Taken only while the lower rows are not open, so it never swaps Jellyfin's row out from under focus
    var jtSimilar by remember(series.id) { mutableStateOf<com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow?>(null) }
    LaunchedEffect(jtSimilarLoaded, rowsFrozen) {
        if (jtSimilarLoaded != null && !rowsFrozen) jtSimilar = jtSimilarLoaded
    }

    val chipRequester = remember { FocusRequester() }
    val episodeRowRequester = remember { FocusRequester() }
    // OK on a season chip: hand focus to the episode row once that season's episodes are showing
    var focusEpisodesPending by remember { mutableStateOf(false) }

    // One season list over the library's and the IPTV copy's. The selection is kept by IDENTITY (the library season index, or
    // the number of the picked IPTV-only season), never by position in the merged list, so a late IPTV answer that inserts
    // chips cannot move it.
    var selIptvSeason by remember(series.id) { mutableStateOf(iptvPosition.season) }
    var iptvFocusEpisode by remember(series.id) { mutableStateOf(iptvPosition.episodeId) }
    val libNumbers = seasons.map { it?.indexNumber }
    val merged = remember(libNumbers, iptv) { mergeSeasons(libNumbers.mapIndexed { i, n -> LibrarySeasonRef(i, n) }, iptv?.seasons.orEmpty()) }
    val mergedSel = selectedMergedIndex(merged, seasonIndex, selIptvSeason)
    val entry = merged.getOrNull(mergedSel)
    val libEpisodes = (episodes as? EpisodeList.Success)?.episodes
    val entryLibraryIndex = entry?.libraryIndex
    val libEpisodesReady =
        entryLibraryIndex != null && (episodes as? EpisodeList.Success)?.seasonId == seasons.getOrNull(entryLibraryIndex)?.id
    // The tiles of the selected season (library episodes with IPTV-only ones interleaved, or an IPTV-only season's episodes)
    val tiles: List<IptvSeasons.SeasonTile>? =
        when {
            entry == null -> null
            entry.source == SeasonSource.IPTV -> seasonTiles(entry, 0, true, { null }, { false })
            libEpisodes == null -> null
            else ->
                seasonTiles(
                    entry,
                    libEpisodes.size,
                    libEpisodesReady,
                    { libEpisodes[it]?.data?.indexNumber },
                    { libEpisodes[it]?.data?.locationType == org.jellyfin.sdk.model.api.LocationType.VIRTUAL },
                )
        }
    val split = seriesIsSplit(merged, tiles.orEmpty())

    // Split series (library + IPTV episodes): next up over BOTH (Jellyfin's next up only knows library episodes, so after
    // library S2E2 it said E6 past IPTV-only E3-E5). Null = not split / not known yet / all watched: the library's answer.
    val splitNext =
        remember(allLibraryEpisodes, iptv) {
            val seasonsIptv = iptv?.seasons.orEmpty()
            if (seasonsIptv.isEmpty() || allLibraryEpisodes == null) {
                null
            } else {
                splitNextUp(
                    splitEpisodes(
                        allLibraryEpisodes.map {
                            SplitLibEp(
                                id = it.id,
                                season = it.data.parentIndexNumber,
                                episode = it.data.indexNumber,
                                played = it.data.userData?.played == true,
                                positionTicks = it.data.userData?.playbackPositionTicks ?: 0L,
                                virtual = it.data.locationType == org.jellyfin.sdk.model.api.LocationType.VIRTUAL,
                            )
                        },
                        seasonsIptv,
                    ),
                )
            }
        }
    // The library episode the button plays: the split answer's library copy, else the library's own answer
    val playLib: BaseItem? = splitNext?.lib?.let { l -> allLibraryEpisodes?.firstOrNull { it.id == l.id } } ?: resolved?.takeIf { splitNext == null }
    val splitIptv = splitNext?.takeIf { it.lib == null && it.iptv != null }
    val playIptv = splitIptv?.iptv
    val primaryLabel =
        when {
            splitIptv != null -> seriesPlayLabel(splitIptv.season, splitIptv.episode, splitIptv.inProgress, resumeWord, playWord)
            playLib != null -> seriesPlayLabel(playLib.data.parentIndexNumber, playLib.data.indexNumber, (playLib.data.userData?.playbackPositionTicks ?: 0L) > 0L, resumeWord, playWord)
            else -> playWord
        }
    val statusLine =
        seriesStatusLine(
            seasonNumbers = merged.map { it.number },
            nextSeason = splitNext?.season ?: resolved?.data?.parentIndexNumber,
            nextEpisode = splitNext?.episode ?: resolved?.data?.indexNumber,
        )
    val nextEpisodeId = if (splitNext != null) splitNext.lib?.id else resolved?.id ?: nextUpId
    val nextIptvEpisodeId = playIptv?.id

    JellytipDetailsScaffold(
        modifier = modifier,
        state = scaffoldState,
        openAt = if (returned) EPISODES_SECTION else null,
        backdrop = { buttonRowFocused, dimmed ->
            JellytipDetailsBackground(series, trailers, buttonRowFocused, dimmed = dimmed)
        },
        // One review, bottom right, balancing the info block (loaded with the page, fades in once)
        overlay = { headerAlpha -> JellytipDetailsPullQuote(reviews, headerAlpha) },
        hero = { slot ->
            JellytipDetailsBlock(
                item = series,
                statusLine = statusLine,
                sourceBadge = splitSourceBadge(split),
                buttons =
                    buildList {
                        add(
                            JellytipButton(primaryLabel, primary = true, onClick = {
                                position = 0
                                // One resolved episode names the button and is what plays; no answer yet = the old next-up lookup
                                val copy = iptv
                                when {
                                    playIptv != null && copy != null -> onPlayIptvEpisode(copy, playIptv)
                                    playLib != null -> onPlayEpisode(playLib)
                                    else -> playOnClick(false)
                                }
                            }),
                        )
                        add(
                            JellytipButton(stringResource(R.string.shuffle), {
                                position = 0
                                playOnClick.invoke(true)
                            }),
                        )
                        add(JellytipButton(myListLabel(favorite), favoriteOnClick))
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
                                        // "Discover" (open in Seerr) lives here; the Discover row below keeps the name to itself
                                        trailingItems =
                                            if (discoverSeries != null) {
                                                listOf(DialogItem(discoverLabel, dismissOnClick = true) { onClickDiscoverSeries() })
                                            } else {
                                                emptyList()
                                            },
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
                buttonRowModifier = slot.buttonRowModifier.onFocusChanged { if (it.hasFocus) position = 0 },
                modifier = slot.modifier,
                onFocusWithin = slot.onFocusWithin,
            )
        },
        sections =
            listOf(
                JellytipDetailsSection(EPISODES_SECTION, merged.isNotEmpty()) { focus ->
                    val now = remember(mergedSel) { System.currentTimeMillis() / 1000 }
                    val tilesReady = tiles != null && (entry?.source == SeasonSource.IPTV || libEpisodesReady)
                    // The tile the row opens on and focuses: the IPTV episode last played, else the library episode the page remembers
                    val focusTile =
                        tiles
                            ?.let { list ->
                                val byIptv =
                                    iptvFocusEpisode?.let { id -> list.indexOfFirst { it is IptvSeasons.SeasonTile.Iptv && it.episode.id == id } } ?: -1
                                if (byIptv >= 0) {
                                    byIptv
                                } else {
                                    list.indexOfFirst { it is IptvSeasons.SeasonTile.Lib && it.index == initialEpisodeIndex }
                                }
                            }?.takeIf { it >= 0 } ?: 0
                    // Down (and Back from playback) lands on the episode to watch next, not on a season chip: the tile takes the
                    // section's focus request whenever tiles exist; while they load, the section itself (its chips) takes it
                    val tileRequester = focus
                    LaunchedEffect(focusEpisodesPending, libEpisodes, tilesReady) {
                        if (focusEpisodesPending && tilesReady) {
                            withFrameNanos { }
                            withFrameNanos { }
                            tileRequester.tryRequestFocus()
                            focusEpisodesPending = false
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .ifElse(!tilesReady, Modifier.focusRequester(focus))
                                .onFocusChanged { if (it.hasFocus) episodesHadFocus = true },
                    ) {
                        ItemRowTitle("Episodes")
                        val seasonLabels =
                            remember(merged) {
                                merged.mapIndexed { i, season -> seasonChipLabel(season.number, i) }
                            }
                        JellytipSeasonChips(
                            labels = seasonLabels,
                            selectedIndex = mergedSel,
                            onSelect = { i ->
                                val picked = merged.getOrNull(i)
                                val libraryIndex = picked?.libraryIndex
                                if (picked != null) {
                                    selIptvSeason = if (libraryIndex == null) picked.number else null
                                    iptvFocusEpisode = null
                                    onIptvPosition(IptvSeasonPosition(selIptvSeason, null))
                                    if (libraryIndex != null) onChangeSeason(libraryIndex)
                                }
                            },
                            selectedRequester = chipRequester,
                            onConfirm = { focusEpisodesPending = true },
                            modifier = Modifier.padding(start = JellytipLayout.RowInset),
                        )
                        if (tiles != null) {
                            // The scroll position restarts on the focus tile whenever another season is picked
                            val selectionKey = entry?.libraryIndex?.let { "l$it" } ?: "i${entry?.number}"
                            val rowState = remember(selectionKey) { LazyListState(focusTile) }
                            JellytipRowFocusScope { rowFocusModifier ->
                                LazyRow(
                                    state = rowState,
                                    horizontalArrangement = Arrangement.spacedBy(JellytipLayout.TileGap),
                                    // Focus room: the first tile's growth and border are not cut; the row is pulled back so the tile
                                    // still starts on the row title's line (8 dp, like People and Reviews)
                                    contentPadding = PaddingValues(horizontal = JellytipLayout.FocusRoomWide, vertical = JellytipLayout.FocusRoom),
                                    modifier =
                                        rowFocusModifier
                                            .fillMaxWidth()
                                            .jellytipFocusRoom(JellytipLayout.FocusRoomWide - JellytipLayout.RowInset)
                                            .focusRestorer(tileRequester),
                                ) {
                                    items(
                                        tiles.size,
                                        key = { i ->
                                            when (val x = tiles[i]) {
                                                is IptvSeasons.SeasonTile.Lib -> "l${x.index}"
                                                is IptvSeasons.SeasonTile.Iptv -> "i${x.episode.id}"
                                            }
                                        },
                                    ) { t ->
                                        val tileFocus = if (t == focusTile) Modifier.focusRequester(tileRequester) else Modifier
                                        when (val tile = tiles[t]) {
                                            is IptvSeasons.SeasonTile.Iptv -> {
                                                val e = tile.episode
                                                IptvEpisodeTile(
                                                    e = e,
                                                    isNext = e.id == nextIptvEpisodeId,
                                                    mark =
                                                        episodeSourceMark(
                                                            split,
                                                            iptv = true,
                                                            failNote = IptvStreamFail.note(IptvStreamFail.failOf(e.id, e.lastFail), now),
                                                        ),
                                                    onClick = {
                                                        val copy = iptv
                                                        if (copy != null) {
                                                            position = 1
                                                            iptvFocusEpisode = e.id
                                                            onIptvPosition(IptvSeasonPosition(selIptvSeason, e.id))
                                                            onPlayIptvEpisode(copy, e)
                                                        }
                                                    },
                                                    modifier = tileFocus,
                                                )
                                            }

                                            is IptvSeasons.SeasonTile.Lib -> {
                                                val episodeIndex = tile.index
                                                val episode = libEpisodes?.get(episodeIndex)
                                                LibraryEpisodeTile(
                                                    episode = episode,
                                                    isNext = episode != null && episode.id == nextEpisodeId,
                                                    sourceMark = episodeSourceMark(split, iptv = false),
                                                    onClick = {
                                                        if (episode != null) {
                                                            position = 1
                                                            iptvFocusEpisode = null
                                                            onIptvPosition(IptvSeasonPosition(selIptvSeason, null))
                                                            onEpisodeSelected(episodeIndex)
                                                            onPlayEpisode(episode)
                                                        }
                                                    },
                                                    onLongClick = { if (episode != null) onLongClickEpisode(episodeIndex, episode) },
                                                    modifier = tileFocus,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                JellytipDetailsSection("people", people.isNotEmpty()) { focus ->
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
                                .focusRequester(focus),
                    )
                },
                JellytipDetailsSection("reviews", hasReviews) { focus ->
                    reviews?.let { r ->
                        JellytipReviewsRow(
                            reviews = r,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focus),
                        )
                    }
                },
                JellytipDetailsSection("extras", extras.isNotEmpty()) { focus ->
                    ExtrasRow(
                        extras = extras,
                        onClickItem = { index, item ->
                            onClickExtra.invoke(index, item)
                        },
                        onLongClickItem = { _, _ -> },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focus),
                    )
                },
                JellytipDetailsSection("similar", jtSimilar != null || similar.isNotEmpty()) { focus ->
                    val simRow = jtSimilar
                    if (simRow != null) {
                        com.github.damontecres.wholphin.jellytip.similar.JellytipSimilarRowView(
                            row = simRow,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focus),
                        )
                    } else {
                        ItemRow(
                            title = stringResource(R.string.more_like_this),
                            items = similar,
                            onClickItem = { index, item ->
                                onClickItem.invoke(index, item)
                            },
                            onLongClickItem = { index, item ->
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
                                    .focusRequester(focus),
                        )
                    }
                },
                JellytipDetailsSection("discover", discovered.isNotEmpty()) { focus ->
                    DiscoverRow(
                        row =
                            DiscoverRowData(
                                ResStringProvider(R.string.discover),
                                DataLoadingState.Success(discovered),
                                type = DiscoverRequestType.UNKNOWN,
                            ),
                        onClickItem = { index: Int, item: DiscoverItem ->
                            onClickDiscover.invoke(index, item)
                        },
                        onLongClickItem = { _, _ -> },
                        onCardFocus = {},
                        focusRequester = focus,
                    )
                },
            ),
    )
}

/** A library episode as the one episode tile: still, number, name, overview line, watched / progress / "Next". */
@Composable
private fun LibraryEpisodeTile(
    episode: BaseItem?,
    isNext: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    sourceMark: String? = null,
) {
    val imageUrlService = LocalImageUrlService.current
    val density = LocalDensity.current
    val stillHeightPx = remember(density) { with(density) { JellytipEpisodeTileHeight.roundToPx() } }
    val url =
        remember(episode, stillHeightPx) {
            episode?.let {
                it.imageUrlOverride
                    ?: imageUrlService.getItemImageUrl(
                        it,
                        ImageType.PRIMARY,
                        fillWidth = null,
                        fillHeight = stillHeightPx,
                        useSeriesForPrimary = false,
                    )
            }
        }
    val data = episode?.data
    val user = data?.userData
    val percent = user?.playedPercentage ?: 0.0
    val partWatched = percent > 0.0 && percent < 100.0
    JellytipEpisodeTile(
        image = url,
        number = data?.indexNumber,
        name = episode?.name,
        overview = data?.overview,
        played = user?.played == true && !partWatched,
        progress = if (partWatched) (percent / 100.0).toFloat() else 0f,
        isNext = isNext,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        sourceMark = sourceMark,
    )
}

/** An IPTV episode as the one episode tile (OK plays it; hold OK does nothing: IPTV episodes have no menu). */
@Composable
private fun IptvEpisodeTile(
    e: com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode,
    mark: String?,
    isNext: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = e.progress
    JellytipEpisodeTile(
        image = e.still,
        number = e.episode,
        name = e.title,
        overview = e.overview,
        played = p?.played == true,
        progress = p?.takeIf { it.resumable && it.durationSecs > 0 }?.let { (it.positionSecs / it.durationSecs).toFloat().coerceIn(0f, 1f) } ?: 0f,
        isNext = isNext,
        onClick = onClick,
        onLongClick = {},
        modifier = modifier,
        sourceMark = mark,
    )
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
