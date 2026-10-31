package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
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
    ): Boolean {
        if (code == 403) {
            noIptv = true
            JellytipTrace.log("iptv", "match routes 403: no IPTV, hidden")
            return true
        }
        if (code == 200 && body != null && runCatching { org.json.JSONObject(body).optBoolean("unknown", false) }.getOrDefault(false)) {
            unknown.add(id)
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
    if (MatchHidden.note(id, code, body) || code != 200) return code to null
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
    // Entry (plan "more pop"): the modal scales and fades in (220 ms), the crests slide in from either side
    val entry = remember(initial.id) { androidx.compose.animation.core.Animatable(0f) }
    val crestIn = remember(initial.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(initial.id) { entry.animateTo(1f, androidx.compose.animation.core.tween(220)) }
    LaunchedEffect(initial.id) {
        delay(60)
        crestIn.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val (homeC, awayC) = remember(m.home?.color, m.away?.color, m.away?.altColor) { matchColors(m) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .width(860.dp)
                    .height(500.dp)
                    .graphicsLayer {
                        val p = entry.value
                        scaleX = 0.94f + 0.06f * p
                        scaleY = 0.94f + 0.06f * p
                        alpha = p
                    }.background(Color(0xF2101014), RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
        ) {
            // Header: the split hero in the team colours
            val sc = (revealed ?: m.score)?.takeIf { showScore }
            MatchHero(m, detail?.venueImage, sc, crestIn = { crestIn.value })
            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val channels = detail?.channels.orEmpty()
                ModalButton("▶  Watch", Modifier.focusRequester(first), enabled = channels.isNotEmpty()) { channels.firstOrNull()?.let(onWatch) }
                onRemind?.let { ModalButton("Remind me") { it() } }
                onRecord?.let { ModalButton("Record") { it() } }
                // In play or finished: the score stays hidden until asked for
                if (!showScore && (m.score != null || m.status == "in" || m.status == "post")) {
                    ModalButton(if (revealing) "Getting score…" else "Show score") {
                        if (m.score != null) showScore = true else if (!revealing) revealing = true
                    }
                }
            }
            when (load) {
                MatchLoad.Loading -> Muted("Loading match details…")
                MatchLoad.Failed -> Muted("Match details aren't available right now.")
                is MatchLoad.Loaded -> {}
            }
            detail?.let { d ->
                // Where to watch
                if (d.channels.isNotEmpty() || d.broadcasts.isNotEmpty()) {
                    Section("Where to watch") {
                        d.channels.forEach { ch ->
                            val name = ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ModalButton("Watch on $name") { onWatch(ch) }
                                QualityChips(ch.quality)
                            }
                        }
                        if (d.broadcasts.isNotEmpty()) Muted("On air: ${d.broadcasts.joinToString(", ")}")
                    }
                }
                // Odds
                d.odds?.let { o ->
                    Section("Odds" + listOfNotNull(o.provider, o.updatedAt?.let { "updated ${hhmm(it)}" }).joinToString(" · ").let { if (it.isEmpty()) "" else "  ·  $it" }) {
                        OddsBar(o, m)
                        Line(m.home?.name ?: "Home", listOfNotNull(o.home?.moneyline, o.home?.spread?.let { "spread $it" }).joinToString("  "))
                        Line(m.away?.name ?: "Away", listOfNotNull(o.away?.moneyline, o.away?.spread?.let { "spread $it" }).joinToString("  "))
                        o.draw?.let { Line("Draw", it) }
                        o.overUnder?.let { Line("Over/under", it) }
                    }
                }
                // Line-ups
                if (d.homeLineup.isNotEmpty() || d.awayLineup.isNotEmpty() || d.lineupsAnnounced == false) {
                    Section("Line-ups") {
                        if (d.homeLineup.isEmpty() && d.awayLineup.isEmpty()) {
                            Muted("Not announced yet")
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                LineupColumn(m.home?.name, d.homeLineup, homeC, Modifier.weight(1f))
                                LineupColumn(m.away?.name, d.awayLineup, awayC, Modifier.weight(1f))
                            }
                        }
                    }
                }
                // Form and head-to-head
                if (d.homeForm.isNotEmpty() || d.awayForm.isNotEmpty() || d.h2h.isNotEmpty()) {
                    Section("Form and head-to-head") {
                        if (d.homeForm.isNotEmpty()) FormChips(m.home?.name ?: "Home", d.homeForm)
                        if (d.awayForm.isNotEmpty()) FormChips(m.away?.name ?: "Away", d.awayForm)
                        d.h2h.take(5).forEach { Muted(it) }
                    }
                }
            }
        }
    }
    LaunchedEffect(initial.id) { JellytipTrace.log("iptv", "match info ${initial.id}") }
}

/** A titled section, focusable as a whole so D-pad Down scrolls through the modal on a TV. */
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = {},
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.06f),
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = JellytipPalette.Vanilla,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(8.dp)) {
            Text(title.uppercase(), color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 11.sp, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun LineupColumn(
    team: String?,
    players: List<MatchPlayer>,
    tint: Color,
    modifier: Modifier,
) {
    // Tinted with the team colour
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.background(tint.copy(alpha = 0.18f), RoundedCornerShape(8.dp)).padding(10.dp),
    ) {
        team?.let { Text(it, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp) }
        val (starters, bench) = players.partition { it.starter }
        starters.forEach { p -> Text(listOfNotNull(p.num, p.name, p.pos?.let { "($it)" }).joinToString(" "), color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1) }
        if (bench.isNotEmpty()) Text("Bench: " + bench.joinToString(", ") { it.name }, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 11.sp)
    }
}

@Composable
private fun Line(
    label: String,
    value: String,
) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.width(200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = Color.White, fontFamily = JellytipFontFamily, fontSize = 13.sp)
    }
}

@Composable
private fun Muted(text: String) = Text(text, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 12.sp)

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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black,
                disabledContainerColor = Color.White.copy(alpha = 0.05f),
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
        modifier = modifier,
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

private const val MAX_TRIES = 3
private const val PENDING_RETRY_MS = 3_000L
