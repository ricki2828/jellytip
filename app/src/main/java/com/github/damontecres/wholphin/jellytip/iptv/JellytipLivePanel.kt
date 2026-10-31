package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/*
 * In-event panel (~/ai/plans/jellytip-live-sport.md, "In-event panel"): `GET /v1/sport/match/{id}/live` while a key
 * match is live: Stats · Plays · Odds · Chat, refreshed every 15 s. Every block is optional (a source that failed is
 * just missing); replays and catch-up show a spoiler notice instead unless `sport.spoilers` is on.
 */

/** `/live` is missing on this server (404): no panel and no hint, until it is tried again 10 min later. */
object LivePanelRoute {
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

data class LiveStat(
    val label: String,
    val home: String,
    val away: String,
)

data class LivePlay(
    val clock: String?,
    val period: String?,
    val text: String,
    val scoring: Boolean,
)

data class LiveMarket(
    val source: String?,
    val question: String?,
    val home: Double?,
    val away: Double?,
    val draw: Double?,
    val volume: Double?,
    val updatedAt: Long?,
    /** Favourite flip (plan "Favourite-flip toast"): when it happened (dedupe key), the new favourite (home | away), its probability. */
    val flipAt: String? = null,
    val flipTo: String? = null,
    val flipProb: Double? = null,
)

data class LiveComment(
    val author: String?,
    val text: String,
    val score: Int?,
)

data class MatchLive(
    val status: String?,
    val clock: String?,
    val period: String?,
    val scoreHome: String?,
    val scoreAway: String?,
    val stats: List<LiveStat>,
    val leaders: List<String>,
    val plays: List<LivePlay>,
    val winProbHome: Double?,
    val market: LiveMarket?,
    val chatSource: String?,
    val comments: List<LiveComment>,
    /** The answer carries a `chat` block; without one the Chat tab is hidden (no match-thread source). */
    val hasChat: Boolean = false,
    val updatedAt: Long?,
)

object LiveParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.dbl(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    fun live(json: String): MatchLive {
        val o = JSONObject(json)
        val sc = o.optJSONObject("score")
        val stats =
            o.optJSONArray("stats")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { s -> LiveStat(s.str("label") ?: return@mapNotNull null, s.str("home").orEmpty(), s.str("away").orEmpty()) } }
            }.orEmpty()
        val leaders =
            o.optJSONArray("leaders")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { l -> listOfNotNull(l.str("name"), l.str("line")).joinToString("  ").ifBlank { null } } }
            }.orEmpty()
        val plays =
            o.optJSONArray("plays")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { p -> LivePlay(p.str("clock"), p.str("period"), p.str("text") ?: return@mapNotNull null, p.optBoolean("scoring", false)) } }
            }.orEmpty()
        val market =
            o.optJSONObject("market")?.let { m ->
                val flip = m.optJSONObject("flip")
                LiveMarket(
                    m.str("source"),
                    m.str("question"),
                    m.dbl("home"),
                    m.dbl("away"),
                    m.dbl("draw"),
                    m.dbl("volume"),
                    IptvParse.isoSeconds(m.str("updatedAt")),
                    flipAt = flip?.str("at"),
                    flipTo = flip?.str("to"),
                    flipProb = flip?.dbl("prob"),
                )
            }
        val chat = o.optJSONObject("chat")
        val comments =
            chat?.optJSONArray("comments")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> LiveComment(c.str("author"), c.str("text") ?: return@mapNotNull null, if (c.has("score")) c.optInt("score") else null) } }
            }.orEmpty()
        return MatchLive(
            status = o.str("status"),
            clock = o.str("clock"),
            period = o.str("period"),
            scoreHome = sc?.str("home"),
            scoreAway = sc?.str("away"),
            stats = stats,
            leaders = leaders,
            plays = plays,
            winProbHome = o.optJSONObject("winProb")?.dbl("home"),
            market = market,
            chatSource = chat?.str("source"),
            hasChat = chat != null,
            comments = comments,
            updatedAt = IptvParse.isoSeconds(o.str("updatedAt")),
        )
    }
}

internal suspend fun fetchLive(id: String): Pair<Int, MatchLive?>? {
    val api = JellytipApi.shared ?: return null
    val (code, body) = api.userCall("v1/sport/match/${java.net.URLEncoder.encode(id, "UTF-8")}/live", timeoutMs = 8_000) ?: return null
    if (MatchHidden.note(id, code, body) || code != 200) return code to null
    return code to runCatching { LiveParse.live(body) }.getOrNull()
}

val LIVE_TABS = listOf("Stats", "Plays", "Odds", "Chat")

/**
 * The side panel over the right of the video (the player keeps playing). [tab] is driven by the player's Up/Down;
 * [spoilerNotice] = a replay / catch-up with spoilers off: no data is fetched.
 */
