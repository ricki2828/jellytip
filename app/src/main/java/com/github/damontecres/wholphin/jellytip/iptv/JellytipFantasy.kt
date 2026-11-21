package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/*
 * Fantasy live scoring for NFL (~/ai/plans/jellytip-live-sport.md): `GET /v1/fantasy/live` (Sleeper, linked on the
 * web; no settings on TV). Feeds the live panel's Fantasy tab, the player banner chip, scoring toasts and the
 * "3 of yours" pill on NFL Live Sport tiles. Nothing shows without a linked account; a missing route is tried again
 * after 10 min. Plain StateFlow (snapshot rule).
 */

data class FantasyStarter(
    /** me | opp */
    val side: String,
    val name: String,
    val pos: String?,
    val nflTeam: String?,
    val points: Double?,
    /** The match (matchInfo.id, compared verbatim; plan "fantasy ids and shape"). */
    val matchId: String?,
    val gameState: String?,
    /** Catalogue player id, when sent (last scoring play is matched by it first). */
    val id: String? = null,
)

data class FantasyLeague(
    val id: String,
    val name: String,
    val meTeam: String?,
    val mePoints: Double?,
    val meProjected: Double?,
    val oppTeam: String?,
    val oppPoints: Double?,
    val starters: List<FantasyStarter>,
) {
    /** "CCP" from "CCP Dynasty": the chip's short name. */
    val short get() = name.split(' ').firstOrNull()?.takeIf { it.isNotBlank() } ?: name

    val scoreLine get() = "${pts(mePoints)} – ${pts(oppPoints)}"
}

data class FantasyEvent(
    val at: String,
    val league: String?,
    val name: String,
    val delta: Double,
    val side: String?,
    /** The play from the ESPN play-by-play ("TD catch, 34 yds"); null = none matched. */
    val reason: String? = null,
    val playerId: String? = null,
    val nflTeam: String? = null,
)

data class FantasyLive(
    val week: Int?,
    val leagues: List<FantasyLeague>,
    val events: List<FantasyEvent>,
)

internal fun pts(p: Double?): String = p?.let { String.format(Locale.US, "%.1f", it) } ?: "–"

/** Same match: ids are compared verbatim (plan "fantasy ids and shape"). */
internal fun sameGame(
    starterMatchId: String?,
    matchId: String?,
): Boolean = !starterMatchId.isNullOrBlank() && starterMatchId == matchId

/** NFL: `matchInfo.leagueKey == "nfl"` (plan "fantasy, round 2"); a match without the key falls back to its league name. */
internal fun isNfl(match: IptvMatch?): Boolean =
    match != null && (match.leagueKey?.equals("nfl", ignoreCase = true) ?: match.league.equals("NFL", ignoreCase = true))

object FantasyParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.dbl(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun events(a: org.json.JSONArray?): List<FantasyEvent> =
        if (a == null) {
            emptyList()
        } else {
            (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                FantasyEvent(e.str("at") ?: return@mapNotNull null, e.str("league"), e.str("name") ?: return@mapNotNull null, e.dbl("delta") ?: return@mapNotNull null, e.str("side"), e.str("reason"), playerId = e.str("playerId"), nflTeam = e.str("nflTeam"))
            }
        }

    /** `GET /v1/fantasy/game/{matchId}`: `{matchId, leagues:[{id, name, me, opp, starters}]}` (same league shape). */
    fun game(json: String): List<FantasyLeague> = live(json).leagues

    fun live(json: String): FantasyLive {
        val o = JSONObject(json)
        val la = o.optJSONArray("leagues")
        val leagues =
            (0 until (la?.length() ?: 0)).mapNotNull { i ->
                val l = la!!.optJSONObject(i) ?: return@mapNotNull null
                val me = l.optJSONObject("me")
                val opp = l.optJSONObject("opp")
                val sa = l.optJSONArray("starters")
                FantasyLeague(
                    id = l.str("id") ?: return@mapNotNull null,
                    name = l.str("name") ?: "League",
                    meTeam = me?.str("team"),
                    mePoints = me?.dbl("points"),
                    meProjected = me?.dbl("projected"),
                    oppTeam = opp?.str("team"),
                    oppPoints = opp?.dbl("points"),
                    starters =
                        (0 until (sa?.length() ?: 0)).mapNotNull { j ->
                            val s = sa!!.optJSONObject(j) ?: return@mapNotNull null
                            FantasyStarter(s.str("side") ?: "me", s.str("name") ?: return@mapNotNull null, s.str("pos"), s.str("nflTeam"), s.dbl("points"), s.str("matchId"), s.str("gameState"), id = s.str("id") ?: s.str("playerId"))
                        },
                )
            }
        // Scoring events are top-level, each naming its league
        return FantasyLive(if (o.has("week")) o.optInt("week") else null, leagues, events(o.optJSONArray("events")))
    }
}

