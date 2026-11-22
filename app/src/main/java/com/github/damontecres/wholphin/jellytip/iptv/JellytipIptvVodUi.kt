package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleMenu
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.JellytipButton
import com.github.damontecres.wholphin.jellytip.JellytipDetailsBlockContent
import com.github.damontecres.wholphin.jellytip.JellytipDetailsStill
import com.github.damontecres.wholphin.jellytip.details.IptvPlayTarget
import com.github.damontecres.wholphin.jellytip.details.JellytipDetailsHero
import com.github.damontecres.wholphin.jellytip.details.JellytipDetailsScaffold
import com.github.damontecres.wholphin.jellytip.details.JellytipDetailsSection
import com.github.damontecres.wholphin.jellytip.details.JellytipEpisodeTile
import com.github.damontecres.wholphin.jellytip.details.iptvHeroOf
import com.github.damontecres.wholphin.jellytip.details.myListLabel
import com.github.damontecres.wholphin.jellytip.details.rememberJellytipDetailsScaffoldState
import com.github.damontecres.wholphin.jellytip.details.resolveIptvSeriesPlay
import com.github.damontecres.wholphin.jellytip.details.seasonChipLabel
import com.github.damontecres.wholphin.jellytip.details.seriesPlayLabel
import com.github.damontecres.wholphin.jellytip.details.seriesStatusLine
import com.github.damontecres.wholphin.jellytip.nav.JellytipSeasonChips
import com.github.damontecres.wholphin.ui.cards.personRowCardWidth
import com.github.damontecres.wholphin.ui.ifElse
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
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
import com.github.damontecres.wholphin.jellytip.jellytipFocusRoom
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class JellytipIptvVodViewModel
    @Inject
    constructor(
        val vod: JellytipIptvVodService,
        val navigationManager: NavigationManager,
        private val api: org.jellyfin.sdk.api.client.ApiClient,
        /** The library trailer player's client (the trailer cache is the same). */
        @param:com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient val httpClient: okhttp3.OkHttpClient,
        preferences: androidx.datastore.core.DataStore<com.github.damontecres.wholphin.preferences.AppPreferences>,
    ) : ViewModel() {
        /**
         * OK on an IPTV Continue Watching entry. An episode whose series is in the library, with that exact episode there too,
         * plays from the library (it seeks, has subtitles; "source less important") at the IPTV copy's position (same episode,
         * so the timestamp is close enough). Otherwise, or when the lookup is slow / fails, the IPTV player as before.
         */
        fun openResume(r: IptvResume) {
            val series = r.seriesLibraryUuid
            if (series == null || r.season == null || r.episode == null) {
                navigationManager.navigateTo(r.destination(), "continue IPTV ${r.kind}")
                return
            }
            viewModelScope.launch {
                val lib =
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(IptvResumeLibrary.LOOKUP_MS) {
                            val eps =
                                api.tvShowsApi
                                    .getEpisodes(seriesId = series, season = r.season, isMissing = false, limit = 200)
                                    .content.items
                                    .map { IptvResumeLibrary.Ep(it.id, it.parentIndexNumber, it.indexNumber) }
                            IptvResumeLibrary.pick(eps, r.season, r.episode)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        timber.log.Timber.w(e, "library episode lookup")
                        null
                    }
                if (lib != null) {
                    JellytipTrace.log("iptv", "continue: ${r.id} S${r.season}E${r.episode} plays from the library $lib at ${r.pos.toLong()} s")
                    navigationManager.navigateTo(Destination.Playback(itemId = lib, positionMs = (r.pos * 1000).toLong()), "continue IPTV episode -> library")
                } else {
                    navigationManager.navigateTo(r.destination(), "continue IPTV ${r.kind}")
                }
            }
        }

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

/** Test tag of [IptvCornerMark]: tiles no longer draw one (source-neutral tiles), so shots assert it is absent from them. */
const val IPTV_CORNER_MARK_TAG = "iptv-corner-mark"

/** The IPTV cue: a small jelly dot, with the quality next to it ("● 4K"). Not on tiles any more (they look like library tiles); still on the Top hit and special cards. */
@Composable
fun IptvCornerMark(
    quality: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.testTag(IPTV_CORNER_MARK_TAG).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(JellytipPalette.Jelly))
        quality?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.8f),
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * An IPTV film/series tile: the same tile as a library item's ([com.github.damontecres.wholphin.jellytip.cards.JellytipTile]):
 * same size, art treatment, title-in-art, badges and focus, fed by [toTileModel]. No source mark.
 */
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
    if (menu) IptvTileMenu(item, onDismiss = { menu = false }, onOpen = onClick)
    val titleMenu = IptvVodParse.isTitleId(item.id) && (item.kind == "movie" || item.kind == "series")
    // My list: the library cards' heart, kept in step with + My list anywhere in the app
    val favorite = IptvMyList.isOn(item)
    val model = remember(item, poster, favorite) { item.toTileModel(poster, favorite) }
    com.github.damontecres.wholphin.jellytip.cards.JellytipTile(
        model = model,
        width = width,
        height = height,
        onClick = onClick,
        onLongClick = onLongClick ?: { if (titleMenu) menu = true else onClick() },
        modifier = modifier,
    )
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
    IptvVodRowContent(
        title = title,
        items = items,
        poster = poster,
        heightDp = heightDp,
        onFocusTile = onFocusTile,
        onOpen = { item ->
            onOpened?.invoke(item.id)
            viewModel.navigationManager.navigateTo(JellytipTitleOpen.detailsFor(item), "iptv row OK")
        },
        modifier = modifier,
        subtitle = subtitle,
    )
}

