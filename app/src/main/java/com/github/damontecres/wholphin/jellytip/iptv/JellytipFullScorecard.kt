package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Cricket full scorecard (Ricki 27-Nov): an overlay on the live panel's Stats tab, opened with a short OK press while the
 * scorecard block shows its "Full scorecard" hint. D-pad only (the player page owns the keys): Left / Right switch the
 * innings tab, Up / Down scroll the tables, Back / OK close. Draw only; the parse is SportParse.fullCard.
 */

private val NumW = 46.dp
private val OverW = 58.dp
private const val ROW = 17
private const val HEAD = 13

/** Tab label: "SA 198/2" (the short code when the server sent one). */
internal fun fullTabLabel(i: FullInnings): String = listOfNotNull(i.abbr ?: i.title, i.score).joinToString(" ")

/** Left / Right on the tabs: stops at the ends (no wrap, so the viewer always knows where they are). */
internal fun stepFullTab(
    tab: Int,
    delta: Int,
    size: Int,
): Int = if (size <= 0) 0 else (tab + delta).coerceIn(0, size - 1)

@Composable
fun FullScorecardSheet(
    card: FullCard,
    tab: Int,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val i = card.innings.getOrNull(tab.coerceIn(0, (card.innings.size - 1).coerceAtLeast(0))) ?: return
    Column(
        modifier = modifier.fillMaxHeight(0.9f).fillMaxWidth(0.82f).background(Broadcast.Base.copy(alpha = 0.98f), RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cond("Full scorecard", 24.sp, Broadcast.Gold, tracking = 0.06f, modifier = Modifier.weight(1f))
            Cond("◀ ▶ innings   ▲ ▼ scroll   Back closes", 13.sp, Broadcast.Secondary, caps = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            card.innings.forEachIndexed { n, t ->
                key(t.n) {
                    val on = n == tab
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        Cond(fullTabLabel(t) + if (t.current) " *" else "", 17.sp, if (on) Color.White else Broadcast.Secondary, modifier = Modifier.padding(vertical = 3.dp))
                        Box(Modifier.height(3.dp).fillMaxWidth().background(if (on) Broadcast.Gold else Color.Transparent))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Broadcast.Line))
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            InningsBody(i)
        }
    }
}

@Composable
private fun InningsBody(i: FullInnings) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cond(i.title, 21.sp, Color.White, modifier = Modifier.weight(1f, fill = false))
        Cond(listOfNotNull(i.score, i.overs?.let { "($it ov)" }).joinToString(" "), 21.sp, Broadcast.Gold)
        i.result?.let { BodyText(it, 15.sp, Broadcast.Secondary) }
        i.target?.let { BodyText("target $it", 15.sp, Broadcast.Secondary) }
    }
    if (i.batting.isNotEmpty()) {
        SectionHead("Batting")
        TableHead("Batter", "", listOf("R" to NumW, "B" to NumW, "4s" to NumW, "6s" to NumW, "SR" to OverW))
        i.batting.forEach { b ->
            key(b.name) {
                TableRow(
                    b.name + if (b.atCrease) " *" else "",
                    b.out,
                    listOf(b.runs, b.balls, b.fours, b.sixes, b.sr).zip(listOf(NumW, NumW, NumW, NumW, OverW)).map { (v, w) -> (v ?: "") to w },
                    strong = b.atCrease,
                )
            }
        }
    }
    i.extras?.let { TableRow("Extras", it, emptyList(), muted = true) }
    TableRow("Total", listOfNotNull(i.score, i.overs?.let { "($it ov)" }).joinToString(" "), emptyList(), strong = true)
    if (i.didNotBat.isNotEmpty()) {
        BodyText((if (i.current) "Yet to bat: " else "Did not bat: ") + i.didNotBat.joinToString(", "), ROW.sp, Broadcast.Secondary, maxLines = 4)
    }
    if (i.fow.isNotEmpty()) {
        SectionHead("Fall of wickets")
        BodyText(
            i.fow.joinToString("   ") { f -> "${f.wicket ?: "?"}-${f.runs ?: "?"} (${listOfNotNull(f.name, f.overs?.let { "$it ov" }).joinToString(", ")})" },
            15.sp,
            Broadcast.Body,
            maxLines = 12,
        )
    }
    if (i.bowling.isNotEmpty()) {
        SectionHead("Bowling")
        TableHead("Bowler", "", listOf("O" to OverW, "M" to NumW, "R" to NumW, "W" to NumW, "Econ" to OverW))
        i.bowling.forEach { b ->
            key(b.name) {
                TableRow(b.name, "", listOf(b.overs, b.maidens, b.runs, b.wickets, b.econ).zip(listOf(OverW, NumW, NumW, NumW, OverW)).map { (v, w) -> (v ?: "") to w })
            }
        }
    }
}

@Composable
private fun TableHead(
    name: String,
    out: String,
    cols: List<Pair<String, Dp>>,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cond(name, HEAD.sp, Broadcast.Secondary, modifier = Modifier.width(210.dp))
        Cond(out, HEAD.sp, Broadcast.Secondary, modifier = Modifier.weight(1f))
        cols.forEach { (c, w) -> Cond(c, HEAD.sp, Broadcast.Secondary, modifier = Modifier.width(w), align = TextAlign.End) }
    }
}

@Composable
private fun TableRow(
    name: String,
    out: String,
    cols: List<Pair<String, Dp>>,
    strong: Boolean = false,
    muted: Boolean = false,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BodyText(name, ROW.sp, if (muted) Broadcast.Secondary else Color.White, modifier = Modifier.width(210.dp), weight = if (strong) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
            BodyText(out, 15.sp, if (out == "not out") Broadcast.Win else Broadcast.Secondary, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1)
            cols.forEach { (v, w) -> Cond(v, ROW.sp, if (strong) Color.White else Broadcast.Text, modifier = Modifier.width(w), align = TextAlign.End, weight = if (strong) FontWeight.Bold else FontWeight.SemiBold) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1C212A)))
    }
}
