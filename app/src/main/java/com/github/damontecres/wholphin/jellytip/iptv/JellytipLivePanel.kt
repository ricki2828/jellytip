package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * In-event panel (~/ai/plans/jellytip-live-sport.md, "In-event panel" and "Pinned 31-Oct (Live panel: pop, context,
 * performance)"): `GET /v1/sport/match/{id}/live` while a key match is live. A team-colour scoreboard strip (crests,
 * score, clock, live pulse; it flashes on big moments), pill tabs Stats · Plays · Odds · Chat (· Fantasy), split-bar
 * stats, leader cards, a play timeline, an odds bar with a sparkline, and a per-sport context block (`layout` +
 * `situation`). Every block is optional: an older answer just shows less.
 *
 * Performance (TV budget < 5% janky frames with the panel open): the player polls (15 s full while open, 30 s light
 * `?fields=market,moments,score` while closed) and parses off the main thread; unchanged rows keep their instances
 * ([reuse]) so they skip recomposition; motion is graphicsLayer only (translation, alpha, scale), no shadows or blur;
 * images load at their display size.
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
    /** home | away (the timeline dot's colour); null = unknown. */
    val team: String? = null,
    /** Points the play scored ("+3"); null = unknown. */
    val points: Int? = null,
    val id: String? = null,
) {
    val key get() = id ?: listOfNotNull(period, clock, text).joinToString("|")
}

data class LiveLeader(
    val name: String,
    val line: String?,
    val team: String?,
    /** ESPN athlete headshot when present. */
    val headshot: String?,
)

/** A big event from the server's play diffs: kind touchdown | wicket | red_card | lead_change | clutch | score… */
data class LiveMoment(
    val at: String,
    val kind: String?,
    val team: String?,
    val label: String,
)

data class MarketPoint(
    val t: Long,
    val p: Double,
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
    /** The favourite's probability over the last 30 min (`market.series`). */
    val series: List<MarketPoint> = emptyList(),
)

data class LiveComment(
    val author: String?,
    val text: String,
    val score: Int?,
)

data class LiveBatter(
    val name: String,
    val runs: String?,
    val balls: String?,
    val onStrike: Boolean,
)

/** Sport-specific state (`situation`); only the fields of the match's sport are set. */
data class LiveSituation(
    // NFL
    val downDistance: String? = null,
    val yardLine: String? = null,
    /** Ball position, 0 = the home end zone (left of the bar), 1 = the away one; null = unknown. */
    val fieldPos: Float? = null,
    val possession: String? = null,
    val redZone: Boolean = false,
    val drive: String? = null,
    // Cricket
    val batters: List<LiveBatter> = emptyList(),
    val bowler: String? = null,
    val bowlerFigures: String? = null,
    val lastBalls: List<String> = emptyList(),
    val runRate: Double? = null,
    val requiredRate: Double? = null,
    val equation: String? = null,
    // Basketball
    val run: String? = null,
    val runTeam: String? = null,
    /** Period scores (Q1…Q4, OT): home, away. */
    val periods: List<Pair<String, String>> = emptyList(),
    /** Every `{home, away}` pair in the situation (possession %, territory, fouls, shots, xG, cards, penalties…). */
    val pairs: List<LiveStat> = emptyList(),
) {
    /** One line for under the scoreboard strip. */
    val summary: String?
        get() =
            listOfNotNull(
                downDistance,
                yardLine,
                if (redZone) "RED ZONE" else null,
                equation,
                if (equation == null && requiredRate != null) "RRR ${fmt(requiredRate)}" else null,
                run,
            ).joinToString("  ·  ").ifBlank { null }
}

data class MatchLive(
    val status: String?,
    val clock: String?,
    val period: String?,
    val scoreHome: String?,
    val scoreAway: String?,
    val stats: List<LiveStat>,
    val leaders: List<LiveLeader>,
    val plays: List<LivePlay>,
    val winProbHome: Double?,
    val market: LiveMarket?,
    val chatSource: String?,
    val comments: List<LiveComment>,
    /** The answer carries a `chat` block; without one the Chat tab is hidden (no match-thread source). */
    val hasChat: Boolean = false,
    val updatedAt: Long?,
    /** basketball | nfl | football | rugby | cricket | …; null = generic. */
    val layout: String? = null,
    val situation: LiveSituation? = null,
    val moments: List<LiveMoment> = emptyList(),
    /** The answer had a `moments` key (else moments are inferred from score changes). */
    val hasMoments: Boolean = false,
)

