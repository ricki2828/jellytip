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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.JellytipApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * In-event panel (~/ai/plans/jellytip-live-sport.md, "In-event panel", "Live panel: pop, context, performance" and
 * "In-match visual direction: DECIDED 31-Oct"): `GET /v1/sport/match/{id}/live` while a key match is live, in the
 * Broadcast look ([Broadcast]): the panel washed in both team colours, angled full-colour score slabs with odometer
 * scores and the 2 s moment wipe, a gold period box and live dot, underlined tabs Stats · Plays · Odds · Chat
 * (· Fantasy), split-bar stats, leader rows with headshots, a play list, odds with a sparkline, and a per-sport context
 * block (`layout` + `situation`). Docked to the right edge with a gold border, or a floating card
 * (`sport.panelStyle`). Every block is optional: an older answer just shows less.
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
    /** The line under the label ("Brunson 3PT from 26 ft · Knicks lead 98–96"); null = none. */
    val detail: String? = null,
)

/** A winner-market outcome; [favourite] = one of the user's favourite drivers / golfers. */
data class Outcome(
    val name: String,
    val p: Double,
    val favourite: Boolean = false,
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
    /** "winner" (plan "Polymarket coverage round 2": F1 race, golf tournament, …) or null (head-to-head). */
    val kind: String? = null,
    /** A thin market (plan "thin markets", decided 31-Oct): little money behind it; a grey pill, never a flip toast. */
    val thin: Boolean = false,
    /** Winner markets: the top 6 plus the user's favourite drivers / golfers wherever they rank, best first. */
    val outcomes: List<Outcome> = emptyList(),
) {
    val isWinner get() = kind == "winner" && outcomes.isNotEmpty()
}

data class LiveComment(
    val author: String?,
    val text: String,
    val score: Int?,
)

/** F1 timing tower row (OpenF1). */
data class F1Row(
    val pos: String,
    val driver: String,
    val team: String?,
    val teamColor: String?,
    val gap: String?,
    val interval: String?,
    /** S | M | H | I | W */
    val tyre: String?,
    val tyreAge: String?,
    val pits: String?,
    val fastest: Boolean,
    /** Practice / qualifying: the best lap ("1:17.123"); gap is then "BEST" | "+0.123". */
    val best: String? = null,
)

/** One match of a tennis tournament event (situation.matches). */
data class TennisMatch(
    val id: String,
    val home: MatchTeam?,
    val away: MatchTeam?,
    val status: String?,
    val round: String?,
    val court: String?,
    val sets: List<Triple<String, String, String?>>,
    val serving: String?,
)

/** Golf leaderboard row. */
data class GolfRow(
    val pos: String,
    val player: String,
    val country: String?,
    val toPar: String?,
    val thru: String?,
    val today: String?,
)

