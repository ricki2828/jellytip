package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/*
 * Game Centre look 2A (Decisions brief b_c84a78e16a54, mock 02a-gamecentre-broadcast): a team-colour match hero (Watch /
 * From kick-off / More, Watch focused) and colour-edge fixture tiles; the same hero tops Home while a live game is on
 * (Ricki 21-Nov: only his teams' / followed leagues' live games, no fallback; spoiler-safe; Left / Right cycles them).
 * Fixtures carry no colours: they come from the match record (`GET v1/sport/match/{id}`, the in-match palette's source),
 * cached per fixture; neutral slabs until then or when the server has none.
 */
object LiveHero {
    private val liveCmp = compareBy<FxItem> { it.rank ?: Double.MAX_VALUE }.thenByDescending { it.significance }.thenBy { it.kickoff }.thenBy { it.fid }

    private fun watchableLive(
        items: List<FxItem>,
        now: Long,
    ) = items.filter { it.status == "in" && SportFixtures.pickChannel(it, now).channel != null }

    /**
     * Home's hero: live games of his starred teams or followed leagues (ranked), nothing else (Ricki 21-Nov: no
     * "top-ranked live game" fallback; with none, Home keeps its normal hero).
     */
    fun homePicks(
        items: List<FxItem>,
        now: Long,
    ): List<FxItem> = watchableLive(items, now).filter { it.favourite || it.followedLeague }.sortedWith(liveCmp)

    /** Game Centre's hero: the Home picks, else the top-ranked live game, else his next game within a day, else the top-ranked one starting within a day. */
    fun gameCentrePicks(
        items: List<FxItem>,
        now: Long,
    ): List<FxItem> {
        homePicks(items, now).takeIf { it.isNotEmpty() }?.let { return it }
        // The sport page keeps a fallback: the top-ranked live game
        watchableLive(items, now).sortedWith(liveCmp).firstOrNull()?.let { return listOf(it) }
        val soon = items.filter { it.status == "pre" && it.kickoff - now in 0..DAY_S }
        val cmp = compareBy<FxItem> { it.rank ?: Double.MAX_VALUE }.thenByDescending { it.significance }.thenBy { it.kickoff }
        val next = soon.filter { it.favourite }.minByOrNull { it.kickoff } ?: soon.sortedWith(cmp).firstOrNull()
        return listOfNotNull(next)
    }

    /** Left / Right on the hero: the previous / next game, wrapping. */
    fun cycle(
        index: Int,
        size: Int,
        dir: Int,
    ): Int = if (size <= 0) 0 else ((index + dir) % size + size) % size

    private const val DAY_S = 24 * 3600L

    // ---- team colours per fixture (match record), fetched at most 3 at a time ----
    private val colours = ConcurrentHashMap<String, Pair<String?, String?>>()
    private val gate = Semaphore(3)

    fun peekColours(f: FxItem): Pair<String?, String?>? = colours[f.companionId]

    private val misses = ConcurrentHashMap<String, Long>()
    private const val MISS_TTL_MS = 10 * 60_000L

