package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Match info, more pop (~/ai/plans/jellytip-live-sport.md, "Pinned 31-Oct (match info: more pop, plus coverage)"): a
 * split diagonal hero in the team colours (neutral greys without them) with the venue photo faint behind, big crests
 * with a soft glow sliding in from either side, a LIVE pulse / countdown / FT in the centre; an odds split bar, form
 * chips and tinted line-ups. Motion is graphicsLayer only (alpha, scale, translation): no blur on the Chromecast GPU.
 */

private val NeutralHome = Color(0xFF4A4E58)
private val NeutralAway = Color(0xFF2E3138)
private val FormWin = Color(0xFF2E9E5B)
private val FormLoss = Color(0xFFD64545)
private val FormDraw = Color(0xFF6B6F78)

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

/** Home and away colours; the away side uses its alternate when both teams share a colour. */
internal fun matchColors(m: IptvMatch): Pair<Color, Color> {
    val home = teamColor(m.home?.color, NeutralHome)
    val hc = m.home?.color
    val same = hc != null && hc.equals(m.away?.color, ignoreCase = true)
    val away = if (same) teamColor(m.away?.altColor, NeutralAway) else teamColor(m.away?.color, NeutralAway)
    return home to away
}

/** The split hero. [crestIn] 0..1 slides the crests in (read in graphicsLayer only). */
@Composable
internal fun MatchHero(
    m: IptvMatch,
    venueImage: String?,
    score: MatchScore?,
    crestIn: () -> Float,
    modifier: Modifier = Modifier,
) {
    val (homeC, awayC) = remember(m.home?.color, m.away?.color, m.away?.altColor) { matchColors(m) }
    val homeBg = lerp(homeC, Color.Black, 0.45f)
    val awayBg = lerp(awayC, Color.Black, 0.45f)
    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(12.dp))
            .drawBehind {
                drawRect(homeBg)
                val p =
                    Path().apply {
                        moveTo(size.width * 0.58f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(size.width * 0.42f, size.height)
                        close()
                    }
                drawPath(p, awayBg)
            },
    ) {
        if (venueImage != null) {
            AsyncImage(
                model = venueImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.16f },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            HeroTeam(m.home, homeC, crestIn, fromLeft = true, modifier = Modifier.weight(1f))
            HeroCentre(m, score, Modifier.width(250.dp))
            HeroTeam(m.away, awayC, crestIn, fromLeft = false, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroTeam(
    t: MatchTeam?,
    color: Color,
    crestIn: () -> Float,
    fromLeft: Boolean,
    modifier: Modifier,
) {
    val glow = lerp(color, Color.White, 0.35f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Box(
            Modifier
                .size(140.dp)
                .graphicsLayer {
                    val p = crestIn()
                    translationX = (1f - p) * (if (fromLeft) -1f else 1f) * 120.dp.toPx()
                    alpha = p
                }.drawBehind {
                    // Soft glow: a radial fade behind the crest (no blur)
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent), center = center, radius = size.minDimension / 2f))
                },
            contentAlignment = Alignment.Center,
        ) {
            var failed by remember(t?.logo) { mutableStateOf(false) }
            if (t?.logo != null && !failed) {
                AsyncImage(model = t.logo, contentDescription = t.name, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(120.dp))
            } else {
                Box(Modifier.size(96.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initials(t?.name ?: "?"), color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                }
            }
        }
        Text(
            t?.name ?: "TBC",
            color = Color.White,
            fontFamily = JellytipFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        t?.record?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 12.sp) }
    }
}