/** One game of a whip-around programme (RedZone, `layout: "multi"`). */
data class MultiGame(
    val matchId: String,
    val home: MatchTeam?,
    val away: MatchTeam?,
    val scoreHome: String?,
    val scoreAway: String?,
    val clock: String?,
    val period: String?,
    val downDistance: String?,
    val yardLine: String?,
    val redZone: Boolean,
    /** pre | in | post */
    val status: String? = null,
    val startsAt: Long? = null,
    val lastPlay: String? = null,
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
    // F1 (plan "Live coverage round 2")
    val lap: String? = null,
    val laps: String? = null,
    val session: String? = null,
    /** green | yellow | sc | vsc | red | chequered */
    val flag: String? = null,
    val standings: List<F1Row> = emptyList(),
    val fastestLap: String? = null,
    // Tennis: sets (home, away, tie-break), the current game, who serves
    val sets: List<Triple<String, String, String?>> = emptyList(),
    val gameHome: String? = null,
    val gameAway: String? = null,
    val serving: String? = null,
    // Golf
    val round: String? = null,
    val leaderboard: List<GolfRow> = emptyList(),
    val favourites: List<GolfRow> = emptyList(),
    /** Tennis tournament event: up to 8 matches, on court first. */
    val matches: List<TennisMatch> = emptyList(),
) {
    /** One line for under the scoreboard strip. */
    val summary: String?
        get() =
            listOfNotNull(
                downDistance,
                yardLine,
                if (redZone) "RED ZONE" else null,
                equation,
                lap?.let { "Lap $it" + (laps?.let { l -> "/$l" } ?: "") },
                flag?.takeIf { it != "green" }?.let { flagLabel(it) },
                round?.let { "Round $it" },
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
    /** RedZone: every live game, red-zone ones first (server order). */
    val games: List<MultiGame> = emptyList(),
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
        games = games.reuse(prev.games),
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
                    kind = m.str("kind"),
                    thin = m.optBoolean("thin", false),
                    outcomes = outcomes(m.optJSONArray("outcomes")),
                )
            }
        val chat = o.optJSONObject("chat")
        val comments =
            (chat?.optJSONArray("comments")).objects().mapNotNull { c -> LiveComment(c.str("author"), c.str("text") ?: return@mapNotNull null, if (c.has("score")) c.optInt("score") else null) }
        val moments =
            o.optJSONArray("moments").objects().mapNotNull { m ->
                val kind = m.str("kind")
                val label = m.str("label") ?: kind?.replace('_', ' ')?.uppercase(Locale.ROOT) ?: return@mapNotNull null
                LiveMoment(m.str("at") ?: label, kind, m.str("team"), label, m.str("detail") ?: m.str("text"))
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
            games =
                o.optJSONArray("games").objects().mapNotNull { g ->
                    val sc = g.optJSONObject("score")
                    val sit = situation(g.optJSONObject("situation"))
                    MultiGame(
                        matchId = g.str("matchId") ?: return@mapNotNull null,
                        home = gameTeam(g, "home"),
                        away = gameTeam(g, "away"),
                        scoreHome = sc?.str("home"),
                        scoreAway = sc?.str("away"),
                        clock = g.str("clock") ?: sc?.str("clock"),
                        period = g.str("period") ?: sc?.str("period"),
                        downDistance = sit?.downDistance,
                        yardLine = sit?.yardLine,
                        redZone = g.optBoolean("redZone", false) || sit?.redZone == true,
                        status = g.str("status"),
                        startsAt = IptvParse.isoSeconds(g.str("startsAt")),
                        lastPlay = g.optJSONObject("situation")?.str("lastPlay"),
                    )
                },
        )
    }

    /** A game's team: an object like matchInfo's, or just a name. */
    private fun gameTeam(
        g: JSONObject,
        side: String,
    ): MatchTeam? {
        g.optJSONObject(side)?.let { t -> return MatchTeam(t.str("name") ?: t.str("short") ?: return null, t.str("short"), t.str("logo"), null, t.str("color"), t.str("altColor")) }
        return g.str(side)?.let { MatchTeam(it, null, null, null) }
    }

    private val consumed =
        setOf("batters", "bowler", "lastBalls", "lastSix", "run", "drive", "periods", "quarters", "linescores", "standings", "sets", "game", "leaderboard", "favourites", "stats", "fastestLap", "matches")

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
        // Tennis stats: {aces: {home, away}, …}
        o.optJSONObject("stats")?.let { st ->
            st.keys().forEach { k ->
                val v = st.optJSONObject(k) ?: return@forEach
                if (v.has("home") && v.has("away")) pairs += LiveStat(label(k, null), v.optString("home"), v.optString("away"))
            }
        }
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
            lap = o.str("lap"),
            laps = o.str("laps"),
            session = o.str("session"),
            flag = o.str("flag")?.lowercase(Locale.ROOT),
            standings =
                o.optJSONArray("standings").objects().mapNotNull { r ->
                    F1Row(
                        r.str("pos") ?: return@mapNotNull null,
                        r.str("driver") ?: return@mapNotNull null,
                        r.str("team"),
                        r.str("teamColor"),
                        r.str("gap"),
                        r.str("interval"),
                        r.str("tyre")?.uppercase(Locale.ROOT)?.take(1),
                        r.str("tyreAge"),
                        r.str("pits"),
                        r.optBoolean("fastest", false),
                        best = r.str("best"),
                    )
                }.take(20),
            fastestLap = o.optJSONObject("fastestLap")?.let { f -> listOfNotNull(f.str("driver"), f.str("time"), f.str("lap")?.let { "lap $it" }).joinToString(" ").ifBlank { null } } ?: o.str("fastestLap"),
            sets = sets(o.optJSONArray("sets")),
            gameHome = o.optJSONObject("game")?.str("home"),
            gameAway = o.optJSONObject("game")?.str("away"),
            serving = o.str("serving")?.takeIf { it == "home" || it == "away" },
            round = o.str("round"),
            leaderboard = golfRows(o.optJSONArray("leaderboard")).take(15),
            favourites = golfRows(o.optJSONArray("favourites")),
            matches =
                o.optJSONArray("matches").objects().mapNotNull { mt ->
                    TennisMatch(
                        id = mt.str("id") ?: return@mapNotNull null,
                        home = player(mt, "home"),
                        away = player(mt, "away"),
                        status = mt.str("status"),
                        round = mt.str("round"),
                        court = mt.str("court"),
                        sets = sets(mt.optJSONArray("sets")),
                        serving = mt.str("serving")?.takeIf { it == "home" || it == "away" },
                    )
                }.take(8),
        )
    }

    private fun sets(a: JSONArray?) = a.objects().mapNotNull { st -> Triple(st.str("home") ?: return@mapNotNull null, st.str("away") ?: return@mapNotNull null, st.str("tb")) }

    /** A tennis player: {name, short, country, flag} (flag = logo) or just a name. */
    private fun player(
        o: JSONObject,
        side: String,
    ): MatchTeam? {
        o.optJSONObject(side)?.let { t -> return MatchTeam(t.str("name") ?: return null, t.str("short"), t.str("flag") ?: t.str("logo"), t.str("rank")?.let { "#$it" }, null, null, t.str("country")) }
        return o.str(side)?.let { MatchTeam(it, null, null, null) }
    }

    /** `outcomes: [{name, p, favourite?}]` (the server sends the top 6 plus favourites), best first. */
    fun outcomes(a: JSONArray?): List<Outcome> =
        a.objects().mapNotNull { o -> Outcome(o.str("name") ?: return@mapNotNull null, o.dbl("p") ?: return@mapNotNull null, o.optBoolean("favourite", false)) }.sortedByDescending { it.p }.take(10)

    private fun golfRows(a: JSONArray?): List<GolfRow> =
        a.objects().mapNotNull { r ->
            GolfRow(r.str("pos") ?: "", r.str("player") ?: return@mapNotNull null, r.str("country"), r.str("toPar"), r.str("thru"), r.str("today"))
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


/** A moment to wipe across the strip; [seq] retriggers it. */
data class LiveFlash(
    val seq: Int,
    val moment: LiveMoment,
)

/**
 * The side panel (the player keeps playing). [tab] is driven by the player's Up/Down; [data] comes from the player's
 * poll; [spoilerNotice] = a replay / catch-up with spoilers off: nothing is shown. [floating] = the rounded card
 * inset 12 dp instead of docked to the edge.
 */
@Composable
fun JellytipLivePanel(
    match: IptvMatch,
    tab: Int,
    spoilerNotice: Boolean,
    data: MatchLive?,
    /** The tabs: [LIVE_TABS] (Chat only with a chat block), plus "Fantasy" for NFL with a linked account. */
    tabs: List<String> = LIVE_TABS,
    fantasy: FantasyLive? = null,
    flash: LiveFlash? = null,
    floating: Boolean = false,
    modifier: Modifier = Modifier,
    /** 0 = off screen to the right, 1 = in place (the player animates it; read in graphicsLayer only). */
    progress: () -> Float = { 1f },
) {
    val pal = remember(match.home?.color, match.away?.color, match.away?.altColor) { matchPalette(match) }
    val shape = if (floating) RoundedCornerShape(16.dp) else RectangleShape
    // Slide via graphicsLayer translation only (no relayout, no blur, no shadow: light on the Chromecast GPU)
    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer { translationX = (1f - progress()) * (size.width + 24.dp.toPx()) },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(if (floating) 12.dp else 0.dp)
                .clip(shape)
                // The wash: home colour left, away right, 22% into #0D1015 (a static brush), at 95%
                .background(pal.wash, alpha = 0.95f),
        ) {
            if (!floating) Box(Modifier.width(3.dp).fillMaxHeight().background(Broadcast.Gold))
            Column(
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(if (floating) Modifier.border(2.dp, Broadcast.Gold.copy(alpha = 0.6f), shape) else Modifier)
                        .padding(14.dp),
            ) {
                if (match.isEvent || (match.home == null && match.away == null)) {
                    EventStrip(match, pal, if (spoilerNotice) null else flash)
                } else {
                    ScoreStrip(match, if (spoilerNotice) null else data, pal, if (spoilerNotice) null else flash)
                }
                ClockRow(if (spoilerNotice) null else data, pal)
                if (!spoilerNotice) data?.situation?.flag?.takeIf { it != "green" }?.let { FlagBanner(it) }
                // Body text sits on a near-black card within the wash (3 m legibility)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f).background(Broadcast.Card).padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Tabs(tabs, tab)
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
                                        "Plays" -> PlaysTab(d.plays, match.id, pal)
                                        "Odds" -> OddsTab(d.market, match, pal, full = true)
                                        "Chat" -> ChatTab(d)
                                        else -> StatsTab(d, match, pal)
                                    }
                                    d.updatedAt?.let { Note("Updated ${ago(nowSec() - it)}") }
                                }
                            }
                    }
                }
            }
        }
    }
}