object JellytipFantasy {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _live = MutableStateFlow<FantasyLive?>(null)

    /** null = nothing linked, not loaded, or no route. */
    val live: StateFlow<FantasyLive?> = _live

    @Volatile
    private var lastLoad = 0L

    @Volatile
    private var missingUntil = 0L

    /** Load when older than [maxAgeMs] (and the route isn't known missing); fire-and-forget. */
    fun refresh(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        if (now < missingUntil || now - lastLoad < maxAgeMs) return
        lastLoad = now
        scope.launch {
            val api = JellytipApi.shared ?: return@launch
            val (code, body) = api.userCall("v1/fantasy/live", timeoutMs = 8_000) ?: return@launch
            when (code) {
                200 -> _live.value = runCatching { FantasyParse.live(body) }.getOrNull()?.takeIf { it.leagues.isNotEmpty() }
                404 -> {
                    missingUntil = System.currentTimeMillis() + RETRY_AFTER_MS
                    _live.value = null
                }
                else -> {}
            }
            JellytipTrace.log("iptv", "fantasy live -> $code (${_live.value?.leagues?.size ?: 0} leagues)")
        }
    }

    /** Both sides' starters in [matchId], per league (`GET /v1/fantasy/game/{id}`, id verbatim); null = unavailable. */
    suspend fun game(matchId: String): List<FantasyLeague>? {
        if (System.currentTimeMillis() < missingUntil) return null
        val api = JellytipApi.shared ?: return null
        val (code, body) = api.userCall("v1/fantasy/game/${java.net.URLEncoder.encode(matchId, "UTF-8")}", timeoutMs = 8_000) ?: return null
        if (code != 200) return null
        return runCatching { FantasyParse.game(body) }.getOrNull()
    }

    /** My starters playing in [matchId]. */
    fun myStartersIn(
        live: FantasyLive?,
        matchId: String?,
    ): Int =
        live?.leagues?.sumOf { l ->
            // RedZone (kind "multi"): every starter of yours playing now, from every game
            l.starters.count { it.side == "me" && if (isMulti(matchId)) it.gameState == "in" else sameGame(it.matchId, matchId) }
        } ?: 0

    /** The league to show for [matchId]: one with starters in that game, else the first. */
    fun leagueFor(
        live: FantasyLive?,
        matchId: String?,
    ): FantasyLeague? = live?.leagues?.let { ls -> ls.firstOrNull { l -> l.starters.any { sameGame(it.matchId, matchId) } } ?: ls.firstOrNull() }

    private const val RETRY_AFTER_MS = 10 * 60_000L
}

/** A whip-around programme (RedZone; plan "RedZone / whip-around mode"): fantasy covers every game. */
fun isMulti(matchId: String?) = matchId?.startsWith("multi:") == true

/**
 * The live panel's Fantasy tab (Broadcast): each league's matchup on a dark chip with a green edge, then "In this game"
 * (yours and your opponent's); in RedZone mode (a multi id) "Live now" across every game instead.
 */
