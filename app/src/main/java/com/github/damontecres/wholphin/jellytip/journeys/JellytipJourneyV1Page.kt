package com.github.damontecres.wholphin.jellytip.journeys

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.jellytipFocusRoom
import com.github.damontecres.wholphin.jellytip.rememberIsJellytipAdmin
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * One journey, v1 ("See the path"): its title, premise, progress and Curated label, Play tonight / Start / Pause / Drop, and every
 * step as a card with ✓ / ● / ○; the focused step's "why" below. OK on a step plays it (a later one than tonight's counts as a skip
 * of tonight's), or for an admin "Get it" on the next missing one. Back from the player lands on the same step.
 */

data class JvJourneyState(
    val load: JvLoad<JvJourney>? = null,
    val retrying: Boolean = false,
    val note: String? = null,
    /** "Get it" per step index (Ricki 23-Nov: any gettable step can be asked for, not only the next one). */
    val asks: Map<Int, JvAsk> = emptyMap(),
)

@HiltViewModel
class JellytipJourneyV1ViewModel
    @Inject
    constructor(
        private val service: JellytipJourneysV1Service,
        private val api: JellytipApi,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow(JvJourneyState())
        val state: StateFlow<JvJourneyState> = _state
        private var job: Job? = null
        private var id: String? = null

        /** The step last focused (index): Back lands on it. */
        var lastStep: Int? = null

        fun load(id: String) {
            if (this.id != id) {
                this.id = id
                lastStep = null
                _state.value = JvJourneyState()
            } else {
                // back on the page: the steps reload (a fetched film may have arrived); a finished ask is kept, a hung one is not
                _state.update { s -> s.copy(asks = s.asks.filterValues { it.state != JvAskState.ASKING }) }
            }
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    var attempt = 0
                    while (true) {
                        val r = service.journey(id)
                        val had = _state.value.load is JvLoad.Ok
                        if (r is JvLoad.Failed && had) return@launch // a page already up keeps its steps
                        _state.update { it.copy(load = r, retrying = false) }
                        if (r !is JvLoad.Failed) return@launch
                        delay(JourneysV1.backoffMs(attempt++))
                    }
                }
        }

        /** Hidden: no retries until it shows again. */
        fun pause() {
            job?.cancel()
        }

        fun retry() {
            job?.cancel()
            _state.update { it.copy(retrying = true) }
            id?.let { load(it) }
        }

        fun play(
            j: JvJourney,
            i: Int,
        ) {
            lastStep = i
            jvPlay(viewModelScope, service, api, navigationManager, j, i)
        }

        /** Get it for step [index] (null = the next missing one, as the header button). One ask per step at a time. */
        fun getIt(
            j: JvJourney,
            index: Int?,
        ) {
            if (!JellytipAdmin.isAdmin) return
            val i = index ?: JourneysV1.getIndex(j.steps) ?: return
            if (_state.value.asks[i]?.state == JvAskState.ASKING) return
            val asking = JvAsk(JvAskState.ASKING)
            _state.update { it.copy(note = JourneysV1.askText(asking), asks = it.asks + (i to asking)) }
            viewModelScope.launch {
                val a = JourneysV1.ask(service.get(j.summary.id, JourneysV1.getBody(j.steps, i)))
                _state.update { it.copy(note = JourneysV1.askText(a), asks = it.asks + (i to a)) }
            }
        }

        fun action(
            j: JvJourney,
            what: String,
        ) {
            service.action(j.summary.id, what)
            val status =
                when (what) {
                    "start" -> "active"
                    "pause" -> "paused"
                    else -> "dropped"
                }
            service.toast(
                when (what) {
                    "start" -> JourneysV1.TOAST_ADDED
                    "pause" -> JourneysV1.TOAST_REMOVED
                    else -> JourneysV1.TOAST_DROPPED
                },
            )
            _state.update { s -> (s.load as? JvLoad.Ok)?.let { s.copy(load = JvLoad.Ok(it.value.copy(summary = it.value.summary.copy(status = status)))) } ?: s }
        }
    }