// ---- Score strip: two angled full-colour slabs, odometer scores, the moment wipe ---------------------------------

@Composable
private fun ScoreStrip(
    m: IptvMatch,
    d: MatchLive?,
    pal: MatchPalette,
    flash: LiveFlash?,
) {
    // The score swaps under the wipe: while a moment plays, the shown score changes only once the band covers it
    val target = (d?.scoreHome ?: "") to (d?.scoreAway ?: "")
    val latest by rememberUpdatedState(target)
    var shown by remember { mutableStateOf(target) }
    val wiping = remember { booleanArrayOf(false) }
    LaunchedEffect(flash?.seq) { if (flash != null) wiping[0] = true }
    LaunchedEffect(target) { if (!wiping[0]) shown = target }
    Box(Modifier.fillMaxWidth().height(56.dp).clipToBounds()) {
        Row(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).fillMaxHeight().clip(SlabLeft).background(pal.home).padding(horizontal = 9.dp),
            ) {
                Crest(m.home, 28.dp)
                Cond(m.home?.short ?: m.home?.name ?: "", 17.sp, pal.onHome, modifier = Modifier.weight(1f))
                if (shown.first.isNotEmpty()) Odometer(shown.first, 34.sp, pal.onHome)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .offset(x = (-7).dp)
                        .fillMaxHeight()
                        .clip(SlabRight)
                        .background(pal.away)
                        .padding(start = 15.dp, end = 2.dp),
            ) {
                if (shown.second.isNotEmpty()) Odometer(shown.second, 34.sp, pal.onAway)
                Cond(m.away?.short ?: m.away?.name ?: "", 17.sp, pal.onAway, modifier = Modifier.weight(1f), caps = true)
                Crest(m.away, 28.dp)
            }
        }
        MomentWipe(
            flash,
            pal,
            onCovered = { shown = latest },
            onDone = {
                wiping[0] = false
                shown = latest
            },
        )
    }
}

/** An event (F1, golf): the title on a full-width slab in the series colour; the moment wipe over it. */
@Composable
private fun EventStrip(
    m: IptvMatch,
    pal: MatchPalette,
    flash: LiveFlash?,
) {
    Box(Modifier.fillMaxWidth().height(56.dp).clipToBounds()) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().clip(SlabLeft).background(pal.home).padding(horizontal = 12.dp),
        ) {
            Cond(m.title ?: m.league ?: "Live", 20.sp, pal.onHome)
            m.league?.takeIf { m.title != null }?.let { BodyText(it, 12.sp, pal.onHome.copy(alpha = 0.85f), weight = FontWeight.SemiBold, maxLines = 1) }
        }
        MomentWipe(flash, pal)
    }
}

/** F1 flag state as a full-width banner (yellow, safety car, red, chequered). */
@Composable
private fun FlagBanner(flag: String) {
    val (bg, fg) =
        when (flag) {
            "red" -> Color(0xFFE10600) to Color.White
            "chequered" -> Color.White to Broadcast.Ink
            else -> Color(0xFFFFD400) to Broadcast.Ink
        }
    Cond(
        flagLabel(flag),
        15.sp,
        fg,
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        tracking = 0.08f,
        align = TextAlign.Center,
    )
}

/** Gold period box (or F1 lap counter / golf round), clock (or session), live dot; the situation line on the right. */
@Composable
private fun ClockRow(
    d: MatchLive?,
    pal: MatchPalette,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        val post = d?.status == "post"
        val s = d?.situation
        val period =
            when {
                post -> "FT"
                s?.lap != null -> "Lap ${s.lap}" + (s.laps?.let { "/$it" } ?: "")
                s?.round != null -> "R${s.round}"
                else -> d?.period
            }
        period?.let { Cond(it, 15.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 7.dp, vertical = 2.dp)) }
        (s?.session ?: d?.clock)?.takeIf { !post }?.let { Cond(it, 15.sp, pal.onHomeWash, weight = FontWeight.SemiBold) }
        if (!post) {
            LiveDot()
            Cond("Live", 15.sp, pal.onHomeWash, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        // F1 / golf: the box already says lap / round; the rest of the line is for matches
        s?.summary?.takeIf { s.lap == null && s.round == null }?.let { BodyText(it, 13.sp, pal.onAwayWash.copy(alpha = 0.8f), weight = FontWeight.SemiBold, maxLines = 1) }
    }
}

