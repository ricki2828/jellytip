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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** `/live` is missing on this server (404): no panel, no hint, for the rest of the run. */
object LivePanelRoute {
    @Volatile
    var missing = false
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
                LiveMarket(m.str("source"), m.str("question"), m.dbl("home"), m.dbl("away"), m.dbl("draw"), m.dbl("volume"), IptvParse.isoSeconds(m.str("updatedAt")))
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
            comments = comments,
            updatedAt = IptvParse.isoSeconds(o.str("updatedAt")),
        )
    }
}

private suspend fun fetchLive(id: String): Pair<Int, MatchLive?>? {
    val api = JellytipApi.shared ?: return null
    val (code, body) = api.userCall("v1/sport/match/${java.net.URLEncoder.encode(id, "UTF-8")}/live", timeoutMs = 8_000) ?: return null
    if (code != 200) return code to null
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
    onRouteMissing: () -> Unit,
    modifier: Modifier = Modifier,
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
            got?.second?.let { value = it }
            delay(REFRESH_MS)
        }
    }
    Row(
        modifier
            .fillMaxHeight()
            .background(Color(0xE6101014))
            .padding(horizontal = 14.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Tab rail (Up/Down)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(64.dp)) {
            LIVE_TABS.forEachIndexed { i, t ->
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
                d == null -> Note("Loading live data…")
                else -> {
                    if (d.scoreHome != null && d.scoreAway != null) {
                        Text(
                            listOfNotNull("${d.scoreHome} – ${d.scoreAway}", d.period, d.clock).joinToString("  ·  "),
                            color = JellytipPalette.Vanilla,
                            fontFamily = JellytipFontFamily,
                            fontSize = 13.sp,
                        )
                    }
                    when (tab) {
                        0 -> StatsTab(d, match)
                        1 -> PlaysTab(d)
                        2 -> OddsTab(d, match)
                        else -> ChatTab(d)
                    }
                    d.updatedAt?.let { Note("Updated ${ago(nowSec() - it)}") }
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
private fun PlaysTab(d: MatchLive) {
    if (d.plays.isEmpty()) {
        Note("No plays yet.")
        return
    }
    d.plays.take(10).forEach { p ->
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
    Text(line, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
private fun ChatTab(d: MatchLive) {
    if (d.comments.isEmpty()) {
        Note("No match thread comments.")
        return
    }
    d.chatSource?.let { Note("From $it") }
    d.comments.take(8).forEach { c ->
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

private fun pct(p: Double) = "${Math.round(p * 100)}%"

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
