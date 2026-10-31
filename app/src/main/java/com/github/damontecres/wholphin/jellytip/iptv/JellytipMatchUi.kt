package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay

/** `GET /v1/sport/match/{id}` is missing on this server (404): the action is hidden until it is tried again 10 min later. */
object MatchInfoRoute {
    @Volatile
    private var missingUntil = 0L

    /** Set on a 404; the route is tried again 10 min later (the server endpoints land after an install). */
    var missing: Boolean
        get() = System.currentTimeMillis() < missingUntil
        set(value) {
            missingUntil = if (value) System.currentTimeMillis() + RETRY_AFTER_MS else 0L
        }

    private const val RETRY_AFTER_MS = 10 * 60_000L
}

/**
 * Match routes' status codes (plan live-sport, "Pinned 31-Oct (match routes' status codes)"): `200 {unknown: true}`
 * hides that one match (modal, live panel, ⓘ); 403 = no IPTV provider, so match features hide for this user (until
 * the app restarts); 404 stays "route missing" ([MatchInfoRoute] / [LivePanelRoute], tried again after 10 min).
 * Plain sets, not snapshot state: read in composition like [MatchInfoRoute.missing].
 */
object MatchHidden {
    private val unknown = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var noIptv = false
        private set

    fun hides(id: String?): Boolean = noIptv || (id != null && id in unknown)

    /** Reads a match route answer: 403 = no IPTV; 200 with `unknown: true` = this match only. True = hide it. */
    fun note(
        id: String,
        code: Int,
        body: String?,
        matchRoute: Boolean = false,
    ): Boolean {
        if (code == 403) {
            noIptv = true
            JellytipTrace.log("iptv", "match routes 403: no IPTV, hidden")
            return true
        }
        if (code == 200 && body != null && runCatching { org.json.JSONObject(body).optBoolean("unknown", false) }.getOrDefault(false)) {
            // A whip-around id the match route doesn't know keeps its live panel (RedZone has no single fixture)
            if (!(matchRoute && id.startsWith("multi:"))) unknown.add(id)
            JellytipTrace.log("iptv", "match $id unknown: hidden")
            return true
        }
        return false
    }
}

private sealed interface MatchLoad {
    data object Loading : MatchLoad

    data class Loaded(
        val detail: MatchDetail,
    ) : MatchLoad

    data object Failed : MatchLoad
}

private suspend fun fetchMatch(
    id: String,
    withScore: Boolean = false,
): Pair<Int, MatchDetail?>? {
    val api = JellytipApi.shared ?: return null
    val base = runCatching { api.companionBase() }.getOrNull() ?: IptvVodParse.imageBase
    val q = if (withScore) "?score=1" else ""
    val (code, body) = api.userCall("v1/sport/match/${java.net.URLEncoder.encode(id, "UTF-8")}$q", timeoutMs = 8_000) ?: return null
    if (MatchHidden.note(id, code, body, matchRoute = true) || code != 200) return code to null
    return code to runCatching { MatchParse.detail(body) { IptvVodParse.image(base, it) } }.getOrNull()
}

/** "ⓘ Match info" for a tile menu; none when the item has no fixture or the route is missing. */
fun matchInfoItem(
    match: IptvMatch?,
    /** False when the menu itself must stay composed while the modal opens (it holds the modal's state). */
    dismissOnClick: Boolean = true,
    onOpen: (IptvMatch) -> Unit,
): DialogItem? {
    val m = match ?: return null
    if (MatchInfoRoute.missing || MatchHidden.hides(m.id)) return null
    return DialogItem("ⓘ  Match info", dismissOnClick = dismissOnClick) { onOpen(m) }
}

/**
 * The match modal (plan "Match info"): header (crests, names, records, start / LIVE / FT, venue, score behind "Show
 * score" when the server sent one), actions (Watch, Remind me, Record), Where to watch (one Watch per channel, quality
 * pills), Odds, Line-ups (two columns), Form and head-to-head. Every section shows only what the server has; a pending
 * answer is asked again (3 tries, 3 s apart).
 */
