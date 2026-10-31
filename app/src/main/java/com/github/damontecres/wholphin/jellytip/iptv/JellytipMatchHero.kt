package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Match info header and blocks in the Broadcast look (~/ai/plans/jellytip-live-sport.md, "In-match visual direction:
 * DECIDED 31-Oct"; mockup A (b)): two full-colour angled slabs with 50 dp crests (sliding in), names in condensed caps,
 * the score at 32 sp when shown, and a gold tag on top (LIVE · Q3, a countdown, FT); odds as a gold-led split bar; form
 * as W / L chips. Replaces the diagonal hero ("more pop"), which conflicted.
 */

/** "#0B2265" / "0B2265" -> colour; anything else = [fallback]. */
internal fun teamColor(
    hex: String?,
    fallback: Color,
): Color =
    hex
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.length == 6 }
        ?.toLongOrNull(16)
        ?.let { Color(0xFF000000 or it) } ?: fallback

/** The header: home slab left, away slab right (overlapping on the angle), the gold status tag. */
@Composable
internal fun BroadcastHeader(
    m: IptvMatch,
    score: MatchScore?,
    pal: MatchPalette,
    crestIn: () -> Float,
) {
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    Box(Modifier.fillMaxWidth().height(95.dp)) {
        Row(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier.weight(1f).fillMaxHeight().clip(SlabLeft).background(pal.home).padding(start = 18.dp, end = 26.dp),
            ) {
                HeaderCrest(m.home, pal.home, crestIn, fromLeft = true)
                TeamName(m.home, pal.onHome, Modifier.weight(1f))
                score?.let { HeaderScore(it.home, listOfNotNull(it.period, it.clock).joinToString(" · "), pal.onHome, TextAlign.End) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .offset(x = (-14).dp)
                        .fillMaxHeight()
                        .clip(SlabRight)
                        .background(pal.away)
                        .padding(start = 32.dp, end = 4.dp),
            ) {
                score?.let { HeaderScore(it.away, "", pal.onAway, TextAlign.Start) }
                TeamName(m.away, pal.onAway, Modifier.weight(1f))
                HeaderCrest(m.away, pal.away, crestIn, fromLeft = false)
            }
        }
        val tag =
            when (m.status) {
                "in" -> "Live" + (m.score?.period?.let { " · $it" } ?: "")
                "post" -> "Full time"
                else -> m.startsAt?.let { countdown(it, now) } ?: "Upcoming"
            }
        Cond(
            tag,
            13.sp,
            Broadcast.Ink,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).background(Broadcast.Gold).padding(horizontal = 8.dp, vertical = 2.dp),
            tracking = 0.06f,
        )
    }
}

/** An event (F1, golf; plan "Live coverage round 2"): the title as the hero on the series colour, the gold status tag. */
@Composable
internal fun EventHeader(
    m: IptvMatch,
    pal: MatchPalette,
) {
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    Box(Modifier.fillMaxWidth().height(95.dp).clip(SlabLeft).background(pal.home)) {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Cond(m.title ?: m.league ?: "Event", 28.sp, pal.onHome)
            m.league?.takeIf { m.title != null }?.let { BodyText(it, 13.sp, pal.onHome.copy(alpha = 0.85f), weight = FontWeight.SemiBold, maxLines = 1) }
        }
        val tag =
            when (m.status) {
                "in" -> "Live"
                "post" -> "Finished"
                else -> m.startsAt?.let { countdown(it, now) } ?: "Upcoming"
            }
        Cond(
            tag,
            13.sp,
            Broadcast.Ink,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 18.dp).background(Broadcast.Gold).padding(horizontal = 8.dp, vertical = 2.dp),
            tracking = 0.06f,
        )
    }
}

@Composable
private fun HeaderCrest(
    t: MatchTeam?,
    color: Color,
    crestIn: () -> Float,
    fromLeft: Boolean,
) {
    Box(
        Modifier
            .size(50.dp)
            .graphicsLayer {
                val p = crestIn()
                translationX = (1f - p) * (if (fromLeft) -1f else 1f) * 60.dp.toPx()
                alpha = p
            },
        contentAlignment = Alignment.Center,
    ) {
        var failed by remember(t?.logo) { mutableStateOf(false) }
        val logo = t?.logo
        val flag = flagEmoji(t?.country)
        if (logo != null && !failed) {
            AsyncImage(model = logo, contentDescription = t?.name, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(50.dp))
        } else if (flag != null) {
            androidx.tv.material3.Text(flag, fontSize = 30.sp)
        } else {
            Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
                Cond(initials(t?.name ?: "?"), 16.sp, contrastOn(color))
            }
        }
    }
}

