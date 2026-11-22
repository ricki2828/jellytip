package com.github.damontecres.wholphin.jellytip.iptv

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * Game Centre (web parity 21-Nov; web screens/iptv/GameCentre.tsx over GET v1/iptv/sport/fixtures): fixture tiles with
 * crests in Live now / Your teams (starred first) / Upcoming / Finished rows, "+N more <league> today · Show all", and an
 * OK menu with Watch live (carries the match id, so the companion works), catch-up from the start, the other channels,
 * Remind me on/off and a deliberate "Reveal score". A server without the route (404) gets the old upcoming list.
 */

private const val POLL_MS = 30_000L

@HiltViewModel
class JellytipGameCentreViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: NavigationManager,
        /** "Remind me for every <team> game" (t5j b1eca15d, gap row 4). */
        val teamReminders: JellytipTeamReminders,
    ) : ViewModel() {
        init {
            if (teamReminders.items.value == null) teamReminders.refresh()
        }

        data class State(
            val base: FxList? = null,
            /** The uncapped answer, fetched once on the first "Show all". */
            val full: List<FxItem> = emptyList(),
            val expanded: Set<String> = emptySet(),
            /** 404 = older server: show the legacy upcoming page. */
            val legacy: Boolean = false,
            val failed: Boolean = false,
            /** Reminder state changed locally before the next poll (fid -> on). */
            val reminderOverride: Map<String, Boolean> = emptyMap(),
            val message: String? = null,
        ) {
            val items: List<FxItem> get() = base?.let { SportFixtures.merge(it.items, full, expanded) } ?: emptyList()
        }

        /** The tile last opened (or next to "Show all"): focus returns to it when the page comes back. */
        var lastFid: String? = null

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        /** The person's recordings (for "Your recording, from the start" and to hide Record once one exists). */
        var recordings by androidx.compose.runtime.mutableStateOf<List<IptvRecording>?>(service.peekRecordings())
            private set

        private var polls = 0

        suspend fun refresh() {
            val r = service.fixtures()
            // After the fixtures (first paint isn't held up), and again every 4th poll so new recordings show up
            if (recordings == null || polls++ % 4 == 0) recordings = service.recordings()?.second ?: service.peekRecordings() ?: recordings ?: emptyList()
            val s = _state.value
            when {
                r == null -> if (s.base == null) _state.value = s.copy(failed = true)
                r.first == 404 -> _state.value = s.copy(legacy = true)
                r.second != null -> {
                    _state.value = s.copy(base = r.second, failed = false, reminderOverride = emptyMap())
                    if (s.expanded.isNotEmpty()) loadFull()
                }
                s.base == null -> _state.value = s.copy(failed = true)
            }
        }

        private suspend fun loadFull() {
            val r = service.fixtures(capOff = true)?.second ?: return
            _state.value = _state.value.copy(full = r.items)
        }

        fun showAll(key: String) {
            JellytipTrace.log("iptv", "game centre show all $key")
            viewModelScope.launch {
                _state.value = _state.value.copy(expanded = _state.value.expanded + key)
                loadFull()
            }
        }

        fun reminderOn(f: FxItem): Boolean = _state.value.reminderOverride[f.fid] ?: (f.reminderId != null)

        fun toggleReminder(f: FxItem) {
            val on = reminderOn(f)
            viewModelScope.launch {
                val ok =
                    if (on) {
                        service.removeFixtureReminders(f.reminderId, f.fid, f.matchId)
                    } else {
                        service.addFixtureReminder(f.fid, f.matchId) != null
                    }
                if (ok && !on) JellytipSportEvents.log(SportEventRules.REMIND, f, SportEventRules.GAMECENTRE)
                _state.value =
                    _state.value.copy(
                        reminderOverride = if (ok) _state.value.reminderOverride + (f.fid to !on) else _state.value.reminderOverride,
                        message = if (ok) (if (on) "Reminder off" else "Reminder set for ${f.title}") else "Couldn't change the reminder",
                    )
            }
        }

        /** Record [f] on [ch] (web f4e82c60), then refresh fixtures and recordings. */
        fun record(
            f: FxItem,
            ch: FxChannel,
        ) {
            val start = ch.start ?: return
            viewModelScope.launch {
                val r = service.record(ch.id, start)
                if (r.ok) JellytipSportEvents.log(SportEventRules.RECORD, f, SportEventRules.GAMECENTRE)
                _state.value = _state.value.copy(message = if (r.ok) "Recording ${f.title}" else (r.message ?: "Couldn't set the recording"))
                recordings = service.recordings()?.second ?: recordings
                refresh()
            }
        }

        fun dismissMessage() {
            _state.value = _state.value.copy(message = null)
        }

        /**
         * Full screen on [ch]: the fixture's other channels are the switcher's chips (and the failover order); a live
         * launch carries the match id so the companion and scorebug work even when the guide has no fixture for the slot.
         */
        fun watch(
            f: FxItem,
            ch: FxChannel,
            fromStart: Boolean,
        ) = launchFixture(playback, navigationManager, f, ch, fromStart, "game centre", surface = SportEventRules.GAMECENTRE)
    }

