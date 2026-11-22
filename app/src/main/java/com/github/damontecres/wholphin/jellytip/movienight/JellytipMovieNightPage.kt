package com.github.damontecres.wholphin.jellytip.movienight

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsService
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/*
 * Movie night on the TV (round 3 item 6, mock-up 06): the TV hosts. It offers a shortlist of unwatched library films,
 * creates the night (POST /v1/me/movienight), shows the join code big and polls every 3 s while posters fill with
 * votes cast on phones (web #/movienight). "Close voting and reveal" closes it; the winner takes the screen with Play and
 * Details & trailer. The code is remembered per person, so leaving and coming back resumes the same night; Cancel and
 * Done close it on the server (a host may only have three open at once).
 *
 * Shortlist: the web ranks Tonight's candidates client-side; the TV takes unstarted library films from the server's
 * Home recommendation rows in server order instead (same row filter as the web's Tonight loader).
 */

private const val POLL_MS = 3_000L

/** Where phones vote: the web app next to the companion (…/jellytip-api → …/jellytip), Movie night → Join. */
fun movieNightJoinHint(companionBase: String): String {
    val web = companionBase.removePrefix("https://").removePrefix("http://").trimEnd('/').removeSuffix("-api")
    return "$web  →  Movie night  →  Join"
}

data class MnFilm(
    val id: String,
    val uuid: UUID,
    val name: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
)