/** A crest at its display size (Coil sizes the request from the fixed layout size and caches it). */
@Composable
private fun Crest(
    t: MatchTeam?,
    size: Dp,
) {
    var failed by remember(t?.logo) { mutableStateOf(false) }
    val logo = t?.logo
    val flag = flagEmoji(t?.country)
    if (logo != null && !failed) {
        AsyncImage(model = logo, contentDescription = t?.name, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(size))
    } else if (flag != null) {
        // Tennis: the player's flag instead of a crest
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { androidx.tv.material3.Text(flag, fontSize = (size.value * 0.6f).sp) }
    } else {
        Box(Modifier.size(size).background(Color.Black.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Cond(initials(t?.name ?: "?"), 11.sp, Color.White)
        }
    }
}

/** A red dot that breathes (alpha, read in graphicsLayer only). */
@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live-dot")
    val a = t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Reverse), label = "live-dot-alpha")
    Box(
        Modifier
            .size(7.dp)
            .graphicsLayer { alpha = a.value }
            .background(Broadcast.Live, CircleShape),
    )
}

// ---- Tabs: condensed caps with icons, a gold underline on the selected one -----------------------------------------

@Composable
private fun Tabs(
    tabs: List<String>,
    tab: Int,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            tabs.forEachIndexed { i, t ->
                key(t) {
                    val on = i == tab
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                            Icon(BroadcastIcons.forTab(t), contentDescription = null, tint = if (on) Color.White else Broadcast.Secondary, modifier = Modifier.size(13.dp))
                            Cond(t, 14.sp, if (on) Color.White else Broadcast.Secondary, weight = FontWeight.SemiBold, tracking = 0.04f)
                        }
                        Box(Modifier.height(3.dp).fillMaxWidth().background(if (on) Broadcast.Gold else Color.Transparent))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Broadcast.Line))
    }
}

// ---- Stats ---------------------------------------------------------------------------------------------------------

@Composable
private fun StatsTab(
    d: MatchLive,
    m: IptvMatch,
    pal: MatchPalette,
) {
    // RedZone: the multi-game scoreboard instead
    if (d.layout == "multi" || d.games.isNotEmpty()) {
        if (d.games.isEmpty()) Note("No games on right now.")
        d.games.take(9).forEach { g -> key(g.matchId) { GameRow(g) } }
        return
    }
    d.situation?.let { ContextBlock(d.layout, it, m, d, pal) }
    d.winProbHome?.let { p -> StatBar(LiveStat("Win probability", pct(p), pct(1 - p)), pal) }
    if (d.stats.isEmpty() && d.leaders.isEmpty() && d.situation == null && d.winProbHome == null) {
        Note("No stats for this match yet.")
    }
    d.stats.take(7).forEach { s -> key(s.label) { StatBar(s, pal) } }
    d.leaders.take(3).forEach { l -> key(l.name) { LeaderRow(l, pal) } }
    // The market in one line at the foot (as on air)
    d.market?.takeIf { it.home != null || it.away != null || it.isWinner }?.let { mk -> OddsLine(mk, m) }
}

private val leadingNumber = Regex("""-?\d+(?:\.\d+)?""")

/** "54%" / "12" / "13/34" -> the leading number; anything else = null. */
private fun num(s: String) = leadingNumber.find(s)?.value?.toDoubleOrNull()

/** Bar colours: the team colours when known, else gold v grey so the split still reads. */
private fun barColors(pal: MatchPalette) = if (pal.known) pal.home to pal.away else Broadcast.Gold to Broadcast.Secondary

/** One stat: the two values in condensed numerals, the label between, a square split track under them. */
@Composable
private fun StatBar(
    s: LiveStat,
    pal: MatchPalette,
) {
    val h = num(s.home)
    val a = num(s.away)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Cond(s.home, 16.sp)
            Cond(s.label, 12.sp, Broadcast.Secondary, modifier = Modifier.weight(1f), weight = FontWeight.SemiBold, tracking = 0.06f, align = TextAlign.Center)
            Cond(s.away, 16.sp)
        }
        if (h != null && a != null && h >= 0 && a >= 0 && h + a > 0) {
            val (hc, ac) = barColors(pal)
            Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(h.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(hc))
                Box(Modifier.weight(a.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(ac))
            }
        }
    }
}

