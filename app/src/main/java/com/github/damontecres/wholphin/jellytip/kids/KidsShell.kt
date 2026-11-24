package com.github.damontecres.wholphin.jellytip.kids

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.search.SearchPage
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens and plays titles for the Kids shell. Every play navigates to the player page FIRST and only then (the page itself)
 * starts playback; the IPTV player page starts its own stream (see pattern-jellytip-navigate-before-play). A navigation
 * the gate blocks returns false and nothing else happens.
 */
@Singleton
class KidsPlayer
    @Inject
    constructor(
        private val navigationManager: NavigationManager,
        private val library: KidsLibraryService,
    ) {
        private val busy = AtomicBoolean(false)

        /** One play at a time: a second OK while the first is still looking up its resume point does nothing. */
        private suspend fun guarded(block: suspend () -> Unit) {
            if (!busy.compareAndSet(false, true)) return
            try {
                block()
            } finally {
                busy.set(false)
            }
        }

        private suspend fun go(
            destination: Destination?,
            reason: String,
        ) {
            destination ?: return
            withContext(WholphinDispatchers.Main) { navigationManager.navigateToChecked(destination, reason) }
        }

        /** A tap on a tile: its page. */
        suspend fun open(tile: KidsTile) = go(KidsOpen.destination(tile), "kids open ${tile.kind}")

        /** The big Play for [tile] (Home hero): the player for a film / episode, the next episode for a series. */
        suspend fun play(tile: KidsTile) =
            guarded {
                val destination: Destination? =
                    if (tile.source == "iptv") {
                        // An IPTV film plays; an IPTV series / episode opens its page (it works out the episode)
                        if (tile.kind == KidsKind.MOVIE) Destination.JellytipIptvPlay(title = tile.name, vodId = tile.id) else KidsOpen.destination(tile)
                    } else {
                        val id = parseId(tile.id)
                        when {
                            id == null -> null
                            tile.kind == KidsKind.MOVIE || tile.kind == KidsKind.EPISODE -> Destination.Playback(id, library.resumeMs(id))
                            tile.kind == KidsKind.SERIES ->
                                library.seriesPlayTarget(id)?.let { target -> parseId(target.itemId)?.let { Destination.Playback(it, target.resumeMs) } }
                                    ?: KidsOpen.destination(tile)

                            else -> KidsOpen.destination(tile)
                        }
                    }
                go(destination, "kids play ${tile.kind}")
            }

        /** Play a library item at a known position (details Play, with the target the page already holds). */
        suspend fun playAt(
            itemId: String,
            resumeMs: Long,
        ) =
            guarded {
                val id = parseId(itemId) ?: return@guarded
                go(Destination.Playback(id, resumeMs), "kids play item")
            }

        /** Play a library item from its own resume position (an episode tile). */
        suspend fun playItem(itemId: String) =
            guarded {
                val id = parseId(itemId) ?: return@guarded
                go(Destination.Playback(id, library.resumeMs(id)), "kids play episode")
            }

        private fun parseId(s: String): UUID? = runCatching { s.toUUID() }.getOrNull()
    }

/** My list changes (the heart on a details page) tell the My list section to reload. */
internal object KidsMyListEpoch {
    val flow = MutableStateFlow(0)

    fun bump() {
        flow.value = flow.value + 1
    }
}

@HiltViewModel
class KidsShellViewModel
    @Inject
    constructor(
        val navigationManager: NavigationManager,
        private val setupNavigationManager: SetupNavigationManager,
        private val musicService: MusicService,
    ) : ViewModel() {
        fun go(section: KidsSection) {
            KidsSectionState.set(section)
            // From Search (or anything above Home) the section button lands on that section's Home entry
            if (navigationManager.backStack.lastOrNull() !is Destination.Home) navigationManager.goToHome()
        }

        fun search() {
            navigationManager.navigateTo(Destination.Search(), "kids search")
        }

        /** The EXISTING switch-user screen (decision B1: no PIN, no hold). */
        fun switchProfile(server: JellyfinServer) {
            viewModelScope.launchDefault {
                musicService.stop()
                setupNavigationManager.navigateTo(SetupDestination.UserList(server))
            }
        }
    }