/** Unchanged parts keep the previous answer's instances, so their rows skip recomposition (strong skipping). */
fun MatchLive.reuse(prev: MatchLive?): MatchLive {
    prev ?: return this
    if (this == prev) return prev
    return copy(
        stats = stats.reuse(prev.stats),
        leaders = leaders.reuse(prev.leaders),
        plays = plays.reuse(prev.plays),
        market = if (market == prev.market) prev.market else market,
        comments = comments.reuse(prev.comments),
        situation = if (situation == prev.situation) prev.situation else situation,
        moments = moments.reuse(prev.moments),
    )
}

private fun <T : Any> List<T>.reuse(prev: List<T>): List<T> {
    if (this == prev) return prev
    if (prev.isEmpty()) return this
    val old = HashMap<T, T>(prev.size * 2)
    prev.forEach { old[it] = it }
    return map { old[it] ?: it }
}

object LiveParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.dbl(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun JSONObject.int(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    fun live(json: String): MatchLive {
        val o = JSONObject(json)
        val sc = o.optJSONObject("score")
        val stats =
            o.optJSONArray("stats").objects().mapNotNull { s -> LiveStat(s.str("label") ?: return@mapNotNull null, s.str("home").orEmpty(), s.str("away").orEmpty()) }
        val leadersArr = o.optJSONArray("leaders")
        val leaders =
            (0 until (leadersArr?.length() ?: 0)).mapNotNull { i ->
                val l = leadersArr!!.optJSONObject(i)
                if (l == null) {
                    leadersArr.optString(i).takeIf { it.isNotBlank() && it != "null" }?.let { LiveLeader(it, null, null, null) }
                } else {
                    LiveLeader(l.str("name") ?: return@mapNotNull null, l.str("line") ?: l.str("stat"), l.str("team"), l.str("headshot"))
                }
            }
        val plays =
            o.optJSONArray("plays").objects().mapNotNull { p ->
                LivePlay(
                    p.str("clock"),
                    p.str("period"),
                    p.str("text") ?: return@mapNotNull null,
                    p.optBoolean("scoring", false),
                    team = p.str("team"),
                    points = p.int("points") ?: p.int("delta"),
                    id = p.str("id"),
                )
            }
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
                    series =
                        m.optJSONArray("series").objects().mapNotNull { pt ->
                            MarketPoint(IptvParse.isoSeconds(pt.str("t")) ?: return@mapNotNull null, pt.dbl("p") ?: return@mapNotNull null)
                        },
                )
            }
        val chat = o.optJSONObject("chat")
        val comments =
            (chat?.optJSONArray("comments")).objects().mapNotNull { c -> LiveComment(c.str("author"), c.str("text") ?: return@mapNotNull null, if (c.has("score")) c.optInt("score") else null) }
        val moments =
            o.optJSONArray("moments").objects().mapNotNull { m ->
                val kind = m.str("kind")
                val label = m.str("label") ?: kind?.replace('_', ' ')?.uppercase(Locale.ROOT) ?: return@mapNotNull null
                LiveMoment(m.str("at") ?: label, kind, m.str("team"), label)
            }
        return MatchLive(
            status = o.str("status"),
            clock = o.str("clock") ?: sc?.str("clock"),
            period = o.str("period") ?: sc?.str("period"),
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
            layout = o.str("layout"),
            situation = situation(o.optJSONObject("situation")),
            moments = moments,
            hasMoments = o.has("moments"),
        )
    }

    private val consumed = setOf("batters", "bowler", "lastBalls", "lastSix", "run", "drive", "periods", "quarters", "linescores")

    fun situation(o: JSONObject?): LiveSituation? {
        o ?: return null
        val possession = o.str("possession")?.takeIf { it == "home" || it == "away" }
        val down = o.int("down")?.takeIf { it in 1..4 }
        val dd = o.str("downDistance") ?: o.str("downDistanceText") ?: down?.let { d -> ordinal(d) + (o.str("distance")?.let { " & $it" } ?: "") }
        val fieldPos =
            o.dbl("fieldPos")?.let { (it / 100).toFloat() }
                ?: o.dbl("yardsToEndzone")?.let { y -> (if (possession == "away") y / 100 else (100 - y) / 100).toFloat() }
        val run = o.optJSONObject("run")
        val bowler = o.optJSONObject("bowler")
        val drive = o.optJSONObject("drive")
        val periodsArr = o.optJSONArray("periods") ?: o.optJSONArray("quarters") ?: o.optJSONArray("linescores")
        val lastBalls = o.optJSONArray("lastBalls") ?: o.optJSONArray("lastSix")
        val pairs = ArrayList<LiveStat>()
        o.keys().forEach { k ->
            if (k in consumed) return@forEach
            val v = o.optJSONObject(k) ?: return@forEach
            if (!v.has("home") || !v.has("away")) return@forEach
            val h = v.optJSONObject("home")
            val a = v.optJSONObject("away")
            if (h != null && a != null) {
                // {home: {yellow, red}, away: {…}} -> one pair per sub key
                h.keys().forEach { sub -> if (a.has(sub)) pairs += LiveStat(label(sub, k), h.optString(sub), a.optString(sub)) }
            } else {
                pairs += LiveStat(label(k, null), v.optString("home"), v.optString("away"))
            }
        }
        return LiveSituation(
            downDistance = dd,
            yardLine = o.str("yardLine") ?: o.str("possessionText"),
            fieldPos = fieldPos?.coerceIn(0f, 1f),
            possession = possession,
            redZone = o.optBoolean("redZone", false) || o.optBoolean("isRedZone", false),
            drive = drive?.str("summary") ?: o.str("drive"),
            batters =
                o.optJSONArray("batters").objects().mapNotNull { b ->
                    LiveBatter(b.str("name") ?: return@mapNotNull null, b.str("runs"), b.str("balls"), b.optBoolean("onStrike", false) || b.optBoolean("striker", false))
                },
            bowler = bowler?.str("name") ?: o.str("bowler"),
            bowlerFigures = bowler?.let { b -> listOfNotNull(b.str("figures"), b.str("overs")?.let { "($it)" }).joinToString(" ").ifBlank { null } },
            lastBalls = if (lastBalls == null) emptyList() else (0 until lastBalls.length()).mapNotNull { lastBalls.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }.takeLast(6),
            runRate = o.dbl("rr") ?: o.dbl("runRate"),
            requiredRate = o.dbl("rrr") ?: o.dbl("requiredRunRate"),
            equation = o.str("equation") ?: o.str("need"),
            run = run?.str("text") ?: o.str("run"),
            runTeam = run?.str("team"),
            periods = periodsArr.objects().mapNotNull { p -> (p.str("home") ?: return@mapNotNull null) to (p.str("away") ?: return@mapNotNull null) },
            pairs = pairs,
        )
    }

    private fun ordinal(n: Int) =
        when (n) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            else -> "${n}th"
        }

    private fun label(
        key: String,
        parent: String?,
    ): String {
        val known =
            mapOf(
                "possession" to "Possession",
                "territory" to "Territory",
                "fouls" to "Team fouls",
                "teamFouls" to "Team fouls",
                "shots" to "Shots",
                "shotsOnTarget" to "On target",
                "xg" to "xG",
                "xG" to "xG",
                "penalties" to "Penalties",
                "yellow" to "Yellow cards",
                "red" to "Red cards",
                "timeouts" to "Timeouts",
                "corners" to "Corners",
            )
        known[key]?.let { return it }
        val words = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
        return if (parent != null && parent == "cards") "$words cards" else words
    }
}

