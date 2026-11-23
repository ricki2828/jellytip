package com.github.damontecres.wholphin.jellytip.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.delay

/*
 * Home's "My Channels" row (Ricki 23-Nov, via t3x: directly under Continue watching). The same channels, numbers and order
 * as the My Channels page (one view model, so both agree), as landscape tiles: what's on now with the time left, and
 * what's next. OK joins mid-programme, hold OK starts the programme from the beginning (the page's rules).
 */

/** Home's My Channels presence: true once the channels loaded and at least one is viable. Starts the load. */
@Composable
fun rememberHomeMyChannels(viewModel: JellytipMyChannelsViewModel = hiltViewModel()): Boolean {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsState()
    return state.defs.isNotEmpty()
}

/** "Next 21:30 · Title" for the slot after the current one; "" when the schedule has none. */
internal fun myChannelsNextLine(
    r: ChannelRow,
    hm: (Long) -> String,
): String {
    val s = r.sched.slots.getOrNull(1) ?: return ""
    return "Next ${hm(s.startMs)} · ${Channels.itemTitle(s.item)}"
}

@Composable
fun JellytipMyChannelsRow(
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellytipMyChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val rows = remember(state.defs, state.pools, now) { viewModel.rows(now) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemRowTitle("My Channels")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(8.dp, 8.dp),
            modifier = modifier.jellytipRowBleed().fillMaxWidth(),
        ) {
            items(rows, key = { it.def.id }) { r ->
                MyChannelCard(
                    r,
                    now,
                    art = viewModel.art(r.sched.slots.firstOrNull()?.item),
                    onClick = { viewModel.tune(r, fromStart = false) },
                    onLongClick = { viewModel.tune(r, fromStart = true) },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
                )
            }
        }
    }
}

@Composable
internal fun MyChannelCard(
    r: ChannelRow,
    nowMs: Long,
    art: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slot = r.sched.slots.firstOrNull()
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = JellytipSurfaceFocus.scale(row = true),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF1B1B1B),
                focusedContainerColor = Color(0xFF1B1B1B),
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = JellytipPalette.Vanilla,
            ),
        border = JellytipSurfaceFocus.border(shape),
        modifier = modifier.width(320.dp).height(180.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.45f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.9f))))
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${r.number}", fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = JellytipPalette.Jelly)
                Text(
                    r.def.name,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    slot?.let { s -> Channels.itemTitle(s.item) } ?: "",
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(slot?.let { s -> Channels.itemSub(s.item) }.orEmpty(), leftText(slot, nowMs)).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontFamily = JellytipFontFamily,
                    fontSize = 14.sp,
                    color = JellytipPalette.Vanilla.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                myChannelsNextLine(r, ::hm).takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontFamily = JellytipFontFamily, fontSize = 13.sp, color = JellytipPalette.Vanilla.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