@Composable
private fun HeroCentre(
    m: IptvMatch,
    score: MatchScore?,
    modifier: Modifier,
) {
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        m.league?.let { Text(it.uppercase(), color = Color.White.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 11.sp, letterSpacing = 1.5.sp, maxLines = 1) }
        val clock = listOfNotNull(m.score?.period, m.score?.clock).joinToString(" · ")
        when {
            score != null -> {
                Text("${score.home} – ${score.away}", color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp)
                listOfNotNull(score.period, score.clock).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = Color.White.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
                }
            }
            m.status == "in" -> {
                LivePulse()
                if (clock.isNotEmpty()) Text(clock, color = Color.White.copy(alpha = 0.85f), fontFamily = JellytipFontFamily, fontSize = 14.sp)
            }
            m.status == "post" -> Text("FT", color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp)
            else ->
                m.startsAt?.let { at ->
                    Text(countdown(at, now), color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, textAlign = TextAlign.Center)
                    Text(whenLabel(at), color = Color.White.copy(alpha = 0.75f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
                }
        }
        m.venue?.let { Text("📍 $it", color = Color.White.copy(alpha = 0.75f), fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (m.inferred) Text("identified from the fixtures", color = Color.White.copy(alpha = 0.5f), fontFamily = JellytipFontFamily, fontSize = 10.sp)
    }
}

/** "Starts in 2h 14m" / "Starts in 25m" / "Starting now"; a day or more away = "Starts Sat 19:30". */
private fun countdown(
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

/** A big red LIVE that breathes (scale only). */
@Composable
private fun LivePulse() {
    val t = rememberInfiniteTransition(label = "live-pulse")
    val s by t.animateFloat(1f, 1.12f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "live-pulse-scale")
    Text(
        "●  LIVE",
        color = Color.White,
        fontFamily = JellytipFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = s
                    scaleY = s
                }.background(JellytipPalette.Jelly, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
    )
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

/** The odds as one bar in the team colours (draw grey), with percentages; nothing when the lines can't be read. */
@Composable
internal fun OddsBar(
    o: MatchOdds,
    m: IptvMatch,
) {
    val h = impliedProb(o.home?.moneyline) ?: return
    val a = impliedProb(o.away?.moneyline) ?: return
    val d = impliedProb(o.draw) ?: 0.0
    val sum = h + a + d
    val (homeC, awayC) = remember(m.home?.color, m.away?.color, m.away?.altColor) { matchColors(m) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            Box(Modifier.weight((h / sum).toFloat().coerceAtLeast(0.01f)).height(14.dp).background(homeC))
            if (d > 0) Box(Modifier.weight((d / sum).toFloat().coerceAtLeast(0.01f)).height(14.dp).background(FormDraw))
            Box(Modifier.weight((a / sum).toFloat().coerceAtLeast(0.01f)).height(14.dp).background(awayC))
        }
        Row(Modifier.fillMaxWidth()) {
            OddsLabel("${m.home?.short ?: m.home?.name ?: "Home"}  ${pctText(h / sum)}", Modifier.weight(1f), TextAlign.Start)
            if (d > 0) OddsLabel("Draw  ${pctText(d / sum)}", Modifier.weight(1f), TextAlign.Center)
            OddsLabel("${pctText(a / sum)}  ${m.away?.short ?: m.away?.name ?: "Away"}", Modifier.weight(1f), TextAlign.End)
        }
    }
}

private fun pctText(p: Double) = String.format(Locale.US, "%d%%", Math.round(p * 100))

@Composable
private fun OddsLabel(
    text: String,
    modifier: Modifier,
    align: TextAlign,
) = Text(text, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, textAlign = align, maxLines = 1, modifier = modifier)

/** W / L / D as coloured chips (entries may be single letters or a run like "WWLDW"). */
@Composable
internal fun FormChips(
    label: String,
    form: List<String>,
) {
    val letters = form.flatMap { f -> if (f.length > 1 && f.all { it.uppercaseChar() in "WLD" }) f.map { it.toString() } else listOf(f) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = JellytipPalette.Vanilla.copy(alpha = 0.75f), fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(200.dp))
        letters.take(10).forEach { r ->
            val c =
                when (r.uppercase(Locale.ROOT).firstOrNull()) {
                    'W' -> FormWin
                    'L' -> FormLoss
                    else -> FormDraw
                }
            Box(Modifier.size(24.dp).background(c, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                Text(r.uppercase(Locale.ROOT).take(1), color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