@Composable
fun JellytipJourneyV1Page(
    id: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipJourneyV1ViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(id) {
        viewModel.load(id)
        onPauseOrDispose { viewModel.pause() }
    }
    val isAdmin by rememberIsJellytipAdmin()
    JourneyV1Content(
        state = state,
        isAdmin = isAdmin,
        restoreStep = viewModel.lastStep,
        onPlay = viewModel::play,
        onGet = viewModel::getIt,
        onAction = viewModel::action,
        onFocusStep = { viewModel.lastStep = it },
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@Composable
internal fun JourneyV1Content(
    state: JvJourneyState,
    isAdmin: Boolean,
    restoreStep: Int?,
    onPlay: (JvJourney, Int) -> Unit,
    onGet: (JvJourney, Int?) -> Unit,
    onAction: (JvJourney, String) -> Unit,
    onFocusStep: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** The shots: start with the drop confirm showing. */
    confirmingDrop: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = JellytipLayout.PageStart, top = JellytipLayout.PageTop, end = JellytipLayout.PageEnd),
    ) {
        when (val l = state.load) {
            null -> {
                Text("Journey", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = JellytipLayout.PageTitle)
                com.github.damontecres.wholphin.jellytip.JellytipSkeleton(tileW = 120.dp, tileH = 180.dp, rows = 1, perRow = 5)
            }

            JvLoad.NotFound, JvLoad.NoRoute -> {
                Text("This journey isn't here any more.", color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 16.sp)
            }

            is JvLoad.Failed -> {
                Text("Couldn't load this journey. Trying again…", color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 16.sp)
                val retry = remember { FocusRequester() }
                JvButton(if (state.retrying) "Retrying…" else "Retry", Modifier.focusRequester(retry)) { onRetry() }
                LaunchedEffect(Unit) { retry.tryRequestFocus() }
            }

            is JvLoad.Ok -> JourneyV1Body(l.value, state.note, state.asks, isAdmin, restoreStep, onPlay, onGet, onAction, onFocusStep, confirmingDrop)
        }
    }
}

@Composable
private fun JourneyV1Body(
    j: JvJourney,
    note: String?,
    asks: Map<Int, JvAsk>,
    isAdmin: Boolean,
    restoreStep: Int?,
    onPlay: (JvJourney, Int) -> Unit,
    onGet: (JvJourney, Int?) -> Unit,
    onAction: (JvJourney, String) -> Unit,
    onFocusStep: (Int) -> Unit,
    confirmingDropAtStart: Boolean = false,
) {
    val s = j.summary
    val now = JourneysV1.nowIndex(j.steps)
    val getAt = JourneysV1.getIndex(j.steps)
    var focused by remember { mutableIntStateOf(restoreStep ?: now ?: 0) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.title, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (s.curated) CuratedPill()
    }
    // Once it's yours, say so (Ricki: after Start "not sure what happened")
    JourneysV1.yourJourneyLine(s.title, s.status, now, j.steps.size)?.let {
        Text(it, color = JvColors.Peach, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("jv-yours"))
    }
    Text(
        listOfNotNull("${s.done} of ${j.steps.size} seen", s.playable.takeIf { it > 0 }?.let { "$it ready to play" }, j.maxRating?.let { "up to $it" }).joinToString(" · "),
        color = JvColors.Dim,
        fontFamily = JellytipFontFamily,
        fontSize = 13.sp,
    )
    j.premise?.let { Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.88f), fontFamily = JellytipFontFamily, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 640.dp)) }
    var confirmingDrop by remember(s.id) { mutableStateOf(confirmingDropAtStart) }
    val playFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusGroup().testTag("jv-path-buttons")) {
        for (b in JourneysV1.pathButtons(s.status, now != null, confirmingDrop)) {
            val label = JourneysV1.btnLabel(b, now?.let { j.steps[it].title })
            val m =
                when (b) {
                    JvBtn.PLAY -> Modifier.focusRequester(playFocus).testTag("jv-btn-play")
                    JvBtn.DROP_CANCEL -> Modifier.focusRequester(confirmFocus).testTag("jv-btn-keep")
                    else -> Modifier.testTag("jv-btn-${b.name.lowercase()}")
                }
            JvButton(label, m, primary = b == JvBtn.PLAY) {
                when (b) {
                    JvBtn.PLAY -> now?.let { onPlay(j, it) } // starts the journey too (jvPlay)
                    JvBtn.REMOVE -> onAction(j, "pause")
                    JvBtn.ADD_BACK -> onAction(j, "start")
                    JvBtn.DROP -> confirmingDrop = true
                    JvBtn.DROP_CONFIRM -> {
                        confirmingDrop = false
                        onAction(j, "drop")
                    }
                    JvBtn.DROP_CANCEL -> confirmingDrop = false
                }
            }
        }
        if (!confirmingDrop && isAdmin && getAt != null) JvButton("Get ${j.steps[getAt].title}") { onGet(j, getAt) }
    }
    if (confirmingDrop) {
        Text("Drop this journey? It leaves your rows; you can still open it from here.", color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 13.sp)
        // focus on the safe choice
        LaunchedEffect(Unit) {
            withFrameNanos { }
            confirmFocus.tryRequestFocus()
        }
    }
    note?.let { Text(it, color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 13.sp) }
    val first = remember { FocusRequester() }
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = ((restoreStep ?: now ?: 0) - 1).coerceAtLeast(0))
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(JellytipLayout.TileGap),
        contentPadding = PaddingValues(start = JellytipLayout.FocusRoom, end = JellytipLayout.PageEnd, top = 6.dp, bottom = 6.dp),
        modifier = Modifier.jellytipFocusRoom().focusGroup().focusRestorer(first).testTag("jv-steps"),
    ) {
        itemsIndexed(j.steps, key = { i, st -> "${st.n}:$i" }) { i, st ->
            val target = restoreStep ?: now ?: 0
            val dot = JourneysV1.dot(j.steps, i, now)
            JvStepCard(
                st,
                dot,
                label = if (JourneysV1.stepAction(st, isAdmin) == JvStepAction.GET) JourneysV1.askLabel(asks[i]) else null,
                modifier =
                    Modifier
                        .then(if (i == target) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { f ->
                            if (f.isFocused) {
                                focused = i
                                onFocusStep(i)
                            }
                        },
            ) {
                when (JourneysV1.stepAction(st, isAdmin)) {
                    JvStepAction.PLAY -> onPlay(j, i)
                    JvStepAction.GET -> onGet(j, i) // any gettable step, not only the next one
                    JvStepAction.NONE -> Unit // missing: the line under the steps says why
                }
            }
        }
    }
    LaunchedEffect(j.summary.id) {
        withFrameNanos { }
        // Not started yet: Play (it starts the journey) is the first thing; otherwise the steps, as before
        if (restoreStep == null && s.status == "none" && now != null && !confirmingDropAtStart) playFocus.tryRequestFocus() else first.tryRequestFocus()
    }
    // The focused step: its "why" and what OK does
    j.steps.getOrNull(focused)?.let { st ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(max = 700.dp).testTag("jv-step-why")) {
            Text(st.title + (st.year?.let { " ($it)" } ?: "") + " · " + JourneysV1.meta(st), color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            st.why?.let { Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.85f), fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val act = JourneysV1.stepAction(st, isAdmin)
            val line =
                when {
                    act == JvStepAction.GET -> asks[focused]?.let { JourneysV1.askText(it) } ?: "Not here yet. OK to get it."
                    act == JvStepAction.NONE -> JourneysV1.missingReason(st)
                    else -> null
                }
            line?.let {
                Text(it, color = if (act == JvStepAction.GET) JvColors.Peach else JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("jv-step-get"))
            }
        }
    }
}