    suspend fun colours(f: FxItem): Pair<String?, String?> {
        colours[f.companionId]?.let { return it }
        // No match record (404 / no IPTV / a recent miss): neutral, no request
        if (MatchHidden.hides(f.companionId)) return null to null
        misses[f.companionId]?.let { at -> if (System.currentTimeMillis() - at < MISS_TTL_MS) return null to null }
        val got =
            try {
                gate.withPermit { fetchMatch(f.companionId)?.second?.match?.let { it.home?.color to it.away?.color } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        val v = got ?: (null to null)
        if (got != null) colours[f.companionId] = v else misses[f.companionId] = System.currentTimeMillis()
        return v
    }

    /** The palette for colours (home, away), neutral when both are missing (same rules as the in-match palette). */
    fun palette(c: Pair<String?, String?>?): MatchPalette =
        matchPalette(
            IptvMatch(
                id = "",
                league = null,
                home = MatchTeam("", null, null, null, color = c?.first),
                away = MatchTeam("", null, null, null, color = c?.second),
                startsAt = null,
                venue = null,
                status = null,
                inferred = false,
                score = null,
            ),
        )
}

@Composable
fun rememberFixturePalette(f: FxItem): MatchPalette {
    // The fixture's own colours (guarded) when it has any; else the match record's, fetched as before
    val own = remember(f.homeColor, f.homeAltColor, f.awayColor, f.awayAltColor) { FixtureColours.of(f) }
    // produceState keeps its value across key changes (hero cycling reuses the slot), so the fixture's own colours are
    // never routed through it: they apply directly, the fetched ones only when the fixture has none
    val fetched by produceState(LiveHero.peekColours(f), f.companionId, own == null) { if (own == null) value = LiveHero.colours(f) }
    val c = own ?: fetched
    return remember(c) { LiveHero.palette(c) }
}

/** Gold tag text: LIVE (no clock or period: spoiler-safe), a countdown, or FT. */
internal fun heroTag(
    f: FxItem,
    now: Long,
): String =
    when (f.status) {
        "in" -> "Live"
        "post" -> "Full time"
        else -> countdown(f.kickoff, now)
    }

@Composable
internal fun BigCrest(
    logo: String?,
    label: String,
    bg: Color,
    size: androidx.compose.ui.unit.Dp,
    textSize: androidx.compose.ui.unit.TextUnit,
) {
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.85f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null && !failed) {
            AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(size * 0.66f))
        } else {
            Cond(label.take(3), textSize, contrastOn(bg))
        }
    }
}

/**
 * The 2A match hero: a full-width card washed in the two team colours, big crests, the gold tag, "HOME v AWAY",
 * "LEAGUE · SCORE HIDDEN UNTIL YOU WATCH" and Watch / From kick-off / More (Watch takes focus via [focusRequester]).
 * [onCycle] (with [count] > 1): Left on the first button / Right on the last = the previous / next game.
 */