/** Home, Shows, Movies and My list: loads each once per Home entry (no refetch, no re-rank on Back). */
@HiltViewModel
class KidsBrowseViewModel
    @Inject
    constructor(
        private val library: KidsLibraryService,
        private val player: KidsPlayer,
    ) : ViewModel() {
        val home = MutableStateFlow<KidsHomeUi>(KidsHomeUi.Loading)
        val shows = MutableStateFlow<List<KidsTile>?>(null)
        val movies = MutableStateFlow<List<KidsTile>?>(null)
        val myList = MutableStateFlow<List<KidsTile>?>(null)
        private val started: MutableSet<KidsSection> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        init {
            // After a heart on a details page the list reloads (in place: the old list stays up until the new one lands)
            viewModelScope.launchIO {
                KidsMyListEpoch.flow.drop(1).collect {
                    // Not opened yet = nothing to refresh: the first open loads it fresh
                    if (KidsSection.MY_LIST in started) myList.value = library.myList()
                }
            }
        }

        fun ensure(section: KidsSection) {
            if (!started.add(section)) return
            viewModelScope.launchIO {
                when (section) {
                    KidsSection.HOME ->
                        home.value =
                            when (val r = library.home()) {
                                is KidsHomeResult.Ok -> KidsHomeUi.Ready(r.data)
                                KidsHomeResult.Unavailable -> KidsHomeUi.Unavailable
                            }

                    KidsSection.SHOWS -> shows.value = library.browse(BaseItemKind.SERIES)
                    KidsSection.MOVIES -> movies.value = library.browse(BaseItemKind.MOVIE)
                    KidsSection.MY_LIST -> myList.value = library.myList()
                }
            }
        }

        fun open(tile: KidsTile) {
            viewModelScope.launchIO { player.open(tile) }
        }

        fun playHero(hero: KidsHero) {
            viewModelScope.launchIO { player.play(hero.tile) }
        }
    }

sealed interface KidsDetailsUi {
    data object Loading : KidsDetailsUi

    data class Ready(
        val details: KidsDetails,
    ) : KidsDetailsUi

    data object Failed : KidsDetailsUi
}

@HiltViewModel
class KidsDetailsViewModel
    @Inject
    constructor(
        private val library: KidsLibraryService,
        private val player: KidsPlayer,
    ) : ViewModel() {
        val state = MutableStateFlow<KidsDetailsUi>(KidsDetailsUi.Loading)
        val favourite: StateFlow<Boolean> get() = fav
        private val fav = MutableStateFlow(false)
        private var loadedId: UUID? = null

        fun load(id: UUID) {
            if (loadedId == id) return
            loadedId = id
            viewModelScope.launchIO {
                val d = library.details(id)
                if (d == null) {
                    state.value = KidsDetailsUi.Failed
                } else {
                    fav.value = d.favourite
                    state.value = KidsDetailsUi.Ready(d)
                }
            }
        }

        fun play() {
            val target = (state.value as? KidsDetailsUi.Ready)?.details?.playTarget ?: return
            viewModelScope.launchIO { player.playAt(target.itemId, target.resumeMs) }
        }

        fun playEpisode(ep: KidsEpisode) {
            viewModelScope.launchIO { player.playItem(ep.id) }
        }

        fun toggleList() {
            val d = (state.value as? KidsDetailsUi.Ready)?.details ?: return
            val id = runCatching { d.id.toUUID() }.getOrNull() ?: return
            val want = !fav.value
            fav.value = want
            viewModelScope.launchIO {
                try {
                    if (library.setFavourite(id, want)) KidsMyListEpoch.bump() else fav.value = !want
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "[kids] heart failed")
                    fav.value = !want
                }
            }
        }
    }

// ---- the shell ----

@Composable
private fun KidsMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KidsMascot(84.dp)
            KidsText(text, 20.sp, black = true)
        }
    }
}

