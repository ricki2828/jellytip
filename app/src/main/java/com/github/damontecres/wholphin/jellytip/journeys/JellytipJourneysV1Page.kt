package com.github.damontecres.wholphin.jellytip.journeys

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.JellytipAdmin
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.fillFullBleed
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.jellytipFocusRoom
import com.github.damontecres.wholphin.jellytip.rememberIsJellytipAdmin
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject

/*
 * Journeys v1 page (plan ~/ai/plans/jellytip-journeys-100x.md §4, approved mock ~/ai/state/briefs/journeys/tv-mock.png): the hero is
 * tonight's step of the active journey (backdrop, "<JOURNEY> · STEP n OF m", title / meta, the "why", ▶ Play focused + See the path),
 * the path strip (✓ done / ● now / ○ next), then rows: Your journeys, Start something new, Finish what you started. Server-built
 * per person (kids policy applied there). A server without Journeys v1 shows the client-built journeys page as before.
 */

internal object JvColors {
    val Peach = Color(0xFFF3B37C)
    val Dim = JellytipPalette.Vanilla.copy(alpha = 0.62f)
    val Faint = JellytipPalette.Vanilla.copy(alpha = 0.38f)
    val Card = Color(0xFF1C2130)
}

data class JvPageState(
    /** null = loading. */
    val home: JvLoad<JvHome>? = null,
    /** The hero's journey (with steps): null = loading or none. */
    val hero: JvJourney? = null,
    val heroFailed: Boolean = false,
    val retrying: Boolean = false,
    /** "Get it" answer to show under the buttons. */
    val note: String? = null,
)

/** What the page can do (the view model; a fake in the shots). */
interface JvActions {
    fun play(
        j: JvJourney,
        index: Int,
    )

    fun open(id: String)

    fun getIt(j: JvJourney)

    fun retry()

    fun focused(id: String?)
}