@Composable
fun LiveMatchHero(
    f: FxItem,
    now: Long,
    onWatch: (() -> Unit)?,
    onFromStart: (() -> Unit)?,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    index: Int = 0,
    count: Int = 1,
    onCycle: ((Int) -> Unit)? = null,
    onFocused: () -> Unit = {},
    motion: Boolean = !com.github.damontecres.wholphin.jellytip.rememberReducedMotion(),
    /** The sport event log's `shown`: the card at least half on screen for 1 s, again for each game cycled to. */
    onShown: (() -> Unit)? = null,
) {
    val pal = rememberFixturePalette(f)
    // The fixture's photo (FixtureArt); a photo that fails to load falls back to the plain colour wash
    var artFailed by remember(f.art) { mutableStateOf(false) }
    val art = f.art?.takeUnless { artFailed }
    val wash =
        remember(pal) {
            Brush.horizontalGradient(0f to pal.home, 0.46f to Broadcast.Base, 0.54f to Broadcast.Base, 1f to pal.away)
        }
    val buttons =
        listOfNotNull(
            onWatch?.let { "▶  Watch" to it },
            onFromStart?.let { "From kick-off" to it },
            onMore?.let { "More" to it },
        ).ifEmpty { listOf("No stream found yet" to {}) }
    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (art == null) wash else androidx.compose.ui.graphics.SolidColor(Broadcast.Base)),
    ) {
        if (art != null) FixtureArtHeroBackdrop(art, f.fid, pal.home, pal.away, motion, onFailed = { artFailed = true })
        // Layout visibility (not composition): a hero scrolled away doesn't count. Keyed on the game only, so cycling to
        // another game starts a fresh 1 s but a poll's new list id doesn't (a fresh node that is already visible would fire
        // again); an empty, unfocusable layer, so the buttons keep their focus.
        if (onShown != null) {
            androidx.compose.runtime.key(f.fid) {
                Box(
                    Modifier.matchParentSize().onVisibilityChanged(minDurationMs = SportEventRules.SHOWN_MIN_MS, minFractionVisible = 0.5f) {
                        if (it) onShown()
                    },
                )
            }
        }
        Box(Modifier.align(Alignment.CenterStart).padding(start = 28.dp)) { BigCrest(f.homeLogo, f.homeShort ?: f.home, pal.home, 104.dp, 34.sp) }
        if (f.away.isNotBlank()) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 28.dp)) { BigCrest(f.awayLogo, f.awayShort ?: f.away, pal.away, 104.dp, 34.sp) }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 160.dp),
        ) {
            Cond(
                heroTag(f, now),
                14.sp,
                Color.White,
                modifier = Modifier.background(if (f.status == "in") Color(0xFFE2264D) else Broadcast.Chip, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
                tracking = 0.06f,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cond(f.home, 44.sp, Broadcast.Text)
                if (f.away.isNotBlank()) {
                    Cond("v", 44.sp, Broadcast.Gold)
                    Cond(f.away, 44.sp, Broadcast.Text)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                f.badge?.let { b ->
                    var badgeFailed by remember(b) { mutableStateOf(false) }
                    if (!badgeFailed) {
                        AsyncImage(
                            model = FixtureArt.sized(b, 96),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            onError = { badgeFailed = true },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Cond(
                    listOfNotNull(f.league, f.stage, "score hidden until you watch".takeIf { f.status != "pre" }).joinToString(" · "),
                    15.sp,
                    // On a photo the grey is too faint (FixtureArtTest); white keeps 4.5:1 over the dark centre
                    if (art != null) Broadcast.Text else Broadcast.Secondary,
                    weight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 6.dp).focusGroup().let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
            ) {
                buttons.forEachIndexed { i, (label, action) ->
                    val first = i == 0
                    val last = i == buttons.lastIndex
                    // Keyed by label: cycling to a game with fewer buttons keeps the focused button's node (and focus)
                    androidx.compose.runtime.key(label) {
                        Button(
                            onClick = action,
                            modifier =
                                Modifier
                                    .onPreviewKeyEvent { e ->
                                        if (onCycle == null || count < 2 || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when {
                                            first && e.key == Key.DirectionLeft -> {
                                                onCycle(LiveHero.cycle(index, count, -1))
                                                true
                                            }
                                            last && e.key == Key.DirectionRight -> {
                                                onCycle(LiveHero.cycle(index, count, 1))
                                                true
                                            }
                                            else -> false
                                        }
                                    }.onFocusChangedCompat(onFocused),
                        ) { Text(label, fontFamily = JellytipFontFamily, fontSize = 16.sp) }
                    }
                }
            }
            if (count > 1) {
                Text("‹  ${index + 1} of $count of your games live  ›", color = if (art != null) Broadcast.Text else Broadcast.Secondary, fontFamily = JellytipFontFamily, fontSize = 12.sp)
            }
        }
    }
}

private fun Modifier.onFocusChangedCompat(onFocused: () -> Unit): Modifier = this.onFocusChanged { if (it.isFocused) onFocused() }

/** The 2A fixture tile: crests and names in condensed caps on two lines, a team-colour edge each side, the status line. */
@Composable
fun ColourEdgeFixtureTile(
    f: FxItem,
    now: Long,
    reminder: Boolean,
    revealed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val pal = rememberFixturePalette(f)
    var artFailed by remember(f.art) { mutableStateOf(false) }
    val art = f.art?.takeUnless { artFailed }
    // On a photo the grey status text is too faint (FixtureArtTest)
    val quiet = if (art != null) Broadcast.Text else Broadcast.Secondary
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF141414),
                focusedContainerColor = Color(0xFF1E1E1E),
                contentColor = Broadcast.Text,
                focusedContentColor = Color.White,
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color(0xFFF2F2F0)), shape = RoundedCornerShape(10.dp)),
            ),
        modifier = modifier.size(320.dp, 140.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (art != null) FixtureArtTileBackdrop(art, pal.home, pal.away.takeIf { f.away.isNotBlank() }, onFailed = { artFailed = true })
            Box(Modifier.align(Alignment.CenterStart).width(5.dp).fillMaxHeight().background(pal.home))
            if (f.away.isNotBlank()) Box(Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxHeight().background(pal.away))
            Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 16.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TeamLine(f.homeLogo, f.homeShort ?: f.home, f.home, pal.home, f.favourite)
                if (f.away.isNotBlank()) TeamLine(f.awayLogo, f.awayShort ?: f.away, f.away, pal.away, false)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val score = f.score?.takeIf { revealed }
                    when (f.status) {
                        "in" ->
                            Cond(
                                "Live" + (score?.let { " · ${it.home}–${it.away}" } ?: ""),
                                13.sp,
                                Color.White,
                                modifier = Modifier.background(Color(0xFFE2264D), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
                            )
                        "post" -> Cond("FT · " + (score?.let { "${it.home}–${it.away}" } ?: "score hidden"), 13.sp, quiet)
                        else -> Cond(SportFixtures.startsText(f.kickoff, now), 13.sp, quiet)
                    }
                    if (reminder) Text("🔔", fontSize = 11.sp)
                    if (f.recordingId != null) Text("●", color = Color(0xFFE2264D), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TeamLine(
    logo: String?,
    short: String,
    name: String,
    colour: Color,
    star: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigCrest(logo, short, colour, 34.dp, 11.sp)
        Text(
            name.uppercase(),
            color = Broadcast.Text,
            fontFamily = BarlowCondensed,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (star) Text("★", color = Broadcast.Gold, fontSize = 13.sp)
    }
}

/**
 * Home's live match hero (Ricki 21-Nov, option 1): his teams' live games (else the top-ranked live one) from the fixtures,
 * re-checked every minute; muted leagues / sports left out. Empty = no live game (Home is as before); null = not known yet.
 */
@Composable
fun rememberHomeLiveHero(viewModel: JellytipGameCentreViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()): List<FxItem>? {
    val picks by produceState<List<FxItem>?>(null) {
        while (true) {
            val r = viewModel.service.fixtures(maxAgeMs = 60_000)
            if (r?.first == 404 || r?.first == 403) {
                // No fixtures route / no Live TV for this person: no hero, no more polling this visit
                value = emptyList()
                return@produceState
            }
            val muted = com.github.damontecres.wholphin.jellytip.me.JellytipMe.prefs.value?.sportMuted
            val items = r?.second?.items?.filterNot { muted?.mutes(it.league, it.sport) == true }
            // Unreachable / no IPTV: no hero (keep the last answer while it is only a blip)
            value = if (items != null) LiveHero.homePicks(items, nowSec()) else (value ?: emptyList())
            kotlinx.coroutines.delay(60_000)
        }
    }
    return picks
}

/**
 * The Home hero carousel's match slide buttons (Watch = the best channel with the match id, From kick-off, More = Game
 * Centre); labels and actions for [f] at [now]. The slide itself is drawn by HeroMatchBackdrop / HeroMatchHeader.
 */
class HomeMatchActions internal constructor(
    private val vm: JellytipGameCentreViewModel,
) {
    fun buttons(
        f: FxItem,
        now: Long,
    ): List<Pair<String, () -> Unit>> {
        val best = SportFixtures.pickChannel(f, now).channel
        val fromStart = best?.takeIf { it.startOver && it.start != null && it.stop != null && !it.dormant }
        val out = ArrayList<Pair<String, () -> Unit>>()
        best?.let { ch ->
            out.add("\u25B6  Watch" to { launchFixture(vm.playback, vm.navigationManager, f, ch, false, "home hero", surface = SportEventRules.HOME_HERO) })
        }
        fromStart?.let { ch ->
            out.add("From kick-off" to { launchFixture(vm.playback, vm.navigationManager, f, ch, true, "home hero", surface = SportEventRules.HOME_HERO) })
        }
        val more: () -> Unit = {
            com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl.stop()
            vm.navigationManager.navigateTo(com.github.damontecres.wholphin.ui.nav.Destination.JellytipUpcomingSport, "home hero More")
        }
        out.add("More" to more)
        return out
    }
}

@Composable
fun rememberHomeMatchActions(viewModel: JellytipGameCentreViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()): HomeMatchActions =
    remember(viewModel) { HomeMatchActions(viewModel) }