@Composable
fun FantasyTab(
    live: FantasyLive,
    matchId: String?,
    /** The player's reveal clock: a per-game answer shows only once the delay has released its arrival (web 4e467fec). */
    reveal: (Double?) -> Boolean = { true },
) {
    val multi = isMulti(matchId)
    val revealNow by androidx.compose.runtime.rememberUpdatedState(reveal)
    // "In this game" from /v1/fantasy/game/{matchId} (60 s), else the live answer's starters for that match. Each answer
    // waits for the spoiler delay (stamped when it arrived), so points never run ahead of the picture.
    val game by androidx.compose.runtime.produceState<List<FantasyLeague>?>(null, matchId) {
        val id = matchId?.takeIf { !multi } ?: return@produceState
        val held = ArrayList<Pair<Double, List<FantasyLeague>>>()
        var nextFetch = 0L
        while (true) {
            val nowMs = System.currentTimeMillis()
            if (nowMs >= nextFetch) {
                nextFetch = nowMs + 60_000
                JellytipFantasy.game(id)?.let { held += (nowMs / 1000.0) to it }
                if (held.size > 10) held.removeAt(0)
            }
            val rel = held.lastOrNull { revealNow(it.first) }
            if (rel != null) {
                if (value !== rel.second) value = rel.second
                held.removeAll { it.first < rel.first }
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        live.leagues.forEach { l ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(Broadcast.Sub),
            ) {
                Box(Modifier.width(4.dp).height(28.dp).background(Broadcast.Win))
                BodyText(l.name, 13.sp, Broadcast.Body, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), weight = FontWeight.SemiBold, maxLines = 1)
                Cond(l.scoreLine, 16.sp, modifier = Modifier.padding(end = 8.dp))
            }
            val here =
                if (multi) {
                    l.starters.filter { it.gameState == "in" }
                } else {
                    game?.firstOrNull { it.id == l.id }?.starters ?: l.starters.filter { sameGame(it.matchId, matchId) }
                }
            // Matchup summary (web 1c129131): who leads, by how much, how many of yours are playing, your projection
            FantasyGameLogic.summaryLine(l)?.let { BodyText(it, 12.sp, Broadcast.Secondary, maxLines = 1) }
            if (here.isNotEmpty()) {
                Cond(if (multi) "Live now" else "In this game", 12.sp, Broadcast.Secondary, weight = FontWeight.SemiBold, tracking = 0.06f)
                here.sortedWith(compareBy({ if (it.side == "me") 0 else 1 }, { -(it.points ?: 0.0) })).forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.width(3.dp).height(14.dp).background(if (st.side == "me") Broadcast.Win else Color(0xFFE5534B)))
                        BodyText(
                            listOfNotNull(st.name, st.pos, st.nflTeam?.takeIf { multi }).joinToString(" · "),
                            13.sp,
                            if (st.side == "me") Color.White else Broadcast.Body,
                            modifier = Modifier.weight(1f),
                            weight = if (st.side == "me") FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                        Cond(pts(st.points), 15.sp)
                    }
                    // Your player's last scoring play (web 0d44f288: by player id, else full name + team; never a bare surname)
                    if (st.side == "me") {
                        FantasyGameLogic.lastEvent(live, l, st)?.let { ev ->
                            BodyText("Last: ${if (ev.delta >= 0) "+" else ""}${pts(ev.delta)}" + (ev.reason?.let { " · $it" } ?: ""), 11.sp, Broadcast.Secondary, modifier = Modifier.padding(start = 9.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** "3 of yours" on an NFL Live Sport tile. */
@Composable
fun FantasyPill(count: Int) {
    if (count <= 0) return
    Text(
        "$count of yours",
        color = Color.Black,
        fontFamily = JellytipFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        maxLines = 1,
        modifier = Modifier.background(Color(0xFFB5E3A1), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Fantasy ↔ game (web lib/fantasyGame.ts): a starter's last scoring play and the matchup summary. Pure; FantasyGameTest. */
object FantasyGameLogic {
    private fun norm(s: String?) = (s ?: "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * The starter's newest scoring event: by player id when both carry one, else by full name plus team (when the event
     * names one). An event belongs to a league by id; by name only when no other league shares that name.
     */
    fun lastEvent(
        live: FantasyLive,
        league: FantasyLeague,
        s: FantasyStarter,
    ): FantasyEvent? {
        val nameUnique = live.leagues.count { it.name == league.name } == 1
        val full = norm(s.name)
        return live.events
            .filter { e -> e.side == "me" && (e.league == league.id || (nameUnique && e.league == league.name)) }
            .filter { e ->
                if (e.playerId != null && s.id != null) {
                    e.playerId == s.id
                } else {
                    full.isNotEmpty() && norm(e.name) == full && (e.nflTeam == null || norm(e.nflTeam) == norm(s.nflTeam))
                }
            }.maxByOrNull { IptvParse.isoSeconds(it.at) ?: 0L }
    }

    /** "You lead by 14.6 · 3 playing · projected 112.4"; null without points. */
    fun summaryLine(l: FantasyLeague): String? {
        val me = l.mePoints ?: return null
        val opp = l.oppPoints ?: return null
        val d = me - opp
        val lead =
            when {
                kotlin.math.abs(d) < 0.05 -> "Level"
                d > 0 -> "You lead by ${pts(d)}"
                else -> "You trail by ${pts(-d)}"
            }
        val playing = l.starters.count { it.side == "me" && it.gameState == "in" }
        return listOfNotNull(lead, if (playing > 0) "$playing playing" else null, l.meProjected?.let { "projected ${pts(it)}" }).joinToString(" · ")
    }
}
