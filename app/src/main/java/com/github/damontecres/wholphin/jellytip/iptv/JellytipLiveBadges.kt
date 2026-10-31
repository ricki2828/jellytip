package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/*
 * Live sport badges (~/ai/plans/jellytip-live-sport.md §1b, §2, §5 TV): LIVE / REPLAY pills, a sport mark, the stream
 * quality chip (UHD / 1080 50fps / 1080 / 720 / SD) with an ATMOS / 5.1 chip, and "may buffer" for slow channels.
 * Every field may be missing (older server): then nothing is drawn and the tiles look as before.
 */

private val MayBuffer = Color(0xFFE0A84A)

/** Red LIVE for `live`, grey REPLAY for `replay` / `highlights`, nothing otherwise. [legacyLive]: old server, keep LIVE. */
@Composable
fun KindPill(
    kind: String?,
    legacyLive: Boolean = false,
) {
    when {
        kind == "live" || (kind == null && legacyLive) -> Pill("LIVE", Color.White, JellytipPalette.Jelly)
        // Broadcast (plan "In-match visual direction"): condensed caps on slate
        kind == "replay" || kind == "highlights" ->
            Cond("Replay", 11.sp, Color.White, modifier = Modifier.background(Broadcast.Loss, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp), tracking = 0.06f)
    }
}

/** The programme's kind, with an identified game being re-aired (`matchInfo.replay`) as "replay". */
val IptvProg.shownKind: String?
    get() = if (match?.replay == true) "replay" else kind

/** Small "identified" mark: the matchup came from the fixtures, the listing didn't name it (plan "Match info"). */
@Composable
fun IdentifiedMark() =
    Text(
        text = "identified",
        color = Color.White.copy(alpha = 0.75f),
        fontFamily = JellytipFontFamily,
        fontSize = 9.sp,
        maxLines = 1,
        modifier = Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )

/** Neutral "PRE-GAME" (plan "Pre-game state"): the channel is on but the event hasn't started; not the red LIVE. */
@Composable
fun PreGamePill() = Pill("PRE-GAME", Color.White, Color(0xFF5A5F6B))

/** A neutral start-time pill ("19:30", "Sat 19:30") for something not on yet (search "Live & upcoming"). */
@Composable
fun TimePill(text: String) = Pill(text, Color.White, Color.White.copy(alpha = 0.2f))

/** "Starts 18:00 · in 35 min" ("· now" once due); local time. */
fun startsLabel(
    startsAt: Long,
    now: Long,
): String {
    val mins = ((startsAt - now + 59) / 60)
    val inText =
        when {
            mins <= 0 -> "now"
            mins < 60 -> "in $mins min"
            else -> "in ${mins / 60}h" + if (mins % 60 > 0) " ${mins % 60}m" else ""
        }
    return "Starts ${hhmm(startsAt)} · $inText"
}

/** Amber "STARTING SOON" (plan "Remind me": a reminder within 15 minutes of kick-off). */
@Composable
fun SoonPill() = Pill("STARTING SOON", Color.Black, MayBuffer)

@Composable
private fun Pill(
    text: String,
    color: Color,
    background: Color,
) {
    Text(
        text = text,
        color = color,
        fontFamily = JellytipFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        modifier = Modifier.background(background, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Small sport mark, tinted like the text beside it. */
@Composable
fun SportMark(
    color: Color,
    size: Dp = 12.dp,
) {
    Image(
        painter = painterResource(R.drawable.jt_sport),
        contentDescription = "Sport",
        colorFilter = ColorFilter.tint(color),
        modifier = Modifier.size(size),
    )
}

/**
 * Quality chip plus audio chip (ATMOS / 5.1), in the film tech badges' look (white at 80% on a dark pill), then
 * "may buffer" for slow channels. Nothing when the channel was never probed.
 */
@Composable
fun QualityChips(
    quality: IptvQuality?,
    modifier: Modifier = Modifier,
) {
    quality ?: return
    val chips = listOfNotNull(quality.label, quality.audioLabel)
    if (chips.isEmpty() && !quality.slow) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        chips.forEach { Pill(it, Color.White.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.45f)) }
        if (quality.slow) {
            Text(
                text = "may buffer",
                color = MayBuffer,
                fontFamily = JellytipFontFamily,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

/** Sized companion image link (the art proxy scales like the logo proxy: s=96|192|480|960). */
private fun sized(
    url: String,
    s: Int,
): String = if (url.contains('?')) url else "$url?s=$s"

/**
 * League fanart as a dimmed tile background (plan "Sport artwork"), darker on the left where the text sits; a lighter
 * scrim when [focused]. Draw it first inside the tile; nothing when [art] has no fanart.
 */
@Composable
fun SportArtBackdrop(
    art: IptvArt?,
    focused: Boolean,
) {
    val fanart = art?.leagueFanart ?: return
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = sized(fanart, 480),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = if (focused) 0.6f else 0.8f),
                    1f to Color.Black.copy(alpha = if (focused) 0.25f else 0.5f),
                ),
            ),
        )
    }
}

/** Tile colours: dark with white text on fanart (the white focus fill would wash it out), else today's. */
@Composable
fun liveTileColors(art: IptvArt?) =
    if (art?.leagueFanart != null) {
        ClickableSurfaceDefaults.colors(
            containerColor = Color.Black,
            focusedContainerColor = Color.Black,
            contentColor = JellytipPalette.Vanilla,
            focusedContentColor = Color.White,
        )
    } else {
        ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.07f),
            focusedContainerColor = Color.White,
            contentColor = JellytipPalette.Vanilla,
            focusedContentColor = Color.Black,
        )
    }

/** On fanart, focus shows as a white outline instead of the white fill. */
@Composable
fun liveTileBorder(art: IptvArt?) =
    if (art?.leagueFanart != null) {
        ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(6.dp)),
        )
    } else {
        ClickableSurfaceDefaults.border()
    }

/**
 * The tile's left slot with art: home v away badges when both are known, else the league badge; null art (or no
 * badge) = [fallback] (today's channel logo).
 */
@Composable
fun SportArtSlot(
    art: IptvArt?,
    height: Dp,
    fallback: @Composable () -> Unit,
) {
    val home = art?.homeBadge
    val away = art?.awayBadge
    val league = art?.leagueBadge
    when {
        home != null && away != null ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(home, art?.homeName, height)
                Text("v", fontFamily = JellytipFontFamily, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
                Badge(away, art?.awayName, height)
            }

        league != null -> Badge(league, null, height)

        else -> fallback()
    }
}

@Composable
private fun Badge(
    url: String,
    name: String?,
    size: Dp,
) {
    AsyncImage(
        model = sized(url, 192),
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size),
    )
}

/** The channel logo, small, for a corner of an art tile (the art took its usual slot). */
@Composable
fun CornerLogo(
    logo: String?,
    modifier: Modifier = Modifier,
) {
    logo ?: return
    AsyncImage(
        model = sized(logo, 96),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(28.dp, 16.dp),
    )
}
