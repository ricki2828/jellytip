package com.github.damontecres.wholphin.jellytip.search

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.iptv.AlternatesMenu
import com.github.damontecres.wholphin.jellytip.iptv.IdentifiedMark
import com.github.damontecres.wholphin.jellytip.iptv.IptvChannel
import com.github.damontecres.wholphin.jellytip.iptv.IptvOnNow
import com.github.damontecres.wholphin.jellytip.iptv.IptvTarget
import com.github.damontecres.wholphin.jellytip.iptv.IptvUpcoming
import com.github.damontecres.wholphin.jellytip.iptv.JellytipMatchModal
import com.github.damontecres.wholphin.jellytip.iptv.JellytipOnNowViewModel
import com.github.damontecres.wholphin.jellytip.iptv.JellytipUpcomingSportViewModel
import com.github.damontecres.wholphin.jellytip.iptv.KindPill
import com.github.damontecres.wholphin.jellytip.iptv.PreGamePill
import com.github.damontecres.wholphin.jellytip.iptv.QualityChips
import com.github.damontecres.wholphin.jellytip.iptv.SportArtBackdrop
import com.github.damontecres.wholphin.jellytip.iptv.SportArtSlot
import com.github.damontecres.wholphin.jellytip.iptv.TimePill
import com.github.damontecres.wholphin.jellytip.iptv.UpcomingOverlays
import com.github.damontecres.wholphin.jellytip.iptv.initials
import com.github.damontecres.wholphin.jellytip.iptv.liveTileBorder
import com.github.damontecres.wholphin.jellytip.iptv.liveTileColors
import com.github.damontecres.wholphin.jellytip.iptv.matchInfoItem
import com.github.damontecres.wholphin.jellytip.iptv.nowSec
import com.github.damontecres.wholphin.jellytip.iptv.rememberSportMuteItems
import com.github.damontecres.wholphin.jellytip.iptv.whenLabel
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.delay

/*
 * Search covers live TV (~/ai/plans/jellytip-search-10x.md, "E"): "Live & upcoming" (wide tiles: LIVE / REPLAY /
 * PRE-GAME / start-time pill, channel logo and quality, the matchup; OK plays what is on, or opens the upcoming overlay
 * with Remind me; long-press = Match info and the other channels) and "Channels" (logo tiles; OK tunes).
 */

private val LIVE_W = 300.dp
private val LIVE_H = 84.dp
private val CHANNEL_W = 220.dp
private val CHANNEL_H = 84.dp

private val providerPrefix = Regex("""^\s*\|[A-Z]{2,3}\|\s*""")

@Composable
internal fun SearchLiveRow(
    hits: List<IptvOnNow>,
    onOpened: () -> Unit,
    onNow: JellytipOnNowViewModel = hiltViewModel(),
    upcoming: JellytipUpcomingSportViewModel = hiltViewModel(),
) {
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    // Remind me in the upcoming overlay shows once the reminders route has answered
    LaunchedEffect(Unit) { upcoming.reminders.start() }
    val airing = remember(hits, now) { hits.filter { now in it.start until it.stop } }
    var menu by remember { mutableStateOf<IptvOnNow?>(null) }
    var matchFor by remember { mutableStateOf<IptvOnNow?>(null) }
    var ahead by remember { mutableStateOf<IptvUpcoming?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ItemRowTitle("Live & upcoming")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(),
        ) {
            items(hits, key = { "${it.channel.id}|${it.start}" }) { o ->
                val on = now in o.start until o.stop
                SearchLiveCard(
                    o,
                    now,
                    onClick = {
                        onOpened()
                        if (on) onNow.open(o, airing, "search live OK") else ahead = o.toUpcoming()
                    },
                    onLongClick = { if (on) menu = o else ahead = o.toUpcoming() },
                )
            }
        }
    }
    matchFor?.let { o ->
        o.match?.let { m ->
            JellytipMatchModal(m, onWatch = { ch -> matchFor = null; onNow.open(o, airing, "search match info watch", ch) }, onDismiss = { matchFor = null })
        }
    }
    menu?.let { o ->
        AlternatesMenu(
            o,
            onPick = { ch -> onNow.open(o, airing, "search live alternate", ch) },
            onDismiss = { menu = null },
            extra = listOfNotNull(matchInfoItem(o.match) { matchFor = o }) + if (o.sport) rememberSportMuteItems(o.league, o.sportName) else emptyList(),
        )
    }
    UpcomingOverlays(ahead, now, onDismiss = { ahead = null }, upcoming)
}