/**
 * GET /live; [light] = `?fields=market,moments,score` (the 30 s call while the panel is closed). Parsed off the main
 * thread.
 */
internal suspend fun fetchLive(
    id: String,
    light: Boolean = false,
): Pair<Int, MatchLive?>? {
    val api = JellytipApi.shared ?: return null
    val q = if (light) "?fields=market,moments,score" else ""
    val (code, body) = api.userCall("v1/sport/match/${java.net.URLEncoder.encode(id, "UTF-8")}/live$q", timeoutMs = 8_000) ?: return null
    if (MatchHidden.note(id, code, body) || code != 200) return code to null
    return code to withContext(Dispatchers.Default) { runCatching { LiveParse.live(body) }.getOrNull() }
}

val LIVE_TABS = listOf("Stats", "Plays", "Odds", "Chat")

private fun tabIcon(t: String) =
    when (t) {
        "Stats" -> "📊"
        "Plays" -> "⏱"
        "Odds" -> "📈"
        "Chat" -> "💬"
        "Fantasy" -> "🏈"
        else -> "•"
    }

/** A moment to flash on the strip; [seq] retriggers it. */
data class LiveFlash(
    val seq: Int,
    val moment: LiveMoment,
)

/**
 * The side panel over the right of the video (the player keeps playing). [tab] is driven by the player's Up/Down;
 * [data] comes from the player's poll; [spoilerNotice] = a replay / catch-up with spoilers off: nothing is shown.
 */
