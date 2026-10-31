package com.github.damontecres.wholphin.jellytip.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.rememberIsJellytipAdmin
import com.github.damontecres.wholphin.jellytip.specials.PosterCard
import com.github.damontecres.wholphin.jellytip.specials.SpecialCard
import com.github.damontecres.wholphin.jellytip.specials.SpecialTokens
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONObject

/*
 * Collections hub (~/ai/plans/jellytip-search-10x.md, "C. Collections hub"): `GET /v1/collections` sections
 * (collections, top lists, franchises, countries, decades, awards, directors, actors) as chips over a card grid;
 * `GET /v1/collections/{id}` is one row in the rows=1 shape (itemIds, iptvItems, order, ranks, missing), shown as a
 * poster grid with rank badges on ranked lists and watched ticks.
 */

data class HubCollection(
    val id: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val total: Int,
    val libCount: Int,
    val iptvCount: Int,
    val ranked: Boolean,
    /** movie | tv | mixed (optional). */
    val mediaType: String? = null,
    /** Art fallbacks (optional): a library title of the collection (its Jellyfin backdrop), or the person (director/actor cards). */
    val artItemId: java.util.UUID? = null,
    val personId: java.util.UUID? = null,
    val personImageTag: String? = null,
) {
    /** "12 films · 5 on IPTV" */
    val subtitle: String
        get() = listOfNotNull("$total ${if (total == 1) "title" else "titles"}", iptvCount.takeIf { it > 0 }?.let { "$it on IPTV" }).joinToString(" · ")
}

data class HubSection(
    val key: String,
    val title: String,
    val items: List<HubCollection>,
)

object HubParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    fun sections(json: String): List<HubSection> {
        val a = JSONObject(json).optJSONArray("sections") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val s = a.optJSONObject(i) ?: return@mapNotNull null
            val items = s.optJSONArray("items")
            val list =
                (0 until (items?.length() ?: 0)).mapNotNull { j ->
                    val o = items!!.optJSONObject(j) ?: return@mapNotNull null
                    val art = o.optJSONObject("art")
                    HubCollection(
                        id = o.str("id") ?: return@mapNotNull null,
                        title = o.str("title") ?: return@mapNotNull null,
                        poster = art?.str("poster"),
                        backdrop = art?.str("backdrop"),
                        total = o.optInt("total", 0),
                        libCount = o.optInt("libCount", 0),
                        iptvCount = o.optInt("iptvCount", 0),
                        ranked = o.optBoolean("ranked", false),
                        mediaType = o.str("mediaType"),
                        artItemId = art?.str("itemId")?.let { runCatching { it.toUUID() }.getOrNull() },
                        personId = art?.optJSONObject("person")?.str("id")?.let { runCatching { it.toUUID() }.getOrNull() },
                        personImageTag = art?.optJSONObject("person")?.str("imageTag"),
                    )
                }
            HubSection(s.str("key") ?: return@mapNotNull null, s.str("title") ?: s.optString("key"), list).takeIf { it.items.isNotEmpty() }
        }
    }
}

/**
 * Which row ids open as a hub collection ("See all" on Home rows): one `GET /v1/collections` per process (retried after
 * 10 min on failure), started by the first server row drawn and delayed so it never competes with Home loading. A plain
 * StateFlow (snapshot rule).
 */
object JellytipHubIndex {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val _ids = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val ids: kotlinx.coroutines.flow.StateFlow<Set<String>> = _ids

    @Volatile
    private var nextTry = 0L

    fun ensureLoaded() {
        val now = System.currentTimeMillis()
        if (now < nextTry) return
        nextTry = now + RETRY_MS
        scope.launch {
            kotlinx.coroutines.delay(START_DELAY_MS)
            val sections = loadSections()
            if (sections == null) return@launch
            _ids.value = sections.flatMap { s -> s.items.map { it.id } }.toSet()
            nextTry = Long.MAX_VALUE // loaded: once per process
        }
    }

    private const val RETRY_MS = 10 * 60_000L
    private const val START_DELAY_MS = 5_000L
}

/** The last card of a Home row whose id is a hub collection: opens it as a full grid. */
@Composable
internal fun SeeAllCard(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SpecialCard(width = width, height = height, onClick = onClick, onLongClick = onClick, modifier = modifier) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)))
        Text("See all  ›", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
    }
}