@Composable
fun JellytipGameCentrePage(
    modifier: Modifier = Modifier,
    viewModel: JellytipGameCentreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SportEventsFlushOnLeave()
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            if (viewModel.state.value.legacy) return@LaunchedEffect
            delay(POLL_MS)
        }
    }
    if (state.legacy) {
        JellytipUpcomingSportPage(modifier = modifier)
        return
    }
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    val muted = rememberSportMuted()
    val items = remember(state.base, state.full, state.expanded, muted) { state.items.filterNot { muted.mutes(it.league, it.sport) } }
    val groups = remember(items) { SportFixtures.group(items) }
    val more = remember(state.base, state.expanded, now) { SportFixtures.todayMore(state.base?.more.orEmpty(), now, state.expanded) }
    var menu by remember { mutableStateOf<FxItem?>(null) }
    val revealed = remember { mutableStateOf(setOf<String>()) }
    val first = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    // Focus on arrival and after "Show all": the tile last opened (or the one before Show all), else the first tile.
    // Polls don't move focus.
    var focusTick by remember { mutableStateOf(0) }
    // 2A match hero (Decisions brief b_c84a78e16a54): the game that matters most, Watch focused on arrival
    val heroPicks = remember(items, now) { LiveHero.gameCentrePicks(items, now) }
    var heroIndex by remember { mutableStateOf(0) }
    val hero = heroPicks.getOrNull(heroIndex.coerceIn(0, (heroPicks.size - 1).coerceAtLeast(0)))
    val heroFocus = remember { FocusRequester() }
    LaunchedEffect(groups.isNotEmpty(), focusTick, hero != null) {
        if (groups.isEmpty() && hero == null) return@LaunchedEffect
        withFrameNanos { }
        val want = viewModel.lastFid
        when {
            want != null && groups.any { g -> g.items.any { it.fid == want } } -> restore.tryRequestFocus()
            hero != null && heroFocus.tryRequestFocus() -> {}
            else -> first.tryRequestFocus()
        }
    }
    // OK on a live tile starts it on the best channel (2A); anything else opens the menu
    val open: (FxItem) -> Unit = { f ->
        viewModel.lastFid = f.fid
        val best = SportFixtures.pickChannel(f, now).channel
        if (f.status == "in" && best != null) viewModel.watch(f, best, false) else menu = f
    }
    // (audit 22-Nov) rows start 8 dp in (ItemRowTitle + row padding), so the page starts 8 dp early and its own texts take that inset:
    // title, section headers and first tile share one left line; rows run to the page edge, text stops at the safe area
    val heroBest = hero?.let { SportFixtures.pickChannel(it, now).channel }
    val heroFromStart = hero?.let { h -> heroBest?.takeIf { h.status != "pre" && it.startOver && it.start != null && it.stop != null && !it.dormant } }
    JellytipGameCentreContent(
        modifier = modifier,
        degraded = state.base?.degraded == true,
        loaded = state.base != null,
        failed = state.failed,
        groups = groups,
        more = more,
        hero = hero,
        heroIndex = heroIndex,
        heroCount = heroPicks.size,
        now = now,
        reminderOverride = state.reminderOverride,
        revealed = revealed.value,
        lastFid = viewModel.lastFid,
        heroFocus = heroFocus,
        restore = restore,
        first = first,
        onOpen = { f, section, pos ->
            JellytipSportEvents.log(SportEventRules.OPENED, f, SportEventRules.GAMECENTRE, section, pos)
            open(f)
        },
        onLongClick = { f ->
            viewModel.lastFid = f.fid
            menu = f
        },
        onMoreInRow = { m, lastOfGroup ->
            // Show all removes this tile: keep focus in the row on the game before it
            viewModel.lastFid = lastOfGroup?.fid
            viewModel.showAll(m.key)
            focusTick++
        },
        onMoreAlone = { m -> viewModel.showAll(m.key) },
        onHeroWatch =
            hero?.let { h ->
                heroBest?.takeIf { h.status == "in" }?.let { ch ->
                    {
                        viewModel.lastFid = null
                        viewModel.watch(h, ch, false)
                    }
                }
            },
        onHeroFromStart =
            hero?.let { h ->
                heroFromStart?.let { ch ->
                    {
                        viewModel.lastFid = null
                        viewModel.watch(h, ch, true)
                    }
                }
            },
        onHeroMore = { menu = hero },
        onHeroCycle = { heroIndex = it },
        onHeroShown = { hero?.let { h -> JellytipSportEvents.log(SportEventRules.SHOWN, h, SportEventRules.GAMECENTRE, "hero", heroIndex + 1) } },
    )
    val teamItems by viewModel.teamReminders.items.collectAsState()
    val teamsAvailable by viewModel.teamReminders.available.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    menu?.let { f ->
        val rec = SportFixtures.yourRecording(f, viewModel.recordings)
        FixtureMenu(
            f,
            now,
            recordOn = SportFixtures.recordOn(f, rec, now),
            yourRecording = rec,
            onRecord = { ch ->
                menu = null
                viewModel.record(f, ch)
            },
            onRecording = { r ->
                menu = null
                com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl.stop()
                viewModel.navigationManager.navigateTo(Destination.JellytipIptvPlay(title = f.title, recordingId = r.id), "game centre recording")
            },
            reminder = state.reminderOverride[f.fid] ?: (f.reminderId != null),
            teamItems = teamItems,
            teamsAvailable = teamsAvailable,
            onTeam = { side, turnOn ->
                menu = null
                viewModel.teamReminders.toggle(side, turnOn = turnOn) { err -> err?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
            },
            revealed = f.fid in revealed.value,
            onDismiss = { menu = null },
            onWatch = { ch, fromStart ->
                menu = null
                viewModel.watch(f, ch, fromStart)
            },
            onReminder = {
                menu = null
                viewModel.toggleReminder(f)
            },
            onReveal = {
                menu = null
                revealed.value = revealed.value + f.fid
            },
        )
    }
    state.message?.let { msg ->
        LaunchedEffect(msg) {
            delay(2_500)
            viewModel.dismissMessage()
        }
        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                msg,
                color = JellytipPalette.Vanilla,
                fontFamily = JellytipFontFamily,
                fontSize = 14.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The page body (extract-only split of [JellytipGameCentrePage], audit3 shots): everything it draws from plain values, no view
 * model. [listState] is the LazyColumn's, so a test can scroll it.
 */
@Composable
internal fun JellytipGameCentreContent(
    modifier: Modifier,
    degraded: Boolean,
    loaded: Boolean,
    failed: Boolean,
    groups: List<FxGroup>,
    more: List<SportFixtures.MoreRow>,
    hero: FxItem?,
    heroIndex: Int,
    heroCount: Int,
    now: Long,
    reminderOverride: Map<String, Boolean>,
    revealed: Set<String>,
    lastFid: String?,
    heroFocus: FocusRequester,
    restore: FocusRequester,
    first: FocusRequester,
    onOpen: (FxItem, String, Int) -> Unit,
    onLongClick: (FxItem) -> Unit,
    onMoreInRow: (SportFixtures.MoreRow, FxItem?) -> Unit,
    onMoreAlone: (SportFixtures.MoreRow) -> Unit,
    onHeroWatch: (() -> Unit)?,
    onHeroFromStart: (() -> Unit)?,
    onHeroMore: () -> Unit,
    onHeroCycle: (Int) -> Unit,
    onHeroShown: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    // (audit 22-Nov) rows start 8 dp in (ItemRowTitle + row padding), so the page starts 8 dp early and its own texts take that inset:
    // title, section headers and first tile share one left line; rows run to the page edge, text stops at the safe area
    val inset = Modifier.padding(start = com.github.damontecres.wholphin.jellytip.JellytipLayout.RowInset, end = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageEnd)
    Column(modifier.fillMaxSize().padding(start = com.github.damontecres.wholphin.jellytip.JellytipLayout.RowPageStart, top = com.github.damontecres.wholphin.jellytip.JellytipLayout.PageTop), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(inset, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Game Centre", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
            if (degraded) {
                Text("Some games may be missing right now", color = JellytipPalette.Vanilla.copy(alpha = com.github.damontecres.wholphin.jellytip.JellytipLayout.TEXT_ALPHA), fontFamily = JellytipFontFamily, fontSize = 12.sp)
            }
        }
        when {
            !loaded && failed -> Text("Couldn't load games. Trying again…", color = JellytipPalette.Vanilla.copy(alpha = com.github.damontecres.wholphin.jellytip.JellytipLayout.TEXT_ALPHA), fontFamily = JellytipFontFamily, modifier = inset)
            !loaded -> com.github.damontecres.wholphin.jellytip.JellytipSkeleton(tileW = 320.dp, tileH = 140.dp, rows = 2, perRow = 2, modifier = inset)
            groups.isEmpty() && more.isEmpty() -> Text("No games in the next few days.", color = JellytipPalette.Vanilla.copy(alpha = com.github.damontecres.wholphin.jellytip.JellytipLayout.TEXT_ALPHA), fontFamily = JellytipFontFamily, modifier = inset)
            else ->
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                    hero?.let { h ->
                        item(key = "hero") {
                            LiveMatchHero(
                                f = h,
                                now = now,
                                onWatch = onHeroWatch,
                                onFromStart = onHeroFromStart,
                                onMore = onHeroMore,
                                focusRequester = heroFocus,
                                index = heroIndex,
                                count = heroCount,
                                onCycle = onHeroCycle,
                                modifier = inset.padding(vertical = 8.dp),  // (the hero is text and buttons: it stops at the safe area; tile rows run on)
                                onShown = onHeroShown,
                            )
                        }
                    }
                    groups.forEachIndexed { gi, g ->
                        item(key = g.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ItemRowTitle("${g.label} (${g.items.size})")
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                    itemsIndexed(g.items, key = { _, it -> it.fid }) { i, f ->
                                        ColourEdgeFixtureTile(
                                            f,
                                            now,
                                            reminder = reminderOverride[f.fid] ?: (f.reminderId != null),
                                            revealed = f.fid in revealed,
                                            onClick = { onOpen(f, g.id, i + 1) },
                                            onLongClick = { onLongClick(f) },
                                            modifier =
                                                when {
                                                    f.fid == lastFid -> Modifier.focusRequester(restore)
                                                    gi == 0 && f === g.items.first() -> Modifier.focusRequester(first)
                                                    else -> Modifier
                                                }.sportShown(f, SportEventRules.GAMECENTRE, g.id, i + 1),
                                        )
                                    }
                                    // "+N more NBA games today · Show all" ends the Upcoming row (web d47d3b77)
                                    if (g.id == "big" || (g.id == "yours" && groups.none { it.id == "big" })) {
                                        items(more, key = { "more:${it.key}" }) { m ->
                                            MoreTile(m) { onMoreInRow(m, g.items.lastOrNull()) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (more.isNotEmpty() && groups.none { it.id == "big" || it.id == "yours" }) {
                        item(key = "more") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                                items(more, key = { "more:${it.key}" }) { m -> MoreTile(m, if (groups.isEmpty()) Modifier.focusRequester(first) else Modifier) { onMoreAlone(m) } }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun tileBorder() =
    ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = RoundedCornerShape(8.dp)),
        focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp)),
    )

@Composable
private fun tileColors() =
    ClickableSurfaceDefaults.colors(
        containerColor = Color(0xFF141414),
        focusedContainerColor = Color(0xFF242424),
        contentColor = JellytipPalette.Vanilla,
        focusedContentColor = Color.White,
    )

@Composable
internal fun MoreTile(
    m: SportFixtures.MoreRow,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = tileColors(),
        border = tileBorder(),
        modifier = modifier.size(220.dp, 140.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
            Text("+${m.hidden} more ${m.league} today", fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2)
            Text("Show all", fontFamily = JellytipFontFamily, fontSize = 12.sp, color = JellytipPalette.Vanilla.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun FixtureMenu(
    f: FxItem,
    now: Long,
    recordOn: FxChannel?,
    yourRecording: IptvRecording?,
    onRecord: (FxChannel) -> Unit,
    onRecording: (IptvRecording) -> Unit,
    reminder: Boolean,
    /** Read so the menu redraws when team reminders change. */
    teamItems: List<TeamReminder>?,
    teamsAvailable: Boolean?,
    onTeam: (TeamSide, Boolean) -> Unit,
    revealed: Boolean,
    onDismiss: () -> Unit,
    onWatch: (FxChannel, Boolean) -> Unit,
    onReminder: () -> Unit,
    onReveal: () -> Unit,
) {
    val pick = remember(f, now) { SportFixtures.pickChannel(f, now) }
    val best = pick.channel
    val items =
        buildList {
            // A finished game you recorded: your recording first (no more "No replay available")
            yourRecording?.let { r -> add(DialogItem("Your recording, from the start", dismissOnClick = true) { onRecording(r) }) }
            if (f.status == "in" && best != null) {
                add(DialogItem((if (best.solid) "Watch live on " else "May be live on ") + best.name, dismissOnClick = true) { onWatch(best, false) })
            }
            if (f.status != "pre" && best != null && best.startOver && best.start != null && best.stop != null && !best.dormant) {
                add(DialogItem("Catch-up on ${best.name}, from the start", dismissOnClick = true) { onWatch(best, true) })
            }
            if (f.status == "in") {
                pick.order.drop(1).forEach { c ->
                    add(DialogItem((if (c.solid) "Live on " else "May be on ") + c.name, dismissOnClick = true) { onWatch(c, false) })
                }
            }
            recordOn?.let { ch -> add(DialogItem("Record on ${ch.name}", dismissOnClick = true) { onRecord(ch) }) }
            if (f.status != "post") add(DialogItem(if (reminder) "Reminder on — turn off" else "Remind me", dismissOnClick = true) { onReminder() })
            // Every game of a team (hidden when the server has no team reminders)
            // Only once the list is known, so the label and the action agree (turnOn = the opposite of what it showed)
            val known = teamItems
            if (teamsAvailable == true && known != null) {
                val sides =
                    TeamReminderLogic.offerSides(
                        listOf(
                            TeamSide(f.homeId, f.home, f.homeShort, f.sport, f.league),
                            TeamSide(f.awayId, f.away, f.awayShort, f.sport, f.league),
                        ),
                    )
                sides.forEach { side ->
                    val name = TeamReminderLogic.shortTeam(side)
                    val on = known.any { it.teamId == side.teamId }
                    add(DialogItem(if (on) TeamReminderLogic.remindingText(name) else TeamReminderLogic.remindEveryText(name), dismissOnClick = true) { onTeam(side, !on) })
                }
            }
            if (f.score != null && !revealed) add(DialogItem("Reveal score", dismissOnClick = true) { onReveal() })
            if (isEmpty()) add(DialogItem(if (f.status == "post") "No replay available" else "No stream found yet", dismissOnClick = true) {})
        }
    val title = f.title + (pick.skipped?.let { "  ·  skipped $it (failed earlier)" } ?: "")
    com.github.damontecres.wholphin.jellytip.JellytipDialog(showDialog = true, title = title, dialogItems = items, onDismissRequest = onDismiss, dismissOnClick = false)
}

/**
 * Full screen on [ch] for fixture [f] (Game Centre, the companion's "closer game"): the fixture's other channels are the
 * switcher's chips and failover order; a live launch carries the match id so the companion works without a guide fixture.
 */
fun launchFixture(
    playback: JellytipIptvPlayback,
    navigationManager: NavigationManager,
    f: FxItem,
    ch: FxChannel,
    fromStart: Boolean,
    from: String,
    /** From the player itself: swap the page instead of stacking a second player. */
    replaceTop: Boolean = false,
    /** Carry the match id even before kick-off (kick-off takeover: the companion is ready when the game starts). */
    forceMatch: Boolean = false,
    /** The sport event log's surface for this launch (a `watch` event); null = not logged. */
    surface: String? = null,
) {
    com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl.stop()
    val matchId = if (fromStart) null else if (forceMatch) f.companionId else SportFixtures.liveMatchId(f, ch)
    val dest = Destination.JellytipLiveTvPlayer(ch.id, matchId)
    // The page first: a blocked navigation must not leave a stream playing with no screen (sound, no picture)
    if (replaceTop) {
        navigationManager.replaceTop(dest)
    } else if (!navigationManager.navigateToChecked(dest, "$from ${f.title}")) {
        return
    }
    surface?.let { JellytipSportEvents.log(SportEventRules.WATCH, f, it) }
    val order = SportFixtures.pickChannel(f, nowSec()).order
    val main = ch.toIptvChannel()
    playback.lineup = listOf(main)
    playback.alternates = mapOf(main.id to (order.filter { it.id != ch.id }.map { it.toIptvChannel() }))
    val start = ch.start
    val stop = ch.stop
    val target =
        if (fromStart && start != null && stop != null && stop > start) {
            IptvTarget(ch.id, f.title, start, stop)
        } else {
            IptvTarget(ch.id, ch.name)
        }
    playback.play(target, "$from ${if (fromStart) "from start" else "live"} ${f.fid}")
    JellytipTrace.log("iptv", "$from watch ${f.fid} on ${ch.id} fromStart=$fromStart match=$matchId")
}
