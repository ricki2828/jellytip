package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class JellytipIptvVodViewModel
    @Inject
    constructor(
        val vod: JellytipIptvVodService,
        val navigationManager: NavigationManager,
        /** The library trailer player's client (the trailer cache is the same). */
        @param:com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient val httpClient: okhttp3.OkHttpClient,
        preferences: androidx.datastore.core.DataStore<com.github.damontecres.wholphin.preferences.AppPreferences>,
    ) : ViewModel() {
        /** Trailer autoplay / sound, the same switches as the library details page. */
        val trailerSettings: kotlinx.coroutines.flow.Flow<com.github.damontecres.wholphin.jellytip.JellytipSettings> =
            preferences.data
                .map {
                    com.github.damontecres.wholphin.jellytip.JellytipSettings(
                        trailerAutoplay = !it.jellytipDisableTrailerAutoplay,
                        trailerSound = it.jellytipTrailerSound,
                    )
                }.distinctUntilChanged()
    }

/**
 * Quiet trailer preview on IPTV details (web de81df8e): only the companion's cached trailer, never a YouTube link
 * (that one leaves the app), and only with trailer autoplay on.
 */
fun iptvPassiveTrailer(
    url: String?,
    autoplay: Boolean,
): Boolean = autoplay && !url.isNullOrBlank() && !IptvVodParse.isYouTube(url)

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
    /** The caller's own long-press; null = the title's menu (films and series only, not episodes or recordings). */
    onLongClick: (() -> Unit)? = null,
) {
    // Long-press: the title's menu (web 6d8398bd): My list, Like / Not for me, Not interested, Mark watched
    var menu by remember(item.id) { mutableStateOf(false) }
    if (menu) IptvTileMenu(item, onOpen = onClick) { menu = false }
    val titleMenu = IptvVodParse.isTitleId(item.id) && (item.kind == "movie" || item.kind == "series")
    Surface(
        onClick = onClick,
        onLongClick = onLongClick ?: { if (titleMenu) menu = true else onClick() },
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
        IptvCornerMark(listOfNotNull(item.qualityLabel, item.audioLabel).joinToString(" · ").ifEmpty { null }, Modifier.align(Alignment.TopEnd).padding(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        ) {
            // My list: the library cards' heart (Ricki 21-Nov), kept in step with + My list anywhere in the app
            if (IptvMyList.isOn(item)) {
                com.github.damontecres.wholphin.ui.cards.FavoriteIndicator(fontSize = 16.sp)
            }
            if (item.played) {
                // Watched: a tick like the library's played mark
                Text(
                    text = "✓",
                    color = Color.White,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier =
                        Modifier
                            .background(JellytipPalette.Jelly, RoundedCornerShape(50))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
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
    onOpened: ((String) -> Unit)? = null,
    viewModel: JellytipIptvVodViewModel = hiltViewModel(),
    subtitle: String? = null,
) {
    val h = heightDp.dp
    val w = if (poster) h * 2f / 3f else h * 16f / 9f
    Column(verticalArrangement = Arrangement.spacedBy(com.github.damontecres.wholphin.ui.cards.itemRowTitleGap(subtitle)), modifier = modifier) {
        ItemRowTitle(title, subtitle = subtitle)
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
                    onClick = {
                        onOpened?.invoke(item.id)
                        viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(item.id, item.title), "iptv row OK")
                    },
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
    // Ways to watch: the copy shown can change in place (a pick, or the copy picked last time); Back still leaves the page
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var shownId by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableStateOf(id) }
    var redirected by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableStateOf(false) }
    val detail by produceState<IptvVodDetail?>(null, shownId) {
        val got = viewModel.vod.detail(shownId)
        if (got == null && shownId != id) {
            // The other copy didn't load: back to the one that was opened
            shownId = id
            return@produceState
        }
        val it = got?.item
        if (got != null && it != null && !redirected) {
            redirected = true
            val to =
                IptvStreamFail.rememberedTarget(
                    IptvStreamFail.recallPick(ctx, IptvStreamFail.pickKey(it.kind, it.tmdbId, it.title, it.year)),
                    it.id,
                    got.copies,
                    System.currentTimeMillis() / 1000,
                )
            if (to != null) {
                JellytipTrace.log("iptv", "details: remembered copy $to instead of ${it.id}")
                shownId = to
                return@produceState
            }
        }
        value = got
    }
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
    // Taste signals: details dwell on this IPTV title
    androidx.compose.runtime.DisposableEffect(item?.id) {
        val since = System.currentTimeMillis()
        val t = item?.let { com.github.damontecres.wholphin.jellytip.signals.TasteTarget(if (it.isSeries) "series" else "movie", it.tmdbId, iptvId = it.id) }
        onDispose {
            t?.let { com.github.damontecres.wholphin.jellytip.signals.TasteRules.detailSignal(it, System.currentTimeMillis() - since, System.currentTimeMillis()) }
                ?.let(com.github.damontecres.wholphin.jellytip.signals.JellytipSignals::emit)
        }
    }
    fun playMovie(
        fromStart: Boolean,
        manual: Boolean = false,
    ) {
        val it = item ?: return
        val resume = if (fromStart) 0L else it.progress?.takeIf { p -> p.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L
        // A copy that failed in the last 24 h is passed over for one that didn't (never for a hand-picked copy)
        val now = System.currentTimeMillis() / 1000
        val own = d?.copies?.firstOrNull { c -> c.id == it.id }
        val alt =
            if (!manual && IptvStreamFail.failedLately(IptvStreamFail.failOf(it.id, own?.lastFail), now)) {
                d?.copies
                    ?.filter { c -> c.id != it.id && !IptvStreamFail.failedLately(IptvStreamFail.failOf(c.id, c.lastFail), now) }
                    ?.let { ok -> ok.firstOrNull { c -> c.opted != false } ?: ok.firstOrNull() }
            } else {
                null
            }
        if (alt != null) {
            android.widget.Toast.makeText(ctx, "Skipped ${it.providerName ?: "this copy"}: failed earlier", android.widget.Toast.LENGTH_SHORT).show()
            JellytipTrace.log("iptv", "play vod ${alt.id} (skipped failed ${it.id})")
            viewModel.navigationManager.navigateTo(Destination.JellytipIptvPlay(title = it.title, vodId = alt.id, resumeMs = 0L), "iptv details Play (skip failed)")
            return
        }
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
    // Trailer from the companion's cache plays in-app behind the text, as on the library details page
    val context = androidx.compose.ui.platform.LocalContext.current
    var trailerPlaying by remember(id) { mutableStateOf(false) }
    val trailerUrl = d?.trailer
    val playTrailer: () -> Unit = {
        val url = trailerUrl
        if (url != null) {
            com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl.stop()
            if (IptvVodParse.isYouTube(url)) {
                // Fallback only: a full YouTube URL goes to YouTube, like the library's remote trailers
                com.github.damontecres.wholphin.services.TrailerService.onClick(
                    context,
                    com.github.damontecres.wholphin.data.model.RemoteTrailer("${item?.title ?: title} trailer", url, null),
                ) { dest -> viewModel.navigationManager.navigateTo(dest, "iptv details trailer") }
            } else {
                trailerPlaying = !trailerPlaying
            }
        }
    }
    // Passive preview (web de81df8e, library details' rule): after a rest on the buttons or season chips, the cached
    // trailer plays behind the text; once per visit to that zone, never over the Trailer button's own playback
    val trailerSettings by viewModel.trailerSettings.collectAsState(com.github.damontecres.wholphin.jellytip.JellytipSettings())
    var resumed by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    var buttonsFocused by remember { mutableStateOf(false) }
    var seasonsFocused by remember { mutableStateOf(false) }
    val restZone = buttonsFocused || seasonsFocused
    var autoTrailer by remember(id) { mutableStateOf(false) }
    var autoDone by remember(id) { mutableStateOf(false) }
    LaunchedEffect(restZone) {
        if (!restZone) autoDone = false
    }
    LaunchedEffect(restZone, resumed, trailerUrl, trailerSettings.trailerAutoplay, autoDone, trailerPlaying) {
        autoTrailer = false
        if (restZone && resumed && !trailerPlaying && !autoDone && iptvPassiveTrailer(trailerUrl, trailerSettings.trailerAutoplay)) {
            kotlinx.coroutines.delay(com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens.TRAILER_DELAY_MS)
            autoTrailer = true
        }
    }
    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        (item?.backdrop ?: item?.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (trailerPlaying && trailerUrl != null) {
            com.github.damontecres.wholphin.jellytip.TrailerLayer(
                url = trailerUrl,
                sound = true,
                httpClient = viewModel.httpClient,
                onEnded = {
                    trailerPlaying = false
                    autoDone = true // just watched it: no passive replay until focus leaves and comes back
                },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = autoTrailer && !trailerPlaying && trailerUrl != null,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(800)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(800)),
        ) {
            if (trailerUrl != null) {
                com.github.damontecres.wholphin.jellytip.TrailerLayer(
                    url = trailerUrl,
                    sound = trailerSettings.trailerSound,
                    httpClient = viewModel.httpClient,
                    onEnded = {
                        autoDone = true
                        autoTrailer = false
                    },
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        // Jellytip: "More like this" (companion /v1/similar) sits under the page; the column scrolls to it
        val similar by produceState<com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow?>(null, d?.item?.id) {
            val it = d?.item ?: return@produceState
            value =
                com.github.damontecres.wholphin.jellytip.similar.JellytipSimilar
                    .path(if (it.isSeries) "series" else "movie", it.tmdbId, iptvId = it.id)
                    ?.let { p -> com.github.damontecres.wholphin.jellytip.similar.JellytipSimilar.row(p) }
        }
        // The row only composes once Down finds nothing more below (as the library pages do), so it never pushes the
        // block up under focus when it arrives
        var showSimilar by remember(id) { mutableStateOf(false) }
        val similarFocus = remember { FocusRequester() }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val pageScope = androidx.compose.runtime.rememberCoroutineScope()
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .onPreviewKeyEvent { ev ->
                        if (showSimilar || similar == null || ev.type != KeyEventType.KeyDown || ev.key != Key.DirectionDown) return@onPreviewKeyEvent false
                        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) {
                            showSimilar = true
                            pageScope.launch {
                                withFrameNanos { }
                                withFrameNanos { }
                                similarFocus.tryRequestFocus()
                            }
                        }
                        true
                    }.verticalScroll(rememberScrollState())
                    .padding(start = 48.dp, bottom = 40.dp, end = 48.dp, top = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(JellytipPalette.Jelly))
                if (item != null && IptvMyList.isOn(item)) {
                    com.github.damontecres.wholphin.ui.cards.FavoriteIndicator(fontSize = 14.sp)
                }
                Text(
                    text = "via IPTV" + (item?.providerName?.let { " · $it" } ?: ""),
                    color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 13.sp,
                )
            }
            // TMDB title logo like the library details page; the text title when there is none (or it fails)
            var logoFailed by remember(d?.logo) { mutableStateOf(false) }
            val logo = d?.logo
            if (logo != null && !logoFailed) {
                AsyncImage(
                    model = logo,
                    contentDescription = item?.title ?: title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    onError = { logoFailed = true },
                    modifier = Modifier.width(380.dp).height(120.dp),
                )
            } else {
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
            }
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
                    d.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() } ?: d.genre,
                ).joinToString("  ·  ")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(meta, color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 1)
                if (item.langUnknown) {
                    // Outlined, quiet: the server couldn't confirm this copy's audio language
                    Text(
                        "Language unknown",
                        color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                        fontFamily = JellytipFontFamily,
                        fontSize = 12.sp,
                        modifier =
                            Modifier
                                .border(1.dp, JellytipPalette.Vanilla.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.onFocusChanged { buttonsFocused = it.hasFocus }.horizontalScroll(rememberScrollState()),
                ) {
                    Button(onClick = { playMovie(fromStart = false) }, modifier = Modifier.focusRequester(focus)) {
                        Text(if (p != null) "Resume from ${(p.positionSecs / 60).toInt()} min" else "Play")
                    }
                    if (p != null) {
                        Button(onClick = { playMovie(fromStart = true) }) { Text("Play from start") }
                    }
                    // Opened from a Home double feature: this film, then the other half (library or IPTV)
                    val partner = remember(item.id) { com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.partnerAfterIptv(item.id) }
                    if (partner != null) {
                        Button(
                            onClick = {
                                com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.then =
                                    com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.Then(
                                        com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.IptvHalf(item),
                                        partner,
                                    )
                                // The double bill names this copy: never auto-skip to another one here
                                playMovie(fromStart = true, manual = true)
                            },
                        ) { Text("Play both") }
                    }
                    TrailerButton(d.trailer, trailerPlaying, playTrailer)
                    MyListButton(item, viewModel)
                    IptvTasteButtons(item)
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.onFocusChanged { buttonsFocused = it.hasFocus }.horizontalScroll(rememberScrollState()),
                ) {
                    TrailerButton(d.trailer, trailerPlaying, playTrailer)
                    MyListButton(item, viewModel)
                    IptvTasteButtons(item)
                }
            }
            if (d.copies.size > 1) {
                WaysToWatchRow(d.copies, current = item.id) { c ->
                    IptvStreamFail.rememberPick(ctx, IptvStreamFail.pickKey(item.kind, item.tmdbId, item.title, item.year), c.id)
                    if (c.id != item.id) {
                        JellytipTrace.log("iptv", "ways to watch: pick ${c.id}")
                        shownId = c.id
                    } else if (!item.isSeries) {
                        playMovie(fromStart = false, manual = true)
                    }
                }
            }
            if (d.people.isNotEmpty()) CastRow(d.people)
            if (!item.isSeries) {
                Unit // films: the buttons above are all
            } else if (d.seasons.isEmpty()) {
                Text("No episodes are available.", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 14.sp)
            } else {
                var season by remember(item.id) { mutableIntStateOf(0) }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.onFocusChanged { seasonsFocused = it.hasFocus }.focusGroup().focusRestorer(),
                ) {
                    items(d.seasons.size) { i ->
                        Surface(
                            onClick = { season = i },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                            border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(16.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors =
                                ClickableSurfaceDefaults.colors(
                                    containerColor = if (i == season) JellytipPalette.Vanilla.copy(alpha = 0.18f) else Color.Transparent,
                                    focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                                    contentColor = JellytipPalette.Vanilla,
                                    focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                                    pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                                    pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
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
                        IptvEpisodeCard(e) { playEpisode(e) }
                    }
                }
            }
            similar?.takeIf { showSimilar }?.let { row ->
                Box(Modifier.padding(top = 12.dp).offset(x = (-48).dp)) {
                    com.github.damontecres.wholphin.jellytip.similar.JellytipSimilarRowView(row, Modifier.focusRequester(similarFocus))
                }
            }
        }
    }
}

/**
 * "Ways to watch" (web 846292d8): every copy the companion offers, best first, with quality / language chips, the
 * copy shown marked Selected and a failure note ("Failed 2 h ago"). OK on another copy shows it (and remembers the
 * pick for next time); OK on the shown copy plays it (films).
 */
@Composable
private fun WaysToWatchRow(
    copies: List<IptvCopy>,
    current: String,
    onPick: (IptvCopy) -> Unit,
) {
    val now = remember(copies) { System.currentTimeMillis() / 1000 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ways to watch", color = JellytipPalette.Vanilla.copy(alpha = 0.8f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusGroup().focusRestorer()) {
            items(copies, key = { it.id }) { c ->
                val selected = c.id == current
                val fail = IptvStreamFail.note(IptvStreamFail.failOf(c.id, c.lastFail), now)
                Surface(
                    onClick = { onPick(c) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = if (selected) JellytipPalette.Vanilla.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                            contentColor = JellytipPalette.Vanilla,
                            focusedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                            pressedContainerColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Pastel,
                            pressedContentColor = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.Ink,
                        ),
                    modifier = Modifier.width(240.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = (c.providerName ?: "IPTV") + if (selected) "  ·  Selected" else "",
                            fontFamily = JellytipFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val chips = c.qualityWords + listOfNotNull(c.langLabel)
                        if (chips.isNotEmpty()) {
                            Text(chips.joinToString("  ·  "), fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (c.opted == false) {
                            Text("Outside your switched-on groups", fontFamily = JellytipFontFamily, fontSize = 11.sp, maxLines = 1, modifier = Modifier.alpha(0.7f))
                        }
                        if (fail != null) {
                            Text(fail, color = JellytipPalette.Jelly, fontFamily = JellytipFontFamily, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Like / Not for me / Watched on an IPTV title (web 6d8398bd); the same reactions the library thumbs use. */
@Composable
private fun IptvTasteButtons(item: IptvVodItem) {
    val me = com.github.damontecres.wholphin.jellytip.me.JellytipMe
    // Same gate as the library thumbs: a server without /v1/me shows none of these
    val available by me.available.collectAsState()
    if (!available) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val key = remember(item.id) { me.iptvTasteKey(item.id, item.kind, item.tmdbId) }
    val thumb = me.thumbOfKey(key)
    var played by remember(item.id) { mutableStateOf(item.played || item.progress?.played == true) }
    var busy by remember(item.id) { mutableStateOf(false) }
    val toast: (String?) -> Unit = { err -> if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show() }
    Button(onClick = { me.thumbIptv(item.id, item.kind, item.tmdbId, if (thumb == 1) 0 else 1, toast) }) {
        Text(if (thumb == 1) "Liked ✓" else "Like")
    }
    Button(onClick = { me.thumbIptv(item.id, item.kind, item.tmdbId, if (thumb == -1) 0 else -1, toast) }) {
        Text(if (thumb == -1) "Not for me ✓" else "Not for me")
    }
    Button(
        onClick = {
            if (busy) return@Button
            busy = true
            val next = !played
            played = next
            me.setIptvPlayed(item.id, next) { err ->
                busy = false
                if (err != null) played = !next
                toast(err)
            }
        },
    ) {
        Text(if (played) "Watched ✓" else "Mark watched")
    }
}

/** Trailer: plays the companion's mp4 in-app behind the page (OK again stops it); a YouTube URL opens YouTube. */
@Composable
private fun TrailerButton(
    url: String?,
    playing: Boolean,
    onClick: () -> Unit,
) {
    url ?: return
    Button(onClick = onClick) { Text(if (playing) "Stop trailer" else "Trailer") }
}

/** The TMDB cast with photos (initials when there is none), name and character; focusable so the row scrolls. */
@Composable
private fun CastRow(people: List<IptvCastMember>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.focusGroup().focusRestorer(),
    ) {
        items(people.take(MAX_CAST).size) { i ->
            val p = people[i]
            Surface(
                onClick = {},
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors =
                    ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = JellytipPalette.Vanilla,
                        focusedContentColor = Color.White,
                    ),
                modifier = Modifier.width(92.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        var failed by remember(p.profile) { mutableStateOf(false) }
                        if (p.profile != null && !failed) {
                            AsyncImage(model = p.profile, contentDescription = p.name, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
                        } else {
                            Text(p.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontFamily = JellytipFontFamily, fontSize = 16.sp)
                        }
                    }
                    Text(p.name, fontFamily = JellytipFontFamily, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    p.character?.let {
                        Text(it, fontFamily = JellytipFontFamily, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = JellytipPalette.Vanilla.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

private const val MAX_CAST = 12

/**
 * My list toggle for an IPTV title (plan "My list"), next to Play like the library favourite. Flips at once; goes
 * back with a short note when the server can't change it (older server without the route, offline).
 */
@Composable
private fun MyListButton(
    item: IptvVodItem,
    viewModel: JellytipIptvVodViewModel,
) {
    val on = IptvMyList.isOn(item)
    var busy by remember(item.id) { mutableStateOf(false) }
    var note by remember(item.id) { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                if (busy) return@Button
                val want = !on
                IptvMyList.set(item.id, want)
                if (want) {
                    com.github.damontecres.wholphin.jellytip.signals.JellytipSignals.emit(
                        com.github.damontecres.wholphin.jellytip.signals.TasteRules.event(
                            com.github.damontecres.wholphin.jellytip.signals.TasteTarget(if (item.isSeries) "series" else "movie", item.tmdbId, iptvId = item.id),
                            "mylist",
                            System.currentTimeMillis(),
                        ),
                    )
                }
                busy = true
                note = null
                JellytipTrace.log("iptv", "my list ${item.id} on=$want")
                scope.launch {
                    val result = viewModel.vod.setFavourite(item.id, want)
                    if (result == null) {
                        IptvMyList.set(item.id, !want)
                        note = "Couldn't update My list"
                    } else {
                        IptvMyList.set(item.id, result)
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

/**
 * My list state for IPTV titles across the app: the payload's `favourite`, overridden by changes made on this TV until
 * the rows reload (so a tile's heart follows + My list at once). Cleared per title once a fresh payload agrees.
 */
object IptvMyList {
    private val overrides = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun isOn(item: IptvVodItem): Boolean = overrides[item.id] ?: item.favourite

    fun set(
        id: String,
        on: Boolean,
    ) {
        overrides[id] = on
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** Shown at once, saved behind; put back with a note when the server says no. */
    fun toggle(
        id: String,
        on: Boolean,
        onError: (String?) -> Unit,
    ) {
        set(id, on)
        scope.launch {
            if (save(id, on) == null) {
                set(id, !on)
                onError("Couldn't update My list")
            }
        }
    }

    /** `POST v1/iptv/vod/{id}/favourite {on}`: the server's state, or null when it could not be changed. */
    suspend fun save(
        id: String,
        on: Boolean,
    ): Boolean? {
        val api = com.github.damontecres.wholphin.jellytip.JellytipApi.shared ?: return null
        val (code, body) = api.userCall("v1/iptv/vod/${java.net.URLEncoder.encode(id, "UTF-8")}/favourite", body = """{"on":$on}""") ?: return null
        if (code !in 200..299) return null
        val v = runCatching { org.json.JSONObject(body).optBoolean("favourite", on) }.getOrDefault(on)
        set(id, v)
        return v
    }
}

/** An IPTV tile's long-press menu: the same reactions as the library tiles and IPTV details. */
@Composable
private fun IptvTileMenu(
    item: IptvVodItem,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val me = com.github.damontecres.wholphin.jellytip.me.JellytipMe
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val available by me.available.collectAsState()
    val kind = if (item.isSeries) "series" else "movie"
    val key = me.iptvTasteKey(item.id, kind, item.tmdbId)
    val thumb = me.thumbOfKey(key)
    val toast: (String?) -> Unit = { err -> if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show() }
    val inList = IptvMyList.isOn(item)
    val items =
        buildList {
            add(com.github.damontecres.wholphin.ui.components.DialogItem("Open", dismissOnClick = true) { onOpen() })
            add(
                com.github.damontecres.wholphin.ui.components.DialogItem(if (inList) "♥  In My list — remove" else "♡  Add to My list", dismissOnClick = true) {
                    // Fire and forget in a scope that outlives the menu (it closes on this click)
                    IptvMyList.toggle(item.id, !inList, toast)
                },
            )
            if (available) {
                add(com.github.damontecres.wholphin.ui.components.DialogItem(if (thumb == 1) "Liked ✓ — undo" else "Like", dismissOnClick = true) { me.thumbIptv(item.id, kind, item.tmdbId, if (thumb == 1) 0 else 1, toast) })
                add(com.github.damontecres.wholphin.ui.components.DialogItem(if (thumb == -1) "Not for me ✓ — undo" else "Not for me", dismissOnClick = true) { me.thumbIptv(item.id, kind, item.tmdbId, if (thumb == -1) 0 else -1, toast) })
                val ni = me.isNotInterestedKey(key)
                add(com.github.damontecres.wholphin.ui.components.DialogItem(if (ni) "Not interested ✓ — undo" else "Not interested", dismissOnClick = true) { me.setIptvNotInterested(item.id, kind, item.tmdbId, !ni, toast) })
                val played = item.played || item.progress?.played == true
                add(com.github.damontecres.wholphin.ui.components.DialogItem(if (played) "Mark unwatched" else "Mark watched", dismissOnClick = true) { me.setIptvPlayed(item.id, !played, toast) })
            }
        }
    com.github.damontecres.wholphin.jellytip.JellytipDialog(showDialog = true, title = item.title, dialogItems = items, onDismissRequest = onDismiss, dismissOnClick = true)
}