/** null = no answer / no route (older server); empty = nothing to show. */
private suspend fun loadSections(): List<HubSection>? {
    val api = JellytipApi.shared ?: return null
    val (code, body) = api.userCall("v1/collections", timeoutMs = 10_000) ?: return null
    if (code != 200) return null
    val sections = runCatching { HubParse.sections(body) }.getOrNull() ?: return null
    // Art may be companion-relative: make it absolute
    return sections.map { s ->
        s.copy(items = s.items.map { c -> c.copy(poster = c.poster?.let { api.resolveUrl(it) }, backdrop = c.backdrop?.let { api.resolveUrl(it) }) })
    }
}

private suspend fun loadCollection(id: String): JellytipServerRow? {
    val api = JellytipApi.shared ?: return null
    val (code, body) = api.userCall("v1/collections/${java.net.URLEncoder.encode(id, "UTF-8")}", timeoutMs = 15_000) ?: return null
    if (code != 200) return null
    return runCatching {
        val o = JSONObject(body)
        JellytipRowsParser.row(o.optJSONObject("row") ?: o)
    }.getOrNull()
}

/** The hub: section chips across the top, the selected section's collections as a grid of landscape cards. */
@Composable
fun JellytipCollectionsHubPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    // null = loading; empty = none or unavailable
    var failed by remember { mutableStateOf(false) }
    val sections by produceState<List<HubSection>?>(null) {
        val got = loadSections()
        failed = got == null
        value = got.orEmpty()
    }
    var selected by remember { mutableIntStateOf(0) }
    val firstChip = remember { FocusRequester() }
    val list = sections
    LaunchedEffect(list?.isNotEmpty() == true) {
        if (list?.isNotEmpty() == true) {
            withFrameNanos { }
            firstChip.tryRequestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxSize().padding(top = 24.dp)) {
        Text("Collections", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, modifier = Modifier.padding(start = 8.dp))
        when {
            list == null -> Text("Loading…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, modifier = Modifier.padding(8.dp))
            list.isEmpty() ->
                Text(
                    if (failed) "Collections aren't available right now." else "No collections yet.",
                    color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                    fontFamily = JellytipFontFamily,
                    modifier = Modifier.padding(8.dp),
                )
            else -> {
                val sel = selected.coerceIn(0, list.size - 1)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.focusGroup().focusRestorer(firstChip),
                ) {
                    itemsIndexed(list, key = { _, s -> s.key }) { i, s ->
                        HubChip("${s.title}  ${s.items.size}", selected = i == sel, onClick = { selected = i }, modifier = if (i == 0) Modifier.focusRequester(firstChip) else Modifier)
                    }
                }
                val section = list[sel]
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 48.dp),
                    modifier = Modifier.fillMaxSize().focusGroup().focusRestorer(),
                ) {
                    items(section.items, key = { "${section.key}:${it.id}" }) { c ->
                        HubCard(c) {
                            JellytipTrailerControl.stop()
                            viewModel.navigationManager.navigateTo(Destination.JellytipHubCollection(c.id, c.title), "collections hub OK")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) JellytipPalette.Vanilla.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier,
    ) {
        Text(text, fontFamily = JellytipFontFamily, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** A collection: its art (backdrop, else poster), title and "12 titles · 5 on IPTV". */
@Composable
private fun HubCard(
    c: HubCollection,
    onClick: () -> Unit,
) {
    val images = com.github.damontecres.wholphin.ui.LocalImageUrlService.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Art: the hub's backdrop / poster, else a library title's Jellyfin backdrop, else the person's photo
    val art =
        remember(c.id, density) {
            with(density) {
                c.backdrop ?: c.poster
                    ?: c.artItemId?.let { images.getItemImageUrl(itemId = it, imageType = org.jellyfin.sdk.model.api.ImageType.BACKDROP, fillWidth = 220.dp.roundToPx(), fillHeight = 124.dp.roundToPx()) }
                    ?: c.personId?.let { images.getItemImageUrl(itemId = it, imageType = org.jellyfin.sdk.model.api.ImageType.PRIMARY, fillWidth = 220.dp.roundToPx(), tag = c.personImageTag) }
            }
        }
    SpecialCard(width = 220.dp, height = 124.dp, onClick = onClick, onLongClick = onClick, modifier = Modifier) {
        var failed by remember(art) { mutableStateOf(false) }
        if (art != null && !failed) {
            AsyncImage(
                model = art,
                contentDescription = c.title,
                contentScale = ContentScale.Crop,
                // Portraits: keep the face (top of the photo)
                alignment = if (c.backdrop == null && c.poster == null && c.artItemId == null && c.personId != null) Alignment.TopCenter else Alignment.Center,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(c.title, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.subtitle, color = Color.White.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/**
 * One hub collection as a poster grid: library, IPTV and "Not available" titles in the row's order (rank order on
 * ranked lists, with a rank badge), watched library films ticked.
 */
@Composable
fun JellytipHubCollectionPage(
    id: String,
    title: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    var failed by remember(id) { mutableStateOf(false) }
    val row by produceState<JellytipServerRow?>(null, id) {
        value = loadCollection(id)
        failed = value == null
    }
    val r = row
    val library by produceState<List<BaseItem>?>(null, r?.id, r?.itemIds) {
        value = if (r == null) null else viewModel.rows.items(r) ?: emptyList()
    }
    val isAdmin by rememberIsJellytipAdmin()
    val tiles =
        remember(r, library) {
            if (r == null || library == null) {
                null
            } else {
                val shown =
                    com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse.ordered<TrendTile>(
                        r.order,
                        library.orEmpty(),
                        r.iptvItems,
                        { TrendTile.Lib(it, r.ranks[JellytipServerRow.rankKey(it.id.toString())] ?: 0) },
                        { TrendTile.Vod(it, r.ranks[JellytipServerRow.rankKey(it.id)] ?: 0) },
                    )
                val all = shown + r.missing.map { TrendTile.Missing(it) }
                if (r.ranks.isEmpty() && r.missing.none { it.rank > 0 }) all else all.sortedBy { if (it.rank > 0) it.rank else Int.MAX_VALUE }
            }
        }
    val ranked = r != null && (r.ranks.isNotEmpty() || r.isTop10)
    val first = remember { FocusRequester() }
    LaunchedEffect(tiles?.isNotEmpty() == true) {
        if (tiles?.isNotEmpty() == true) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.fillMaxSize().padding(top = 24.dp)) {
        ItemRowTitle(r?.title?.ifBlank { null } ?: title)
        when {
            tiles == null ->
                Text(
                    if (failed) "This collection isn't available right now." else "Loading…",
                    color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                    fontFamily = JellytipFontFamily,
                    modifier = Modifier.padding(8.dp),
                )
            tiles.isEmpty() -> Text("Nothing in this collection yet.", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, modifier = Modifier.padding(8.dp))
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(SpecialTokens.POSTER_W + 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 48.dp),
                    modifier = Modifier.fillMaxSize().focusGroup().focusRestorer(first),
                ) {
                    items(tiles.size, key = { tiles[it].key }) { i ->
                        val t = tiles[i]
                        val m = if (i == 0) Modifier.focusRequester(first) else Modifier
                        Box {
                            when (t) {
                                is TrendTile.Lib ->
                                    PosterCard(t.item, onClick = {
                                        JellytipTrailerControl.stop()
                                        viewModel.navigationManager.navigateTo(t.item.destination(), "hub collection OK")
                                    }, onLongClick = {}, modifier = m)

                                is TrendTile.Vod ->
                                    com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile(
                                        item = t.item,
                                        width = SpecialTokens.POSTER_W,
                                        height = SpecialTokens.POSTER_H,
                                        poster = true,
                                        onClick = { viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(t.item.id, t.item.title), "hub collection IPTV OK") },
                                        modifier = m,
                                    )

                                is TrendTile.Missing ->
                                    if (isAdmin && t.m.tmdbId > 0) {
                                        SpecialCard(
                                            width = SpecialTokens.POSTER_W,
                                            height = SpecialTokens.POSTER_H,
                                            onClick = {
                                                viewModel.navigationManager.navigateTo(
                                                    Destination.JellytipDiscover(
                                                        com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle(
                                                            tmdbId = t.m.tmdbId,
                                                            mediaType = if (r?.mediaType == "tv") "tv" else "movie",
                                                            title = t.m.title,
                                                            year = t.m.year,
                                                            posterUrl = t.m.poster,
                                                        ),
                                                    ),
                                                    "hub collection missing OK",
                                                )
                                            },
                                            onLongClick = {},
                                            modifier = m,
                                        ) { MissingTrendArt(t.m) }
                                    } else {
                                        Box(
                                            Modifier
                                                .size(SpecialTokens.POSTER_W, SpecialTokens.POSTER_H)
                                                .background(Color(0xFF141414), RoundedCornerShape(8.dp)),
                                        ) { MissingTrendArt(t.m) }
                                    }
                            }
                            if (t is TrendTile.Lib && t.item.data.userData?.played == true) {
                                Text(
                                    "✓",
                                    color = Color.White,
                                    fontFamily = JellytipFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .background(JellytipPalette.Jelly, RoundedCornerShape(50))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            if (ranked && t.rank > 0) {
                                Text(
                                    t.rank.toString(),
                                    color = Color.Black,
                                    fontFamily = JellytipFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier =
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .padding(6.dp)
                                            .background(JellytipPalette.Vanilla, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
        }
    }
}