/** Not on yet: the upcoming overlay's model (channels, Match info, Remind me keyed on the fixture, else channel + start). */
private fun IptvOnNow.toUpcoming() =
    IptvUpcoming(
        key = match?.id ?: "${channel.id}:$start",
        title = displayTitle,
        league = league ?: match?.league,
        favourite = false,
        start = start,
        stop = stop,
        kind = kind,
        channel = channel,
        alternateChannels = alternates,
        recordable = false,
        recordingId = null,
        art = art,
        sportName = sportName,
        phase = phase,
        startsAt = startsAt,
        matchInfo = match,
    )

@Composable
private fun SearchLiveCard(
    o: IptvOnNow,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = liveTileColors(o.art),
        border = liveTileBorder(o.art),
        modifier = Modifier.size(LIVE_W, LIVE_H).onFocusChanged { focused = it.isFocused },
    ) {
        SportArtBackdrop(o.art, focused)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            SportArtSlot(o.art, LIVE_H * 0.55f) { ChannelLogo(o.channel, LIVE_H * 0.75f, LIVE_H * 0.55f) }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        o.kind == "replay" || o.match?.replay == true -> KindPill("replay")
                        o.isPre(now) -> PreGamePill()
                        now in o.start until o.stop -> KindPill("live")
                        else -> TimePill(whenLabel(o.startsAt ?: o.start))
                    }
                    QualityChips(o.channel.quality)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        o.displayTitle,
                        fontFamily = JellytipFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (o.match?.inferred == true && o.match.matchup != null) IdentifiedMark()
                }
                Text(
                    listOfNotNull(o.league, o.channel.name.replace(providerPrefix, "")).joinToString("  ·  "),
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Channels": name matches as logo tiles; OK tunes, Up/Down in the player zaps through this row's channels. */
@Composable
internal fun SearchChannelsRow(
    hits: List<SearchChannel>,
    onOpened: () -> Unit,
    onNow: JellytipOnNowViewModel = hiltViewModel(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ItemRowTitle("Channels")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(),
        ) {
            items(hits, key = { it.channel.id }) { c ->
                Surface(
                    onClick = {
                        onOpened()
                        com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl.stop()
                        val all = hits.map { it.channel }
                        onNow.playback.lineup = all
                        onNow.playback.alternates = emptyMap()
                        onNow.playback.play(IptvTarget(c.channel.id, c.channel.name), "search channel OK")
                        onNow.navigationManager.navigateTo(Destination.JellytipLiveTvPlayer(c.channel.id), "search channel OK ${c.channel.name}")
                    },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = liveTileColors(null),
                    modifier = Modifier.size(CHANNEL_W, CHANNEL_H),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        ChannelLogo(c.channel, CHANNEL_H * 0.75f, CHANNEL_H * 0.55f)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (c.nowPhase == "pre") PreGamePill()
                                QualityChips(c.channel.quality)
                            }
                            Text(
                                listOfNotNull(c.channel.num?.toString(), c.channel.name.replace(providerPrefix, "")).joinToString("  "),
                                fontFamily = JellytipFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                c.nowTitle ?: c.channel.group,
                                color = LocalContentColor.current.copy(alpha = 0.75f),
                                fontFamily = JellytipFontFamily,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelLogo(
    ch: IptvChannel,
    width: Dp,
    height: Dp,
) {
    Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
        var failed by remember(ch.logo) { mutableStateOf(false) }
        val logo = ch.logo
        if (logo != null && !failed) {
            AsyncImage(
                model = if (logo.contains('?')) logo else "$logo?s=96",
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(initials(ch.name), color = Color.Unspecified, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
    }
}