@Composable
private fun KidsSectionContent(
    section: KidsSection,
    viewModel: KidsBrowseViewModel = hiltViewModel(),
) {
    LaunchedEffect(section) { viewModel.ensure(section) }
    // key(section): a section keeps its scroll / focus memory while it is the open section and across a details page (saved with
    // the Home entry); switching to another section and back starts that section fresh
    key(section) {
        val memory = rememberKidsFocusMemory()
        when (section) {
            KidsSection.HOME -> {
                val ui by viewModel.home.collectAsState()
                KidsHomeContent(ui, memory, onOpen = viewModel::open, onPlayHero = viewModel::playHero)
            }

            else -> {
                val flow =
                    when (section) {
                        KidsSection.SHOWS -> viewModel.shows
                        KidsSection.MOVIES -> viewModel.movies
                        else -> viewModel.myList
                    }
                val tiles by flow.collectAsState()
                KidsGridContent(section, tiles, memory, onOpen = viewModel::open, gridState = rememberLazyGridState())
            }
        }
    }
}

@Composable
fun KidsDetailsHost(
    itemId: UUID,
    modifier: Modifier = Modifier,
    viewModel: KidsDetailsViewModel = hiltViewModel(),
) {
    LaunchedEffect(itemId) { viewModel.load(itemId) }
    val state by viewModel.state.collectAsState()
    val fav by viewModel.favourite.collectAsState()
    val memory = rememberKidsFocusMemory()
    when (val s = state) {
        KidsDetailsUi.Loading -> Box(modifier.fillMaxSize())
        KidsDetailsUi.Failed -> KidsMessage("I could not open that one")
        is KidsDetailsUi.Ready -> {
            var seasonIndex by rememberSaveable { mutableIntStateOf(s.details.initialSeason) }
            KidsDetailsContent(
                details = s.details,
                seasonIndex = seasonIndex,
                favourite = fav,
                onSeason = { seasonIndex = it },
                onPlay = viewModel::play,
                onToggleList = viewModel::toggleList,
                onEpisode = viewModel::playEpisode,
                memory = memory,
                modifier = modifier,
            )
        }
    }
}

/**
 * The Kids shell, drawn instead of the nav drawer + pages when the signed-in user is a kid. A whitelist of destinations: Home
 * (its four sections), Search (the app's own search page: no chat) and Details (movie / series / episode). Anything else a stray
 * key or link might push shows Home: never an adult page.
 */
@Composable
fun KidsShell(
    destination: Destination,
    preferences: UserPreferences,
    user: JellyfinUser,
    server: JellyfinServer,
    onClearBackdrop: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KidsShellViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { onClearBackdrop() }
    val section by KidsSectionState.flow.collectAsState()
    BackHandler(enabled = destination is Destination.Home && section != KidsSection.HOME) { KidsSectionState.set(KidsSection.HOME) }
    CompositionLocalProvider(LocalKidsTokens provides KidsTokens.Playground) {
        KidsCanvas(modifier) {
            val isSearch = destination is Destination.Search || destination is Destination.JellytipAskChat || destination is Destination.JellytipAsk
            val detailsId: UUID? =
                when {
                    // A film, a series, or an episode (which opens its series' page); people, box sets, folders and the rest are not on the list
                    destination is Destination.MediaItem &&
                        (destination.type == BaseItemKind.MOVIE || destination.type == BaseItemKind.SERIES || destination.type == BaseItemKind.EPISODE) -> destination.itemId
                    destination is Destination.SeriesOverview -> destination.itemId
                    else -> null
                }
            when {
                detailsId != null -> KidsDetailsHost(detailsId)
                else ->
                    Column(Modifier.fillMaxSize()) {
                        KidsNavBar(
                            userName = user.name,
                            selected = if (isSearch) null else section,
                            onSection = viewModel::go,
                            onSearch = { if (!isSearch) viewModel.search() },
                            onSwitch = { viewModel.switchProfile(server) },
                        )
                        Box(Modifier.weight(1f)) {
                            if (isSearch) {
                                val query = (destination as? Destination.Search)?.query.orEmpty()
                                SearchPage(initialQuery = query, userPreferences = preferences, modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp))
                            } else {
                                // Home, and the fallback for any destination not on the list: never an adult page
                                KidsSectionContent(section)
                            }
                        }
                    }
            }
        }
    }
}
