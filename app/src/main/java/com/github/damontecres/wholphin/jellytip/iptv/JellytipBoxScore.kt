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
 * NFL / basketball full box score (Ricki 27-Nov): an overlay on the live panel's Stats tab, opened with a short OK while the
 * "Full box score" hint shows. Same keys as the cricket sheet (the player page owns them): Left / Right switch the tab
 * (NFL: Team stats / away / home; basketball: away / home), Up / Down scroll, Back / OK close. Draw only; the parse is SportParse.boxScore.
 */

private const val ROW = 17
private const val HEAD = 13

/** A stat column's width from the longest cell (labels included): "11-18" and "21/31" need more room than "32". */
internal fun boxColWidth(
    label: String,
    values: List<String>,
): Dp {
    val n = maxOf(label.length, values.maxOfOrNull { it.length } ?: 0)
    return when {
        n >= 6 -> 72.dp
        n >= 5 -> 62.dp
        n >= 4 -> 54.dp
        else -> 46.dp
    }
}

/** Left / Right on the box tabs: stops at the ends, like the cricket sheet. */
internal fun stepBoxTab(
    tab: Int,
    delta: Int,
    size: Int,
): Int = stepFullTab(tab, delta, size)

@Composable
fun BoxScoreSheet(
    box: BoxScore,
    tab: Int,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val tabs = box.tabs
    val at = tab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val sel = tabs.getOrNull(at) ?: return
    Column(
        modifier = modifier.fillMaxHeight(0.9f).fillMaxWidth(0.82f).background(Broadcast.Base.copy(alpha = 0.98f), RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cond("Box score", 24.sp, Broadcast.Gold, tracking = 0.06f, modifier = Modifier.weight(1f))
            Cond("◀ ▶ tabs   ▲ ▼ scroll   Back closes", 13.sp, Broadcast.Secondary, caps = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            tabs.forEachIndexed { n, t ->
                key(t.key) {
                    val on = n == at
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        Cond(t.label, 17.sp, if (on) Color.White else Broadcast.Secondary, modifier = Modifier.padding(vertical = 3.dp))
                        Box(Modifier.height(3.dp).fillMaxWidth().background(if (on) Broadcast.Gold else Color.Transparent))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Broadcast.Line))
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (sel.key == "compare") {
                CompareBody(box)
            } else {
                box.teams.firstOrNull { it.side == sel.key }?.let { TeamBody(it) }
            }
        }
    }
}

@Composable
private fun CompareBody(box: BoxScore) {
    val away = box.teams.firstOrNull { it.side == "away" }
    val home = box.teams.firstOrNull { it.side == "home" }
    Row(verticalAlignment = Alignment.Bottom) {
        Cond(away?.abbr ?: away?.name ?: "Away", 21.sp, Color.White, modifier = Modifier.width(150.dp))
        Cond("", 21.sp, modifier = Modifier.weight(1f))
        Cond(home?.abbr ?: home?.name ?: "Home", 21.sp, Color.White, modifier = Modifier.width(150.dp), align = TextAlign.End)
    }
    box.compare.forEach { c ->
        key(c.label) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cond(c.away, 20.sp, Color.White, modifier = Modifier.width(150.dp))
                    Cond(c.label, 14.sp, Broadcast.Secondary, modifier = Modifier.weight(1f), align = TextAlign.Center, tracking = 0.06f)
                    Cond(c.home, 20.sp, Color.White, modifier = Modifier.width(150.dp), align = TextAlign.End)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1C212A)))
            }
        }
    }
}

@Composable
private fun TeamBody(t: BoxTeam) {
    Cond(t.name ?: t.abbr ?: t.side, 21.sp, Color.White)
    t.sections.forEach { s ->
        key(s.title) {
            SectionHead(s.title)
            val widths = s.labels.mapIndexed { i, l -> boxColWidth(l, s.rows.map { it.stats.getOrNull(i) ?: "" }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                s.labels.forEachIndexed { i, l -> Cond(l, HEAD.sp, Broadcast.Secondary, modifier = Modifier.width(widths[i]), align = TextAlign.End) }
            }
            s.rows.forEachIndexed { r, row ->
                key(r, row.name) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BodyText(row.name, ROW.sp, if (row.total) Broadcast.Gold else Color.White, weight = if (row.total) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                row.pos?.let { Cond(it, 12.sp, Broadcast.Secondary) }
                            }
                            s.labels.indices.forEach { i ->
                                Cond(
                                    row.stats.getOrNull(i) ?: "",
                                    ROW.sp,
                                    if (row.total) Color.White else Broadcast.Text,
                                    modifier = Modifier.width(widths[i]),
                                    align = TextAlign.End,
                                    weight = if (row.total) FontWeight.Bold else FontWeight.SemiBold,
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1C212A)))
                    }
                }
            }
        }
    }
    if (t.dnp.isNotEmpty()) BodyText("Did not play: " + t.dnp.joinToString(", "), ROW.sp, Broadcast.Secondary, maxLines = 3)
}