@HiltViewModel
class JellytipJourneysV1ViewModel
    @Inject
    constructor(
        private val service: JellytipJourneysV1Service,
        private val api: JellytipApi,
        val navigationManager: NavigationManager,
    ) : ViewModel(),
        JvActions {
        private val _state = MutableStateFlow(JvPageState())
        val state: StateFlow<JvPageState> = _state
        private var loadJob: Job? = null
        private var attempt = 0
        private var user: String? = null

        /** The card / button last focused: Back from the player or a journey lands on it again. */
        var lastFocus: String? = null
            private set

        override fun focused(id: String?) {
            if (id != null) lastFocus = id
        }

        /** Open / back on the page: a fresh answer, kept in place (no re-rank). Failures retry with backoff while the page is up. */
        fun load(again: Boolean = false) {
            val who = api.currentUserKey()
            if (who != user) {
                // Another profile: nothing of the last one stays (kids policy), and its load is dropped
                loadJob?.cancel()
                user = who
                lastFocus = null
                attempt = 0
                _state.value = JvPageState()
            }
            if (loadJob?.isActive == true) return
            loadJob =
                viewModelScope.launch {
                    while (true) {
                        val r = service.home()
                        val old = (_state.value.home as? JvLoad.Ok)?.value
                        when (r) {
                            is JvLoad.Ok -> {
                                attempt = 0
                                val merged = JourneysV1.stable(old, r.value)
                                _state.update { it.copy(home = JvLoad.Ok(merged), retrying = false) }
                                loadHero(merged)
                                return@launch
                            }

                            JvLoad.NoRoute, JvLoad.NotFound -> {
                                _state.update { it.copy(home = JvLoad.NoRoute, retrying = false) }
                                return@launch
                            }

                            is JvLoad.Failed -> {
                                // Shown only when nothing is on screen yet; a page already up keeps its cards
                                if (old == null) _state.update { it.copy(home = r, retrying = false) }
                                if (again && old != null) return@launch
                                delay(JourneysV1.backoffMs(attempt++))
                            }
                        }
                    }
                }
        }

        /** The page is hidden: no retries while nobody is looking (they start again on resume). */
        fun pause() {
            loadJob?.cancel()
        }

        override fun retry() {
            loadJob?.cancel()
            attempt = 0
            _state.update { it.copy(retrying = true) }
            load()
        }

        /**
         * The hero: the active journey, else the first of yours, else the first to start. A 404 (hidden / gone since the list) moves
         * on to the next one; a failure retries with backoff (this runs in the page's load job: it stops while the page is hidden).
         */
        private suspend fun loadHero(h: JvHome) {
            val candidates = heroCandidates(h)
            if (candidates.isEmpty()) return _state.update { it.copy(hero = null, heroFailed = false, note = null) }
            var tries = 0
            for (id in candidates.take(HERO_TRIES)) {
                while (true) {
                    when (val r = service.journey(id)) {
                        is JvLoad.Ok ->
                            return _state.update {
                                it.copy(hero = r.value, heroFailed = false, note = if (it.hero?.summary?.id == id) it.note else null)
                            }

                        JvLoad.NotFound, JvLoad.NoRoute -> break

                        is JvLoad.Failed -> {
                            // A hero already up stays; nothing yet: say so (Retry), and keep trying
                            _state.update { if (it.hero?.summary?.id == id) it else it.copy(hero = null, heroFailed = true) }
                            delay(JourneysV1.backoffMs(tries++))
                        }
                    }
                }
            }
            _state.update { it.copy(hero = null, heroFailed = false, note = null) }
        }

        override fun play(
            j: JvJourney,
            index: Int,
        ) {
            jvPlay(viewModelScope, service, api, navigationManager, j, index)
        }

        /** A card or the hero's See the path (focus has already said which: [focused]). */
        override fun open(id: String) {
            JellytipTrailerControl.stop()
            navigationManager.navigateTo(Destination.JellytipJourney(id, v1 = true), "journeys v1 card OK")
        }

        override fun getIt(j: JvJourney) {
            if (!JellytipAdmin.isAdmin) return
            _state.update { it.copy(note = "Asking for it…") }
            viewModelScope.launch {
                val note =
                    when (val r = service.get(j.summary.id)) {
                        is JvGet.Queued -> "On its way. It shows up here when it's downloaded."
                        is JvGet.Refused -> r.reason
                    }
                _state.update { it.copy(note = note) }
            }
        }

        companion object {
            const val HERO_TRIES = 3

            fun heroCandidates(h: JvHome): List<String> = (listOfNotNull(h.active) + h.yours + h.start + h.finish).distinct()

            fun heroId(h: JvHome): String? = heroCandidates(h).firstOrNull()
        }
    }

/**
 * Play step [index] of [j]: library or IPTV alike. Navigate first; only a navigation that went counts as a play (pattern
 * navigate-before-play): then start the journey if it wasn't active, a skip for tonight's step if a later one was picked, and the play.
 */
