package com.github.damontecres.wholphin.jellytip.iptv

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette

/*
 * The live game as a slide of the Home hero carousel (Ricki 22-Nov: one slide among the others, not a takeover card).
 * Backdrop = the fixture art with the team colours (FixtureArtHeroBackdrop) or the plain team wash, the two crests on
 * the right, a dark scrim behind the bottom-left text (FixtureArt.slideTextBehind, checked in FixtureArtTest) and the
 * film hero's fade into the rows. Text block = the film hero's place and size: LIVE tag, league, "HOME v AWAY",
 * "score hidden until you watch". Spoiler-safe: no score, no clock.
 */

/** Full hero area behind the slide; [onShown] = at least half on screen for 1 s (sport event log), once per game. */
@Composable
fun HeroMatchBackdrop(
    f: FxItem,
    motion: Boolean,
    modifier: Modifier = Modifier,
    onShown: (() -> Unit)? = null,
) {
    val pal = rememberFixturePalette(f)
    var artFailed by remember(f.art) { mutableStateOf(false) }
    val art = f.art?.takeUnless { artFailed }
    val wash =
        remember(pal) {
            Brush.horizontalGradient(
                0f to pal.home,
                FixtureArt.WASH_CENTRE to Broadcast.Base,
                1f - FixtureArt.WASH_CENTRE to Broadcast.Base,
                1f to pal.away,
            )
        }
    Box(modifier.fillMaxSize().background(Broadcast.Base)) {
        if (art != null) {
            FixtureArtHeroBackdrop(art, f.fid, pal.home, pal.away, motion, onFailed = { artFailed = true })
        } else {
            Box(Modifier.fillMaxSize().background(wash))
        }
        // The crests, right of centre (the text block owns the bottom left)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 96.dp, bottom = 40.dp),
        ) {
            BigCrest(f.homeLogo, f.homeShort ?: f.home, pal.home, 120.dp, 38.sp)
            if (f.away.isNotBlank()) {
                Cond("v", 40.sp, Broadcast.Gold)
                BigCrest(f.awayLogo, f.awayShort ?: f.away, pal.away, 120.dp, 38.sp)
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Broadcast.Base.copy(alpha = FixtureArt.TEXT_SCRIM_ALPHA),
                    FixtureArt.TEXT_SCRIM_HOLD to Broadcast.Base.copy(alpha = FixtureArt.TEXT_SCRIM_ALPHA),
                    FixtureArt.TEXT_SCRIM_END to Color.Transparent,
                ),
            ),
        )
        // Into the rows, as the film hero does
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.6f to Color.Transparent, 0.9f to JellytipPalette.Night),
            ),
        )
        if (onShown != null) {
            // Keyed on the game: each game shown gets its own 1 s; an empty, unfocusable layer
            key(f.fid) {
                Box(
                    Modifier.fillMaxSize().onVisibilityChanged(minDurationMs = SportEventRules.SHOWN_MIN_MS, minFractionVisible = 0.5f) {
                        if (it) onShown()
                    },
                )
            }
        }
    }
}

/** The slide's text block, in the film hero header's place (bottom left of the header area). */
@Composable
fun HeroMatchHeader(
    f: FxItem,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current
    with(density) {
        Box(
            modifier = modifier.fillMaxWidth().height((screenHeight * JellytipHomeTokens.HEADER_HEIGHT_FRACTION).dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .padding(start = JellytipHomeTokens.START_PAD_PX.toDp(), bottom = JellytipHomeTokens.BOTTOM_PAD_PX.toDp())
                        .width(JellytipHomeTokens.TEXT_BLOCK_W_PX.toDp()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Cond(
                        heroTag(f, now),
                        13.sp,
                        Color.White,
                        modifier =
                            Modifier
                                .background(if (f.status == "in") Color(0xFFE2264D) else Broadcast.Chip, RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                        tracking = 0.06f,
                    )
                    f.badge?.let { b ->
                        var badgeFailed by remember(b) { mutableStateOf(false) }
                        if (!badgeFailed) {
                            AsyncImage(
                                model = FixtureArt.sized(b, 96),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                onError = { badgeFailed = true },
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Cond(listOfNotNull(f.league, f.stage).joinToString(" · "), 15.sp, Broadcast.Text, weight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Long names give way (ellipsis) rather than push "v" / the away side off the block
                    Cond(f.home, 40.sp, Broadcast.Text, modifier = Modifier.weight(1f, fill = false))
                    if (f.away.isNotBlank()) {
                        Cond("v", 40.sp, Broadcast.Gold)
                        Cond(f.away, 40.sp, Broadcast.Text, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                if (f.status != "pre") Cond("Score hidden until you watch", 14.sp, Broadcast.Text, weight = FontWeight.Medium)
            }
        }
    }
}