@Composable
private fun LeaderRow(
    l: LiveLeader,
    pal: MatchPalette,
) {
    val c = pal.side(l.team)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(c.copy(alpha = 0.35f))
                .border(2.dp, c, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            var failed by remember(l.headshot) { mutableStateOf(false) }
            if (l.headshot != null && !failed) {
                AsyncImage(model = l.headshot, contentDescription = l.name, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.size(32.dp))
            } else {
                Cond(initials(l.name), 12.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Cond(l.name, 15.sp)
            l.line?.let { BodyText(it, 12.5.sp, maxLines = 1) }
        }
    }
}

// ---- Per-sport context ---------------------------------------------------------------------------------------------

@Composable
private fun ContextBlock(
    layout: String?,
    s: LiveSituation,
    m: IptvMatch,
    d: MatchLive,
    pal: MatchPalette,
) {
    if (s.standings.isNotEmpty()) TimingTower(s.standings, s.fastestLap)
    if (s.sets.isNotEmpty() || s.gameHome != null) SetGrid(s, m)
    // A tennis tournament: its matches (on court first), each a compact set grid under its round and court
    s.matches.take(6).forEach { mt ->
        key(mt.id) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BodyText(listOfNotNull(mt.round, mt.court, mt.status?.takeIf { it != "in" }?.let { if (it == "post") "Final" else it }).joinToString(" · "), 11.5.sp, Broadcast.Secondary, maxLines = 1)
                SetGrid(mt.home, mt.away, mt.sets, mt.serving, size = 14f)
            }
        }
    }
    if (s.leaderboard.isNotEmpty() || s.favourites.isNotEmpty()) Leaderboard(s)
    // NFL: down & distance in the broadcasters' gold box, the field (drive side, ball, line to gain), the drive
    if (s.downDistance != null || s.fieldPos != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.downDistance?.let { Cond(it, 17.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 7.dp, vertical = 1.dp)) }
            s.yardLine?.let { BodyText("Ball on $it", 13.sp, weight = FontWeight.SemiBold, maxLines = 1) }
            if (s.redZone) Cond("Red zone", 13.sp, Color.White, modifier = Modifier.background(Color(0xFFD64545)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        s.fieldPos?.let { FieldBar(it, s, m, pal) }
        s.drive?.let { BodyText(it, 12.5.sp, maxLines = 1) }
    }
    // Cricket: batters, the bowler, RR v RRR, the last six balls
    if (s.batters.isNotEmpty() || s.bowler != null || s.lastBalls.isNotEmpty()) {
        s.batters.take(2).forEach { b ->
            key(b.name) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cond(b.name + if (b.onStrike) " *" else "", 15.sp, if (b.onStrike) Color.White else Broadcast.Body, modifier = Modifier.weight(1f))
                    Cond(listOfNotNull(b.runs, b.balls?.let { "($it)" }).joinToString(" "), 15.sp)
                }
            }
        }
        s.bowler?.let { BodyText("Bowling: $it" + (s.bowlerFigures?.let { f -> "  $f" } ?: ""), 12.5.sp, maxLines = 1) }
        val rates = listOfNotNull(s.runRate?.let { "RR ${fmt(it)}" }, s.requiredRate?.let { "RRR ${fmt(it)}" }).joinToString("  v  ")
        if (rates.isNotEmpty()) Cond(rates, 14.sp, Broadcast.Gold)
        if (s.lastBalls.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { s.lastBalls.forEach { BallChip(it) } }
    }
    // Basketball: period scores (gold totals), the run
    if (s.periods.isNotEmpty()) PeriodGrid(s.periods, m, d, layout, pal)
    s.run?.let { run ->
        Row(Modifier.fillMaxWidth().background(Broadcast.Chip), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(7.dp).height(26.dp).background(pal.side(s.runTeam).takeIf { s.runTeam != null } ?: Broadcast.Gold))
            Cond(run, 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), tracking = 0.03f)
        }
    }
    // Everything paired (possession, territory, fouls, shots, xG, cards, penalties…)
    s.pairs.take(5).forEach { p -> key(p.label) { StatBar(p, pal) } }
}

/** RedZone scoreboard row: two compact slabs (team colour, short name, score), the clock, red-zone flag, down & distance. */
@Composable
private fun GameRow(g: MultiGame) {
    val p = remember(g.home?.color, g.away?.color) { matchPalette(IptvMatch(g.matchId, null, "nfl", g.home, g.away, null, null, null, false, null)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).fillMaxHeight().clip(SlabLeft).background(p.home).padding(start = 6.dp, end = 10.dp),
            ) {
                Cond(g.home?.short ?: g.home?.name ?: "", 14.sp, p.onHome, modifier = Modifier.weight(1f))
                g.scoreHome?.let { Odometer(it, 17.sp, p.onHome) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).offset(x = (-4).dp).fillMaxHeight().clip(SlabRight).background(p.away).padding(start = 10.dp, end = 6.dp),
            ) {
                g.scoreAway?.let { Odometer(it, 17.sp, p.onAway) }
                Cond(g.away?.short ?: g.away?.name ?: "", 14.sp, p.onAway, modifier = Modifier.weight(1f), align = TextAlign.End)
            }
            val clock =
                when (g.status) {
                    "pre" -> g.startsAt?.let { hhmm(it) } ?: "Soon"
                    "post" -> "Final"
                    else -> listOfNotNull(g.period, g.clock).joinToString(" ")
                }
            Cond(clock, 13.sp, Broadcast.Secondary, modifier = Modifier.width(62.dp), weight = FontWeight.SemiBold, align = TextAlign.End)
        }
        if (g.redZone || g.downDistance != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g.redZone) Cond("Red zone", 12.sp, Color.White, modifier = Modifier.background(Color(0xFFD64545)).padding(horizontal = 5.dp))
                g.downDistance?.let { Cond(it, 12.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 5.dp)) }
                g.yardLine?.let { BodyText("Ball on $it", 12.sp, maxLines = 1) }
            }
        }
        g.lastPlay?.takeIf { g.status == "in" || g.status == null }?.let { BodyText(it, 12.sp, Broadcast.Secondary, maxLines = 1) }
    }
}

/** F1 timing tower: position, team colour bar, driver code, gap (interval when no gap), tyre chip, fastest-lap dot. */
@Composable
private fun TimingTower(
    rows: List<F1Row>,
    fastestLap: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.take(12).forEach { r ->
            key(r.driver) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Cond(r.pos, 15.sp, if (r.pos == "1") Broadcast.Gold else Broadcast.Secondary, modifier = Modifier.width(20.dp), align = TextAlign.End)
                    Box(Modifier.width(4.dp).height(16.dp).background(teamColor(r.teamColor, Broadcast.Secondary)))
                    Cond(r.driver, 15.sp, modifier = Modifier.weight(1f))
                    if (r.fastest) Box(Modifier.size(7.dp).background(Color(0xFFA020F0), CircleShape))
                    // Race: gap ("LEADER" for P1) or interval; practice / qualifying: P1's best lap, the others' gap
                    val gapText = if (r.pos == "1") r.best ?: r.gap ?: "Leader" else r.gap ?: r.interval
                    Cond(gapText ?: "", 13.sp, Broadcast.Body, weight = FontWeight.SemiBold)
                    TyreChip(r.tyre)
                }
            }
        }
        fastestLap?.let { BodyText("Fastest lap: $it", 12.sp, Color(0xFFC77DFF), weight = FontWeight.SemiBold, maxLines = 1) }
    }
}