@HiltViewModel
class JellytipMovieNightViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val jellytipApi: JellytipApi,
        private val rows: JellytipRowsService,
        private val api: ApiClient,
        private val images: ImageUrlService,
        private val serverRepository: ServerRepository,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        sealed interface Phase {
            data object Loading : Phase

            data class Pick(
                val films: List<MnFilm>,
                val on: Set<String>,
            ) : Phase

            data object Empty : Phase

            data class Night(
                val session: MnSession,
            ) : Phase
        }

        data class State(
            val phase: Phase = Phase.Loading,
            val films: Map<String, MnFilm> = emptyMap(),
            val busy: Boolean = false,
            val error: String? = null,
            val offline: Boolean = false,
            val notice: String? = null,
            val joinHint: String = "",
            /** The companion address, for the join QR (JoinQr.joinUrl; null link = code only). */
            val joinBase: String? = null,
            /** The result was shown once: a late joiner must not flip the screen back to voting. */
            val revealed: Boolean = false,
        )

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state
        private var poll: Job? = null

        private val prefs by lazy { context.getSharedPreferences("jellytip_movienight", Context.MODE_PRIVATE) }

        private fun codeKey() = "code.${serverRepository.currentUser?.id ?: "none"}"

        private fun remembered(): String? = prefs.getString(codeKey(), null)

        private fun saveCode(code: String?) {
            prefs.edit().apply { if (code == null) remove(codeKey()) else putString(codeKey(), code) }.apply()
        }

        fun start() {
            viewModelScope.launch {
                val base = jellytipApi.companionBase()
                _state.value = _state.value.copy(joinHint = movieNightJoinHint(base), joinBase = base)
            }
            val code = remembered()
            if (code != null) {
                follow(code)
            } else {
                loadShortlist()
            }
        }

        private fun loadShortlist() {
            _state.value = _state.value.copy(phase = Phase.Loading)
            viewModelScope.launch {
                val films = runCatching { shortlist() }.onFailure { Timber.w(it, "movie night shortlist") }.getOrDefault(emptyList())
                _state.value =
                    _state.value.copy(
                        phase = if (films.size < MovieNight.MIN_FILMS) Phase.Empty else Phase.Pick(films, films.map { it.id }.toSet()),
                        films = _state.value.films + films.associateBy { it.id },
                    )
            }
        }

        /** Unstarted library films from the Home recommendation rows, server order, at most [MovieNight.TV_SHORTLIST]. */
        private suspend fun shortlist(): List<MnFilm> {
            val answer = rows.load("home") ?: return emptyList()
            val recRows =
                answer.rows.filter { r ->
                    r.source != "iptv" && r.person == null && r.collection == null && r.iptvItems.isEmpty() && r.mediaType == "movie" &&
                        r.family !in setOf("fresh", "ratings", "awards") && !r.id.startsWith("trending")
                }
            val ids = LinkedHashSet<UUID>()
            for (r in recRows.take(6)) ids += r.itemIds.take(8)
            if (ids.isEmpty()) return emptyList()
            return load(ids.toList())
                .filter { it.type == BaseItemKind.MOVIE && !it.played && it.resumeMs <= 0L }
                .mapNotNull { film(it) }
                .take(MovieNight.TV_SHORTLIST)
        }

        private suspend fun load(ids: List<UUID>): List<BaseItem> {
            val userId = serverRepository.currentUser?.id
            val byId =
                ids
                    .chunked(40)
                    .flatMap { chunk ->
                        GetItemsRequestHandler
                            .execute(
                                api,
                                GetItemsRequest(userId = userId, ids = chunk, fields = HomeItemFields + ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, enableUserData = true),
                            ).content.items
                            .map { BaseItem(it, false) }
                    }.associateBy { it.id }
            return ids.mapNotNull { byId[it] }
        }

        private fun film(it: BaseItem): MnFilm? {
            val id = MovieNight.itemId(it.id.toString()) ?: return null
            return MnFilm(
                id = id,
                uuid = it.id,
                name = it.name ?: "",
                year = it.data.productionYear,
                poster = images.getItemImageUrl(it.id, ImageType.PRIMARY, maxHeight = 480),
                backdrop = images.getItemImageUrl(it.id, ImageType.BACKDROP, maxWidth = 1920),
            )
        }

        fun toggle(id: String) {
            val p = _state.value.phase as? Phase.Pick ?: return
            val on = if (id in p.on) p.on - id else if (p.on.size < MovieNight.MAX_FILMS) p.on + id else p.on
            _state.value = _state.value.copy(phase = p.copy(on = on), error = null)
        }

        fun create() {
            val p = _state.value.phase as? Phase.Pick ?: return
            val chosen = p.films.filter { it.id in p.on }.map { it.id }
            if (chosen.size < MovieNight.MIN_FILMS || _state.value.busy) return
            _state.value = _state.value.copy(busy = true, error = null)
            viewModelScope.launch {
                val body = JSONObject().put("items", JSONArray(chosen)).toString()
                val r = jellytipApi.userCall("v1/me/movienight", body)
                val s = r?.takeIf { it.first in 200..299 }?.let { MovieNight.parse(it.second) }
                if (s == null) {
                    _state.value = _state.value.copy(busy = false, error = MovieNight.errorText(r?.first ?: 0, r?.second))
                    return@launch
                }
                JellytipTrace.log("movienight", "created ${s.code} with ${s.items.size} films")
                saveCode(s.code)
                _state.value = _state.value.copy(busy = false, phase = Phase.Night(s))
                follow(s.code)
            }
        }

        /** Poll the night every [POLL_MS] while this page is up (the page cancels it when it leaves). */
        private fun follow(code: String) {
            poll?.cancel()
            poll =
                viewModelScope.launch {
                    while (isActive) {
                        val epoch = voteEpoch
                        val r = jellytipApi.userCall("v1/me/movienight/$code", timeoutMs = 8_000)
                        when {
                            r == null -> _state.value = _state.value.copy(offline = true)
                            r.first == 200 -> {
                                // An answer that may predate one of your votes (pressed, or still being saved) would undo it on
                                // screen: skip it, the next poll is 3 s away
                                val fresh = epoch == voteEpoch && sending == 0
                                MovieNight.parse(r.second)?.takeIf { fresh }?.let { s ->
                                    _state.value =
                                        _state.value.copy(
                                            phase = Phase.Night(s),
                                            offline = false,
                                            // a vote error goes with the first answer asked after it (a fresh one); close()'s errors stay
                                            error = if (voteError) null else _state.value.error,
                                            revealed = _state.value.revealed || MovieNight.showReveal(s, lastOwnVote, System.currentTimeMillis()),
                                        )
                                    voteError = false
                                    ensureFilms(s.items)
                                }
                            }
                            r.first == 404 -> {
                                saveCode(null)
                                _state.value = _state.value.copy(notice = "That movie night has ended or expired.")
                                loadShortlist()
                                return@launch
                            }
                            r.first == 401 || r.first == 403 -> {
                                _state.value = _state.value.copy(error = "You're signed out. Sign in again to get back to your movie night.")
                                return@launch
                            }
                            else -> _state.value = _state.value.copy(offline = true)
                        }
                        // Nothing changes after the night closed: one last read is enough
                        if ((_state.value.phase as? Phase.Night)?.session?.open == false) return@launch
                        delay(POLL_MS)
                    }
                }
        }

        /** Bumped by every vote press and every finished save: a poll started before it may not repaint your votes. */
        @Volatile private var voteEpoch = 0

        /** Saves in flight (one at a time, see [sendLock]). */
        @Volatile private var sending = 0
        private val sendLock = kotlinx.coroutines.sync.Mutex()

        /** Your latest wish per film, not yet confirmed by the server: only the newest value is sent. */
        private val wanted = java.util.concurrent.ConcurrentHashMap<String, MnVote>()

        /** What the server last confirmed per film (to undo a vote it didn't save). */
        private val confirmed = java.util.concurrent.ConcurrentHashMap<String, MnVote>()
        private var lastOwnVote = 0L
        /** The error on screen is a vote's (cleared by the next fresh poll), not close()'s. */
        private var voteError = false

        /** OK on a poster: your own vote (the TV's signed-in person is a member as host), Yes → Maybe → No. Shown at once. */
        fun vote(id: String) {
            val s = (_state.value.phase as? Phase.Night)?.session ?: return
            if (!s.open || id !in s.items) return
            if (!wanted.containsKey(id)) s.mine[id]?.let { confirmed[id] = it } ?: confirmed.remove(id)
            val v = MovieNight.nextVote(s.mine[id])
            voteEpoch++
            lastOwnVote = System.currentTimeMillis()
            wanted[id] = v
            _state.value = _state.value.copy(phase = Phase.Night(MovieNight.withVote(s, id, v)), error = null)
            viewModelScope.launch { send(s.code, id) }
        }

        /** One save at a time, in press order; a save sends the newest wish for its film (older presses are dropped). */
        private suspend fun send(
            code: String,
            id: String,
        ) {
            sending++
            try {
                sendLock.withLock {
                    val v = wanted[id] ?: return
                    val body = JSONObject().put("item", id).put("vote", v.wire).toString()
                    val r = jellytipApi.userCall("v1/me/movienight/$code/vote", body, timeoutMs = 8_000)
                    voteEpoch++
                    // A newer press for this film is queued: its own save paints the result
                    val newest = wanted[id] == v
                    if (newest) wanted.remove(id)
                    // Left this night (Cancel / Done / Leave) while the save was out: nothing to paint
                    val here = (_state.value.phase as? Phase.Night)?.session?.code == code
                    when {
                        r?.first == 200 -> {
                            confirmed[id] = v
                            if (newest && here) {
                                MovieNight.parse(r.second)?.let { n ->
                                    // keep any other film's vote still on its way
                                    val shown = wanted.entries.fold(n) { acc, (k, w) -> MovieNight.withVote(acc, k, w) }
                                    _state.value = _state.value.copy(phase = Phase.Night(shown), revealed = _state.value.revealed || MovieNight.showReveal(n, lastOwnVote, System.currentTimeMillis()))
                                }
                            }
                        }
                        !here -> {}
                        else -> {
                            if (newest) {
                                // Put back what the server has, so OK starts again from the truth, and say so
                                val cur = (_state.value.phase as? Phase.Night)?.session
                                if (cur != null) _state.value = _state.value.copy(phase = Phase.Night(MovieNight.withVote(cur, id, confirmed[id])))
                            }
                            voteError = true
                            _state.value = _state.value.copy(error = if (r?.first == 409) "Voting has closed." else "Couldn't save your vote. Press OK to try again.")
                        }
                    }
                }
            } finally {
                sending--
            }
        }

        private suspend fun ensureFilms(ids: List<String>) {
            val missing = ids.filter { it !in _state.value.films }
            if (missing.isEmpty()) return
            val uuids = missing.mapNotNull { dashed(it) }
            val got = runCatching { load(uuids) }.getOrDefault(emptyList()).mapNotNull { film(it) }
            _state.value = _state.value.copy(films = _state.value.films + got.associateBy { it.id })
        }

        fun close() {
            val s = (_state.value.phase as? Phase.Night)?.session ?: return
            if (_state.value.busy) return
            voteError = false // from here the error on screen is close()'s; the poll must not clear it
            _state.value = _state.value.copy(busy = true, error = null)
            viewModelScope.launch {
                val r = jellytipApi.userCall("v1/me/movienight/${s.code}/close", "{}")
                val ns = r?.takeIf { it.first == 200 }?.let { MovieNight.parse(it.second) }
                _state.value =
                    if (ns != null) {
                        _state.value.copy(busy = false, phase = Phase.Night(ns), revealed = _state.value.revealed || MovieNight.finished(ns))
                    } else {
                        _state.value.copy(busy = false, error = MovieNight.errorText(r?.first ?: 0, r?.second))
                    }
            }
        }

        /** Cancel (open) or Done (finished): close it on the server if it's ours and still open, forget it, back to the shortlist. */
        fun finish(then: (() -> Unit)? = null) {
            if (_state.value.busy) return
            _state.value = _state.value.copy(busy = true)
            val s = (_state.value.phase as? Phase.Night)?.session
            poll?.cancel()
            saveCode(null)
            // a save still out must not lay this night's wish over the next one
            wanted.clear()
            confirmed.clear()
            viewModelScope.launch {
                if (s != null && s.host && s.open) jellytipApi.userCall("v1/me/movienight/${s.code}/close", "{}")
                _state.value = State(films = _state.value.films, joinHint = _state.value.joinHint, joinBase = _state.value.joinBase)
                if (then != null) then() else loadShortlist()
            }
        }

        fun play(f: MnFilm) {
            JellytipTrailerControl.stop()
            JellytipTrace.log("movienight", "play winner ${f.id}")
            finish { navigationManager.navigateTo(Destination.Playback(f.uuid, 0L), "movie night winner") }
        }

        fun details(f: MnFilm) = navigationManager.navigateTo(Destination.MediaItem(f.uuid, BaseItemKind.MOVIE), "movie night winner details")

        fun stopPolling() = poll?.cancel()

        fun showReveal(s: MnSession): Boolean = MovieNight.showReveal(s, lastOwnVote, System.currentTimeMillis())

        fun resume() {
            ((_state.value.phase as? Phase.Night)?.session?.code ?: remembered())?.let { follow(it) }
        }

        private fun dashed(id: String): UUID? =
            runCatching {
                UUID.fromString("${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}")
            }.getOrNull()
    }

