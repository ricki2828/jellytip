package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class JellytipIptvVodViewModel
    @Inject
    constructor(
        val vod: JellytipIptvVodService,
        val navigationManager: NavigationManager,
    ) : ViewModel()

/** The IPTV cue: a small jelly dot, with the quality next to it ("● 4K"). */
@Composable
fun IptvCornerMark(
    quality: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(JellytipPalette.Jelly))
        quality?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.8f),
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

/** An IPTV film/series tile: backdrop (or poster) with its title, the corner dot + quality, resume bar. */
@Composable
fun IptvVodTile(
    item: IptvVodItem,
    width: Dp,
    height: Dp,
    poster: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF141414)),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(8.dp)),
            ),
        modifier = modifier.size(width, height),
    ) {
        val image = if (poster) item.poster ?: item.backdrop else item.backdrop ?: item.poster
        var failed by remember(image) { mutableStateOf(false) }
        if (image != null && !failed) {
            AsyncImage(model = image, contentDescription = item.title, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
        }
        if (!poster || image == null || failed) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
            )
            Text(
                text = item.title,
                color = Color.White,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        IptvCornerMark(item.qualityLabel, Modifier.align(Alignment.TopEnd).padding(6.dp))
        item.progress?.takeIf { it.resumable && it.durationSecs > 0 }?.let { p ->
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth((p.positionSecs / p.durationSecs).toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(JellytipPalette.Jelly),
            )
        }
    }
}

/** A server row of IPTV titles (`iptvItems`), drawn like a normal row; OK opens the "via IPTV" details. */
@Composable
fun IptvVodRow(
    title: String,
    items: List<IptvVodItem>,
    poster: Boolean,
    heightDp: Int,
    onFocusTile: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JellytipIptvVodViewModel = hiltViewModel(),
) {
    val h = heightDp.dp
    val w = if (poster) h * 2f / 3f else h * 16f / 9f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        ItemRowTitle(title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(8.dp, 8.dp),
            modifier = Modifier.jellytipRowBleed().fillMaxWidth().focusGroup().focusRestorer(),
        ) {
            items(items, key = { it.id }) { item ->
                IptvVodTile(
                    item = item,
                    width = w,
                    height = h,
                    poster = poster,
                    onClick = { viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(item.id, item.title), "iptv row OK") },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocusTile() },
                )
            }
        }
    }
}