@Composable
private fun TyreChip(tyre: String?) {
    val t = tyre ?: return
    val (ring, fg) =
        when (t) {
            "S" -> Color(0xFFE10600) to Color.White
            "M" -> Color(0xFFFFD400) to Broadcast.Ink
            "H" -> Color.White to Broadcast.Ink
            "I" -> Color(0xFF43B02A) to Color.White
            "W" -> Color(0xFF0067AD) to Color.White
            else -> Broadcast.Loss to Color.White
        }
    Box(Modifier.size(17.dp).background(ring, CircleShape), contentAlignment = Alignment.Center) { Cond(t, 11.sp, fg) }
}

/** Tennis: both players with the serve dot, set scores (tie-breaks small), the current game in gold. */
@Composable
private fun SetGrid(
    s: LiveSituation,
    m: IptvMatch,
) = SetGrid(m.home, m.away, s.sets, s.serving, s.gameHome, s.gameAway)

@Composable
private fun SetGrid(
    home: MatchTeam?,
    away: MatchTeam?,
    sets: List<Triple<String, String, String?>>,
    serving: String?,
    gameHome: String? = null,
    gameAway: String? = null,
    size: Float = 15f,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(Triple("home", home, gameHome), Triple("away", away, gameAway)).forEach { (side, p, game) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(if (serving == side) Broadcast.Gold else Color.Transparent, CircleShape))
                flagEmoji(p?.country)?.let { androidx.tv.material3.Text(it, fontSize = 13.sp) }
                Cond(p?.short ?: p?.name ?: "", size.sp, modifier = Modifier.weight(1f))
                sets.forEach { (h, a, tb) ->
                    val mine = if (side == "home") h else a
                    val theirs = if (side == "home") a else h
                    val won = (mine.toIntOrNull() ?: 0) > (theirs.toIntOrNull() ?: 0)
                    Row(Modifier.width(22.dp), verticalAlignment = Alignment.Top) {
                        Cond(mine, (size + 1).sp, if (won) Color.White else Broadcast.Secondary)
                        if (tb != null && !won) Cond(tb, 9.sp, Broadcast.Secondary)
                    }
                }
                game?.let { Cond(it, 15.sp, Broadcast.Ink, modifier = Modifier.width(30.dp).background(Broadcast.Gold).padding(vertical = 1.dp), align = TextAlign.Center) }
            }
        }
    }
}

/** Golf: favourites pinned at the top (gold star), then the leaderboard: position, flag, player, to par, thru. */
@Composable
private fun Leaderboard(s: LiveSituation) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Cond("Pos", 12.sp, Broadcast.Secondary, modifier = Modifier.width(26.dp), weight = FontWeight.SemiBold)
            Cond("Player", 12.sp, Broadcast.Secondary, modifier = Modifier.weight(1f), weight = FontWeight.SemiBold)
            Cond("To par", 12.sp, Broadcast.Secondary, modifier = Modifier.width(44.dp), weight = FontWeight.SemiBold, align = TextAlign.End)
            Cond("Thru", 12.sp, Broadcast.Secondary, modifier = Modifier.width(40.dp), weight = FontWeight.SemiBold, align = TextAlign.End)
        }
        s.favourites.forEach { r -> key("fav:" + r.player) { GolfLine(r, pinned = true) } }
        if (s.favourites.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(Broadcast.Line))
        val pinned = s.favourites.map { it.player }.toSet()
        s.leaderboard.filter { it.player !in pinned }.take(10).forEach { r -> key(r.player) { GolfLine(r, pinned = false) } }
    }
}

@Composable
private fun GolfLine(
    r: GolfRow,
    pinned: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(if (pinned) Modifier.background(Broadcast.Gold.copy(alpha = 0.12f)) else Modifier).padding(vertical = 1.dp),
    ) {
        Cond(r.pos, 14.sp, Broadcast.Secondary, modifier = Modifier.width(26.dp), weight = FontWeight.SemiBold)
        if (pinned) Cond("★ ", 13.sp, Broadcast.Gold)
        flagEmoji(r.country)?.let { androidx.tv.material3.Text("$it ", fontSize = 12.sp) }
        BodyText(r.player, 13.sp, Color.White, modifier = Modifier.weight(1f), weight = FontWeight.SemiBold, maxLines = 1)
        val under = r.toPar?.startsWith("-") == true
        Cond(r.toPar ?: "", 16.sp, if (under) Broadcast.Gold else Color.White, modifier = Modifier.width(44.dp), align = TextAlign.End)
        Cond(teeOrThru(r.thru), 13.sp, Broadcast.Secondary, modifier = Modifier.width(40.dp), weight = FontWeight.SemiBold, align = TextAlign.End)
    }
}

/** Golf "thru": "F", a hole number, or a UTC tee time ("13:05Z") shown in local time. */
private fun teeOrThru(t: String?): String {
    t ?: return ""
    val m = Regex("^(\\d{1,2}):(\\d{2})Z$").find(t) ?: return t
    val utc = java.time.LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    val local = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).with(utc).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime()
    return String.format(Locale.US, "%02d:%02d", local.hour, local.minute)
}

