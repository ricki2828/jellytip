package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/*
 * Game state for the companion panel (web lib/gameState.ts, plan ~/ai/plans/jellytip-sport-companion.md §3
 * "Game-state content"), the viewer's side of a match (team-aware alerts), and the cricket scorecard / rugby league
 * timeline the server sends on /live. Pure; tested in SportGameStateTest.
 */

enum class GameKind { PRE, TIGHT, LIVE, BLOWOUT, BREAK, FINAL }

data class GameState(
    val kind: GameKind,
    /** nfl | nba | nrl | rugby | football | cricket | default */
    val sport: String,
    val margin: Int?,
    /** home | away | null (level / no score) */
    val leader: String?,
    val reason: String,
)

/** A rugby league scoring / sin-bin entry (server `timeline`); [at] epoch s gates it through the reveal clock. */
data class TimelineEntry(
    val id: String,
    val kind: String,
    val team: String?,
    val text: String,
    val clock: String?,
    val at: Double?,
)

data class CricketInnings(
    val name: String?,
    val team: String?,
    val runs: String?,
    val wickets: String?,
    val overs: String?,
    val current: Boolean,
)

data class CricketBat(
    val name: String,
    val runs: String?,
    val balls: String?,
    val fours: String?,
    val sixes: String?,
    val onStrike: Boolean,
)

data class CricketOver(
    val over: String?,
    val runs: String?,
    val balls: List<String>,
    val live: Boolean,
    val at: Double?,
)

/** The /live `scorecard` (cricket). */
data class CricketCard(
    val innings: List<CricketInnings>,
    val batters: List<CricketBat>,
    val bowling: String?,
    val partnership: String?,
    val target: String?,
    val need: String?,
    val lead: Int?,
    val rr: String?,
    val rrr: String?,
    val overs: List<CricketOver>,
)

/** One batter's line in the full scorecard (values are display strings; `out` is "c X b Y" / "not out"). */
data class FullBat(
    val name: String,
    val out: String,
    val runs: String?,
    val balls: String?,
    val fours: String?,
    val sixes: String?,
    val sr: String?,
    val atCrease: Boolean,
)

data class FullBowl(
    val name: String,
    val overs: String?,
    val maidens: String?,
    val runs: String?,
    val wickets: String?,
    val econ: String?,
)

data class FullFow(
    val wicket: Int?,
    val runs: String?,
    val overs: String?,
    val name: String?,
)

data class FullInnings(
    val n: Int,
    val title: String,
    val abbr: String?,
    val score: String?,
    val overs: String?,
    val result: String?,
    val target: String?,
    val current: Boolean,
    val batting: List<FullBat>,
    val didNotBat: List<String>,
    /** "10 (b 5, lb 3, w 1, nb 1)"; null = none sent. */
    val extras: String?,
    val fow: List<FullFow>,
    val bowling: List<FullBowl>,
)

/** The /live `fullCard` (cricket): every innings, newest first. Released only with the summary [CricketCard]. */
data class FullCard(
    val innings: List<FullInnings>,
)

/** One box-score table row. [total] = the team-totals row (drawn strong). */
data class BoxRow(
    val name: String,
    val pos: String?,
    val stats: List<String>,
    val total: Boolean,
)

data class BoxSection(
    val title: String,
    val labels: List<String>,
    val rows: List<BoxRow>,
)

data class BoxTeam(
    /** home | away */
    val side: String,
    val name: String?,
    val abbr: String?,
    val sections: List<BoxSection>,
    /** Basketball: did-not-play names. */
    val dnp: List<String>,
)

/** NFL team-stat comparison row. */
data class BoxCompare(
    val label: String,
    val home: String,
    val away: String,
)

/** A sheet tab: [key] is stable across refreshes ("compare" / "away" / "home"); the label is what the tab shows. */
data class BoxTab(
    val key: String,
    val label: String,
)

/** The /live `boxScore` (NFL / basketball): released only with the in-play snapshot, like [FullCard]. */
data class BoxScore(
    /** nfl | basketball */
    val kind: String,
    val compare: List<BoxCompare>,
    val teams: List<BoxTeam>,
) {
    /** NFL: "Team stats" first (when the server sent it), then away, home. Basketball: the two teams. */
    val tabs: List<BoxTab> =
        (if (compare.isNotEmpty()) listOf(BoxTab("compare", "Team stats")) else emptyList()) +
            teams.map { BoxTab(it.side, it.abbr ?: it.name ?: it.side) }
}