@Composable
fun JellytipMovieNightPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipMovieNightViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.start() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.stopPolling() }
    }
    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        when (val p = state.phase) {
            JellytipMovieNightViewModel.Phase.Loading ->
                Header(
                    sub =
                        state.error
                            ?: if (state.offline) "Can't reach the server. Trying again…" else "Finding films you haven't watched…",
                )
            JellytipMovieNightViewModel.Phase.Empty ->
                Column {
                    Header(sub = state.notice ?: "Not enough unwatched films in your recommendations to start one yet.")
                }
            is JellytipMovieNightViewModel.Phase.Pick -> PickScreen(p, state, viewModel)
            is JellytipMovieNightViewModel.Phase.Night -> {
                val s = p.session
                if (state.revealed || viewModel.showReveal(s)) RevealScreen(s, state, viewModel) else NightScreen(s, state, viewModel)
            }
        }
    }
}

@Composable
private fun Header(
    title: String = "Pick tonight's film",
    sub: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 64.dp, top = 28.dp, end = 24.dp)) {
        Text("MOVIE NIGHT", color = JellytipPalette.Jelly, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 1.5.sp)
        Text(title, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 34.sp)
        Text(sub, color = JellytipPalette.Vanilla.copy(alpha = 0.75f), fontFamily = JellytipFontFamily, fontSize = 16.sp, maxLines = 2)
    }
}

