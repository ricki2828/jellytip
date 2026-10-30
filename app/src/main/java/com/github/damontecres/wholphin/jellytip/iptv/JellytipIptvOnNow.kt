package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.ViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class JellytipOnNowViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: NavigationManager,
    ) : ViewModel() {
        /**
         * OK on an "on now" card: full screen with sound, Up/Down zapping through the cards' channels. [channel] = one
         * of the card's alternates (long-press menu) instead of its main channel.
         */
        fun open(
            o: IptvOnNow,
            all: List<IptvOnNow>,
            reason: String,
            channel: IptvChannel = o.channel,
        ) {
            com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl.stop()
            playback.openOnNow(o, all, channel, reason)
            navigationManager.navigateTo(Destination.JellytipLiveTvPlayer(channel.id), reason)
        }
    }

/** Card: channel logo, programme, progress, LIVE. */
@Composable
fun OnNowCard(
    o: IptvOnNow,
    now: Long,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = liveTileColors(o.art),
        border = liveTileBorder(o.art),
        modifier = modifier.size(width, height).onFocusChanged { focused = it.isFocused },
    ) {
        SportArtBackdrop(o.art, focused)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(Modifier.height(height * 0.55f), contentAlignment = Alignment.Center) {
                SportArtSlot(o.art, height * 0.55f) {
                    Box(Modifier.size(height * 0.75f, height * 0.55f), contentAlignment = Alignment.Center) {
                        var failed by remember(o.channel.logo) { mutableStateOf(false) }
                        val logo = o.channel.logo
                        if (logo != null && !failed) {
                            AsyncImage(
                                model = if (logo.contains('?')) logo else "$logo?s=96",
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                onError = { failed = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(initials(o.channel.name), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Old server (no kind): every card is LIVE, as before
                    KindPill(o.kind, legacyLive = true)
                    if (o.sport) SportMark(androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.8f), 11.dp)
                    Text(
                        text =
                            listOfNotNull(
                                o.channel.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), ""),
                                alsoOnText(o.alternates.size),
                            ).joinToString("  ·  "),
                        fontFamily = JellytipFontFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    QualityChips(o.channel.quality)
                }
                Text(o.title, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val frac = ((now - o.start).toFloat() / (o.stop - o.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.Gray.copy(alpha = 0.4f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(JellytipPalette.Jelly))
                }
            }
        }
        if (o.art?.hasBadge == true) CornerLogo(o.channel.logo, Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

/** The guide's "On now" strip (above the tabs), in the server's order (old server: live sport first). Long-press = alternates. */
@Composable
fun OnNowStrip(
    items: List<IptvOnNow>,
    now: Long,
    onOpen: (IptvOnNow, IptvChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf<IptvOnNow?>(null) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = modifier.fillMaxWidth().focusGroup().focusRestorer(),
    ) {
        items(items, key = { it.channel.id }) { o ->
            OnNowCard(
                o,
                now,
                230.dp,
                56.dp,
                onClick = { onOpen(o, o.channel) },
                onLongClick = if (o.alternates.isNotEmpty()) ({ menu = o }) else null,
            )
        }
    }
    menu?.let { o -> AlternatesMenu(o, onPick = { ch -> onOpen(o, ch) }, onDismiss = { menu = null }) }
}

/** Long-press on an event card: watch it on the main channel or any alternate (best first, as the server ranked them). */
@Composable
fun AlternatesMenu(
    o: IptvOnNow,
    onPick: (IptvChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val items =
        (listOf(o.channel) + o.alternates).map { ch ->
            com.github.damontecres.wholphin.ui.components.DialogItem(
                headlineContent = {
                    Text(
                        listOfNotNull(ch.num?.toString(), ch.name.replace(Regex("""^\s*\|[A-Z]{2,3}\|\s*"""), "")).joinToString("  "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Box(Modifier.size(40.dp, 26.dp), contentAlignment = Alignment.Center) {
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
                            Text(initials(ch.name), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp)
                        }
                    }
                },
                trailingContent = { QualityChips(ch.quality) },
                onClick = { onPick(ch) },
                dismissOnClick = true,
            )
        }
    com.github.damontecres.wholphin.ui.components.DialogPopup(showDialog = true, title = o.title, dialogItems = items, onDismissRequest = onDismiss)
}

/** Home: "Live sport on now" — only when at least two sport channels are live (people without IPTV get nothing). */
@Composable
fun JellytipLiveSportRow(
    items: List<IptvOnNow>,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellytipOnNowViewModel = hiltViewModel(),
) {
    val now by produceState(nowSec()) {
        while (true) {
            delay(30_000)
            value = nowSec()
        }
    }
    var menu by remember { mutableStateOf<IptvOnNow?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemRowTitle("Live sport on now")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(8.dp, 8.dp),
            modifier = modifier.jellytipRowBleed().fillMaxWidth(),
        ) {
            items(items, key = { it.channel.id }) { o ->
                OnNowCard(
                    o,
                    now,
                    280.dp,
                    72.dp,
                    onClick = { viewModel.open(o, items, "home live sport OK") },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
                    onLongClick = if (o.alternates.isNotEmpty()) ({ menu = o }) else null,
                )
            }
        }
    }
    menu?.let { o -> AlternatesMenu(o, onPick = { ch -> viewModel.open(o, items, "home live sport alternate", ch) }, onDismiss = { menu = null }) }
}

/** Live sport for Home: the last answer at once (no late insert), refreshed for next time. */
@Composable
fun rememberLiveSportOnNow(viewModel: JellytipOnNowViewModel = hiltViewModel()): List<IptvOnNow>? {
    val initial = remember { viewModel.service.peekOnNow()?.sportOnly() }
    val loaded by produceState(initial) {
        value = viewModel.service.onNow().sportOnly()
    }
    return loaded
}

/** At least two live sport channels, or nothing. */
fun List<IptvOnNow>.sportOnly(): List<IptvOnNow>? = filter { it.sport }.takeIf { it.size >= 2 }