object SportParse {
    private fun t(v: Any?): String? =
        when (v) {
            is String -> v.takeIf { it.isNotBlank() }
            is Int, is Long -> v.toString()
            is Number -> v.toDouble().takeIf { it.isFinite() }?.let { d -> if (d == Math.floor(d)) d.toLong().toString() else d.toString() }
            else -> null
        }

    /** A decimal with a fixed number of places (strike rate 1, economy 2), whatever the server's rounding; null for a missing / non-finite value. */
    private fun JSONObject.dp(
        k: String,
        places: Int,
    ): String? = (opt(k) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { String.format(java.util.Locale.US, "%.${places}f", it) }

    private fun JSONObject.t(k: String) = if (has(k) && !isNull(k)) t(opt(k)) else null

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun side(v: String?) = v?.takeIf { it == "home" || it == "away" }

    private fun at(o: JSONObject): Double? = (o.opt("at") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: IptvParse.isoSeconds(o.t("at"))?.toDouble()

    fun timeline(a: JSONArray?): List<TimelineEntry> =
        a.objects().mapNotNull { o ->
            val id = o.t("id") ?: return@mapNotNull null
            val text = o.t("text") ?: o.t("label") ?: return@mapNotNull null
            TimelineEntry(id, o.t("kind") ?: "", side(o.t("team")), text, o.t("clock"), at(o))
        }

    fun scorecard(sc: JSONObject?): CricketCard? {
        sc ?: return null
        val bw = sc.optJSONObject("bowler")
        val ps = sc.optJSONObject("partnership")
        return CricketCard(
            innings = sc.optJSONArray("innings").objects().map { CricketInnings(it.t("name"), side(it.t("team")), it.t("runs"), it.t("wickets"), it.t("overs"), it.optBoolean("current", false)) },
            batters = sc.optJSONArray("batters").objects().mapNotNull { b -> b.t("name")?.let { CricketBat(it, b.t("runs"), b.t("balls"), b.t("fours"), b.t("sixes"), b.optBoolean("onStrike", false)) } },
            bowling = bw?.t("name")?.let { n -> listOfNotNull(n, bw.t("figures"), bw.t("overs")?.let { "($it ov)" }, bw.t("econ")?.let { "econ $it" }).joinToString(" ") },
            partnership = ps?.t("runs")?.let { "$it (${ps.t("balls") ?: "0"})" },
            target = sc.t("target"),
            need = sc.t("equation")?.replace(Regex("^need\\s+", RegexOption.IGNORE_CASE), ""),
            lead = (sc.opt("lead") as? Number)?.toInt()?.takeIf { it != 0 },
            rr = sc.t("rr"),
            rrr = sc.t("rrr"),
            overs =
                sc.optJSONArray("overs").objects().map { o ->
                    val balls = o.optJSONArray("balls")
                    CricketOver(o.t("over"), o.t("runs"), if (balls == null) emptyList() else (0 until balls.length()).mapNotNull { t(balls.opt(it)) }, o.optBoolean("live", false), at(o))
                },
        )
    }


    fun boxScore(b: JSONObject?): BoxScore? {
        b ?: return null
        val kind = b.t("kind")?.takeIf { it == "nfl" || it == "basketball" } ?: return null
        fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { t(a.opt(it)) ?: "" }
        val teams =
            b.optJSONArray("teams").objects().mapNotNull { tm ->
                val side = side(tm.t("team")) ?: return@mapNotNull null
                val sections =
                    tm.optJSONArray("sections").objects().mapNotNull { sec ->
                        val labels = strs(sec.optJSONArray("labels"))
                        val rows =
                            sec.optJSONArray("rows").objects().mapNotNull { r ->
                                val name = r.t("name") ?: return@mapNotNull null
                                BoxRow(name, r.t("pos"), strs(r.optJSONArray("stats")), r.optBoolean("total", false))
                            }
                        if (rows.isEmpty() || labels.isEmpty()) null else BoxSection(sec.t("title") ?: "", labels, rows)
                    }
                if (sections.isEmpty()) null else BoxTeam(side, tm.t("name"), tm.t("abbr"), sections, strs(tm.optJSONArray("dnp")).filter { it.isNotEmpty() })
            }
        if (teams.isEmpty()) return null
        val compare =
            b.optJSONArray("compare").objects().mapNotNull { c ->
                val l = c.t("label") ?: return@mapNotNull null
                val h = c.t("home") ?: return@mapNotNull null
                val a = c.t("away") ?: return@mapNotNull null
                BoxCompare(l, h, a)
            }
        return BoxScore(kind, compare, teams)
    }

    fun fullCard(fc: JSONObject?): FullCard? {
        val innings =
            fc?.optJSONArray("innings").objects().mapIndexed { idx, o ->
                val n = (o.opt("n") as? Number)?.toInt() ?: (idx + 1)
                val score = o.t("score") ?: o.t("runs")?.let { r -> o.t("wickets")?.let { w -> "$r/$w" } ?: r }
                val ex = o.optJSONObject("extras")
                val parts =
                    listOf("b" to "byes", "lb" to "legByes", "w" to "wides", "nb" to "noBalls", "pen" to "penalties")
                        .mapNotNull { (l, k) -> ex?.t(k)?.takeIf { it != "0" }?.let { "$l $it" } }
                val extras = ex?.t("total")?.let { tot -> if (parts.isEmpty()) tot else "$tot (${parts.joinToString(", ")})" }
                FullInnings(
                    n = n,
                    title = o.t("title") ?: o.t("teamName") ?: "Innings $n",
                    abbr = o.t("abbr"),
                    score = score,
                    overs = o.t("overs"),
                    result = o.t("result"),
                    target = o.t("target"),
                    current = o.optBoolean("current", false),
                    batting =
                        o.optJSONArray("batting").objects().mapNotNull { b ->
                            b.t("name")?.let { FullBat(it, b.t("out") ?: "", b.t("runs"), b.t("balls"), b.t("fours"), b.t("sixes"), b.dp("sr", 1), b.optBoolean("atCrease", false)) }
                        },
                    didNotBat = o.optJSONArray("didNotBat")?.let { a -> (0 until a.length()).mapNotNull { t(a.opt(it)) } } ?: emptyList(),
                    extras = extras,
                    fow = o.optJSONArray("fow").objects().map { f -> FullFow((f.opt("wicket") as? Number)?.toInt(), f.t("runs"), f.t("overs"), f.t("name")) },
                    bowling =
                        o.optJSONArray("bowling").objects().mapNotNull { b ->
                            b.t("name")?.let { FullBowl(it, b.t("overs"), b.t("maidens"), b.t("runs"), b.t("wickets"), b.dp("econ", 2)) }
                        },
                )
            }
        return if (innings.isEmpty()) null else FullCard(innings)
    }

    /** Wickets in hand from the situation (`wicketsInHand` / `wicketsLeft`, else 10 - `wickets`/`wicketsDown`/`wkts`). */
    fun wicketsInHand(s: JSONObject?): Int? {
        s ?: return null
        (s.opt("wicketsInHand") ?: s.opt("wicketsLeft"))?.let { if (it is Number) return it.toInt() }
        val down = s.t("wickets") ?: s.t("wicketsDown") ?: s.t("wkts") ?: s.optJSONObject("batting")?.t("wickets")
        val n = down?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: return null
        return if (n in 0..10) 10 - n else null
    }
}

/** Whose side is the viewer's team (alerts, recap "mine", blowout wording). */
object TeamAware {
    /** Kinds that are a team scoring: a favourite's one alerts in KEY mode and survives a blowout (web FlipAlerts SCORING). */
    val SCORING = setOf("goal", "try", "touchdown", "field_goal", "score", "conversion", "penalty_goal")

    private fun norm(s: String?) = (s ?: "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** `sport.teams` names (plain strings, or the `name` of object entries). */
    fun teamNames(sport: JSONObject?): List<String> {
        val a = sport?.optJSONArray("teams") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            when (val v = a.opt(i)) {
                is String -> v.takeIf { it.isNotBlank() }
                is JSONObject -> v.optString("name").takeIf { it.isNotBlank() }
                else -> null
            }
        } + (sport.optJSONArray("teamRefs")?.let { r -> (0 until r.length()).mapNotNull { r.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } } ?: emptyList())
    }

    /** teamId to leagueKey from `sport.teams` objects and `sport.teamRefs`. */
    fun teamRefs(sport: JSONObject?): List<Pair<String, String?>> {
        sport ?: return emptyList()
        val out = LinkedHashMap<String, String?>()
        for (k in listOf("teams", "teamRefs")) {
            val a = sport.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.opt("teamId")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() } ?: continue
                if (id !in out) out[id] = o.optString("leagueKey").takeIf { it.isNotBlank() }
            }
        }
        return out.toList()
    }

