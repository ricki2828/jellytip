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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Game-state content for the companion's Stats tab (web LiveDrawer PreCard / DecideCard / BlowoutCard / RecapCard /
 * FinalCard), the cricket scorecard (web scorecardBlocks) and the rugby league timeline (web Plays tab). Draw only;
 * the logic is in SportGameState.kt.
 */

private val Mine = Color(0xFF22C55E)

@Composable
private fun KvRow(
    label: String,
    value: String,
    highlight: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cond(label, 13.sp, Broadcast.Secondary, modifier = Modifier.width(110.dp))
        BodyText(value, 14.sp, if (highlight) Mine else Color.White, modifier = Modifier.weight(1f), weight = FontWeight.SemiBold, maxLines = 2)
    }
}

/** The card on top of Stats for the game's state; nothing for plain live / unknown. */
@Composable
fun GameStateCard(
    d: MatchLive,
    st: GameState,
    home: String,
    away: String,
    fav: String?,
    allowWinProb: Boolean,
    closer: FxItem?,
) {
    when (st.kind) {
        GameKind.TIGHT -> {
            val rows = GameStateLogic.deciderRows(d, st, home, away, allowWinProb, d.wicketsInHand)
            if (rows.isEmpty()) return
            SectionHead(if (fav != null) "What decides it · your team" else "What decides it")
            rows.forEach { (l, v) -> key(l) { KvRow(l, v) } }
        }
        GameKind.BLOWOUT -> {
            SectionHead("Decided")
            BodyText(GameStateLogic.blowoutLine(d, st, home, away, fav), 15.sp, Color.White, weight = FontWeight.SemiBold)
            closer?.let { c ->
                val note = SportFixtures.pickChannel(c, nowSec()).channel?.name
                BodyText("Closer game: ${c.title}" + (note?.let { " on $it" } ?: "") + "  ·  OK to switch", 13.sp, Broadcast.Gold, maxLines = 2)
            }
        }
        GameKind.BREAK, GameKind.FINAL -> {
            val final = st.kind == GameKind.FINAL
            SectionHead(if (final) "Full time" else "Break recap")
            if (final) GameStateLogic.resultLine(d, home, away)?.let { BodyText(it, 16.sp, Color.White, weight = FontWeight.Bold) }
            val recap = GameStateLogic.recapPlays(d, fav)
            if (recap.isEmpty() && !final) BodyText("No scoring plays yet.", 13.sp, Broadcast.Secondary)
            recap.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Cond(r.whenText, 12.sp, Broadcast.Secondary, modifier = Modifier.width(52.dp))
                    BodyText(r.text, 13.sp, if (r.mine) Mine else Color.White, modifier = Modifier.weight(1f), maxLines = 2)
                    r.points?.takeIf { it > 0 }?.let { Cond("+$it", 13.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 4.dp)) }
                }
            }
        }
        GameKind.PRE -> BodyText("Not started yet.", 13.sp, Broadcast.Secondary)
        GameKind.LIVE -> {}
    }
}

/** Cricket scorecard: innings totals, at the crease, bowling / partnership / target, the last three overs. */
@Composable
fun ScorecardBlocks(
    c: CricketCard,
    home: String,
    away: String,
) {
    if (c.innings.isNotEmpty()) {
        SectionHead("Innings")
        c.innings.forEach { i ->
            val name = (i.name ?: if (i.team == "away") away else home) + if (i.current) " *" else ""
            KvRow(name, "${i.runs ?: "–"}/${i.wickets ?: "–"}  (${i.overs ?: "–"} ov)", highlight = i.current)
        }
    }
    if (c.batters.isNotEmpty()) {
        SectionHead("At the crease")
        c.batters.forEach { b ->
            val extra = if (b.fours != null || b.sixes != null) " · ${b.fours ?: 0}x4 ${b.sixes ?: 0}x6" else ""
            KvRow(b.name + if (b.onStrike) " *" else "", "${b.runs ?: "0"} (${b.balls ?: "0"})$extra")
        }
    }
    val kv =
        listOfNotNull(
            c.bowling?.let { "Bowling" to it },
            c.partnership?.let { "Partnership" to it },
            c.target?.let { "Target" to it },
            c.need?.let { "Need" to it },
            c.lead?.let { (if (it > 0) "Lead" else "Trail") to kotlin.math.abs(it).toString() },
            c.rr?.let { "Run rate" to it },
            c.rrr?.let { "Required" to it },
        )
    kv.forEach { (l, v) -> KvRow(l, v) }
    c.overs.takeLast(3).reversed().forEach { o ->
        if (o.balls.isEmpty()) return@forEach
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Cond("Over ${o.over ?: ""} · ${o.runs ?: 0} runs" + if (o.live) " · live" else "", 12.sp, Broadcast.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                o.balls.forEach { b ->
                    val hot = b == "4" || b == "6" || b.equals("W", true)
                    Cond(b, 13.sp, if (hot) Broadcast.Ink else Color.White, modifier = Modifier.background(if (hot) Broadcast.Gold else Broadcast.Chip).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
        }
    }
}

/** Rugby league timeline (tries, goals, sin bins), newest first. */
@Composable
fun TimelineList(
    t: List<TimelineEntry>,
    pal: MatchPalette,
    fav: String?,
) {
    t.reversed().take(10).forEach { e ->
        key(e.id) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    Cond(e.clock ?: "", 13.sp, Broadcast.Secondary, modifier = Modifier.width(48.dp))
                    Box(Modifier.padding(top = 2.dp).width(3.dp).height(14.dp).background(if (e.team != null) pal.side(e.team) else Color.Transparent))
                    val pts = mapOf("try" to 4, "conversion" to 2, "penalty_goal" to 2, "field_goal" to 1)[e.kind]
                    BodyText(e.text, 14.sp, if (fav != null && e.team == fav) Mine else Color.White, modifier = Modifier.weight(1f), weight = if (pts != null) FontWeight.Bold else FontWeight.Medium)
                    pts?.let { Cond("+$it", 14.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 5.dp)) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1C212A)))
            }
        }
    }
}
