package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Round 3 player cards (mockups ~/ai/state/decision-briefs/jellytip-tv-20261121/mock/03-fulltime-next.html and
 * 08-catch-me-up.html): the full-time card with what's next, and the catch-me-up choice + recap cards. Draw only; the
 * player owns the keys (OK / Left / Right / Back) and the timers.
 */

/** What the full-time card offers next. */
data class FullTimeNext(
    val game: FxItem? = null,
    val pickTitle: String? = null,
    val pickId: java.util.UUID? = null,
    val pickKind: org.jellyfin.sdk.model.api.BaseItemKind? = null,
)

@Composable
private fun Slab(
    name: String,
    color: Color,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(color).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Cond(name, 26.sp, contrastText(color))
    }
}

private fun contrastText(c: Color): Color = if (c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f > 0.6f) Broadcast.Ink else Color.White

/** "FULL TIME", the delayed score between the team slabs, then NEXT (or tonight's film pick). Back stays on this channel. */
@Composable
fun FullTimeCard(
    match: IptvMatch,
    scoreHome: String?,
    scoreAway: String?,
    next: FullTimeNext?,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val pal = matchPalette(match)
    Column(modifier.width(960.dp).clip(RoundedCornerShape(10.dp)).background(Broadcast.Base.copy(alpha = 0.97f))) {
        Row(Modifier.height(92.dp)) {
            Slab(match.home?.name ?: "Home", if (pal.known) pal.home else Broadcast.Chip, false, Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(280.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Cond("${scoreHome ?: "–"}  –  ${scoreAway ?: "–"}", 48.sp, Broadcast.Gold)
            }
            Slab(match.away?.name ?: "Away", if (pal.known) pal.away else Broadcast.Chip, true, Modifier.weight(1f).fillMaxHeight())
        }
        Column(Modifier.padding(horizontal = 30.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Cond("Full time", 22.sp, Broadcast.Gold)
            val g = next?.game
            if (g != null || next?.pickTitle != null) {
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF1F0EC)).border(2.dp, Color.White, RoundedCornerShape(8.dp)).padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (g != null) {
                        Cond("Next: ${g.title}", 26.sp, Broadcast.Ink)
                        BodyText(FullTimePolicy.nextLine(g, now), 18.sp, Broadcast.Ink, maxLines = 1)
                    } else {
                        Cond("Tonight's pick: ${next?.pickTitle}", 26.sp, Broadcast.Ink)
                        BodyText("OK for details", 18.sp, Broadcast.Ink, maxLines = 1)
                    }
                }
            }
            BodyText("Back stays on this channel", 16.sp, Broadcast.Secondary, maxLines = 1)
        }
    }
}

/** The three choices when joining late; [cursor] 0 = from kick-off (only when [canKickOff]), 1 = catch me up, 2 = live. */
@Composable
fun CatchUpChoiceCard(
    match: IptvMatch,
    joining: String,
    canKickOff: Boolean,
    cursor: Int,
    /** Read here only, so the countdown recomposes just this card. */
    secondsLeft: () -> Long,
    modifier: Modifier = Modifier,
) {
    val pal = matchPalette(match)
    Column(modifier.width(1200.dp).clip(RoundedCornerShape(10.dp)).background(Broadcast.Base.copy(alpha = 0.97f))) {
        Row(Modifier.height(92.dp)) {
            Slab(match.home?.name ?: "Home", if (pal.known) pal.home else Broadcast.Chip, false, Modifier.weight(1f).fillMaxHeight())
            Slab(match.away?.name ?: "Away", if (pal.known) pal.away else Broadcast.Chip, true, Modifier.weight(1f).fillMaxHeight())
        }
        Column(Modifier.padding(34.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Cond(joining, 26.sp, Broadcast.Gold)
            BodyText("Score stays hidden until you choose.", 20.sp, Broadcast.Secondary, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val opts =
                    listOf(
                        Triple(0, "From kick-off", "Watch the whole game"),
                        Triple(1, "Catch me up", "60 s of key plays, then live"),
                        Triple(2, "Live", "Jump straight in"),
                    ).filter { it.first != 0 || canKickOff }
                opts.forEach { (i, title, sub) ->
                    val f = i == cursor
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (f) Color(0xFFF1F0EC) else Broadcast.Chip)
                            .border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                            .padding(horizontal = 22.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Cond(title, 28.sp, if (f) Broadcast.Ink else Color.White)
                        BodyText(sub, 18.sp, if (f) Broadcast.Ink else Broadcast.Secondary, maxLines = 1)
                    }
                }
            }
            BodyText("Goes to Live in ${secondsLeft()} s", 18.sp, Broadcast.Secondary, maxLines = 1)
        }
    }
}

/** One recap card: when, a team-colour tick, the play, its points; "n / total" and the keys. */
@Composable
fun CatchUpRecapCard(
    match: IptvMatch,
    card: CatchUpPolicy.Card,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val pal = matchPalette(match)
    Column(modifier.width(900.dp).clip(RoundedCornerShape(10.dp)).background(Broadcast.Base.copy(alpha = 0.97f)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Cond("Catch me up", 20.sp, Broadcast.Gold)
            BodyText("${index + 1} / $total", 16.sp, Broadcast.Secondary, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (card.whenText.isNotBlank()) Cond(card.whenText, 26.sp, Broadcast.Gold, modifier = Modifier.width(110.dp))
            Box(Modifier.width(6.dp).height(40.dp).background(card.team?.let { if (pal.known) pal.side(it) else Broadcast.Gold } ?: Color.Transparent))
            BodyText(card.text, 26.sp, Color.White, modifier = Modifier.weight(1f), maxLines = 2)
            card.points?.takeIf { it > 0 }?.let { Cond("+$it", 26.sp, Broadcast.Ink, modifier = Modifier.background(Broadcast.Gold).padding(horizontal = 8.dp)) }
        }
        BodyText("OK next · Back: straight to live", 16.sp, Broadcast.Secondary, maxLines = 1)
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))