    data class Side(
        val name: String?,
        val short: String?,
        val teamId: String?,
    )

    /**
     * The viewer's side: a followed team id (league must agree when both state one), else an exact normalised name /
     * short-name match (web favouriteSide). null when neither side, or both sides, are followed.
     */
    fun favouriteSide(
        home: Side?,
        away: Side?,
        leagueKey: String?,
        names: List<String>,
        refs: List<Pair<String, String?>>,
    ): String? {
        if (home == null || away == null) return null
        fun byId(s: Side) = s.teamId != null && refs.any { (id, lk) -> id == s.teamId && (lk == null || leagueKey == null || lk.equals(leagueKey, true)) }
        val h = byId(home)
        val a = byId(away)
        if (h != a) return if (h) "home" else "away"
        if (h) return null
        val f = names.map(::norm).filter { it.isNotEmpty() }.toSet()
        fun byName(s: Side) = norm(s.name) in f || (s.short != null && norm(s.short) in f)
        val hn = byName(home)
        val an = byName(away)
        return if (hn && !an) "home" else if (an && !hn) "away" else null
    }

    /** A moment the viewer's team scored. */
    fun isFavouriteMoment(
        kind: String?,
        team: String?,
        favSide: String?,
    ): Boolean = favSide != null && team == favSide && (kind ?: "score") in SCORING
}