@Composable
internal fun JvStepCard(
    st: JvStep,
    dot: JvDot,
    modifier: Modifier = Modifier,
    /** Overrides the small label (a gettable step for an admin: "Get it" / "Asking…" / "On its way" / "Blocked"). */
    label: String? = null,
    onClick: () -> Unit,
) {
    var failed by remember(st.poster) { mutableStateOf(false) }
    val poster = st.poster ?: st.iptv?.poster
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = JellytipSurfaceFocus.border(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = JellytipSurfaceFocus.SCALE),
        colors = ClickableSurfaceDefaults.colors(containerColor = JvColors.Card, focusedContainerColor = JvColors.Card, contentColor = JellytipPalette.Vanilla, focusedContentColor = JellytipPalette.Vanilla),
        modifier = modifier.width(112.dp).height(168.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (poster != null && !failed) {
                AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
            }
            // Not playable yet: dimmed
            if (dot == JvDot.GAP || dot == JvDot.SKIPPED) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                when (dot) {
                    JvDot.DONE -> Text("✓", color = JellytipPalette.Vanilla, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 6.dp))
                    JvDot.NOW -> Box(Modifier.size(12.dp).background(JvColors.Peach, CircleShape))
                    else -> Box(Modifier.size(12.dp).border(1.5.dp, JvColors.Dim, CircleShape))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.align(Alignment.BottomStart).padding(7.dp)) {
                Text(st.title, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = JellytipPalette.Vanilla)
                Text(
                    label ?: when (dot) {
                        JvDot.GAP -> if (st.source == JvSource.GETTABLE) "Not here yet" else "Not available"
                        JvDot.SKIPPED -> "Skipped"
                        JvDot.NOW -> "Tonight"
                        else -> st.year?.toString() ?: ""
                    },
                    fontFamily = JellytipFontFamily,
                    fontSize = 12.sp,
                    color = if (dot == JvDot.NOW || label != null) JvColors.Peach else JvColors.Dim,
                )
            }
        }
    }
}