internal fun jvPlay(
    scope: kotlinx.coroutines.CoroutineScope,
    service: JellytipJourneysV1Service,
    api: JellytipApi,
    navigationManager: NavigationManager,
    j: JvJourney,
    index: Int,
) {
    val step = j.steps.getOrNull(index) ?: return
    if (!step.source.playable) return
    // One play at a time: a second OK while the first is still resolving does nothing (no double navigation / events)
    if (!jvPlayBusy.compareAndSet(false, true)) return
    JellytipTrailerControl.stop()
    scope.launch {
      try {
        val dest =
            if (step.source == JvSource.IPTV) {
                val v = step.iptv ?: return@launch
                Destination.JellytipIptvPlay(
                    // The step's own title: a journey's IPTV object can be just {id, kind} (live payload 23-Nov)
                    title = step.title,
                    vodId = v.id,
                    resumeMs = v.progress?.takeIf { it.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L,
                )
            } else {
                val uuid = step.itemId?.let { runCatching { it.toUUID() }.getOrNull() } ?: return@launch
                val resume = withTimeoutOrNull(1_500) { api.libraryResumeMsOrNull(uuid) } ?: 0L
                Destination.Playback(uuid, resume)
            }
        if (!navigationManager.navigateToChecked(dest, "journey play ${j.summary.id} #${step.n}")) return@launch
        JellytipTrace.log("journeys", "play ${j.summary.id} step ${index + 1}/${j.steps.size} ${step.source}")
        if (j.summary.status != "active") service.action(j.summary.id, "start")
        JourneysV1.skipFor(j.steps, index)?.let { service.event(j.summary.id, "skip", it.tmdbId) }
        service.event(j.summary.id, "play", step.tmdbId)
      } finally {
        jvPlayBusy.set(false)
      }
    }.invokeOnCompletion { jvPlayBusy.set(false) } // also when cancelled before it ever ran (scope gone)
}

/** A play (or Home's fetch-then-play) is under way. */
internal val jvPlayBusy = java.util.concurrent.atomic.AtomicBoolean(false)

@Composable
fun JellytipJourneysV1Page(
    modifier: Modifier = Modifier,
    viewModel: JellytipJourneysV1ViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Open, and back from the player / a journey: fresh progress, cards kept in place
    LifecycleResumeEffect(Unit) {
        viewModel.load(again = true)
        onPauseOrDispose { viewModel.pause() }
    }
    if (state.home is JvLoad.NoRoute) {
        // A server without Journeys v1: the client-built journeys
        JellytipJourneysPage(modifier = modifier)
        return
    }
    val isAdmin by rememberIsJellytipAdmin()
    JourneysV1Content(state, viewModel, isAdmin, viewModel.lastFocus, modifier)
}

/** The page itself (pure: the shots draw it with a fixture). */
@Composable
internal fun JourneysV1Content(
    state: JvPageState,
    actions: JvActions,
    isAdmin: Boolean,
    restoreId: String?,
    modifier: Modifier = Modifier,
) {
    val home = (state.home as? JvLoad.Ok)?.value
    val hero = state.hero
    val heroNow = hero?.let { JourneysV1.nowIndex(it.steps) }
    val heroStep = heroNow?.let { hero?.steps?.getOrNull(it) }
    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        // Backdrop of tonight's film, full width behind the hero
        val backdrop = heroStep?.backdrop ?: heroStep?.iptv?.backdrop ?: hero?.summary?.backdrop
        if (backdrop != null) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f).fillFullBleed()) {
                AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to JellytipPalette.Night, 0.45f to JellytipPalette.Night.copy(alpha = 0.9f), 0.85f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to JellytipPalette.Night)))
            }
        }
        val first = remember { FocusRequester() }
        val restore = remember { FocusRequester() }
        when {
            state.home == null -> {
                Column(Modifier.padding(start = JellytipLayout.PageStart, top = JellytipLayout.PageTop)) {
                    Text("Journeys", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = JellytipLayout.PageTitle)
                    com.github.damontecres.wholphin.jellytip.JellytipSkeleton(tileW = 128.dp, tileH = 192.dp, rows = 1, perRow = 5)
                }
            }

            home == null || home.isEmpty -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = JellytipLayout.PageStart, top = JellytipLayout.PageTop)) {
                    Text("Journeys", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = JellytipLayout.PageTitle)
                    val failed = home == null
                    Text(
                        if (failed) "Journeys aren't available right now. Trying again…" else "No journeys yet. They're built overnight from your lists and films.",
                        color = JvColors.Dim,
                        fontFamily = JellytipFontFamily,
                        fontSize = 16.sp,
                    )
                    val retry = remember { FocusRequester() }
                    JvButton(if (state.retrying) "Retrying…" else "Retry", Modifier.focusRequester(retry).testTag("jv-retry")) { actions.retry() }
                    LaunchedEffect(Unit) { retry.tryRequestFocus() }
                }
            }

            else -> {
                val listState = rememberLazyListState()
                // Focus once per visit: on the restored card / button, else Play. The hero lands after the rows, so a miss is tried
                // again then, but never once he has moved (the page holds focus) or after a refetch
                var focusDone by remember { mutableStateOf(false) }
                LaunchedEffect(home.journeys.keys, hero?.summary?.id) {
                    if (focusDone) return@LaunchedEffect
                    withFrameNanos { }
                    if (focusDone) return@LaunchedEffect
                    // Only real focus counts (the list's onFocusChanged sets focusDone): a requester not attached yet doesn't
                    if (restoreId == null || !restore.tryRequestFocus()) first.tryRequestFocus()
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(JellytipLayout.RowGap),
                    contentPadding = PaddingValues(top = JellytipLayout.PageTop, bottom = 48.dp),
                    modifier = Modifier.fillMaxSize().focusRestorer(first).onFocusChanged { if (it.hasFocus) focusDone = true }.testTag("jv-list"),
                ) {
                    item(key = "hero") {
                        JvHero(state, hero, heroNow, isAdmin, actions, first, restore.takeIf { restoreId == "hero:play" }, restore.takeIf { restoreId == "hero:path" })
                    }
                    listOf("Your journeys" to home.yours, "Start something new" to home.start, "Finish what you started" to home.finish).forEach { (label, ids) ->
                        val row = home.row(ids)
                        if (row.isEmpty()) return@forEach
                        item(key = "row:$label") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = JellytipLayout.PageStart)) {
                                Text(label, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                val at = row.indexOfFirst { it.id == restoreId }
                                val rowState = rememberLazyListState(initialFirstVisibleItemIndex = at.coerceAtLeast(0))
                                LazyRow(
                                    state = rowState,
                                    horizontalArrangement = Arrangement.spacedBy(JellytipLayout.TileGap),
                                    contentPadding = PaddingValues(start = JellytipLayout.FocusRoom, end = JellytipLayout.PageEnd, top = 6.dp, bottom = 6.dp),
                                    modifier = Modifier.jellytipFocusRoom().focusGroup().focusRestorer(),
                                ) {
                                    items(row, key = { it.id }) { s ->
                                        val m = if (s.id == restoreId) Modifier.focusRequester(restore) else Modifier
                                        JvPosterCard(s, m.onFocusChanged { f -> if (f.isFocused) actions.focused(s.id) }) { actions.open(s.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun JvHero(
    state: JvPageState,
    hero: JvJourney?,
    now: Int?,
    isAdmin: Boolean,
    actions: JvActions,
    first: FocusRequester,
    restore: FocusRequester?,
    restorePath: FocusRequester? = null,
) {
    val step = now?.let { hero?.steps?.getOrNull(it) }
    val getAt = hero?.let { JourneysV1.getIndex(it.steps) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = JellytipLayout.PageStart, end = JellytipLayout.PageEnd).testTag("jv-hero"),
    ) {
        if (hero == null) {
            // Loading (or the journey failed to load): the page still has a focus target
            Text(if (state.heroFailed) "Couldn't load tonight's step." else "Loading tonight's step…", color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 15.sp)
            if (state.heroFailed) JvButton("Retry", Modifier.focusRequester(first)) { actions.retry() }
            return@Column
        }
        val s = hero.summary
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (step != null) JourneysV1.kicker(s.title, now ?: 0, hero.steps.size) else s.title.uppercase() + " · FINISHED",
                color = JvColors.Peach,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("jv-kicker"),
            )
            if (s.curated) CuratedPill(Modifier.testTag("jv-curated"))
        }
        if (step != null) {
            Text(
                step.title + (step.year?.let { " ($it)" } ?: ""),
                color = JellytipPalette.Vanilla,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 600.dp),
            )
            Text(JourneysV1.meta(step), color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 13.sp)
            // Curated: the "why"; a quest has none: its premise (if any) says what the path is
            (step.why ?: hero.premise)?.let {
                Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.88f), fontFamily = JellytipFontFamily, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 440.dp).testTag("jv-why"))
            }
        } else {
            Text("Every step seen.", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp).focusGroup()) {
            val playM = Modifier.focusRequester(first).then(if (restore != null) Modifier.focusRequester(restore) else Modifier)
            val pathM = (if (restorePath != null) Modifier.focusRequester(restorePath) else Modifier).onFocusChanged { if (it.isFocused) actions.focused("hero:path") }
            if (step != null) {
                JvButton("▶  Play ${step.title}", playM.onFocusChanged { if (it.isFocused) actions.focused("hero:play") }.testTag("jv-play"), primary = true) { actions.play(hero, now ?: return@JvButton) }
                JvButton("See the path", pathM.testTag("jv-path")) { actions.open(s.id) }
            } else {
                JvButton("See the path", playM.then(pathM).testTag("jv-path")) { actions.open(s.id) }
            }
            if (isAdmin && getAt != null) {
                JvButton("Get ${hero.steps[getAt].title}", Modifier.testTag("jv-get")) { actions.getIt(hero) }
            }
        }
        state.note?.let { Text(it, color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 13.sp) }
        PathStrip(hero.steps, now, Modifier.padding(top = 4.dp).testTag("jv-path-strip"))
    }
}