object GameStateLogic {
    private data class Q(
        val q: Int,
        val m: Int,
        val secsLeft: Int? = null,
    )

    private data class Mn(
        val min: Int,
        val m: Int,
    )

    private val NFL_BLOW = listOf(Q(3, 28), Q(4, 21), Q(4, 17, 480))
    private val NBA_BLOW = listOf(Q(3, 25), Q(4, 20), Q(4, 15, 240))
    private val MIN_RULES =
        mapOf(
            "nrl" to (listOf(Mn(40, 30), Mn(55, 24), Mn(68, 18)) to listOf(Mn(50, 6), Mn(65, 12))),
            "rugby" to (listOf(Mn(45, 35), Mn(60, 28), Mn(70, 21)) to listOf(Mn(55, 7), Mn(68, 14))),
            "football" to (listOf(Mn(50, 4), Mn(70, 3)) to listOf(Mn(60, 1))),
        )
    private const val WP_BLOWOUT = 0.95
    private const val WP_TIGHT_LOW = 0.35
    private const val WP_TIGHT_HIGH = 0.65

    fun sportOf(
        layout: String?,
        leagueKey: String?,
    ): String {
        val lk = (leagueKey ?: "").lowercase()
        when (layout) {
            "nfl" -> return "nfl"
            "basketball" -> return "nba"
            "football" -> return "football"
            "cricket" -> return "cricket"
            "rugby" -> return if (Regex("nrl|league|origin|rlwc|super-?league").containsMatchIn(lk)) "nrl" else "rugby"
            null, "" -> {}
            else -> return "default"
        }
        return when {
            Regex("^(nfl|ncaaf|college-football)").containsMatchIn(lk) -> "nfl"
            Regex("^(nba|wnba|ncaab|ncaaw|mens-college-basketball)").containsMatchIn(lk) -> "nba"
            Regex("nrl|origin|rugby-league|super-?league|rlwc").containsMatchIn(lk) -> "nrl"
            Regex("rugby|super-?rugby|six-?nations|premiership|urc|rwc").containsMatchIn(lk) -> "rugby"
            Regex("cricket|ipl|bbl|t20|odi|test").containsMatchIn(lk) -> "cricket"
            Regex("^(eng|esp|ger|ita|fra|usa|aus|uefa|fifa|conmebol|epl|ucl|mls|a-league|soccer)").containsMatchIn(lk) -> "football"
            else -> "default"
        }
    }