@Composable
fun JellytipLivePanel(
    match: IptvMatch,
    tab: Int,
    spoilerNotice: Boolean,
    data: MatchLive?,
    /** The rail's tabs: [LIVE_TABS] (Chat only with a chat block), plus "Fantasy" for NFL with a linked account. */
    tabs: List<String> = LIVE_TABS,
    fantasy: FantasyLive? = null,
    flash: LiveFlash? = null,
    modifier: Modifier = Modifier,
    /** 0 = off screen to the right, 1 = in place (the player animates it; read in graphicsLayer only). */
    progress: () -> Float = { 1f },
) {
    val (homeC, awayC) = remember(match.home?.color, match.away?.color, match.away?.altColor) { matchColors(match) }
    // Slide via graphicsLayer translation only (no relayout, no blur: light on the Chromecast GPU); the scrim fades over
    // the panel area only, the video is not resized
    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer { translationX = (1f - progress()) * size.width },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress() }
                .background(Color.Black.copy(alpha = 0.35f)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Glass)
                    .padding(horizontal = 12.dp, vertical = 18.dp),
        ) {
            ScoreStrip(match, if (spoilerNotice) null else data, homeC, awayC, flash)
            if (!spoilerNotice) data?.situation?.summary?.let { Text(it, color = Accent, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TabChips(tabs, tab)
            val d = data
            val name = tabs.getOrNull(tab)
            when {
                spoilerNotice -> Note("Live stats would spoil this recording. Turn on spoilers in Settings to see them.")
                name == "Fantasy" && fantasy != null -> FantasyTab(fantasy, match.id)
                d == null -> Note("Loading live data…")
                else ->
                    // Tab switches cross-fade (150 ms, alpha only)
                    androidx.compose.animation.Crossfade(targetState = name, animationSpec = tween(150), label = "live-panel-tab") { t ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (t) {
                                "Plays" -> PlaysTab(d.plays, match.id, homeC, awayC)
                                "Odds" -> OddsTab(d.market, match, homeC, awayC)
                                "Chat" -> ChatTab(d, match.id)
                                else -> StatsTab(d, match, homeC, awayC)
                            }
                            d.updatedAt?.let { Note("Updated ${ago(nowSec() - it)}") }
                        }
                    }
            }
        }
    }
}

private val Glass = Color(0xE60B0C12)
private val Accent = Color(0xFFFFC94A)
private val Neutral = Color(0xFF8A90A0)

private fun sideColor(
    team: String?,
    homeC: Color,
    awayC: Color,
) = when (team) {
    "home" -> homeC
    "away" -> awayC
    else -> Neutral
}

/** Bright version of a team colour for bars and dots on the dark glass. */
private fun bright(c: Color) = lerp(c, Color.White, 0.18f)

// ---- Scoreboard strip -----------------------------------------------------------------------------------------------

@Composable
private fun ScoreStrip(
    m: IptvMatch,
    d: MatchLive?,
    homeC: Color,
    awayC: Color,
    flash: LiveFlash?,
) {
    val homeBg = lerp(homeC, Color.Black, 0.35f)
    val awayBg = lerp(awayC, Color.Black, 0.35f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                drawRect(homeBg)
                val p =
                    Path().apply {
                        moveTo(size.width * 0.54f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(size.width * 0.46f, size.height)
                        close()
                    }
                drawPath(p, awayBg)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            Crest(m.home, 30.dp)
            TeamShort(m.home, TextAlign.Start)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
                val score = if (d?.scoreHome != null && d.scoreAway != null) "${d.scoreHome} – ${d.scoreAway}" else "v"
                // The score pulses once when it changes
                Pulse(score) { Text(score, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (d?.status == null || d.status == "in") LiveDot()
                    val clock = listOfNotNull(d?.period, d?.clock).joinToString(" · ").ifBlank { if (d?.status == "post") "FT" else "LIVE" }
                    Text(clock, color = Color.White.copy(alpha = 0.85f), fontFamily = JellytipFontFamily, fontSize = 10.sp, maxLines = 1)
                }
            }
            TeamShort(m.away, TextAlign.End)
            Crest(m.away, 30.dp)
        }
        MomentFlash(flash, homeC, awayC)
    }
}