/** "via IPTV" details: backdrop, facts, overview, Play / Resume; series list seasons and episodes. */
@Composable
fun JellytipIptvDetailsPage(
    id: String,
    title: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipIptvVodViewModel = hiltViewModel(),
) {
    val detail by produceState<IptvVodDetail?>(null, id) { value = viewModel.vod.detail(id) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        kotlinx.coroutines.delay(10_000)
        if (detail == null) failed = true
    }
    val focus = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(detail != null) {
        if (detail != null && !placed) {
            placed = true
            withFrameNanos { }
            focus.tryRequestFocus()
        }
    }
    val d = detail
    val item = d?.item
    fun playMovie(fromStart: Boolean) {
        val it = item ?: return
        val resume = if (fromStart) 0L else it.progress?.takeIf { p -> p.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L
        JellytipTrace.log("iptv", "play vod ${it.id} resume=$resume")
        viewModel.navigationManager.navigateTo(Destination.JellytipIptvPlay(title = it.title, vodId = it.id, resumeMs = resume), "iptv details Play")
    }

    fun playEpisode(e: IptvEpisode) {
        val it = item ?: return
        val resume = e.progress?.takeIf { p -> p.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L
        viewModel.navigationManager.navigateTo(
            Destination.JellytipIptvPlay(title = "${it.title} · ${e.title}", seriesId = it.id, episodeId = e.id, resumeMs = resume),
            "iptv episode OK",
        )
    }
    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        (item?.backdrop ?: item?.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 40.dp, end = 48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(JellytipPalette.Jelly))
                Text(
                    text = "via IPTV" + (item?.providerName?.let { " · $it" } ?: ""),
                    color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 13.sp,
                )
            }
            Text(
                text = item?.title ?: title,
                color = JellytipPalette.Vanilla,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 30.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(560.dp),
            )
            if (d == null || item == null) {
                Text(
                    text = if (failed) "This title isn't available right now." else "Loading…",
                    color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 14.sp,
                )
                return@Column
            }
            val meta =
                listOfNotNull(
                    item.year?.toString(),
                    if (item.isSeries) "Series" else d.runtimeMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                    com.github.damontecres.wholphin.jellytip.cards.JellytipTech
                        .fullNames(item.tech.flatMap(com.github.damontecres.wholphin.jellytip.cards.JellytipTech::normalize).toSet())
                        .joinToString("  ·  ")
                        .takeIf { it.isNotEmpty() }
                        ?: item.quality.joinToString(" ") { it.uppercase(Locale.ROOT) }.takeIf { it.isNotEmpty() },
                    item.rating?.let { String.format(Locale.US, "TMDB %.1f", it) },
                    d.genre,
                ).joinToString("  ·  ")
            Text(meta, color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 1)
            if (item.overview.isNotBlank()) {
                Text(
                    item.overview,
                    color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 14.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(560.dp),
                )
            }
            listOfNotNull(d.director?.let { "Directed by $it" }, d.cast).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
            }
            if (!item.isSeries) {
                val p = item.progress?.takeIf { it.resumable }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { playMovie(fromStart = false) }, modifier = Modifier.focusRequester(focus)) {
                        Text(if (p != null) "Resume from ${(p.positionSecs / 60).toInt()} min" else "Play")
                    }
                    if (p != null) {
                        Button(onClick = { playMovie(fromStart = true) }) { Text("Play from start") }
                    }
                    MyListButton(item, viewModel)
                }
            } else {
                MyListButton(item, viewModel)
            }
            if (!item.isSeries) {
                Unit // films: the buttons above are all
            } else if (d.seasons.isEmpty()) {
                Text("No episodes are available.", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 14.sp)
            } else {
                var season by remember(id) { mutableIntStateOf(0) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                    items(d.seasons.size) { i ->
                        Surface(
                            onClick = { season = i },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors =
                                ClickableSurfaceDefaults.colors(
                                    containerColor = if (i == season) JellytipPalette.Vanilla.copy(alpha = 0.18f) else Color.Transparent,
                                    focusedContainerColor = Color.White,
                                    contentColor = JellytipPalette.Vanilla,
                                    focusedContentColor = Color.Black,
                                ),
                            modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                        ) {
                            Text("Season ${d.seasons[i].season}", fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                        }
                    }
                }
                val eps = d.seasons.getOrNull(season)?.episodes.orEmpty()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer()) {
                    items(eps, key = { it.id }) { e ->
                        Surface(
                            onClick = { playEpisode(e) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF141414)),
                            border =
                                ClickableSurfaceDefaults.border(
                                    focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(6.dp)),
                                ),
                            modifier = Modifier.size(208.dp, 117.dp),
                        ) {
                            e.still?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
                            Text(
                                text = "${e.episode}. ${e.title}" + (e.runtimeMin?.let { "  ·  ${it}m" } ?: ""),
                                color = Color.White,
                                fontFamily = JellytipFontFamily,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            )
                            e.progress?.takeIf { it.resumable && it.durationSecs > 0 }?.let { p ->
                                Box(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth((p.positionSecs / p.durationSecs).toFloat().coerceIn(0f, 1f))
                                        .height(3.dp)
                                        .background(JellytipPalette.Jelly),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * My list toggle for an IPTV title (plan "My list"), next to Play like the library favourite. Flips at once; goes
 * back with a short note when the server can't change it (older server without the route, offline).
 */
@Composable
private fun MyListButton(
    item: IptvVodItem,
    viewModel: JellytipIptvVodViewModel,
) {
    var on by remember(item.id) { mutableStateOf(item.favourite) }
    var busy by remember(item.id) { mutableStateOf(false) }
    var note by remember(item.id) { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                if (busy) return@Button
                val want = !on
                on = want
                busy = true
                note = null
                JellytipTrace.log("iptv", "my list ${item.id} on=$want")
                scope.launch {
                    val result = viewModel.vod.setFavourite(item.id, want)
                    if (result == null) {
                        on = !want
                        note = "Couldn't update My list"
                    } else {
                        on = result
                    }
                    busy = false
                }
            },
        ) {
            Text(if (on) "\u2665  In My list" else "\u2661  My list")
        }
        note?.let {
            Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 12.sp)
        }
    }
}