@Composable
private fun TeamName(
    t: MatchTeam?,
    color: Color,
    modifier: Modifier,
) {
    Column(modifier) {
        Cond(t?.name ?: "TBC", 23.sp, color)
        t?.record?.let { BodyText(it, 13.sp, color.copy(alpha = 0.85f), weight = FontWeight.SemiBold, maxLines = 1) }
    }
}

@Composable
private fun HeaderScore(
    value: String,
    sub: String,
    color: Color,
    align: TextAlign,
) {
    Column(horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Odometer(value, 32.sp, color)
        if (sub.isNotEmpty()) Cond(sub, 13.sp, color, weight = FontWeight.SemiBold, tracking = 0.04f)
    }
}

/** "Starts in 2h 14m" / "Starts in 25m" / "Starting now"; a day or more away = "Starts Sat 19:30". */
internal fun countdown(
    at: Long,
    now: Long,
): String {
    val mins = (at - now + 59) / 60
    return when {
        mins <= 0 -> "Starting now"
        mins >= 24 * 60 -> "Starts " + whenLabel(at)
        mins >= 60 -> "Starts in ${mins / 60}h" + if (mins % 60 > 0) " ${mins % 60}m" else ""
        else -> "Starts in ${mins}m"
    }
}

/** American (-150 / +130) or decimal (1.85) odds -> implied probability; null = unreadable. */
internal fun impliedProb(s: String?): Double? {
    if (s == null) return null
    val raw = s.trim().replace('−', '-')
    val v = raw.removePrefix("+").toDoubleOrNull() ?: return null
    val signed = raw.startsWith("+") || raw.startsWith("-")
    return when {
        v < 0 -> -v / (-v + 100)
        signed || v >= 100 -> 100 / (v + 100)
        v > 1 -> 1 / v
        else -> null
    }
}

/** "Black Caps 64%  India 36%" (the favourite in gold) over a split track in the team colours. */
@Composable
internal fun OddsBar(
    o: MatchOdds,
    m: IptvMatch,
    pal: MatchPalette,
) {
    val h = impliedProb(o.home?.moneyline) ?: return
    val a = impliedProb(o.away?.moneyline) ?: return
    val d = impliedProb(o.draw) ?: 0.0
    val sum = h + a + d
    val (hc, ac) = if (pal.known) pal.home to pal.away else Broadcast.Gold to Broadcast.Secondary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            BodyText(m.home?.short ?: m.home?.name ?: "Home", 13.sp, Broadcast.Text, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            Cond(pctText(h / sum), 17.sp, if (h >= a) Broadcast.Gold else Broadcast.Text)
            Spacer(Modifier.weight(1f))
            if (d > 0) {
                BodyText("Draw", 13.sp, Broadcast.Secondary, maxLines = 1)
                Spacer(Modifier.width(5.dp))
                Cond(pctText(d / sum), 17.sp)
                Spacer(Modifier.weight(1f))
            }
            BodyText(m.away?.short ?: m.away?.name ?: "Away", 13.sp, Broadcast.Text, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            Cond(pctText(a / sum), 17.sp, if (a > h) Broadcast.Gold else Broadcast.Text)
        }
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.weight((h / sum).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(hc))
            if (d > 0) Box(Modifier.weight((d / sum).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Broadcast.Loss))
            Box(Modifier.weight((a / sum).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(ac))
        }
    }
}

private fun pctText(p: Double) = String.format(Locale.US, "%d%%", Math.round(p * 100))

/** W / L / D as square chips (W green with dark text, the rest slate); entries may be a run like "WWLDW". */
@Composable
internal fun FormChips(
    label: String,
    form: List<String>,
) {
    val letters = form.flatMap { f -> if (f.length > 1 && f.all { it.uppercaseChar() in "WLD" }) f.map { it.toString() } else listOf(f) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Cond(label, 13.sp, Broadcast.Body, modifier = Modifier.width(56.dp), weight = FontWeight.SemiBold)
        letters.take(10).forEach { r ->
            val win = r.uppercase(Locale.ROOT).startsWith("W")
            Box(Modifier.size(17.dp).background(if (win) Broadcast.Win else Broadcast.Loss), contentAlignment = Alignment.Center) {
                Cond(r.take(1), 12.sp, if (win) Broadcast.Ink else Broadcast.Text)
            }
        }
    }
}