@Composable
private fun RowScope.TeamShort(
    t: MatchTeam?,
    align: TextAlign,
) = Text(
    t?.short ?: t?.name ?: "",
    color = Color.White,
    fontFamily = JellytipFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 13.sp,
    textAlign = align,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.weight(1f),
)

/** A crest at its display size (Coil sizes the request from the fixed layout size and caches it). */
@Composable
private fun Crest(
    t: MatchTeam?,
    size: Dp,
) {
    var failed by remember(t?.logo) { mutableStateOf(false) }
    val logo = t?.logo
    if (logo != null && !failed) {
        AsyncImage(model = logo, contentDescription = t?.name, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).background(Color.White.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Text(initials(t?.name ?: "?"), color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

/** A red dot that breathes (alpha, read in graphicsLayer only). */
@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live-dot")
    val a = t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "live-dot-alpha")
    Box(
        Modifier
            .size(7.dp)
            .graphicsLayer { alpha = a.value }
            .background(JellytipPalette.Jelly, CircleShape),
    )
}

/** The strip flashes the scoring team's colour once with a short label ("TOUCHDOWN", "WICKET", "LEAD CHANGE"). */
@Composable
private fun MomentFlash(
    flash: LiveFlash?,
    homeC: Color,
    awayC: Color,
) {
    val a = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<LiveMoment?>(null) }
    LaunchedEffect(flash?.seq) {
        val f = flash ?: return@LaunchedEffect
        shown = f.moment
        a.snapTo(0f)
        a.animateTo(1f, tween(180))
        delay(1_400)
        a.animateTo(0f, tween(420))
        shown = null
    }
    val m = shown ?: return
    val c = sideColor(m.team, homeC, awayC).let { if (it == Neutral) JellytipPalette.Jelly else bright(it) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = a.value
                scaleX = 0.94f + 0.06f * a.value
                scaleY = 0.94f + 0.06f * a.value
            }.background(c),
        contentAlignment = Alignment.Center,
    ) {
        Text(m.label, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = 2.sp, maxLines = 1)
    }
}

// ---- Tabs ----------------------------------------------------------------------------------------------------------