    fun cricketFormat(
        leagueKey: String?,
        period: String?,
    ): String {
        val t = "${leagueKey ?: ""} ${period ?: ""}".lowercase()
        return when {
            Regex("""\btest\b|\bday \d|first-class|shield""").containsMatchIn(t) -> "test"
            Regex("t20|ipl|bbl|psl|cpl|hundred").containsMatchIn(t) -> "t20"
            Regex("""odi|one-?day|\bcwc\b|champions""").containsMatchIn(t) -> "odi"
            else -> "test"
        }
    }

    fun periodNo(period: String?): Int? {
        val p = (period ?: "").trim().lowercase()
        if (p.isEmpty()) return null
        if (Regex("""(^|[^a-z])\d?ot\d?($|[^a-z])|overtime|extra time""").containsMatchIn(p)) {
            val n = Regex("""(\d)\s*ot|ot\s*(\d)""").find(p)
            return 4 + (n?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt() ?: 1)
        }
        Regex("""(\d)""").find(p)?.let { return it.groupValues[1].toInt() }
        return listOf(Regex("""first|\b1h\b""") to 1, Regex("""second|\b2h\b""") to 2, Regex("third") to 3, Regex("fourth") to 4).firstOrNull { it.first.containsMatchIn(p) }?.second
    }