/** ✓ Le Samouraï — ● Thief — ○ Manhunter …, at most [JourneysV1.PATH_MAX] steps around tonight's. */
@Composable
internal fun PathStrip(
    steps: List<JvStep>,
    now: Int?,
    modifier: Modifier = Modifier,
) {
    val w = JourneysV1.window(steps.size, now)
    // One line, always: the separators and "+n" keep their size, the step names share what is left (ellipsised), so a long
    // path never wraps or pushes the strip down
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.fillMaxWidth()) {
        if (w.before > 0) Text("+${w.before} ✓ ——", color = JvColors.Faint, fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 1, softWrap = false)
        for (i in w.from..w.to) {
            if (i > w.from) Text("—", color = JvColors.Faint, fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 1, softWrap = false)
            val dot = JourneysV1.dot(steps, i, now)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f, fill = false).widthIn(max = 170.dp)) {
                when (dot) {
                    JvDot.DONE -> Text("✓", color = JellytipPalette.Vanilla, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    JvDot.NOW -> Box(Modifier.size(11.dp).background(JvColors.Peach, CircleShape))
                    else -> Box(Modifier.size(11.dp).border(1.5.dp, if (dot == JvDot.NEXT) JvColors.Dim else JvColors.Faint, CircleShape))
                }
                Text(
                    steps[i].title,
                    color =
                        when (dot) {
                            JvDot.DONE -> JellytipPalette.Vanilla
                            JvDot.NOW -> JvColors.Peach
                            JvDot.NEXT -> JvColors.Dim
                            else -> JvColors.Faint
                        },
                    fontFamily = JellytipFontFamily,
                    fontWeight = if (dot == JvDot.DONE || dot == JvDot.NOW) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (w.after > 0) Text("— +${w.after}", color = JvColors.Faint, fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun CuratedPill(modifier: Modifier = Modifier) {
    Text(
        "Curated",
        color = JvColors.Dim,
        fontFamily = JellytipFontFamily,
        fontSize = 12.sp,
        modifier = modifier.border(1.dp, JvColors.Faint, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
internal fun JvButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = JellytipSurfaceFocus.SCALE),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) JvColors.Peach.copy(alpha = 0.9f) else JellytipSurfaceFocus.Rest,
                contentColor = if (primary) JellytipSurfaceFocus.Ink else JellytipPalette.Vanilla,
                focusedContainerColor = JellytipSurfaceFocus.Pastel,
                focusedContentColor = JellytipSurfaceFocus.Ink,
                pressedContainerColor = JellytipSurfaceFocus.Pastel,
                pressedContentColor = JellytipSurfaceFocus.Ink,
            ),
        modifier = modifier,
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

/** A journey as a poster card: the step's poster (or a quiet panel), its title, "2/6" and a progress bar. */
@Composable
internal fun JvPosterCard(
    s: JvSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var failed by remember(s.poster) { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = JellytipSurfaceFocus.border(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = JellytipSurfaceFocus.SCALE),
        colors = ClickableSurfaceDefaults.colors(containerColor = JvColors.Card, focusedContainerColor = JvColors.Card, contentColor = JellytipPalette.Vanilla, focusedContentColor = JellytipPalette.Vanilla),
        modifier = modifier.width(128.dp).height(192.dp).testTag("jv-card"),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (s.poster != null && !failed) {
                AsyncImage(model = s.poster, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
            if (s.curated) CuratedPill(Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text(s.title, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = JellytipPalette.Vanilla)
                Text(JourneysV1.progressLabel(s) + (s.nowTitle?.let { " · next: $it" } ?: ""), fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = JvColors.Dim)
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(JourneysV1.progress(s)).height(3.dp).background(JvColors.Peach, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