/** Pill chips with icons; the selected one also names itself (the panel is narrow). */
@Composable
private fun TabChips(
    tabs: List<String>,
    tab: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, t ->
            key(t) {
                val on = i == tab
                Text(
                    if (on) "${tabIcon(t)}  $t" else tabIcon(t),
                    color = if (on) Color.Black else Color.White,
                    fontFamily = JellytipFontFamily,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .background(if (on) Accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

// ---- Stats ---------------------------------------------------------------------------------------------------------

@Composable
private fun StatsTab(
    d: MatchLive,
    m: IptvMatch,
    homeC: Color,
    awayC: Color,
) {
    d.situation?.let { ContextBlock(d.layout, it, m, homeC, awayC) }
    d.winProbHome?.let { p -> SplitStat(LiveStat("Win probability", pct(p), pct(1 - p)), homeC, awayC) }
    if (d.stats.isEmpty() && d.leaders.isEmpty() && d.situation == null && d.winProbHome == null) {
        Note("No stats for this match yet.")
        return
    }
    d.stats.take(8).forEach { s -> key(s.label) { SplitStat(s, homeC, awayC) } }
    if (d.leaders.isNotEmpty()) {
        SectionLabel("Leaders")
        d.leaders.take(4).forEach { l -> key(l.name) { LeaderCard(l, homeC, awayC) } }
    }
}

private val leadingNumber = Regex("""-?\d+(?:\.\d+)?""")

/** "54%" / "12" / "3.4" -> the number; anything else = null. */
private fun num(s: String) = leadingNumber.find(s)?.value?.toDoubleOrNull()

/** One stat as a split bar in the team colours (each side's share); text only when the values aren't numbers. */
@Composable
private fun SplitStat(
    s: LiveStat,
    homeC: Color,
    awayC: Color,
) {
    val h = num(s.home)
    val a = num(s.away)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.home, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(52.dp))
            Text(s.label, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(s.away, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(52.dp))
        }
        if (h != null && a != null && h >= 0 && a >= 0 && h + a > 0) {
            val share = (h / (h + a)).toFloat()
            val hc = bright(homeC)
            val ac = bright(awayC)
            Box(
                Modifier.fillMaxWidth().height(6.dp).drawBehind {
                    val gap = 2.dp.toPx()
                    val split = size.width * share
                    val r = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(hc, size = Size((split - gap / 2).coerceAtLeast(0f), size.height), cornerRadius = r)
                    drawRoundRect(ac, topLeft = Offset(split + gap / 2, 0f), size = Size((size.width - split - gap / 2).coerceAtLeast(0f), size.height), cornerRadius = r)
                },
            )
        }
    }
}

@Composable
private fun LeaderCard(
    l: LiveLeader,
    homeC: Color,
    awayC: Color,
) {
    val c = sideColor(l.team, homeC, awayC)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(c.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                .padding(6.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(bright(c).copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            var failed by remember(l.headshot) { mutableStateOf(false) }
            if (l.headshot != null && !failed) {
                AsyncImage(model = l.headshot, contentDescription = l.name, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.size(36.dp))
            } else {
                Text(initials(l.name), color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(l.name, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            l.line?.let { Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---- Per-sport context ---------------------------------------------------------------------------------------------

@Composable
private fun ContextBlock(
    layout: String?,
    s: LiveSituation,
    m: IptvMatch,
    homeC: Color,
    awayC: Color,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        // NFL: down & distance, the field with the ball, drive, red zone
        if (s.downDistance != null || s.fieldPos != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.downDistance?.let { Text(it, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                s.yardLine?.let { Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 12.sp) }
                if (s.redZone) Chip("RED ZONE", Color(0xFFD64545))
            }
            s.fieldPos?.let { FieldBar(it, sideColor(s.possession, homeC, awayC), s.possession) }
            s.drive?.let { Note("Drive: $it") }
        }
        // Cricket: batters, the bowler, run rates, the last six balls
        if (s.batters.isNotEmpty() || s.bowler != null || s.lastBalls.isNotEmpty()) {
            s.batters.take(2).forEach { b ->
                key(b.name) {
                    Row {
                        Text(b.name + if (b.onStrike) " *" else "", color = Color.White, fontFamily = JellytipFontFamily, fontWeight = if (b.onStrike) FontWeight.SemiBold else FontWeight.Normal, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(listOfNotNull(b.runs, b.balls?.let { "($it)" }).joinToString(" "), color = Color.White, fontFamily = JellytipFontFamily, fontSize = 12.sp)
                    }
                }
            }
            s.bowler?.let { Note("Bowling: $it" + (s.bowlerFigures?.let { f -> "  $f" } ?: "")) }
            val rates = listOfNotNull(s.runRate?.let { "RR ${fmt(it)}" }, s.requiredRate?.let { "RRR ${fmt(it)}" }).joinToString("  v  ")
            if (rates.isNotEmpty()) Text(rates, color = Accent, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            if (s.lastBalls.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { s.lastBalls.forEach { BallChip(it) } }
            }
        }
        // Basketball: the run, period scores
        s.run?.let { Chip(it, bright(sideColor(s.runTeam, homeC, awayC).let { c -> if (c == Neutral) JellytipPalette.Jelly else c })) }
        if (s.periods.isNotEmpty()) PeriodGrid(s.periods, m, layout)
        // Everything paired (possession, territory, fouls, shots, xG, cards, penalties…)
        s.pairs.take(6).forEach { p -> key(p.label) { SplitStat(p, homeC, awayC) } }
    }
}

/** The field: end zones at both ends, 10-yard lines, the ball (team colour of the side in possession). */
@Composable
private fun FieldBar(
    pos: Float,
    color: Color,
    possession: String?,
) {
    val ball = bright(color)
    Box(
        Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).drawBehind {
            drawRect(Color(0xFF1F5E33))
            val ez = size.width * 0.08f
            drawRect(Color(0xFF17472A), size = Size(ez, size.height))
            drawRect(Color(0xFF17472A), topLeft = Offset(size.width - ez, 0f), size = Size(ez, size.height))
            val field = size.width - 2 * ez
            for (i in 1..9) {
                val x = ez + field * i / 10f
                drawLine(Color.White.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            val x = ez + field * pos
            drawCircle(ball, radius = size.height * 0.38f, center = Offset(x, size.height / 2))
            // Direction of play: a small tick towards the end zone being attacked
            val dir = if (possession == "away") -1f else 1f
            drawLine(ball, Offset(x, size.height / 2), Offset(x + dir * size.height * 0.9f, size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        },
    )
}

@Composable
private fun BallChip(b: String) {
    val (bg, fg) =
        when (b.uppercase(Locale.ROOT)) {
            "W" -> Color(0xFFD64545) to Color.White
            "4" -> Color(0xFF2F7DE1) to Color.White
            "6" -> Color(0xFF8E44E0) to Color.White
            "0", "." -> Color.White.copy(alpha = 0.12f) to Color.White.copy(alpha = 0.7f)
            else -> Color.White.copy(alpha = 0.25f) to Color.White
        }
    Box(Modifier.size(22.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(if (b == "0") "·" else b.take(2), color = fg, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

@Composable
private fun PeriodGrid(
    periods: List<Pair<String, String>>,
    m: IptvMatch,
    layout: String?,
) {
    val prefix = if (layout == "basketball" || layout == "nfl") "Q" else "P"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text("", modifier = Modifier.width(44.dp))
            periods.take(6).forEachIndexed { i, _ ->
                Text(if (i < 4 || prefix != "Q") "$prefix${i + 1}" else "OT", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        listOf(m.home to periods.map { it.first }, m.away to periods.map { it.second }).forEach { (t, scores) ->
            Row {
                Text(t?.short ?: t?.name ?: "", color = Color.White, fontFamily = JellytipFontFamily, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(44.dp))
                scores.take(6).forEach { Text(it, color = Color.White, fontFamily = JellytipFontFamily, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// ---- Plays ---------------------------------------------------------------------------------------------------------

/** A timeline: team-colour dots on a line; scoring plays bold and larger with the points ("+3"). */
@Composable
private fun PlaysTab(
    plays: List<LivePlay>,
    matchId: String,
    homeC: Color,
    awayC: Color,
) {
    if (plays.isEmpty()) {
        Note("No plays yet.")
        return
    }
    val shown = plays.take(9)
    val fresh = rememberFresh("plays:$matchId", shown.map { it.key })
    val line = Color.White.copy(alpha = 0.15f)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.drawBehind {
                val x = 5.dp.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            },
    ) {
        shown.forEach { p ->
            key(p.key) {
                SlideInNew(p.key, p.key in fresh) { TimelineRow(p, sideColor(p.team, homeC, awayC)) }
            }
        }
    }
}

@Composable
private fun TimelineRow(
    p: LivePlay,
    color: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 4.dp).size(if (p.scoring) 12.dp else 10.dp).background(bright(color), CircleShape))
        Column(Modifier.weight(1f)) {
            listOfNotNull(p.period, p.clock).joinToString(" ").ifBlank { null }?.let {
                Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.55f), fontFamily = JellytipFontFamily, fontSize = 10.sp)
            }
            Text(
                p.text,
                color = if (p.scoring) Color.White else JellytipPalette.Vanilla.copy(alpha = 0.85f),
                fontFamily = JellytipFontFamily,
                fontWeight = if (p.scoring) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (p.scoring) 14.sp else 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (p.scoring && p.points != null && p.points > 0) Chip("+${p.points}", bright(color))
    }
}

// ---- Odds ----------------------------------------------------------------------------------------------------------

@Composable
private fun OddsTab(
    mk: LiveMarket?,
    m: IptvMatch,
    homeC: Color,
    awayC: Color,
) {
    if (mk == null || (mk.home == null && mk.away == null)) {
        Note("No market for this match.")
        return
    }
    val h = mk.home ?: 0.0
    val a = mk.away ?: 0.0
    val dr = mk.draw ?: 0.0
    val sum = (h + a + dr).takeIf { it > 0 } ?: 1.0
    val hc = bright(homeC)
    val ac = bright(awayC)
    Pulse("$h|$a|$dr") {
        Box(
            Modifier.fillMaxWidth().height(14.dp).drawBehind {
                val r = CornerRadius(size.height / 2, size.height / 2)
                val hw = (size.width * h / sum).toFloat()
                val dw = (size.width * dr / sum).toFloat()
                drawRoundRect(Color.White.copy(alpha = 0.1f), cornerRadius = r)
                drawRoundRect(hc, size = Size(hw, size.height), cornerRadius = r)
                if (dw > 0) drawRect(Neutral, topLeft = Offset(hw, 0f), size = Size(dw, size.height))
                drawRoundRect(ac, topLeft = Offset(hw + dw, 0f), size = Size(size.width - hw - dw, size.height), cornerRadius = r)
            },
        )
    }
    Row {
        mk.home?.let { Text("${m.home?.short ?: m.home?.name ?: "Home"} ${pct(it)}", color = hc, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f)) }
        mk.draw?.let { Text("Draw ${pct(it)}", color = Color.White, fontFamily = JellytipFontFamily, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        mk.away?.let { Text("${pct(it)} ${m.away?.short ?: m.away?.name ?: "Away"}", color = ac, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
    }
    if (mk.series.size >= 2) {
        SectionLabel("Favourite · last 30 min")
        Sparkline(mk.series, if (h >= a) hc else ac)
    }
    Note(
        listOfNotNull(
            mk.source?.replaceFirstChar { it.uppercase() },
            mk.volume?.let { "${money(it)} traded" },
            mk.updatedAt?.let { ago(nowSec() - it) },
        ).joinToString(", "),
    )
    mk.question?.let { Note(it) }
}

/** The probability series as a line (drawn, not composed per point); a faint 50% line. */
@Composable
private fun Sparkline(
    series: List<MarketPoint>,
    color: Color,
) {
    val pts = remember(series) { series.sortedBy { it.t } }
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val t0 = pts.first().t
        val span = (pts.last().t - t0).coerceAtLeast(1).toFloat()
        val lo = minOf(0.4, pts.minOf { it.p })
        val hi = maxOf(0.6, pts.maxOf { it.p })
        fun y(p: Double) = (size.height * (1 - (p - lo) / (hi - lo))).toFloat()
        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y(0.5)), Offset(size.width, y(0.5)), strokeWidth = 1f)
        val path = Path()
        pts.forEachIndexed { i, pt ->
            val x = size.width * (pt.t - t0) / span
            if (i == 0) path.moveTo(x, y(pt.p)) else path.lineTo(x, y(pt.p))
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, y(pts.last().p)))
    }
}

// ---- Chat ----------------------------------------------------------------------------------------------------------

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
        val k = "${c.author}|${c.text}"
        key(k) {
            SlideInNew(k, k in fresh) {
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
}

// ---- Bits ----------------------------------------------------------------------------------------------------------

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

/** A new play or comment slides down from the top with a short highlight that fades (drawn: no recomposition per frame). */
@Composable
private fun SlideInNew(
    key: String,
    isNew: Boolean,
    content: @Composable () -> Unit,
) {
    val enter = remember(key) { Animatable(if (isNew) 0f else 1f) }
    val glow = remember(key) { Animatable(if (isNew) 1f else 0f) }
    LaunchedEffect(key) {
        if (isNew) {
            enter.animateTo(1f, tween(280, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            glow.animateTo(0f, tween(1_200))
        }
    }
    val tint = JellytipPalette.Jelly
    Box(
        Modifier
            .graphicsLayer {
                translationY = (1f - enter.value) * -16.dp.toPx()
                alpha = enter.value
            }.drawBehind {
                val g = glow.value
                if (g > 0f) drawRoundRect(tint.copy(alpha = 0.28f * g), cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()))
            },
    ) { content() }
}

/** Pulses its content once (scale 1 → 1.15 → 1) whenever [value] changes after the first time. */
@Composable
private fun Pulse(
    value: String,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val last = remember { arrayOf<String?>(null) }
    LaunchedEffect(value) {
        val before = last[0]
        last[0] = value
        if (before != null && before != value) {
            scale.animateTo(1.15f, tween(140))
            scale.animateTo(1f, tween(220))
        }
    }
    Box(
        Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    ) { content() }
}

@Composable
private fun Chip(
    text: String,
    color: Color,
) = Text(
    text,
    color = Color.White,
    fontFamily = JellytipFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    maxLines = 1,
    modifier = Modifier.background(color, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
)

@Composable
private fun SectionLabel(text: String) =
    Text(text.uppercase(Locale.ROOT), color = JellytipPalette.Vanilla.copy(alpha = 0.55f), fontFamily = JellytipFontFamily, fontSize = 10.sp, letterSpacing = 1.sp)

@Composable
private fun Note(text: String) = Text(text, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 11.sp)

internal fun pct(p: Double) = "${Math.round(p * 100)}%"

private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

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