@Composable
fun JellytipLivePanel(
    match: IptvMatch,
    tab: Int,
    spoilerNotice: Boolean,
    /** The rail's tabs: [LIVE_TABS], plus "Fantasy" for NFL with a linked account. */
    tabs: List<String> = LIVE_TABS,
    fantasy: FantasyLive? = null,
    onRouteMissing: () -> Unit,
    modifier: Modifier = Modifier,
    /** 0 = off screen to the right, 1 = in place (the player animates it; plan "Panel motion"). */
    progress: Float = 1f,
    /** Each answer says whether it has a `chat` block (the player shows the Chat tab only then). */
    onChatKnown: (Boolean) -> Unit = {},
) {
    val data by produceState<MatchLive?>(null, match.id, spoilerNotice) {
        if (spoilerNotice) return@produceState
        while (true) {
            val got = fetchLive(match.id)
            if (got?.first == 404) {
                LivePanelRoute.missing = true
                onRouteMissing()
                return@produceState
            }
            // 403 (no IPTV) or this match unknown: the panel closes and stays hidden for it
            if (MatchHidden.hides(match.id)) {
                onRouteMissing()
                return@produceState
            }
            got?.second?.let {
                value = it
                onChatKnown(it.hasChat)
            }
            delay(REFRESH_MS)
        }
    }
    // Slide via graphicsLayer translation only (no relayout, no blur: light on the Chromecast GPU); the scrim fades over
    // the panel area only, the video is not resized
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer { translationX = (1f - progress) * size.width },
    ) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = progress }
            .background(Color.Black.copy(alpha = 0.35f)),
    )
    Row(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(Color(0xCC101014))
            .padding(horizontal = 14.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Tab rail (Up/Down)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(64.dp)) {
            tabs.forEachIndexed { i, t ->
                Text(
                    t,
                    color = if (i == tab) Color.Black else JellytipPalette.Vanilla.copy(alpha = 0.8f),
                    fontFamily = JellytipFontFamily,
                    fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (i == tab) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                            .padding(vertical = 6.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(match.matchup ?: match.league ?: "Live", color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val d = data
            when {
                spoilerNotice -> Note("Live stats would spoil this recording. Turn on spoilers in Settings to see them.")
                tabs.getOrNull(tab) == "Fantasy" && fantasy != null -> FantasyTab(fantasy, match.id)
                d == null -> Note("Loading live data…")
                else -> {
                    if (d.scoreHome != null && d.scoreAway != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // The score pulses once when it changes
                            Pulse("${d.scoreHome} – ${d.scoreAway}") {
                                Text("${d.scoreHome} – ${d.scoreAway}", color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                            listOfNotNull(d.period, d.clock).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                                Text(it, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontSize = 13.sp)
                            }
                        }
                    }
                    // Tab switches cross-fade (150 ms)
                    androidx.compose.animation.Crossfade(targetState = tab, animationSpec = androidx.compose.animation.core.tween(150), label = "live-panel-tab") { t ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (tabs.getOrNull(t)) {
                                "Plays" -> PlaysTab(d, match.id)
                                "Odds" -> OddsTab(d, match)
                                "Chat" -> ChatTab(d, match.id)
                                else -> StatsTab(d, match)
                            }
                        }
                    }
                    d.updatedAt?.let { Note("Updated ${ago(nowSec() - it)}") }
                }
            }
        }
    }
    }
}

@Composable
private fun StatsTab(
    d: MatchLive,
    m: IptvMatch,
) {
    if (d.stats.isEmpty() && d.leaders.isEmpty()) {
        Note("No stats for this match yet.")
        return
    }
    if (d.stats.isNotEmpty()) {
        Row {
            Cell(m.home?.short ?: m.home?.name ?: "Home", 0.3f, bold = true)
            Cell("", 0.4f)
            Cell(m.away?.short ?: m.away?.name ?: "Away", 0.3f, bold = true)
        }
        d.stats.take(12).forEach { s ->
            Row {
                Cell(s.home, 0.3f)
                Cell(s.label, 0.4f, muted = true)
                Cell(s.away, 0.3f)
            }
        }
    }
    d.winProbHome?.let { Note("Win probability: ${m.home?.short ?: m.home?.name ?: "Home"} ${pct(it)}") }
    d.leaders.take(4).forEach { Note(it) }
}

@Composable
private fun PlaysTab(
    d: MatchLive,
    matchId: String,
) {
    if (d.plays.isEmpty()) {
        Note("No plays yet.")
        return
    }
    val shown = d.plays.take(10)
    val fresh = rememberFresh("plays:$matchId", shown.map { listOfNotNull(it.period, it.clock, it.text).joinToString("|") })
    shown.forEach { p ->
        val key = listOfNotNull(p.period, p.clock, p.text).joinToString("|")
        SlideInNew(key, key in fresh) {
        Text(
            listOfNotNull(listOfNotNull(p.period, p.clock).joinToString(" ").ifBlank { null }, p.text).joinToString("  "),
            color = if (p.scoring) Color.White else JellytipPalette.Vanilla.copy(alpha = 0.8f),
            fontFamily = JellytipFontFamily,
            fontWeight = if (p.scoring) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        }
    }
}

@Composable
private fun OddsTab(
    d: MatchLive,
    m: IptvMatch,
) {
    val mk = d.market
    if (mk == null || (mk.home == null && mk.away == null)) {
        Note("No market for this match.")
        return
    }
    // "Knicks 58% · 76ers 42%" (implied probabilities); no betting link
    val line =
        listOfNotNull(
            mk.home?.let { "${m.home?.short ?: m.home?.name ?: "Home"} ${pct(it)}" },
            mk.draw?.let { "Draw ${pct(it)}" },
            mk.away?.let { "${m.away?.short ?: m.away?.name ?: "Away"} ${pct(it)}" },
        ).joinToString(" · ")
    Pulse(line) { Text(line, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
    Note(
        listOfNotNull(
            mk.source?.replaceFirstChar { it.uppercase() },
            mk.volume?.let { "${money(it)} traded" },
            mk.updatedAt?.let { ago(nowSec() - it) },
        ).joinToString(", "),
    )
    mk.question?.let { Note(it) }
}

@Composable
private fun ChatTab(
    d: MatchLive,
    matchId: String,
) {
    if (d.comments.isEmpty()) {
        Note("No match thread comments.")
        return
    }
    d.chatSource?.let { Note("From $it") }
    val shown = d.comments.take(8)
    val fresh = rememberFresh("chat:$matchId", shown.map { "${it.author}|${it.text}" })
    shown.forEach { c ->
        SlideInNew("${c.author}|${c.text}", "${c.author}|${c.text}" in fresh) {
        Text(
            listOfNotNull(c.author?.let { "$it:" }, c.text).joinToString(" "),
            color = JellytipPalette.Vanilla.copy(alpha = 0.85f),
            fontFamily = JellytipFontFamily,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        }
    }
}

/**
 * Keys in [keys] that weren't there on the previous answer for [scope] (none on the first one, so opening the panel
 * doesn't animate the whole list). Plain remembered sets: not snapshot state.
 */
@Composable
private fun rememberFresh(
    scope: String,
    keys: List<String>,
): Set<String> {
    val known = remember(scope) { HashSet<String>() }
    val first = remember(scope) { booleanArrayOf(true) }
    return remember(scope, keys) {
        val fresh = if (first[0]) emptySet() else keys.filter { it !in known }.toSet()
        first[0] = false
        known.addAll(keys)
        fresh
    }
}

/** A new play or comment slides down from the top with a short highlight that fades (plan "Panel motion"). */
@Composable
private fun SlideInNew(
    key: String,
    isNew: Boolean,
    content: @Composable () -> Unit,
) {
    val enter = remember(key) { androidx.compose.animation.core.Animatable(if (isNew) 0f else 1f) }
    val glow = remember(key) { androidx.compose.animation.core.Animatable(if (isNew) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(key) {
        if (isNew) {
            enter.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            glow.animateTo(0f, androidx.compose.animation.core.tween(1_200))
        }
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .graphicsLayer {
                translationY = (1f - enter.value) * -16.dp.toPx()
                alpha = enter.value
            }.background(JellytipPalette.Jelly.copy(alpha = 0.28f * glow.value), RoundedCornerShape(4.dp)),
    ) { content() }
}

/** Pulses its content once (scale 1 → 1.15 → 1) whenever [value] changes after the first time. */
@Composable
private fun Pulse(
    value: String,
    content: @Composable () -> Unit,
) {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val last = remember { arrayOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(value) {
        val before = last[0]
        last[0] = value
        if (before != null && before != value) {
            scale.animateTo(1.15f, androidx.compose.animation.core.tween(140))
            scale.animateTo(1f, androidx.compose.animation.core.tween(220))
        }
    }
    androidx.compose.foundation.layout.Box(
        Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    ) { content() }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    weight: Float,
    bold: Boolean = false,
    muted: Boolean = false,
) {
    Text(
        text,
        color = if (muted) JellytipPalette.Vanilla.copy(alpha = 0.6f) else Color.White,
        fontFamily = JellytipFontFamily,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun Note(text: String) = Text(text, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 11.sp)

internal fun pct(p: Double) = "${Math.round(p * 100)}%"

private fun money(v: Double): String =
    when {
        v >= 1_000_000 -> String.format(Locale.US, "$%.1fM", v / 1_000_000)
        v >= 1_000 -> String.format(Locale.US, "$%.0fK", v / 1_000)
        else -> String.format(Locale.US, "$%.0f", v)
    }

private fun ago(secs: Long): String =
    when {
        secs < 60 -> "${secs.coerceAtLeast(0)} s ago"
        secs < 3600 -> "${secs / 60} min ago"
        else -> "${secs / 3600} h ago"
    }

private const val REFRESH_MS = 15_000L