/** The field: end zones in the team colours, 10-yard lines, the ball (white) and the line to gain (gold). */
@Composable
private fun FieldBar(
    pos: Float,
    s: LiveSituation,
    m: IptvMatch,
    pal: MatchPalette,
) {
    val dist = s.downDistance?.substringAfter("&", "")?.trim()?.toIntOrNull()
    val dir = if (s.possession == "away") -1f else 1f
    val (hc, ac) = barColors(pal)
    Box(
        Modifier.fillMaxWidth().height(15.dp).drawBehind {
            val ez = size.width / 12f
            drawRect(hc, size = Size(ez, size.height))
            drawRect(ac, topLeft = Offset(size.width - ez, 0f), size = Size(ez, size.height))
            val field = size.width - 2 * ez
            drawRect(Color(0xFF24572B), topLeft = Offset(ez, 0f), size = Size(field, size.height))
            for (i in 1..9) {
                val x = ez + field * i / 10f
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            val bx = ez + field * pos
            if (dist != null) {
                val gx = (bx + dir * field * dist / 100f).coerceIn(ez, ez + field)
                drawRect(Broadcast.Gold, topLeft = Offset(gx - 1.dp.toPx(), 0f), size = Size(1.5.dp.toPx(), size.height))
            }
            drawRect(Color.White, topLeft = Offset(bx - 1.dp.toPx(), 0f), size = Size(1.5.dp.toPx(), size.height))
        },
    )
}

@Composable
private fun BallChip(b: String) {
    val (bg, fg) =
        when (b.uppercase(Locale.ROOT)) {
            "W" -> Color(0xFFD64545) to Color.White
            "4" -> Color(0xFF2F7DE1) to Color.White
            "6" -> Broadcast.Gold to Broadcast.Ink
            "0", "." -> Broadcast.Loss to Broadcast.Body
            else -> Broadcast.Chip to Color.White
        }
    Box(Modifier.size(17.dp).background(bg), contentAlignment = Alignment.Center) {
        Cond(if (b == "0") "·" else b.take(2), 12.sp, fg)
    }
}

/** Q1…Q4 (OT) scores per team with a team-colour tick; the total in gold. */
@Composable
private fun PeriodGrid(
    periods: List<Pair<String, String>>,
    m: IptvMatch,
    d: MatchLive,
    layout: String?,
    pal: MatchPalette,
) {
    val prefix = if (layout == "basketball" || layout == "nfl") "Q" else "P"
    val shown = periods.take(5)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Spacer(Modifier.weight(1.4f))
            shown.forEachIndexed { i, _ -> Cond(if (prefix == "Q" && i >= 4) "OT" else "$prefix${i + 1}", 12.sp, Broadcast.Secondary, modifier = Modifier.weight(1f), weight = FontWeight.SemiBold, align = TextAlign.Center) }
            Cond("T", 12.sp, Broadcast.Secondary, modifier = Modifier.weight(1.1f), weight = FontWeight.SemiBold, align = TextAlign.Center)
        }
        listOf(Triple(m.home, shown.map { it.first }, "home" to (d.scoreHome ?: "")), Triple(m.away, shown.map { it.second }, "away" to (d.scoreAway ?: ""))).forEach { (t, scores, side) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(4.dp).height(13.dp).background(barColors(pal).let { if (side.first == "home") it.first else it.second }))
                    Cond(t?.short ?: t?.name ?: "", 14.sp, weight = FontWeight.SemiBold)
                }
                scores.forEach { Cond(it, 14.sp, modifier = Modifier.weight(1f), weight = FontWeight.SemiBold, align = TextAlign.Center) }
                Cond(side.second, 14.sp, Broadcast.Gold, modifier = Modifier.weight(1.1f), align = TextAlign.Center)
            }
        }
    }
}

// ---- Plays ---------------------------------------------------------------------------------------------------------

/** A play list: condensed clock, a team-colour tick, the text; scoring plays bold white with "+3". */
@Composable
private fun PlaysTab(
    plays: List<LivePlay>,
    matchId: String,
    pal: MatchPalette,
) {
    if (plays.isEmpty()) {
        Note("No plays yet.")
        return
    }
    val shown = plays.take(9)
    val fresh = rememberFresh("plays:$matchId", shown.map { it.key })
    shown.forEach { p ->
        key(p.key) {
            SlideInNew(p.key, p.key in fresh) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        Cond(listOfNotNull(p.period, p.clock).joinToString(" "), 13.sp, Broadcast.Secondary, modifier = Modifier.width(48.dp))
                        Box(Modifier.padding(top = 2.dp).width(3.dp).height(14.dp).background(if (p.team != null) pal.side(p.team) else Color.Transparent))
                        BodyText(
                            p.text,
                            if (p.scoring) 14.sp else 13.sp,
                            if (p.scoring) Color.White else Color(0xFFDADDE3),
                            modifier = Modifier.weight(1f),
                            weight = if (p.scoring) FontWeight.Bold else FontWeight.Medium,
                        )
                        if (p.scoring && p.points != null && p.points > 0) Cond("+${p.points}", 14.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 5.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1C212A)))
                }
            }
        }
    }
}

// ---- Odds ----------------------------------------------------------------------------------------------------------

/** One line: the favourite and its probability in gold (odometer), the source. */
@Composable
private fun OddsLine(
    mk: LiveMarket,
    m: IptvMatch,
) {
    val homeFav = (mk.home ?: 0.0) >= (mk.away ?: 0.0)
    val team = if (homeFav) m.home else m.away
    // Winner markets: the leading outcome
    val leader = mk.outcomes.firstOrNull()?.takeIf { mk.isWinner }
    val p = leader?.p ?: if (homeFav) mk.home else mk.away
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Broadcast.Line))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            Icon(BroadcastIcons.Odds, contentDescription = null, tint = Broadcast.Gold, modifier = Modifier.size(14.dp))
            BodyText(leader?.name ?: team?.short ?: team?.name ?: "", 13.sp, Color.White, weight = FontWeight.SemiBold, maxLines = 1)
            p?.let { Odometer(pct(it), 17.sp, Broadcast.Gold) }
            BodyText(listOfNotNull(mk.source?.replaceFirstChar { it.uppercase() }, mk.volume?.let { money(it) }, mk.updatedAt?.let { ago(nowSec() - it) }).joinToString(" · "), 12.sp, Broadcast.Secondary, maxLines = 1)
            if (mk.thin) ThinPill()
        }
    }
}