@Composable
private fun PickScreen(
    p: JellytipMovieNightViewModel.Phase.Pick,
    state: JellytipMovieNightViewModel.State,
    vm: JellytipMovieNightViewModel,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        first.tryRequestFocus()
    }
    Column(Modifier.fillMaxSize()) {
        Header(sub = state.notice ?: "OK ticks or unticks a film. Everyone votes on their phone once it starts.")
        PosterRow(p.films.size) { w ->
            p.films.forEachIndexed { i, f ->
                val on = f.id in p.on
                Column {
                    Poster(f, w, onClick = { vm.toggle(f.id) }, dim = !on, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                    Text(if (on) "✓  In" else "Left out", color = if (on) JellytipPalette.Vanilla else JellytipPalette.Vanilla.copy(alpha = 0.5f), fontFamily = JellytipFontFamily, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Row(Modifier.padding(start = 64.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val n = p.on.size
            Button(if (state.busy) "Starting…" else "Start movie night ($n film${if (n == 1) "" else "s"})", primary = true, enabled = n >= MovieNight.MIN_FILMS && !state.busy) { vm.create() }
            state.error?.let { Text(it, color = JellytipPalette.Jelly, fontFamily = JellytipFontFamily, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun NightScreen(
    s: MnSession,
    state: JellytipMovieNightViewModel.State,
    vm: JellytipMovieNightViewModel,
) {
    val close = remember { FocusRequester() }
    val firstPoster = remember { FocusRequester() }
    // Open night: land on the first poster (the host votes too); closed: on the buttons
    LaunchedEffect(s.code) {
        withFrameNanos { }
        if (!(s.open && firstPoster.tryRequestFocus())) close.tryRequestFocus()
    }
    val totalVotes = s.counts.values.sumOf { it.votes }
    val joined = s.members.size
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
          Row(verticalAlignment = Alignment.Top) {
            Header(
                modifier = Modifier.weight(1f),
                sub =
                    if (state.offline) {
                        "Can't reach the server. Showing the last update."
                    } else {
                        "Vote here with OK on a poster, or on your phone. $joined ${if (joined == 1) "person has" else "people have"} joined, $totalVotes vote${if (totalVotes == 1) "" else "s"} in."
                    },
            )
            JoinCard(s.code, state.joinHint, state.joinBase?.let { JoinQr.joinUrl(it, s.code) })
          }
            PosterRow(s.items.size) { w ->
                s.items.forEachIndexed { i, id ->
                    val f = state.films[id]
                    val c = s.counts[id] ?: MnCounts()
                    Column {
                        if (s.open) {
                            // OK = your vote, cycling Yes → Maybe → No; the badge shows it
                            VotePoster(f, w, s.mine[id], if (i == 0) Modifier.focusRequester(firstPoster) else Modifier) { vm.vote(id) }
                        } else {
                            PosterBox(f, w)
                        }
                        VoteDots(c.yes + c.maybe, maxOf(joined, c.votes))
                        Text(MovieNight.votesLabel(c), color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 14.sp)
                    }
                }
            }
            Row(Modifier.padding(start = 64.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.host && s.open) {
                    Button(if (state.busy) "Closing…" else "Close voting and reveal", primary = true, enabled = !state.busy, modifier = Modifier.focusRequester(close)) { vm.close() }
                    Button("Cancel") { vm.finish() }
                } else {
                    Button("Leave", modifier = Modifier.focusRequester(close)) { vm.finish() }
                }
                state.error?.let { Text(it, color = JellytipPalette.Jelly, fontFamily = JellytipFontFamily, fontSize = 14.sp) }
            }
            Text(
                "Back leaves this screen; the night stays open and comes back here.",
                color = JellytipPalette.Vanilla.copy(alpha = com.github.damontecres.wholphin.jellytip.JellytipLayout.TEXT_ALPHA),
                fontFamily = JellytipFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 64.dp, top = 8.dp),
            )
        }
    }
}

/** Join card (mock-up 06), beside the header so it never covers the posters: the code and, when the web link is known, a QR. */
@Composable
internal fun JoinCard(
    code: String,
    hint: String,
    url: String?,
) {
    Row(
        Modifier
            .padding(top = 24.dp, end = 32.dp)
            .background(Color(0xFFF4DCD0), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (url != null) "Scan, or join with code" else "Join on your phone with code", color = Color(0xFF141414), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(MovieNight.showCode(code), color = Color(0xFF0A0A0A), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = 3.sp)
            if (hint.isNotEmpty()) Text(hint, color = Color(0xFF141414).copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 12.sp)
        }
        // ~150 dp: about 3.5 dp a module, readable from a sofa a few metres away on a 55" TV (not verified on a device)
        url?.let { QrCode(it, 150.dp) }
    }
}

/** A QR of [text] (dark modules on white, quiet zone included), drawn module by module. */
@Composable
private fun QrCode(
    text: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val m = remember(text) { runCatching { JoinQr.matrix(text) }.getOrNull() } ?: return
    androidx.compose.foundation.Canvas(Modifier.size(size).background(Color.White)) {
        val cell = this.size.width / m.size
        for (y in m.indices) {
            val row = m[y]
            for (x in row.indices) {
                if (row[x]) drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** A poster you vote on with OK; the badge in the corner is your vote (none yet = no badge). */
@Composable
internal fun VotePoster(
    f: MnFilm?,
    w: androidx.compose.ui.unit.Dp,
    mine: MnVote?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1A1A1A), focusedContainerColor = Color(0xFF1A1A1A)),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), shape = RoundedCornerShape(10.dp)),
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(10.dp)),
            ),
        modifier = modifier.size(w, w * 1.5f),
    ) {
        Box(Modifier.fillMaxSize()) {
            PosterArt(f, false)
            mine?.let { v ->
                val (label, bg) =
                    when (v) {
                        MnVote.YES -> "✓ Yes" to Color(0xFF2E9E5B)
                        MnVote.MAYBE -> "~ Maybe" to Color(0xFFFFC72C)
                        MnVote.NO -> "✕ No" to JellytipPalette.Jelly
                    }
                Text(
                    label,
                    color = if (v == MnVote.MAYBE) Color(0xFF111111) else Color.White,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * A row of [n] posters that always fits the page (about 870 dp beside the closed rail at 960x540 dp): posters
 * shrink below 140 dp when there are more than five.
 */
@Composable
private fun PosterRow(
    n: Int,
    content: @Composable (androidx.compose.ui.unit.Dp) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 64.dp, top = 16.dp, end = 32.dp)) {
        val gap = 16.dp
        val count = n.coerceAtLeast(1)
        val w = ((maxWidth - gap * (count - 1)) / count).coerceAtMost(140.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) { content(w) }
    }
}

@Composable
private fun RevealScreen(
    s: MnSession,
    state: JellytipMovieNightViewModel.State,
    vm: JellytipMovieNightViewModel,
) {
    val rows = remember(s.items, s.counts) { MovieNight.tally(s.items, s.counts) }
    val top = MovieNight.winner(rows)
    val f = top?.let { state.films[it.id] }
    val play = remember { FocusRequester() }
    LaunchedEffect(top?.id, f != null) {
        withFrameNanos { }
        play.tryRequestFocus()
    }
    Box(Modifier.fillMaxSize()) {
        f?.backdrop?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent),
                ),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 64.dp, end = 64.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TONIGHT'S FILM", color = JellytipPalette.Jelly, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 1.5.sp)
            if (top == null) {
                Text("Nobody wanted any of these.", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 36.sp)
                Button("Start another", primary = true, modifier = Modifier.focusRequester(play)) { vm.finish() }
            } else {
                Text(f?.name ?: "Loading…", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(f?.year?.toString(), "${top.counts.yes} yes · ${top.counts.maybe} maybe · ${top.counts.no} no").joinToString(" · "),
                    color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (f != null) {
                        Button("Play", primary = true, modifier = Modifier.focusRequester(play)) { vm.play(f) }
                        Button("Details & trailer") { vm.details(f) }
                    }
                    Button("Done", modifier = if (f == null) Modifier.focusRequester(play) else Modifier) { vm.finish() }
                }
            }
        }
    }
}

@Composable
private fun Poster(
    f: MnFilm,
    w: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    dim: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1A1A1A), focusedContainerColor = Color(0xFF1A1A1A)),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), shape = RoundedCornerShape(10.dp)),
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(10.dp)),
            ),
        modifier = modifier.size(w, w * 1.5f),
    ) {
        PosterArt(f, dim)
    }
}

