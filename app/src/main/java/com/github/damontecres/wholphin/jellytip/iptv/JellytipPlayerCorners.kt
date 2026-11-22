package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/*
 * The live player's corner overlays (delay chip, scorebug, toast, fantasy chip, banner hint, track message), drawn from
 * plain values so a screenshot test can render them all at once (shots: PlayerSizingShotsTest). The player page owns the
 * state, the timers and the toast animation; this only places and draws.
 */

/**
 * Every corner overlay of the player, each at its place. A null slot / value draws nothing. [toastProgress] is the
 * toast's slide-in (0 hidden, 1 in place), read in the draw phase only.
 */
@Composable
internal fun PlayerCornerOverlays(
    delayChip: (@Composable () -> Unit)?,
    scorebug: (@Composable () -> Unit)?,
    toast: PlayerToast?,
    toastProgress: () -> Float,
    fantasy: String?,
    hint: String?,
    trackMessage: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        delayChip?.let { chip -> Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp)) { chip() } }
        scorebug?.let { bug -> Box(Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = 48.dp)) { bug() } }
        toast?.let { t ->
            PlayerToastView(
                t,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 24.dp)
                    .graphicsLayer {
                        val p = toastProgress()
                        translationX = (1f - p) * (size.width + 24.dp.toPx())
                        alpha = p
                    },
            )
        }
        fantasy?.let {
            PlayerChipText(
                it,
                Color.White,
                14.sp,
                Modifier.align(Alignment.TopStart).padding(top = 32.dp, start = 48.dp),
                weight = FontWeight.SemiBold,
            )
        }
        hint?.let {
            PlayerChipText(it, JellytipPalette.Vanilla.copy(alpha = 0.85f), 14.sp, Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp))
        }
        trackMessage?.let {
            Text(
                it,
                color = Color.White,
                fontFamily = JellytipFontFamily,
                fontSize = 15.sp,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 76.dp, start = 48.dp)
                        .background(Broadcast.Base.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** A one-line chip on a 0.6 black pill (fantasy score, banner hint). */
@Composable
private fun PlayerChipText(
    text: String,
    color: Color,
    size: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
    weight: FontWeight? = null,
) {
    Text(
        text,
        color = color,
        fontFamily = JellytipFontFamily,
        fontWeight = weight,
        fontSize = size,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The toast: a Broadcast lower third (team-colour edge, icon block, kicker, main line, sub). */
@Composable
internal fun PlayerToastView(
    t: PlayerToast,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .width(330.dp)
                .height(IntrinsicSize.Min)
                .background(Broadcast.Base.copy(alpha = 0.96f)),
    ) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(t.edge))
        Box(Modifier.width(36.dp).fillMaxHeight().background(Broadcast.Chip), contentAlignment = Alignment.Center) {
            androidx.tv.material3.Icon(t.icon, contentDescription = null, tint = t.kickerColor, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            t.kicker?.let { Cond(it, 12.sp, t.kickerColor, tracking = 0.08f) }
            Text(
                t.main.uppercase(java.util.Locale.ROOT),
                color = Color.White,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            t.sub?.let { BodyText(it, 12.sp, maxLines = 1) }
        }
    }
}