@Composable
fun JellytipMatchModal(
    initial: IptvMatch,
    onWatch: (IptvChannel) -> Unit,
    onDismiss: () -> Unit,
    onRemind: (() -> Unit)? = null,
    onRecord: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val load by produceState<MatchLoad>(MatchLoad.Loading, initial.id) {
        repeat(MAX_TRIES) { attempt ->
            val got = fetchMatch(initial.id)
            when {
                got == null -> {
                    value = MatchLoad.Failed
                    return@produceState
                }
                got.first == 404 -> {
                    MatchInfoRoute.missing = true
                    value = MatchLoad.Failed
                    return@produceState
                }
                got.second == null -> {
                    value = MatchLoad.Failed
                    return@produceState
                }
                else -> {
                    value = MatchLoad.Loaded(got.second!!)
                    if (!got.second!!.pending) return@produceState
                }
            }
            if (attempt < MAX_TRIES - 1) delay(PENDING_RETRY_MS)
        }
    }
    val detail = (load as? MatchLoad.Loaded)?.detail
    val m = detail?.match ?: initial
    var showScore by remember(initial.id) { mutableStateOf(false) }
    // "Show score" asks for it (GET …?score=1, plan "Match info"); a score the server already sent (spoilers on) shows at once
    var revealed by remember(initial.id) { mutableStateOf<MatchScore?>(null) }
    var revealing by remember(initial.id) { mutableStateOf(false) }
    LaunchedEffect(revealing) {
        if (!revealing) return@LaunchedEffect
        val got = fetchMatch(initial.id, withScore = true)?.second?.match?.score
        revealed = got
        showScore = true
        revealing = false
        if (got == null) android.widget.Toast.makeText(context, "No score yet.", android.widget.Toast.LENGTH_SHORT).show()
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        first.tryRequestFocus()
    }
    LaunchedEffect(load) {
        if (load is MatchLoad.Failed && MatchInfoRoute.missing) {
            android.widget.Toast.makeText(context, "Match info isn't available on this server yet.", android.widget.Toast.LENGTH_SHORT).show()
            onDismiss()
        } else if (load is MatchLoad.Failed && MatchHidden.hides(initial.id)) {
            android.widget.Toast.makeText(context, "No match info for this one.", android.widget.Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    // Entry: the modal scales and fades in (220 ms), the crests slide in from either side (graphicsLayer only)
    val entry = remember(initial.id) { androidx.compose.animation.core.Animatable(0f) }
    val crestIn = remember(initial.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(initial.id) { entry.animateTo(1f, androidx.compose.animation.core.tween(220)) }
    LaunchedEffect(initial.id) {
        delay(60)
        crestIn.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val pal = remember(m.home?.color, m.away?.color, m.away?.altColor) { matchPalette(m) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Broadcast (plan "In-match visual direction"): gold top rule, the slab header, a sub bar, three columns, actions
        Column(
            modifier =
                Modifier
                    .width(720.dp)
                    .height(450.dp)
                    .graphicsLayer {
                        val p = entry.value
                        scaleX = 0.94f + 0.06f * p
                        scaleY = 0.94f + 0.06f * p
                        alpha = p
                    }.background(pal.wash),
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Broadcast.Gold))
            val sc = (revealed ?: m.score)?.takeIf { showScore }
            if (m.isEvent || (m.home == null && m.away == null)) EventHeader(m, pal) else BroadcastHeader(m, sc, pal, crestIn = { crestIn.value })
            SubBar(m, detail)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Broadcast.Card)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    val d = detail
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1.25f)) {
                        when (load) {
                            MatchLoad.Loading -> Muted("Loading match details…")
                            MatchLoad.Failed -> Muted("Match details aren't available right now.")
                            is MatchLoad.Loaded -> {}
                        }
                        if (d != null) {
                            if (d.channels.isNotEmpty() || d.broadcasts.isNotEmpty()) {
                                Column {
                                    SectionHead("Where to watch")
                                    d.channels.forEach { ch -> ChannelRow(ch) { onWatch(ch) } }
                                    if (d.broadcasts.isNotEmpty()) Muted("On air: ${d.broadcasts.joinToString(", ")}")
                                }
                            }
                            if (d.winner.isNotEmpty()) {
                                Section("To win" + (d.winnerSource?.let { " · ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: "")) { WinnerList(d.winner, pal) }
                            }
                            d.odds?.let { o ->
                                Section("Odds" + listOfNotNull(o.provider, o.updatedAt?.let { "updated ${hhmm(it)}" }).joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }) {
                                    OddsBar(o, m, pal)
                                    listOfNotNull(
                                        o.home?.spread?.let { "${m.home?.short ?: m.home?.name ?: "Home"} $it" },
                                        o.draw?.let { "Draw $it" },
                                        o.overUnder?.let { "O/U $it" },
                                    ).takeIf { it.isNotEmpty() }?.let { Muted(it.joinToString("  ·  ")) }
                                }
                            }
                            if (d.homeForm.isNotEmpty() || d.awayForm.isNotEmpty() || d.h2h.isNotEmpty()) {
                                Section("Form · last 5") {
                                    if (d.homeForm.isNotEmpty()) FormChips(m.home?.short ?: m.home?.name ?: "Home", d.homeForm)
                                    if (d.awayForm.isNotEmpty()) FormChips(m.away?.short ?: m.away?.name ?: "Away", d.awayForm)
                                    d.h2h.take(5).forEach { Muted(it) }
                                }
                            }
                        }
                    }
                    if (d != null && (d.homeLineup.isNotEmpty() || d.awayLineup.isNotEmpty())) {
                        LineupColumn(m.home?.name, d.homeLineup, pal.home, Modifier.weight(1f))
                        LineupColumn(m.away?.name, d.awayLineup, pal.away, Modifier.weight(1f))
                    } else if (d != null && d.lineupsAnnounced == false) {
                        Column(Modifier.weight(2f)) {
                            SectionHead("Line-ups")
                            Muted("Not announced yet")
                        }
                    } else {
                        Box(Modifier.weight(2f))
                    }
                }
            }
            // Actions: gold fill on focus
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth().background(Broadcast.Base).padding(horizontal = 18.dp, vertical = 11.dp)) {
                val channels = detail?.channels.orEmpty()
                ModalButton("Watch", Modifier.focusRequester(first), enabled = channels.isNotEmpty()) { channels.firstOrNull()?.let(onWatch) }
                onRemind?.let { ModalButton("Remind me") { it() } }
                onRecord?.let { ModalButton("Record") { it() } }
                // In play or finished: the score stays hidden until asked for
                if (!showScore && (m.score != null || m.status == "in" || m.status == "post")) {
                    ModalButton(if (revealing) "Getting score…" else "Show score") {
                        if (m.score != null) showScore = true else if (!revealing) revealing = true
                    }
                }
            }
        }
    }
    LaunchedEffect(initial.id) { JellytipTrace.log("iptv", "match info ${initial.id}") }
}