    fun secsLeft(clock: String?): Int? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})(?:\.\d+)?\s*$""").find(clock ?: "") ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    fun matchMinute(
        clock: String?,
        period: String?,
    ): Int? {
        val c = (clock ?: "").trim()
        Regex("""^(\d{1,3})\s*\+\s*(\d{1,2})""").find(c)?.let { return it.groupValues[1].toInt() + it.groupValues[2].toInt() }
        val m = Regex("""^(\d{1,3})(?:'|:\d{2}|′|\s*min)?""").find(c)
        if (m != null && (c.contains("'") || c.contains(":") || c.contains("′") || Regex("""^\d{1,3}$""").matches(c))) return m.groupValues[1].toInt()
        return when (periodNo(period)) {
            1 -> 1
            2 -> 41
            else -> null
        }
    }

    private val BREAK_RE = Regex("""half.?time|\bht\b|innings break|\binterval\b|\bbreak\b|\blunch\b|\btea\b|\bdrinks\b|\bstumps\b|\bdinner\b|strategic time""", RegexOption.IGNORE_CASE)
    private val END_HALF_RE = Regex("""end of (?:the )?(?:2nd|second)\b""", RegexOption.IGNORE_CASE)
    private val FINAL_RE = Regex("""^(post|ft|result)$|final|full.?time|complete|ended|closed""", RegexOption.IGNORE_CASE)
    private val LIVE_RE = Regex("""^(in|live)$|in.?progress|half.?time|break""", RegexOption.IGNORE_CASE)
    private val PRE_RE = Regex("""^(pre|ns)$|scheduled|upcoming|not.?started""", RegexOption.IGNORE_CASE)

    fun isBreak(
        period: String?,
        clock: String?,
        sport: String,
    ): Boolean {
        val t = "${period ?: ""} ${clock ?: ""}"
        if (BREAK_RE.containsMatchIn(t)) return true
        return (sport == "nfl" || sport == "nba") && END_HALF_RE.containsMatchIn(t)
    }

    fun classify(
        sport: String,
        status: String?,
        home: Int?,
        away: Int?,
        clock: String?,
        period: String?,
        winProb: Double? = null,
        rr: Double? = null,
        rrr: Double? = null,
        leagueKey: String? = null,
    ): GameState {
        val margin = if (home != null && away != null) abs(home - away) else null
        val leader = if (home != null && away != null && margin != null && margin > 0) (if (home > away) "home" else "away") else null
        fun out(
            k: GameKind,
            r: String,
        ) = GameState(k, sport, margin, leader, r)
        val st = (status ?: "").trim()
        if (st.isNotEmpty() && FINAL_RE.containsMatchIn(st)) return out(GameKind.FINAL, "status final")
        if (st.isNotEmpty() && PRE_RE.containsMatchIn(st)) return out(GameKind.PRE, "status pre")
        if (st.isEmpty() && margin == null && clock == null && period == null) return out(GameKind.PRE, "no status, score or clock")
        if (st.isNotEmpty() && !LIVE_RE.containsMatchIn(st) && margin == null && clock == null) return out(GameKind.LIVE, "status $st")
        if (isBreak("$st ${period ?: ""}", clock, sport)) return out(GameKind.BREAK, "period text says break")
        if (margin == null) return out(GameKind.LIVE, "no score")
        val wp = winProb?.let { max(it, 1 - it) }
        val wpTight = winProb != null && winProb >= WP_TIGHT_LOW && winProb <= WP_TIGHT_HIGH
        val q = periodNo(period)
        val left = secsLeft(clock)
        when (sport) {
            "nfl", "nba" -> {
                val nba = sport == "nba"
                val rules = if (nba) NBA_BLOW else NFL_BLOW
                val inRange = q != null && q >= 3 && (!nba || q <= 4)
                if (inRange && (rules.any { r -> margin >= r.m && q!! >= r.q && (r.secsLeft == null || (left != null && left <= r.secsLeft)) } || (wp != null && wp >= WP_BLOWOUT))) {
                    return out(GameKind.BLOWOUT, "margin $margin in Q$q")
                }
                if (q != null) {
                    val tight =
                        if (nba) {
                            q >= 5 || (q == 4 && margin <= 8) || (q == 3 && margin <= 3) || (q >= 4 && wpTight)
                        } else {
                            (q >= 4 && margin <= 8) || (q >= 3 && margin <= 3) || (q >= 4 && wpTight)
                        }
                    if (tight) return out(GameKind.TIGHT, "margin $margin in Q$q")
                }
                return out(GameKind.LIVE, "default")
            }
            "nrl", "rugby", "football" -> {
                val (blow, tight) = MIN_RULES.getValue(sport)
                val min = matchMinute(clock, period) ?: return out(GameKind.LIVE, "no minute")
                if (blow.any { margin >= it.m && min >= it.min } || (wp != null && wp >= WP_BLOWOUT && min >= 50)) return out(GameKind.BLOWOUT, "margin $margin at $min'")
                if (tight.any { min >= it.min && margin <= it.m } || (wpTight && min >= 60)) return out(GameKind.TIGHT, "margin $margin at $min'")
                return out(GameKind.LIVE, "default")
            }
            "cricket" -> {
                val fmt = cricketFormat(leagueKey, period)
                if (fmt == "test") return out(GameKind.LIVE, "test match")
                val (tightT, hopeless, cruising) = if (fmt == "t20") Triple(1.5, 6.0, 5.0) else Triple(1.0, 4.0, 3.0)
                if (rr == null || rrr == null || rr <= 0) return out(GameKind.LIVE, "no chase")
                val gap = rrr - rr
                if (gap >= hopeless || -gap >= cruising) return out(GameKind.BLOWOUT, "required $rrr v current $rr")
                if (abs(gap) <= tightT) return out(GameKind.TIGHT, "required $rrr v current $rr")
                return out(GameKind.LIVE, "chase")
            }
            else -> return out(GameKind.LIVE, "default")
        }
    }

    /** State for a /live answer; [allowWinProb] only with spoilers on. */
    fun of(
        d: MatchLive,
        leagueKey: String?,
        fixtureStatus: String?,
        allowWinProb: Boolean,
    ): GameState {
        val sport = sportOf(d.layout, leagueKey)
        return classify(
            sport,
            // A light answer carries no status; a fixture fetched before kick-off still says "pre" once there's a score
            d.status ?: fixtureStatus?.takeUnless { it == "pre" && (d.scoreHome != null || d.clock != null) },
            d.scoreHome?.toIntOrNull(),
            d.scoreAway?.toIntOrNull(),
            d.clock,
            d.period,
            if (allowWinProb) d.winProbHome else null,
            d.situation?.runRate,
            d.situation?.requiredRate,
            leagueKey,
        )
    }

    fun scoreGapText(
        margin: Int,
        sport: String,
    ): String {
        val per = if (sport == "nrl") 6 else 7
        val n = max(1, ceil(margin / per.toDouble()).toInt())
        return when (n) {
            1 -> "one score"
            2 -> "two scores"
            else -> "$n scores"
        }
    }

    private fun pair(
        d: MatchLive,
        vararg labels: String,
    ) = d.situation?.pairs?.firstOrNull { p -> labels.any { p.label.equals(it, ignoreCase = true) } }

    /** The rows that lead a tight game, per sport (web deciderRows); up to 5, plus win probability with spoilers on. */
    fun deciderRows(
        d: MatchLive,
        st: GameState,
        home: String,
        away: String,
        allowWinProb: Boolean,
        wicketsInHand: Int? = null,
    ): List<Pair<String, String>> {
        val s = d.situation
        val rows = ArrayList<Pair<String, String>>()
        val lead = st.leader?.let { if (it == "home") home else away }
        val gap = st.margin?.let { if (it == 0) "Level" else "${lead ?: "Lead"} by $it" }
        when (st.sport) {
            "nfl" -> {
                s?.downDistance?.let { rows += "Down & distance" to it }
                s?.yardLine?.let { rows += "Ball on" to if (s.redZone) "$it · red zone" else it }
                s?.possession?.let { rows += "Possession" to if (it == "home") home else away }
                pair(d, "Timeouts")?.let { rows += "Timeouts" to "$home ${it.home} · $away ${it.away}" }
                gap?.let { rows += "Score" to it }
            }
            "nba" -> {
                s?.run?.let { rows += "Run" to it }
                pair(d, "Fouls", "Team fouls")?.let { rows += "Team fouls" to "$home ${it.home} · $away ${it.away}" }
                gap?.let { rows += "Score" to it }
            }
            "cricket" -> {
                s?.requiredRate?.let { rrr -> rows += "Required rate" to (s.runRate?.let { "${fmt(rrr)} (now ${fmt(it)})" } ?: fmt(rrr)) }
                wicketsInHand?.let { rows += "Wickets in hand" to it.toString() }
                s?.equation?.let { rows += "Equation" to it }
            }
            "football" -> {
                val min = matchMinute(d.clock, d.period)
                rows += "Clock" to (min?.let { "$it'" } ?: d.clock ?: "Live")
                gap?.let { rows += "Score" to it }
                pair(d, "Cards", "Red cards", "Yellow cards")?.let { rows += "Cards" to "$home ${it.home} · $away ${it.away}" }
            }
            "nrl", "rugby" -> {
                gap?.let { g -> rows += "Score" to (st.margin?.takeIf { it > 0 }?.let { "$g · ${scoreGapText(it, st.sport)}" } ?: g) }
                matchMinute(d.clock, d.period)?.let { rows += "Time left" to "${max(0, 80 - it)}'" }
                d.plays.firstOrNull { it.scoring }?.let { p -> rows += "Last score" to listOfNotNull(p.clock, p.text).joinToString(" ") }
                pair(d, "Cards")?.takeIf { it.home != "0" || it.away != "0" }?.let { rows += "Cards" to "$home ${it.home} · $away ${it.away}" }
            }
            else -> gap?.let { rows += "Score" to it }
        }
        val out = rows.take(5).toMutableList()
        if (allowWinProb) d.winProbHome?.let { out += "Win probability" to "$home ${(it * 100).toInt()}%" }
        return out
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /** "Your team leads 112–78 · Q4 3:10" / "Lakers lead …". */
    fun blowoutLine(
        d: MatchLive,
        st: GameState,
        home: String,
        away: String,
        fav: String?,
    ): String {
        val h = d.scoreHome?.toIntOrNull()
        val a = d.scoreAway?.toIntOrNull()
        val leader = st.leader
        if (h == null || a == null || leader == null) return "Decided."
        val (hi, lo) = if (leader == "home") h to a else a to h
        val name = if (leader == "home") home else away
        val who = if (fav != null && fav == leader) "Your team leads" else "$name lead"
        val whenText = listOfNotNull(d.period, d.clock).joinToString(" ")
        return "$who $hi–$lo" + if (whenText.isNotBlank()) " · $whenText" else ""
    }

    data class Recap(
        val whenText: String,
        val text: String,
        val points: Int?,
        val team: String?,
        val mine: Boolean,
    )

    /** Scoring plays oldest first (server sends newest first), the viewer's side flagged; at most [max]. */
    fun recapPlays(
        d: MatchLive,
        fav: String?,
        max: Int = 6,
    ): List<Recap> =
        d.plays
            .filter { it.scoring }
            .take(max)
            .reversed()
            .map { Recap(listOfNotNull(it.period, it.clock).joinToString(" "), it.text, it.points, it.team, fav != null && it.team == fav) }

    fun resultLine(
        d: MatchLive,
        home: String,
        away: String,
    ): String? {
        val h = d.scoreHome ?: return null
        val a = d.scoreAway ?: return null
        return "$home $h–$a $away"
    }

    /**
     * The live fixture to suggest instead of a blowout: server order kept; only live games with a channel and a score
     * that is tight or plain live; a favourite beats tight beats live. The game being watched is excluded.
     */
    fun closerGame(
        items: List<FxItem>,
        exclude: Set<String>,
        nowS: Long,
    ): FxItem? =
        items
            .withIndex()
            .filter { (_, f) -> f.status == "in" && f.score != null && f.fid !in exclude && (f.matchId == null || f.matchId !in exclude) && SportFixtures.pickChannel(f, nowS).channel != null }
            .map { (i, f) -> Triple(i, f, classify(sportOf(null, f.leagueKey), "in", f.score!!.home, f.score.away, f.score.clock, f.score.period, leagueKey = f.leagueKey)) }
            .filter { it.third.kind == GameKind.TIGHT || it.third.kind == GameKind.LIVE }
            .sortedWith(compareByDescending<Triple<Int, FxItem, GameState>> { it.second.favourite }.thenByDescending { it.third.kind == GameKind.TIGHT }.thenBy { it.first })
            .firstOrNull()
            ?.second

    /** Rugby league timeline entries the clock has released (one without a time follows the snapshot). */
    fun releasedTimeline(
        t: List<TimelineEntry>,
        reveal: (Double?) -> Boolean,
    ): List<TimelineEntry> = t.filter { reveal(it.at) }

    /**
     * The full scorecard follows the summary scorecard's release exactly: it is one snapshot-level block (no per-ball stamps),
     * so it is shown only while the released summary [card] is, and it rides in the same released snapshot.
     */
    fun releasedFullCard(
        full: FullCard?,
        card: CricketCard?,
    ): FullCard? = if (card == null) null else full


    /**
     * The box score is one snapshot-level block (no per-play stamps), so it rides in the released snapshot exactly like the full scorecard
     * and is hidden in a replay or while a spoiler notice is up.
     */
    fun releasedBoxScore(
        box: BoxScore?,
        replay: Boolean,
        spoilerNotice: Boolean,
    ): BoxScore? = if (replay || spoilerNotice) null else box

    /** The selected box tab as an index: keyed by the tab key so a refresh does not move the selection; none/unknown = the first. */
    fun boxTabIndex(
        box: BoxScore?,
        key: String?,
    ): Int = box?.tabs?.indexOfFirst { it.key == key }?.takeIf { it >= 0 } ?: 0

    /** The full-time card may come up only when the game is final and unshown, not in a replay, and not while the full scorecard sheet owns OK. */
    fun fullTimeCardDue(
        finalNotShown: Boolean,
        replay: Boolean,
        fullCardOpen: Boolean,
    ): Boolean = finalNotShown && !replay && !fullCardOpen

    /** The scorecard with only the overs the clock has released. */
    fun releasedCard(
        c: CricketCard?,
        reveal: (Double?) -> Boolean,
    ): CricketCard? = c?.let { cc -> if (cc.overs.all { reveal(it.at) }) cc else cc.copy(overs = cc.overs.filter { reveal(it.at) }) }
}