/** The row of [IptvVodRow] with plain values (extract-only split: source-neutral shots draw this; the view model only supplied navigation). */
@Composable
internal fun IptvVodRowContent(
    title: String,
    items: List<IptvVodItem>,
    poster: Boolean,
    heightDp: Int,
    onFocusTile: () -> Unit,
    onOpen: (IptvVodItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val h = heightDp.dp
    val w = if (poster) h * 2f / 3f else h * 16f / 9f
    Column(verticalArrangement = Arrangement.spacedBy(com.github.damontecres.wholphin.ui.cards.itemRowTitleGap(subtitle)), modifier = modifier) {
        ItemRowTitle(title, subtitle = subtitle)
        // The library rows' focus scope: the focused tile glows and its siblings dim
        com.github.damontecres.wholphin.jellytip.JellytipRowFocusScope { rowFocusModifier ->
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(8.dp, 8.dp),
                modifier = rowFocusModifier.jellytipRowBleed().fillMaxWidth().focusGroup().focusRestorer(),
            ) {
                items(items, key = { it.id }) { item ->
                    IptvVodTile(
                        item = item,
                        width = w,
                        height = h,
                        poster = poster,
                        onClick = { onOpen(item) },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocusTile() },
                    )
                }
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
    /** Picked on purpose (library "Ways to watch"): stays on this IPTV copy even when the library has the title. */
    pick: Boolean = false,
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
        // Last net: the detail itself says the library has this title (a path that never saw the cross-link): its page instead
        if (!pick && it != null && shownId == id) {
            val lib = com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen.detailsFor(it)
            if (lib is com.github.damontecres.wholphin.ui.nav.Destination.MediaItem) {
                JellytipTrace.log("iptv", "details: ${it.id} is in the library -> ${lib.itemId}")
                viewModel.navigationManager.replaceTop(lib)
                return@produceState
            }
        }
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
    val trailerSettings by viewModel.trailerSettings.collectAsState(com.github.damontecres.wholphin.jellytip.JellytipSettings())
    JellytipIptvDetailsContent(
        id = id,
        title = title,
        detail = detail,
        failed = failed,
        trailerSettings = trailerSettings,
        httpClient = viewModel.httpClient,
        onShowCopy = { shownId = it },
        onNavigate = { dest, why -> viewModel.navigationManager.navigateTo(dest, why) },
        setFavourite = { vodId, on -> viewModel.vod.setFavourite(vodId, on) },
        modifier = modifier,
    )
}

/**
 * The IPTV details page itself, from plain values: [detail] null = loading (or [failed]); every action is a lambda.
 * Split from [JellytipIptvDetailsPage] so a test can render it without Hilt.
 *
 * Built on the same scaffold as the library pages: the hero (title / logo, info line, one-line synopsis, buttons) and the
 * sections that Down reveals (Episodes, Ways to watch, People, More like this).
 */
@Composable
fun JellytipIptvDetailsContent(
    id: String,
    title: String,
    detail: IptvVodDetail?,
    failed: Boolean,
    trailerSettings: com.github.damontecres.wholphin.jellytip.JellytipSettings,
    httpClient: okhttp3.OkHttpClient,
    onShowCopy: (String) -> Unit,
    onNavigate: (Destination, String?) -> Unit,
    setFavourite: suspend (String, Boolean) -> Boolean?,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val d = detail
    val item = d?.item
    val scaffoldState = rememberJellytipDetailsScaffoldState()
    // Focus on the buttons row (its restorer lands on the primary button): once the detail has arrived, and again when the
    // full-screen trailer closes
    val rowReq = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(detail != null) {
        if (detail != null && !placed) {
            placed = true
            withFrameNanos { }
            rowReq.tryRequestFocus()
        }
    }
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
            onNavigate(Destination.JellytipIptvPlay(title = it.title, vodId = alt.id, resumeMs = 0L), "iptv details Play (skip failed)")
            return
        }
        JellytipTrace.log("iptv", "play vod ${it.id} resume=$resume")
        onNavigate(Destination.JellytipIptvPlay(title = it.title, vodId = it.id, resumeMs = resume), "iptv details Play")
    }

    fun playEpisode(e: IptvEpisode) {
        val it = item ?: return
        val resume = e.progress?.takeIf { p -> p.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L
        onNavigate(
            Destination.JellytipIptvPlay(title = "${it.title} · ${e.title}", seriesId = it.id, episodeId = e.id, resumeMs = resume),
            "iptv episode OK",
        )
    }
    // Series: the one episode the primary button names and plays, and the season the Episodes section opens on
    val resolved = remember(d) { if (d != null && d.item.isSeries) resolveIptvSeriesPlay(d.seasons) else null }
    var season by remember(item?.id) { mutableIntStateOf(resolved?.seasonIndex ?: 0) }

    // My list: flips at once, goes back with a short note under the buttons when the server can't change it
    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
    var myListBusy by remember(item?.id) { mutableStateOf(false) }
    var myListNote by remember(item?.id) { mutableStateOf<String?>(null) }
    fun toggleMyList(v: IptvVodItem) {
        if (myListBusy) return
        val want = !IptvMyList.isOn(v)
        IptvMyList.set(v.id, want)
        if (want) {
            com.github.damontecres.wholphin.jellytip.signals.JellytipSignals.emit(
                com.github.damontecres.wholphin.jellytip.signals.TasteRules.event(
                    com.github.damontecres.wholphin.jellytip.signals.TasteTarget(if (v.isSeries) "series" else "movie", v.tmdbId, iptvId = v.id),
                    "mylist",
                    System.currentTimeMillis(),
                ),
            )
        }
        myListBusy = true
        myListNote = null
        JellytipTrace.log("iptv", "my list ${v.id} on=$want")
        pageScope.launch {
            val result = setFavourite(v.id, want)
            if (result == null) {
                IptvMyList.set(v.id, !want)
                myListNote = "Couldn't update My list"
            } else {
                IptvMyList.set(v.id, result)
            }
            myListBusy = false
        }
    }

    // Trailer button: the companion's cached mp4 plays full screen over the page (library details plays it in the player);
    // Back returns to the Trailer button. Only the passive preview below plays behind the text.
    val context = androidx.compose.ui.platform.LocalContext.current
    var trailerPlaying by remember(id) { mutableStateOf(false) }
    var trailerWasOpen by remember(id) { mutableStateOf(false) }
    LaunchedEffect(trailerPlaying) {
        // Back (or the end) from the full-screen trailer: the buttons row takes focus again and restores the Trailer button
        if (trailerPlaying) {
            trailerWasOpen = true
        } else if (trailerWasOpen) {
            withFrameNanos { }
            rowReq.tryRequestFocus()
        }
    }
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
                ) { dest -> onNavigate(dest, "iptv details trailer") }
            } else {
                trailerPlaying = !trailerPlaying
            }
        }
    }
    // Passive preview (web de81df8e, library details' rule): after a rest on the buttons, the cached trailer plays behind
    // the text; once per visit to that zone, never over the Trailer button's own playback
    var resumed by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val restZone = scaffoldState.buttonRowFocused
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
    // Jellytip: "More like this" (companion /v1/similar); taken only while the lower rows are not open, so it never
    // pops in under focus
    val similarLoaded by produceState<com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow?>(null, d?.item?.id) {
        val it = d?.item ?: return@produceState
        value =
            com.github.damontecres.wholphin.jellytip.similar.JellytipSimilar
                .path(if (it.isSeries) "series" else "movie", it.tmdbId, iptvId = it.id)
                ?.let { p -> com.github.damontecres.wholphin.jellytip.similar.JellytipSimilar.row(p) }
    }
    var similar by remember(id) { mutableStateOf<com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow?>(null) }
    LaunchedEffect(similarLoaded, scaffoldState.sectionsActive) {
        if (similarLoaded != null && !scaffoldState.sectionsActive) similar = similarLoaded
    }
    var overviewOpen by remember(id) { mutableStateOf(false) }
    var moreOpen by remember(id) { mutableStateOf(false) }

    // The hero: one model for every source (see iptvHeroOf)
    androidx.compose.ui.platform.LocalConfiguration.current // a language change re-resolves the "Directed by" string
    val resources = ctx.resources
    val statusLine =
        if (d != null && d.item.isSeries) {
            seriesStatusLine(d.seasons.map { it.season }, resolved?.season, resolved?.episode?.episode) ?: "No episodes are available."
        } else {
            null
        }
    val hero =
        if (d != null) {
            iptvHeroOf(d, title, statusLine) { resources.getString(R.string.directed_by, it) }
        } else {
            JellytipDetailsHero(title = title, infoText = if (failed) "This title isn't available right now." else "Loading…")
        }
    val playWord = stringResource(R.string.play)
    val resumeWord = stringResource(R.string.resume)
    val restartWord = stringResource(R.string.restart)
    val trailerWord = stringResource(R.string.jellytip_trailer)
    val moreWord = stringResource(R.string.jellytip_more)
    val moreAvailable by com.github.damontecres.wholphin.jellytip.me.JellytipMe.available.collectAsState()
    // Opened from a Home double feature: this film, then the other half (library or IPTV)
    val partner = remember(item?.id) { item?.takeIf { !it.isSeries }?.let { com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.partnerAfterIptv(it.id) } }
    val buttons =
        buildList {
            if (item != null && d != null) {
                if (!item.isSeries) {
                    val p = item.progress?.takeIf { it.resumable }
                    add(JellytipButton(if (p != null) resumeWord else playWord, primary = true, onClick = { playMovie(fromStart = false) }))
                    if (p != null) add(JellytipButton(restartWord, { playMovie(fromStart = true) }))
                    if (partner != null) {
                        add(
                            JellytipButton("▶ Play both", {
                                com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.then =
                                    com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.Then(
                                        com.github.damontecres.wholphin.jellytip.specials.JellytipDoubleBill.IptvHalf(item),
                                        partner,
                                    )
                                // The double bill names this copy: never auto-skip to another one here
                                playMovie(fromStart = true, manual = true)
                            }),
                        )
                    }
                } else if (resolved != null) {
                    add(
                        JellytipButton(
                            seriesPlayLabel(resolved.season, resolved.episode.episode, resolved.resumable, resumeWord, playWord),
                            primary = true,
                            onClick = { playEpisode(resolved.episode) },
                        ),
                    )
                }
                add(JellytipButton(myListLabel(IptvMyList.isOn(item)), { toggleMyList(item) }))
                if (d.trailer != null) add(JellytipButton(trailerWord, playTrailer))
                // (More holds the taste actions; without the companion's account service it would be an empty menu)
                if (moreAvailable) add(JellytipButton(moreWord, { moreOpen = true }))
            }
        }
    val sections =
        listOf(
            JellytipDetailsSection(EPISODES_SECTION, d != null && d.item.isSeries && d.seasons.isNotEmpty()) { focus ->
                if (d != null) {
                    IptvEpisodesSection(
                        seasons = d.seasons,
                        resolved = resolved,
                        season = season,
                        onSeason = { season = it },
                        focus = focus,
                        onPlay = { e -> playEpisode(e) },
                    )
                }
            },
            JellytipDetailsSection("ways", d != null && d.copies.size > 1) { focus ->
                if (d != null && item != null) {
                    WaysToWatchRow(d.copies, current = item.id, modifier = Modifier.focusRequester(focus)) { c ->
                        IptvStreamFail.rememberPick(ctx, IptvStreamFail.pickKey(item.kind, item.tmdbId, item.title, item.year), c.id)
                        if (c.id != item.id) {
                            JellytipTrace.log("iptv", "ways to watch: pick ${c.id}")
                            onShowCopy(c.id)
                        } else if (!item.isSeries) {
                            playMovie(fromStart = false, manual = true)
                        }
                    }
                }
            },
            // The cast has no ids to open: plain cells, drawn but never a focus stop
            JellytipDetailsSection("people", d != null && d.people.isNotEmpty(), focusable = false) { _ ->
                if (d != null) IptvPeopleRow(d.people)
            },
            JellytipDetailsSection("similar", similar != null) { focus ->
                similar?.let {
                    com.github.damontecres.wholphin.jellytip.similar.JellytipSimilarRowView(
                        it,
                        Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            },
        )
    Box(modifier.fillMaxSize()) {
        JellytipDetailsScaffold(
            modifier = Modifier.fillMaxSize(),
            state = scaffoldState,
            backdrop = { _, dimmed ->
                val still = item?.backdrop ?: item?.poster
                // Like the library: no art and no trailer = nothing drawn
                if (still != null || trailerUrl != null) {
                    JellytipDetailsStill(url = still, focal = null, dimmed = dimmed) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = autoTrailer && !trailerPlaying && trailerUrl != null,
                            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(800)),
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(800)),
                        ) {
                            if (trailerUrl != null) {
                                com.github.damontecres.wholphin.jellytip.TrailerLayer(
                                    url = trailerUrl,
                                    sound = trailerSettings.trailerSound,
                                    httpClient = httpClient,
                                    onEnded = {
                                        autoDone = true
                                        autoTrailer = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            hero = { slot ->
                JellytipDetailsBlockContent(
                    hero = hero,
                    buttons = buttons,
                    trailers = emptyList(),
                    onTrailer = {},
                    onOverview = { overviewOpen = true },
                    buttonRowModifier = slot.buttonRowModifier.focusRequester(rowReq),
                    modifier = slot.modifier,
                    onFocusWithin = slot.onFocusWithin,
                    below = {
                        myListNote?.let {
                            Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 12.sp)
                        }
                    },
                )
            },
            sections = sections,
        )
        if (overviewOpen && item != null) {
            // The full synopsis: one item, OK closes it
            com.github.damontecres.wholphin.jellytip.JellytipDialog(
                showDialog = true,
                title = item.title,
                dialogItems = listOf(com.github.damontecres.wholphin.ui.components.DialogItem(headlineContent = { Text(item.overview) }, onClick = {}, dismissOnClick = true)),
                onDismissRequest = { overviewOpen = false },
                waitToLoad = false,
            )
        }
        if (moreOpen && item != null) {
            IptvTileMenu(item, onDismiss = { moreOpen = false }, withMyList = false, provider = item.providerName, fromLongPress = false, withPlay = false, onNavigate = onNavigate)
        }
        if (trailerPlaying && trailerUrl != null) {
            IptvFullScreenTrailer(trailerUrl, httpClient) {
                trailerPlaying = false
                autoDone = true // just watched it: no passive replay until focus leaves and comes back
            }
        }
    }
}

/** Key of the series' Episodes section. */
private const val EPISODES_SECTION = "episodes"

/**
 * The series' Episodes section, like the library's: title, one row of season chips (selecting on OK), one row of the
 * shared episode tile. Opens on the season of [resolved] with that tile as the focus target and scrolled to; OK plays.
 */
@Composable
private fun IptvEpisodesSection(
    seasons: List<IptvSeason>,
    resolved: IptvPlayTarget?,
    season: Int,
    onSeason: (Int) -> Unit,
    focus: FocusRequester,
    onPlay: (IptvEpisode) -> Unit,
) {
    val chipRequester = remember { FocusRequester() }
    val tileRequester = remember { FocusRequester() }
    // OK on a season chip: focus goes to the episode row once that season's tiles are showing
    var focusTilePending by remember { mutableStateOf(false) }
    LaunchedEffect(focusTilePending) {
        if (focusTilePending) {
            withFrameNanos { }
            withFrameNanos { }
            tileRequester.tryRequestFocus()
            focusTilePending = false
        }
    }
    val eps = seasons.getOrNull(season)?.episodes.orEmpty()
    val now = remember(season) { System.currentTimeMillis() / 1000 }
    // The tile the row opens on and focuses: the resolved episode in its own season, else the first
    val focusTile = if (resolved != null && resolved.seasonIndex == season) resolved.episodeIndex else 0
    val labels = remember(seasons) { seasons.mapIndexed { i, s -> seasonChipLabel(s.season, i) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    ) {
        ItemRowTitle("Episodes")
        JellytipSeasonChips(
            labels = labels,
            selectedIndex = season,
            onSelect = onSeason,
            selectedRequester = chipRequester,
            onConfirm = { focusTilePending = true },
            modifier = Modifier.padding(start = com.github.damontecres.wholphin.jellytip.JellytipLayout.RowInset),
        )
        val rowState = key(season) { rememberLazyListState(focusTile) }
        com.github.damontecres.wholphin.jellytip.JellytipRowFocusScope { rowFocusModifier ->
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(com.github.damontecres.wholphin.jellytip.JellytipLayout.TileGap),
                // Focus room: the first tile's growth and border are not cut; the row is pulled back so the tile still starts on
                // the row title's line (8 dp, like People and Reviews)
                contentPadding =
                    PaddingValues(
                        horizontal = com.github.damontecres.wholphin.jellytip.JellytipLayout.FocusRoomWide,
                        vertical = com.github.damontecres.wholphin.jellytip.JellytipLayout.FocusRoom,
                    ),
                modifier =
                    rowFocusModifier
                        .fillMaxWidth()
                        .jellytipFocusRoom(com.github.damontecres.wholphin.jellytip.JellytipLayout.FocusRoomWide - com.github.damontecres.wholphin.jellytip.JellytipLayout.RowInset)
                        .focusRestorer(tileRequester),
            ) {
                itemsIndexed(eps, key = { _, e -> e.id }) { i, e ->
                    val p = e.progress
                    JellytipEpisodeTile(
                        image = e.still,
                        number = e.episode,
                        name = e.title,
                        overview = e.overview,
                        played = p?.played == true,
                        progress = p?.takeIf { it.resumable && it.durationSecs > 0 }?.let { (it.positionSecs / it.durationSecs).toFloat().coerceIn(0f, 1f) } ?: 0f,
                        isNext = resolved != null && resolved.episode.id == e.id,
                        onClick = { onPlay(e) },
                        // IPTV episodes have no menu
                        onLongClick = {},
                        modifier = Modifier.ifElse(i == focusTile, Modifier.focusRequester(tileRequester)),
                        // The failed-stream note ("Failed 2 h ago") sits under the title line
                        sourceMark = IptvStreamFail.note(IptvStreamFail.failOf(e.id, e.lastFail), now),
                    )
                }
            }
        }
    }
}

/**
 * The TMDB cast as plain cells (photo or initials, name, character) at the library person cards' size. The cells do
 * nothing on OK (IPTV has no person ids), so they are not focusable; the first few that fit are shown.
 */
@Composable
private fun IptvPeopleRow(people: List<IptvCastMember>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ItemRowTitle(stringResource(R.string.people_title))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(8.dp)) {
            people.take(MAX_CAST).forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(personRowCardWidth)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        var photoFailed by remember(p.profile) { mutableStateOf(false) }
                        if (p.profile != null && !photoFailed) {
                            AsyncImage(model = p.profile, contentDescription = p.name, contentScale = ContentScale.Crop, onError = { photoFailed = true }, modifier = Modifier.fillMaxSize())
                        } else {
                            Text(
                                p.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                                fontFamily = JellytipFontFamily,
                                fontSize = 24.sp,
                                color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = p.name,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                    p.character?.let {
                        Text(
                            text = it,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_CAST = 6

/**
 * "Ways to watch" (web 846292d8): every copy the companion offers, best first, with quality / language chips, the
 * copy shown marked Selected and a failure note ("Failed 2 h ago"). OK on another copy shows it (and remembers the
 * pick for next time); OK on the shown copy plays it (films).
 */
@Composable
private fun WaysToWatchRow(
    copies: List<IptvCopy>,
    current: String,
    modifier: Modifier = Modifier,
    onPick: (IptvCopy) -> Unit,
) {
    val now = remember(copies) { System.currentTimeMillis() / 1000 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        ItemRowTitle("Ways to watch")
        // The shared left line: the first tile starts on the title's line (focus room 12, pulled back 4)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = com.github.damontecres.wholphin.jellytip.JellytipLayout.FocusRoomWide, vertical = com.github.damontecres.wholphin.jellytip.JellytipLayout.FocusRoom),
            modifier = Modifier.fillMaxWidth().jellytipFocusRoom(com.github.damontecres.wholphin.jellytip.JellytipLayout.FocusRoomWide - com.github.damontecres.wholphin.jellytip.JellytipLayout.RowInset).focusGroup().focusRestorer(),
        ) {
            items(copies, key = { it.id }) { c ->
                val selected = c.id == current
                val fail = IptvStreamFail.note(IptvStreamFail.failOf(c.id, c.lastFail), now)
                Surface(
                    onClick = { onPick(c) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    border = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.border(RoundedCornerShape(8.dp)),
                    scale = com.github.damontecres.wholphin.jellytip.JellytipSurfaceFocus.scale(),
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
                            Text("Outside your switched-on groups", fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1, modifier = Modifier.alpha(0.7f))
                        }
                        if (fail != null) {
                            Text(fail, color = JellytipPalette.Jelly, fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Trailer button's playback: full screen with sound and nothing over it, not even the nav rail (library details hands
 * its trailer to the player). Its own window, so the D-pad can't reach the page underneath; Back, or the end, closes it.
 */
@Composable
private fun IptvFullScreenTrailer(
    url: String,
    httpClient: okhttp3.OkHttpClient,
    onClose: () -> Unit,
) {
    val close by androidx.compose.runtime.rememberUpdatedState(onClose)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { close() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            com.github.damontecres.wholphin.jellytip.TrailerLayer(url = url, sound = true, httpClient = httpClient, onEnded = { close() })
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

/** Outlives the menu dialog (it closes on the click), like [IptvMyList]'s. */
private val menuScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

/**
 * An IPTV tile's long-press menu: the same list, order and words as a library tile's (the common block of
 * [com.github.damontecres.wholphin.jellytip.cards.JellytipTitleMenu]), each line doing the IPTV action.
 */
@Composable
internal fun IptvTileMenu(
    item: IptvVodItem,
    onDismiss: () -> Unit,
    /** The tile's "Details" line; null (the details page's More menu) leaves it out. */
    onOpen: (() -> Unit)? = null,
    /** The tile menu's My list line; the details page has a My list button of its own. */
    withMyList: Boolean = true,
    /** Informational first line ("Provider: X") for the details page's More menu. */
    provider: String? = null,
    /** Opened by a long press (the key is still down: items wait a moment) or by OK on a button (ready at once). */
    fromLongPress: Boolean = true,
    /** Play / Resume / Play from start: the details page has its own Play button, so its More menu leaves them out. */
    withPlay: Boolean = true,
    /** Navigation for Play and Play from library; null = the app's navigation manager. */
    onNavigate: ((Destination, String?) -> Unit)? = null,
) {
    val me = com.github.damontecres.wholphin.jellytip.me.JellytipMe
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val available by me.available.collectAsState()
    val kind = if (item.isSeries) "series" else "movie"
    val key = me.iptvTasteKey(item.id, kind, item.tmdbId)
    val thumb = me.thumbOfKey(key)
    val ni = me.isNotInterestedKey(key)
    val toast: (String?) -> Unit = { err -> if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show() }
    val inList = IptvMyList.isOn(item)
    val played = item.played || item.progress?.played == true
    val nav: (Destination, String?) -> Unit = onNavigate ?: { d, why -> com.github.damontecres.wholphin.services.NavigationManager.shared?.navigateTo(d, why) }
    // The copy Play starts: the shown copy, else the server's next one when this one failed lately (the details page's rule)
    val now = System.currentTimeMillis() / 1000
    val failed: (String) -> Boolean = { id -> IptvStreamFail.failedLately(IptvStreamFail.failOf(id, null), now) }
    val playCopy =
        JellytipTitleMenu
            .bestIptv(listOf(IptvSource("iptv", item.id)) + item.sources.filter { it.type == "iptv" && it.id != item.id }, failed)
            ?.id ?: item.id
    val nextUp = item.nextUp
    val libraryUuid = JellytipTitleOpen.libraryIdOf(item.libraryId, item.sources)
    val state =
        JellytipTitleMenu.State(
            // A film plays at once; a series plays its next episode when the server named it (no network call here)
            canPlay = withPlay && IptvVodParse.isTitleId(item.id) && (!item.isSeries || nextUp != null),
            inProgress = item.progress?.resumable == true,
            inMyList = inList,
            watched = played,
            showWatched = available,
            showDetails = onOpen != null,
            showMyList = withMyList,
            tasteAvailable = available,
            thumb = thumb,
            notInterested = ni,
            other = libraryUuid?.let { JellytipTitleMenu.Other(JellytipTitleMenu.Direction.TO_LIBRARY) },
        )
    fun play(fromStart: Boolean) {
        val resume = if (fromStart) 0L else item.progress?.takeIf { p -> p.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L
        if (item.isSeries) {
            val ep = nextUp ?: return
            nav(Destination.JellytipIptvPlay(title = item.title, seriesId = item.id, episodeId = ep.id, resumeMs = resume), "iptv tile menu Play")
        } else {
            nav(Destination.JellytipIptvPlay(title = item.title, vodId = playCopy, resumeMs = if (playCopy == item.id) resume else 0L), "iptv tile menu Play")
        }
    }
    val dialogItems =
        buildList {
            if (provider != null) {
                add(com.github.damontecres.wholphin.ui.components.DialogItem(headlineContent = { Text("Provider: $provider") }, onClick = {}, enabled = false))
            }
            JellytipTitleMenu.forIptv(state).forEach { e ->
                val action: (() -> Unit)? =
                    when (e.id) {
                        JellytipTitleMenu.Id.PLAY -> {
                            { play(false) }
                        }

                        JellytipTitleMenu.Id.PLAY_FROM_START -> {
                            { play(true) }
                        }

                        JellytipTitleMenu.Id.DETAILS -> onOpen

                        // Fire and forget in a scope that outlives the menu (it closes on this click)
                        JellytipTitleMenu.Id.MY_LIST -> {
                            { IptvMyList.toggle(item.id, !inList, toast) }
                        }

                        JellytipTitleMenu.Id.WATCHED -> {
                            { me.setIptvPlayed(item.id, !played, toast) }
                        }

                        JellytipTitleMenu.Id.LIKE -> {
                            { me.thumbIptv(item.id, kind, item.tmdbId, if (thumb == 1) 0 else 1, toast) }
                        }

                        JellytipTitleMenu.Id.NOT_FOR_ME -> {
                            { me.thumbIptv(item.id, kind, item.tmdbId, if (thumb == -1) 0 else -1, toast) }
                        }

                        JellytipTitleMenu.Id.NOT_INTERESTED -> {
                            { me.setIptvNotInterested(item.id, kind, item.tmdbId, !ni, toast) }
                        }

                        JellytipTitleMenu.Id.PLAY_OTHER -> {
                            {
                                // Continue where the library copy was left (a series starts at 0, as the library tile's Play does): one GET, in a scope that outlives the dialog
                                libraryUuid?.let { uuid ->
                                    menuScope.launch {
                                        // A series starts at 0; a film resumes where the library copy was left. A slow or failed GET must not
                                        // restart the film (and the nav gate drops a browse -> player jump > 3 s after OK): open its page instead.
                                        val pos =
                                            if (item.isSeries) {
                                                0L
                                            } else {
                                                kotlinx.coroutines.withTimeoutOrNull(2_000) {
                                                    com.github.damontecres.wholphin.jellytip.JellytipApi.shared?.libraryResumeMsOrNull(uuid)
                                                }
                                            }
                                        // The dialog click already marked OK (Dialogs.kt); mark again so the gate's window starts at the navigation
                                        com.github.damontecres.wholphin.jellytip.debug.JellytipNavGate.markOk()
                                        if (pos != null) {
                                            nav(Destination.Playback(uuid, pos), "iptv tile menu Play from library")
                                        } else {
                                            nav(Destination.MediaItem(itemId = uuid, type = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE), "iptv tile menu Play from library (no resume position)")
                                        }
                                    }
                                }
                            }
                        }

                        else -> null
                    }
                if (action != null) add(com.github.damontecres.wholphin.ui.components.titleMenuDialogItem(e.id, JellytipTitleMenu.localized(e.label, ctx.resources), inList, played) { action() })
            }
        }
    // The library context menu's dialog (same width, icons, focus and dismiss), with the 1 s guard after a long press
    com.github.damontecres.wholphin.ui.components.DialogPopup(showDialog = true, title = item.title, dialogItems = dialogItems, onDismissRequest = onDismiss, dismissOnClick = false, waitToLoad = fromLongPress)
}