@Composable
private fun OddsTab(
    mk: LiveMarket?,
    m: IptvMatch,
    pal: MatchPalette,
    full: Boolean,
) {
    if (mk != null && mk.isWinner) {
        WinnerList(mk.outcomes, pal)
        if (full && mk.series.size >= 2) {
            SectionHead("Leader · last 30 min")
            Sparkline(mk.series, if (pal.known) pal.home else Broadcast.Gold)
        }
        SourceLine(mk)
        mk.question?.let { BodyText(it, 12.5.sp) }
        return
    }
    if (mk == null || (mk.home == null && mk.away == null)) {
        Note("No market for this match.")
        return
    }
    val h = mk.home ?: 0.0
    val a = mk.away ?: 0.0
    val dr = mk.draw ?: 0.0
    val (hc, ac) = barColors(pal)
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Cond(m.home?.short ?: m.home?.name ?: "Home", 14.sp, Broadcast.Secondary, weight = FontWeight.SemiBold)
            mk.home?.let { Odometer(pct(it), 24.sp, if (h >= a) Broadcast.Gold else Color.White) }
        }
        if (mk.draw != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Cond("Draw", 14.sp, Broadcast.Secondary, weight = FontWeight.SemiBold)
                Odometer(pct(dr), 20.sp, Color.White)
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
            Cond(m.away?.short ?: m.away?.name ?: "Away", 14.sp, Broadcast.Secondary, weight = FontWeight.SemiBold)
            mk.away?.let { Odometer(pct(it), 24.sp, if (a > h) Broadcast.Gold else Color.White) }
        }
    }
    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.weight(h.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(hc))
        if (dr > 0) Box(Modifier.weight(dr.toFloat()).fillMaxHeight().background(Broadcast.Loss))
        Box(Modifier.weight(a.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(ac))
    }
    if (full && mk.series.size >= 2) {
        SectionHead("Favourite · last 30 min")
        Sparkline(mk.series, if (h >= a) hc else ac)
    }
    SourceLine(mk)
    mk.question?.let { BodyText(it, 12.5.sp) }
}

/** "Polymarket · $1.2M traded · 30 s ago", with the THIN MARKET pill when the market is thin. */
@Composable
private fun SourceLine(mk: LiveMarket) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BodyText(
            listOfNotNull(
                mk.source?.replaceFirstChar { it.uppercase() },
                mk.volume?.let { "${money(it)} traded" },
                mk.updatedAt?.let { ago(nowSec() - it) },
            ).joinToString(" · "),
            12.sp,
            Broadcast.Secondary,
            maxLines = 1,
        )
        if (mk.thin) ThinPill()
    }
}

/** Grey caps "THIN MARKET" (plan "thin markets"): little money behind these odds. */
@Composable
internal fun ThinPill() =
    Cond("Thin market", 11.sp, Broadcast.Secondary, modifier = Modifier.border(1.dp, Color(0xFF5A6372)).padding(horizontal = 4.dp), weight = FontWeight.SemiBold, tracking = 0.06f)

/** A winner market (plan "Polymarket coverage round 2"): ranked outcomes with their probability and a bar; the leader in gold. */
@Composable
internal fun WinnerList(
    outcomes: List<Outcome>,
    pal: MatchPalette,
) {
    val bar = if (pal.known) pal.home else Broadcast.Gold
    val top = outcomes.maxOfOrNull { it.p }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        outcomes.forEachIndexed { i, (name, p, fav) ->
            key(name) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Cond("${i + 1}", 14.sp, Broadcast.Secondary, modifier = Modifier.width(16.dp), align = TextAlign.End)
                        BodyText((if (fav) "★ " else "") + name, 13.sp, if (fav) Broadcast.Gold else Color.White, modifier = Modifier.weight(1f), weight = if (i == 0 || fav) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                        Odometer(pct(p), 16.sp, if (i == 0) Broadcast.Gold else Color.White)
                    }
                    // The bar is relative to the leader so the order reads at a glance
                    Box(Modifier.padding(start = 22.dp).fillMaxWidth().height(5.dp).background(Broadcast.Line)) {
                        Box(Modifier.fillMaxWidth((p / top).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(if (i == 0) bar else bar.copy(alpha = 0.55f)))
                    }
                }
            }
        }
    }
}

/** The probability series as a line (drawn, not composed per point); a faint 50% line. */
@Composable
private fun Sparkline(
    series: List<MarketPoint>,
    color: Color,
) {
    val pts = remember(series) { series.sortedBy { it.t } }
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
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
private fun ChatTab(d: MatchLive) {
    if (d.comments.isEmpty()) {
        Note("No match thread comments.")
        return
    }
    d.chatSource?.let { Note("From $it") }
    val shown = d.comments.take(8)
    val fresh = rememberFresh("chat", shown.map { "${it.author}|${it.text}" })
    shown.forEach { c ->
        val k = "${c.author}|${c.text}"
        key(k) {
            SlideInNew(k, k in fresh) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.author?.let { Cond(it, 13.sp, Broadcast.Gold, weight = FontWeight.SemiBold) }
                    BodyText(c.text, 13.sp, Color(0xFFDADDE3), maxLines = 3)
                }
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

/** A new play or comment slides down with a short gold glow that fades (drawn: no recomposition per frame). */
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
    Box(
        Modifier
            .graphicsLayer {
                translationY = (1f - enter.value) * -16.dp.toPx()
                alpha = enter.value
            }.drawBehind {
                val g = glow.value
                if (g > 0f) drawRoundRect(Broadcast.Gold.copy(alpha = 0.2f * g), cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()))
            },
    ) { content() }
}

@Composable
private fun Note(text: String) = BodyText(text, 12.sp, Broadcast.Secondary)

internal fun pct(p: Double) = "${Math.round(p * 100)}%"

private fun flagLabel(flag: String) =
    when (flag) {
        "yellow" -> "Yellow flag"
        "sc" -> "Safety car"
        "vsc" -> "Virtual safety car"
        "red" -> "Red flag"
        "chequered" -> "Chequered flag"
        else -> flag
    }

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