/** A titled section (gold head), focusable as a whole so D-pad Down scrolls through the modal on a TV. */
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                contentColor = Broadcast.Text,
                focusedContentColor = Broadcast.Text,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SectionHead(title)
            content()
        }
    }
}

/** "Sky Sport 1 NZ   1080p 5.1": the whole row watches it; focus = gold text. */
@Composable
private fun ChannelRow(
    ch: IptvChannel,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Broadcast.Gold,
                contentColor = Broadcast.Text,
                focusedContentColor = Broadcast.Ink,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(
                ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), ""),
                fontFamily = Barlow,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            QualityChips(ch.quality)
        }
    }
}

/** Gold league, venue with a pin, what's on air. */
@Composable
private fun SubBar(
    m: IptvMatch,
    d: MatchDetail?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().background(Broadcast.Sub).padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        m.league?.let { Cond(it, 15.sp, Broadcast.Gold) }
        m.startsAt?.takeIf { m.status != "in" && m.status != "post" }?.let { BodyText(whenLabel(it), 13.sp, maxLines = 1) }
        Spacer(Modifier.weight(1f))
        m.venue?.let { BodyText("📍 $it", 13.sp, maxLines = 1) }
        if (m.inferred) BodyText("identified from the fixtures", 12.sp, Broadcast.Secondary, maxLines = 1)
    }
}

@Composable
private fun LineupColumn(
    team: String?,
    players: List<MatchPlayer>,
    tint: Color,
    modifier: Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.width(4.dp).height(13.dp).background(tint))
            SectionHead("${team ?: "Team"} XI", Modifier.weight(1f))
        }
        val (starters, bench) = players.partition { it.starter }
        starters.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cond(p.num ?: "${i + 1}", 12.sp, Color(0xFF8C94A1), modifier = Modifier.width(20.dp), weight = FontWeight.SemiBold)
                BodyText(p.name + (p.pos?.let { " ($it)" } ?: ""), 12.5.sp, Color(0xFFDADDE3), maxLines = 1)
            }
        }
        if (bench.isNotEmpty()) BodyText("Bench: " + bench.joinToString(", ") { it.name }, 12.sp, Broadcast.Secondary, maxLines = 3)
    }
}

@Composable
private fun Muted(text: String) = BodyText(text, 12.5.sp, Broadcast.Body)

/** Condensed caps; focus = gold fill at 1.04x. */
@Composable
private fun ModalButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Broadcast.Chip,
                focusedContainerColor = Broadcast.Gold,
                contentColor = Broadcast.Text,
                focusedContentColor = Broadcast.Ink,
                disabledContainerColor = Broadcast.Chip.copy(alpha = 0.5f),
                disabledContentColor = Broadcast.Secondary,
            ),
        modifier = modifier,
    ) {
        Text(
            label.uppercase(java.util.Locale.ROOT),
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 0.05.em,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 6.dp),
        )
    }
}

private const val MAX_TRIES = 3
private const val PENDING_RETRY_MS = 3_000L
