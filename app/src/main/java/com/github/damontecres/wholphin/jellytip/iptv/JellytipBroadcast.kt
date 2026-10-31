package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Direction A, Broadcast (~/ai/plans/jellytip-live-sport.md, "In-match visual direction: DECIDED 31-Oct"; mockups in
 * ~/ai/state/jellytip-match-design, section A): a TV network's graphics package. Barlow Condensed caps for labels,
 * numbers and team names, Barlow for body text; angled team-colour slabs for the score; network gold #FFC72C for the
 * accent, focus and section heads; the whole surface washed in the two team colours (22% into #0D1015); a 2 s
 * moment wipe. Mockup pixels are 1080p: on the TV 1 px = 0.5 dp (24 px minimum text = 12 sp).
 */

val BarlowCondensed =
    FontFamily(
        Font(R.font.barlow_condensed_medium, FontWeight.Medium),
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )

val Barlow =
    FontFamily(
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
    )

object Broadcast {
    val Base = Color(0xFF0D1015)
    val Gold = Color(0xFFFFC72C)
    val Secondary = Color(0xFFA9B0BC)
    val Body = Color(0xFFC9CED6)
    val Text = Color(0xFFF5F6F8)
    val Line = Color(0xFF232833)
    val Chip = Color(0xFF1A1F28)
    val Sub = Color(0xFF151A22)

    /** Body text sits on near-black cards within the wash (3 m legibility). */
    val Card = Color(0xD90D1015)
    val Win = Color(0xFF22C55E)
    val Loss = Color(0xFF3A414D)
    val Ink = Color(0xFF111111)
    val Live = Color(0xFFFF3B3B)
}

/**
 * The match's colours. [known] = the server sent team colours (else everything stays neutral #0D1015, no wash). The
 * wash is a static brush (drawn once); text on it takes black or white by contrast with the blended colour.
 */
data class MatchPalette(
    val home: Color,
    val away: Color,
    val known: Boolean,
) {
    val homeWash: Color = if (known) lerp(Broadcast.Base, home, 0.22f) else Broadcast.Base
    val awayWash: Color = if (known) lerp(Broadcast.Base, away, 0.22f) else Broadcast.Base
    val wash: Brush = Brush.horizontalGradient(0f to homeWash, 0.42f to homeWash, 0.58f to awayWash, 1f to awayWash)
    val onHome: Color = contrastOn(home)
    val onAway: Color = contrastOn(away)
    val onHomeWash: Color = contrastOn(homeWash)
    val onAwayWash: Color = contrastOn(awayWash)

    fun side(team: String?): Color =
        when (team) {
            "home" -> home
            "away" -> away
            else -> Broadcast.Secondary
        }
}

private val NeutralSlabHome = Color(0xFF2A303B)
private val NeutralSlabAway = Color(0xFF1C212A)

/** Series colours for events (plan "Live coverage round 2"): F1 red, golf green; tennis stays neutral. */
fun seriesColor(leagueKey: String?): Color? =
    when {
        leagueKey == null -> null
        leagueKey == "f1" -> Color(0xFFE10600)
        leagueKey == "nfl" -> Color(0xFF013369)
        leagueKey.startsWith("golf") -> Color(0xFF0B6E4F)
        else -> null
    }

/** Home / away colours; the away side uses its alternate when the two are close (ΔE < 20). Events: the series colour. */
fun matchPalette(m: IptvMatch?): MatchPalette {
    if (m != null && (m.isEvent || (m.home == null && m.away == null))) {
        val c = seriesColor(m.leagueKey) ?: return MatchPalette(NeutralSlabHome, NeutralSlabAway, known = false)
        return MatchPalette(c, c, known = true)
    }
    val hc = m?.home?.color
    val ac = m?.away?.color
    if (hc == null && ac == null) return MatchPalette(NeutralSlabHome, NeutralSlabAway, known = false)
    val home = teamColor(hc, NeutralSlabHome)
    var away = teamColor(ac, NeutralSlabAway)
    if (hc != null && ac != null && deltaE(home, away) < 20) {
        away = teamColor(m?.away?.altColor, NeutralSlabAway).takeIf { deltaE(home, it) >= 20 } ?: NeutralSlabAway
    }
    return MatchPalette(home, away, known = true)
}

/** Black or white, whichever has the higher contrast against [bg] (WCAG relative luminance). */
fun contrastOn(bg: Color): Color {
    val l = bg.luminance()
    val vsWhite = 1.05f / (l + 0.05f)
    val vsBlack = (l + 0.05f) / 0.05f
    return if (vsBlack > vsWhite) Broadcast.Ink else Color.White
}

/** CIE76 colour difference (Lab). */
fun deltaE(
    a: Color,
    b: Color,
): Double {
    val la = lab(a)
    val lb = lab(b)
    return Math.sqrt((la[0] - lb[0]).let { it * it } + (la[1] - lb[1]).let { it * it } + (la[2] - lb[2]).let { it * it })
}

private fun lab(c: Color): DoubleArray {
    fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    val r = lin(c.red)
    val g = lin(c.green)
    val b = lin(c.blue)
    val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
    val y = (r * 0.2126 + g * 0.7152 + b * 0.0722)
    val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
    fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
    return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
}

/** A country's flag: ISO 2 letters (or IOC/ISO 3 mapped to 2) as regional-indicator emoji; null = unknown. */
fun flagEmoji(country: String?): String? {
    val c = country?.trim()?.uppercase(Locale.ROOT) ?: return null
    val two =
        when {
            c.length == 2 -> c
            c.length == 3 -> Locale.getISOCountries().firstOrNull { runCatching { Locale("", it).isO3Country.equals(c, true) }.getOrDefault(false) } ?: IOC[c]
            else -> null
        } ?: return null
    if (!two.all { it in 'A'..'Z' }) return null
    return String(Character.toChars(0x1F1E6 + (two[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (two[1] - 'A')))
}

/** IOC codes that differ from ISO 3166 alpha-3 (the common tennis / golf ones). */
private val IOC =
    mapOf(
        "GER" to "DE", "SUI" to "CH", "NED" to "NL", "RSA" to "ZA", "CRO" to "HR", "GRE" to "GR", "POR" to "PT", "DEN" to "DK",
        "BUL" to "BG", "CHI" to "CL", "URU" to "UY", "PAR" to "PY", "TPE" to "TW", "INA" to "ID", "MAS" to "MY", "PHI" to "PH",
        "LAT" to "LV", "SLO" to "SI", "ZIM" to "ZW", "ENG" to "GB", "SCO" to "GB", "WAL" to "GB", "NIR" to "GB",
    )

/** The left slab: a straight left edge, the right edge leaning in at the bottom (static shape). */
val SlabLeft =
    GenericShape { size, _ ->
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width * 0.94f, size.height)
        lineTo(0f, size.height)
        close()
    }

val SlabRight =
    GenericShape { size, _ ->
        moveTo(size.width * 0.06f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }

/** Barlow Condensed caps (labels, numbers, team names). */
@Composable
fun Cond(
    text: String,
    size: TextUnit,
    color: Color = Broadcast.Text,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
    tracking: Float = 0.02f,
    caps: Boolean = true,
    align: TextAlign? = null,
) = Text(
    if (caps) text.uppercase(Locale.ROOT) else text,
    color = color,
    fontFamily = BarlowCondensed,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = tracking.em,
    textAlign = align,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
)

/** Barlow body text. */
@Composable
fun BodyText(
    text: String,
    size: TextUnit = 13.sp,
    color: Color = Broadcast.Body,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Medium,
    maxLines: Int = 2,
) = Text(text, color = color, fontFamily = Barlow, fontWeight = weight, fontSize = size, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)

/** A gold section head with a rule under it. */
@Composable
fun SectionHead(
    text: String,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    Cond(text, 13.sp, Broadcast.Gold, tracking = 0.08f)
    Box(Modifier.padding(top = 3.dp, bottom = 5.dp).fillMaxWidth().height(1.dp).background(Broadcast.Line))
}

/**
 * Odometer number swap (220 ms): each changed character slides up and out while the new one slides in; unchanged
 * characters don't move (only changed digits animate).
 */
@Composable
fun Odometer(
    value: String,
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Bold,
) {
    Row(Modifier.clipToBounds()) {
        value.forEachIndexed { i, ch ->
            androidx.compose.runtime.key(i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        slideInVertically(tween(220)) { it } togetherWith slideOutVertically(tween(220)) { -it }
                    },
                    label = "odometer",
                ) { c ->
                    Text(c.toString(), color = color, fontFamily = BarlowCondensed, fontWeight = weight, fontSize = size, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The 2-second moment (plan "Moments"): the team's colour band wipes across from the left with a gold leading edge
 * (300 ms), holds 2 s, and wipes off to the right (300 ms). Translation only. [onCovered] runs once the band covers
 * the strip (swap the score underneath then).
 */
@Composable
fun MomentWipe(
    flash: LiveFlash?,
    palette: MatchPalette,
    onCovered: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val x = remember { Animatable(-1.02f) }
    var shown by remember { mutableStateOf<LiveMoment?>(null) }
    LaunchedEffect(flash?.seq) {
        val f = flash ?: return@LaunchedEffect
        shown = f.moment
        x.snapTo(-1.02f)
        x.animateTo(0f, tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        onCovered()
        delay(2_000)
        x.animateTo(1.02f, tween(300, easing = androidx.compose.animation.core.FastOutLinearInEasing))
        shown = null
        onDone()
    }
    val m = shown ?: return
    val band = if (m.team == "home" || m.team == "away") palette.side(m.team) else Broadcast.Live
    val on = contrastOn(band)
    Row(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = x.value * size.width },
    ) {
        Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            modifier = Modifier.weight(1f).fillMaxHeight().background(band).padding(start = 12.dp),
        ) {
            Cond(m.label, 27.sp, on, tracking = 0.03f)
            m.detail?.let { BodyText(it, 13.sp, on, weight = FontWeight.SemiBold, maxLines = 1) }
        }
        Box(Modifier.width(8.dp).fillMaxHeight().background(Broadcast.Gold))
    }
}

/** Tab and toast icons from the mockup's symbols (24-unit view box), stroked. */
object BroadcastIcons {
    private fun icon(
        name: String,
        vararg paths: String,
        width: Float = 2.6f,
    ): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Stats = icon("stats", "M5 20V11M12 20V4M19 20v-6", width = 3f)
    val Plays = icon("plays", "M4 6h16M4 12h16M4 18h10")
    val Odds = icon("odds", "M3 17l6-6 4 4 8-8M15 7h6v6")
    val Chat = icon("chat", "M4 5h16v11H9l-5 4z", width = 2.4f)
    val Ball = icon("ball", "M3.8 16.3C1.6 12.9 4.1 7.6 9.4 4.6c5.3-3 10.6-2.4 12.8 1.1 2.2 3.4-0.3 8.7-5.6 11.7-5.3 3-10.6 2.4-12.8-1.1z", "M9 15l6-6M10.5 11.5l2 2M12.5 9.5l2 2", width = 2.2f)
    val Info = icon("info", "M12 3a9 9 0 1 0 0.01 0zM12 11v6M12 7.5v0.5", width = 2.4f)

    fun forTab(tab: String): ImageVector =
        when (tab) {
            "Plays" -> Plays
            "Odds" -> Odds
            "Chat" -> Chat
            "Fantasy" -> Ball
            else -> Stats
        }
}