@Composable
private fun PosterBox(
    f: MnFilm?,
    w: androidx.compose.ui.unit.Dp,
) {
    Box(Modifier.size(w, w * 1.5f).background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))) {
        PosterArt(f, false)
    }
}

@Composable
private fun PosterArt(
    f: MnFilm?,
    dim: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        f?.poster?.let { AsyncImage(model = it, contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Text(
            f?.name ?: "…",
            color = Color.White,
            fontFamily = JellytipFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
    }
}

@Composable
private fun VoteDots(
    filled: Int,
    of: Int,
) {
    Row(Modifier.padding(top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(of.coerceIn(1, 8)) { i ->
            Box(Modifier.size(13.dp).background(if (i < filled) Color(0xFFFFC72C) else Color.White.copy(alpha = 0.15f), CircleShape))
        }
    }
}

@Composable
internal fun Button(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) Color(0xFFEDEDED) else Color(0xFF242424),
                focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                contentColor = if (primary) Color(0xFF111111) else JellytipPalette.Vanilla,
                focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, JellytipPalette.Jelly), shape = RoundedCornerShape(8.dp)),
            ),
        modifier = modifier.height(48.dp),
    ) {
        Box(Modifier.padding(horizontal = 22.dp).height(48.dp), contentAlignment = Alignment.Center) {
            Text(label, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = if (enabled) Color.Unspecified else Color.Gray)
        }
    }
}
