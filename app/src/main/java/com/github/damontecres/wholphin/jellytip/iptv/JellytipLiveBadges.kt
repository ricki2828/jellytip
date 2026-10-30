package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
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
        kind == "replay" || kind == "highlights" -> Pill("REPLAY", Color.White.copy(alpha = 0.9f), Color(0xFF6B6B6B))
    }
}

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
