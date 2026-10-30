package com.github.damontecres.wholphin.jellytip.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import com.github.damontecres.wholphin.jellytip.cache.focalAlignment
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.BaseItemKind
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.playback.playable
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.home.jellytipRowOptions
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.jellytip.rememberIsJellytipAdmin
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.jellytip.specials.Caption
import com.github.damontecres.wholphin.jellytip.specials.PosterCard
import com.github.damontecres.wholphin.jellytip.specials.SoftShadow
import com.github.damontecres.wholphin.jellytip.specials.SpecialCard
import com.github.damontecres.wholphin.jellytip.specials.SpecialTokens
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.main.HomePageCardContent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus

private object RowTokens {
    const val POSTER_HEIGHT_DP = 150
    const val FRANCHISE_HEIGHT_DP = 120
    val FRANCHISE_INFO_W = 320.dp
    const val BACKDROP_ALPHA = 0.42f
}

/** Standard rows look exactly like the existing Jellytip rows (16:9 titled art, same size and spacing). */
private val StandardOptions get() = jellytipRowOptions(HomeRowViewOptions())

private val PosterOptions
    get() =
        HomeRowViewOptions(
            heightDp = RowTokens.POSTER_HEIGHT_DP,
            spacing = JellytipHomeTokens.LANDSCAPE_CARD_SPACING_DP,
            contentScale = PrefContentScale.CROP,
            aspectRatio = AspectRatio.TALL,
            imageType = ViewOptionImageType.PRIMARY,
            showTitles = false,
            useSeries = true,
        )

/** Franchise rows: the same titled 16:9 art throughout the series, a little taller. */
private val FranchiseOptions get() = StandardOptions.copy(heightDp = RowTokens.FRANCHISE_HEIGHT_DP)

/** The row's view options before the Jellytip home defaults (what the prefetcher and snapshot expect). */
internal fun JellytipServerRow.rawViewOptions(): HomeRowViewOptions =
    when {
        isFranchise -> FranchiseOptions
        isPoster -> PosterOptions
        else -> HomeRowViewOptions()
    }

/**
 * One server row on Home / Movies / Shows. Items load (one batch request) when the row is first drawn; until then the
 * row keeps its height so nothing below it moves. [onFocusItem] drives the hero like a normal row; [onLongClickItem]
 * is the page's context menu (null = long press opens details).
 */
@Composable
fun JellytipServerRowView(
    row: JellytipServerRow,
    onFocusItem: (item: BaseItem?, showHeader: Boolean) -> Unit,
    onLongClickItem: ((BaseItem) -> Unit)?,
    focusModifier: Modifier,
    modifier: Modifier = Modifier,
    /** Told the opened entry's id (library item id or IPTV vod id) before navigating; search logs clicks with it. */
    onOpened: ((String) -> Unit)? = null,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    val items by produceState(viewModel.rows.peekItems(row), row.id, row.itemIds) {
        // A failed load keeps the placeholder and retries once; still failing = placeholder stays (no collapse)
        val first = viewModel.rows.items(row)
        if (first != null) {
            value = first
        } else {
            delay(RETRY_MS)
            viewModel.rows.items(row)?.let { value = it }
        }
    }
    val open: (BaseItem) -> Unit =
        remember(viewModel) {
            { item ->
                JellytipTrailerControl.stop()
                viewModel.navigationManager.navigateTo(item.destination(), "server row OK")
            }
        }
    val click: (BaseItem) -> Unit = { item ->
        if (!JellytipTrailerControl.onOk(item)) {
            onOpened?.invoke(item.id.toString())
            open(item)
        }
    }
    val play: (BaseItem) -> Unit =
        remember(viewModel) {
            { item ->
                JellytipTrailerControl.stop()
                viewModel.navigationManager.navigateTo(Destination.Playback(item), "server row Play key")
            }
        }
    val longClick: (BaseItem) -> Unit = onLongClickItem ?: open
    val options =
        when {
            row.isFranchise -> FranchiseOptions
            row.isPoster -> PosterOptions
            else -> StandardOptions
        }
    val loaded = items
    Box(modifier.fillMaxWidth()) {
        when {
            row.isTop10 && row.itemIds.isNotEmpty() && loaded == null -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ItemRowTitle(row.title)
                    Spacer(Modifier.height(SpecialTokens.POSTER_H + 16.dp))
                }
            }

            row.isTop10 -> {
                TrendingTop10Row(row, loaded.orEmpty(), onFocusItem, click, longClick, play, focusModifier, viewModel)
            }

            // Franchise rows may carry IPTV parts too (`iptvItems` + `order`): before the mixed/IPTV row branches
            row.isFranchise && row.itemIds.isNotEmpty() && loaded == null -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ItemRowTitle(row.title)
                    Spacer(Modifier.height(options.heightDp.dp + 16.dp))
                }
            }

            row.isFranchise && (loaded.orEmpty().isNotEmpty() || row.iptvItems.isNotEmpty()) -> {
                FranchiseRow(row, row.collection!!, loaded.orEmpty(), options, onFocusItem, click, longClick, play, focusModifier, viewModel)
            }

            row.iptvItems.isNotEmpty() && row.itemIds.isNotEmpty() -> {
                // My list rows: library and IPTV titles in one row, in the server's order (newest favourite first)
                MixedRow(row, loaded.orEmpty(), options, onFocusItem, click, longClick, play, focusModifier, viewModel, onOpened)
            }

            row.iptvItems.isNotEmpty() -> {
                // IPTV rows: the titles came with the row (no library ids), tiles carry the IPTV dot + quality
                com.github.damontecres.wholphin.jellytip.iptv.IptvVodRow(
                    title = row.title,
                    items = row.iptvItems,
                    poster = row.isPoster,
                    heightDp = options.heightDp,
                    onFocusTile = { onFocusItem(null, false) },
                    modifier = focusModifier,
                    onOpened = onOpened,
                )
            }

            loaded == null -> {
                // Same height as the loaded row: the title plus a card's height and the row padding
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ItemRowTitle(row.title)
                    Spacer(Modifier.height(options.heightDp.dp + 16.dp))
                }
            }

            loaded.isEmpty() -> {}

            row.isFranchise -> {
                FranchiseRow(row, row.collection!!, loaded, options, onFocusItem, click, longClick, play, focusModifier, viewModel)
            }

            row.person != null -> {
                PersonRow(row, row.person, loaded, options, onFocusItem, click, longClick, play, focusModifier, viewModel)
            }

            else -> {
                ItemRow(
                    title = row.title,
                    items = loaded,
                    onClickItem = { _, item -> click(item) },
                    onLongClickItem = { _, item -> longClick(item) },
                    horizontalPadding = options.spacing.dp,
                    modifier = focusModifier,
                    cardContent = { index, item, cardModifier, onClick, onLongClick ->
                        HomePageCardContent(
                            index = index,
                            item = item,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            viewOptions = options,
                            modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(item, true) }.playKey(item, play),
                        )
                    },
                )
            }
        }
    }
}

private sealed interface PersonRowEntry {
    data object Portrait : PersonRowEntry

    data class Film(
        val item: BaseItem,
    ) : PersonRowEntry
}

/**
 * People rows ("Starring X", "Directed by X"): a 2:3 portrait first (person Primary image, name, "N films"; OK = the
 * person page with their filmography; the hero text hides while it has focus), then the films.
 */
@Composable
private fun PersonRow(
    row: JellytipServerRow,
    person: JellytipServerRow.Person,
    films: List<BaseItem>,
    options: HomeRowViewOptions,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    click: (BaseItem) -> Unit,
    longClick: (BaseItem) -> Unit,
    play: (BaseItem) -> Unit,
    focusModifier: Modifier,
    viewModel: JellytipHomeViewModel,
) {
    val sizes = rememberJellytipSizes()
    val service = LocalImageUrlService.current
    val density = LocalDensity.current
    val height = options.heightDp.dp
    val width = height * 2f / 3f
    val entries = remember(films) { listOf(PersonRowEntry.Portrait) + films.map { PersonRowEntry.Film(it) } }
    val openPerson = {
        JellytipTrailerControl.stop()
        viewModel.navigationManager.navigateTo(Destination.MediaItem(person.id, BaseItemKind.PERSON), "person portrait OK")
    }
    ItemRow(
        title = row.title,
        items = entries,
        onClickItem = { _, entry ->
            when (entry) {
                PersonRowEntry.Portrait -> openPerson()
                is PersonRowEntry.Film -> click(entry.item)
            }
        },
        onLongClickItem = { _, entry ->
            when (entry) {
                PersonRowEntry.Portrait -> openPerson()
                is PersonRowEntry.Film -> longClick(entry.item)
            }
        },
        horizontalPadding = options.spacing.dp,
        modifier = focusModifier,
        cardContent = { index, entry, cardModifier, onClick, onLongClick ->
            when (entry) {
                is PersonRowEntry.Film -> {
                    HomePageCardContent(
                        index = index,
                        item = entry.item,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        viewOptions = options,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(entry.item, true) }.playKey(entry.item, play),
                    )
                }

                else -> {
                    val url =
                        remember(person.id, person.imageTag, density, height) {
                            with(density) {
                                service.getItemImageUrl(
                                    itemId = person.id,
                                    imageType = ImageType.PRIMARY,
                                    fillWidth = width.roundToPx(),
                                    fillHeight = height.roundToPx(),
                                    tag = person.imageTag,
                                )
                            }
                        }
                    var failed by remember(url) { mutableStateOf(false) }
                    SpecialCard(
                        width = width,
                        height = height,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                    ) {
                        if (url != null && !failed) {
                            AsyncImage(
                                model = url,
                                contentDescription = person.name,
                                contentScale = ContentScale.Crop,
                                onError = { failed = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .fillMaxHeight(0.55f)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(sizes.px(12))) {
                            Text(
                                text = person.name,
                                color = Color.White,
                                fontFamily = JellytipFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = sizes.sp(JellytipHomeTokens.CARD_SUBTITLE_PX),
                                style = TextStyle(shadow = SoftShadow),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Caption(if (films.size == 1) "1 film" else "${films.size} films", alpha = 0.75f, px = 16)
                        }
                    }
                }
            }
        },
    )
}

/** The remote's Play key on a tile plays it straight away, as on the normal rows. */
private fun Modifier.playKey(
    item: BaseItem?,
    play: (BaseItem) -> Unit,
): Modifier =
    onKeyEvent { event ->
        if (item != null && isPlayKeyUp(event) && item.type.playable) {
            play(item)
            true
        } else {
            false
        }
    }

private const val RETRY_MS = 3_000L

private sealed interface FranchiseEntry {
    data object Info : FranchiseEntry

    data class Film(
        val item: BaseItem,
    ) : FranchiseEntry

    /** A part the library lacks but the person's IPTV has: drawn in its series slot with the IPTV dot. */
    data class Iptv(
        val item: com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem,
    ) : FranchiseEntry
}

/**
 * A franchise as one thing: the series backdrop (darkened) behind the whole row, a first card with the collection's
 * name, blurb and "3 of 8 watched" (OK = the collection page), then the films in series order.
 */
@Composable
private fun FranchiseRow(
    row: JellytipServerRow,
    collection: JellytipCollection,
    films: List<BaseItem>,
    options: HomeRowViewOptions,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    click: (BaseItem) -> Unit,
    longClick: (BaseItem) -> Unit,
    play: (BaseItem) -> Unit,
    focusModifier: Modifier,
    viewModel: JellytipHomeViewModel,
) {
    val sizes = rememberJellytipSizes()
    // Series order from the parts; IPTV fills the slots the library lacks; library films without a part go last
    val entries =
        remember(films, collection, row.order, row.iptvItems) {
            if (row.order.isNotEmpty() || row.iptvItems.isNotEmpty()) {
                // The server's mixed order (part order): library films and IPTV parts as one list
                val mixed =
                    com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse
                        .ordered<FranchiseEntry>(row.order, films, row.iptvItems, { FranchiseEntry.Film(it) }, { FranchiseEntry.Iptv(it) })
                return@remember listOf(FranchiseEntry.Info) + mixed
            }
            val byId = films.associateBy { it.id }
            val ordered =
                collection.parts.mapNotNull { part ->
                    part.itemId?.let { byId[it] }?.let { FranchiseEntry.Film(it) }
                        ?: part.iptv?.let { FranchiseEntry.Iptv(it) }
                }
            val seen = ordered.mapNotNull { (it as? FranchiseEntry.Film)?.item?.id }.toSet()
            val withLibrary = if (ordered.none { it is FranchiseEntry.Film } && films.isNotEmpty()) films.map { FranchiseEntry.Film(it) } + ordered else ordered + films.filter { it.id !in seen }.map { FranchiseEntry.Film(it) }
            listOf(FranchiseEntry.Info) + withLibrary
        }
    val openIptv: (com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem) -> Unit = { iptv ->
        JellytipTrailerControl.stop()
        viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(iptv.id, iptv.title), "franchise row OK (iptv)")
    }
    val openCollection = {
        JellytipTrailerControl.stop()
        viewModel.navigationManager.navigateTo(Destination.JellytipCollection(collection.tmdbId, collection.name), "franchise info OK")
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))) {
        if (collection.backdrop != null) {
            AsyncImage(
                model = collection.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // TMDB collection art has no focal data: lean to the top, where heads are
                alignment = focalAlignment(null),
                modifier = Modifier.matchParentSize().alpha(RowTokens.BACKDROP_ALPHA),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to JellytipPalette.Night.copy(alpha = 0.85f),
                        0.5f to JellytipPalette.Night.copy(alpha = 0.35f),
                        1f to JellytipPalette.Night.copy(alpha = 0.6f),
                    ),
                ),
        )
        Column(Modifier.padding(top = 6.dp, bottom = 4.dp)) {
            ItemRow(
                title = row.title,
                items = entries,
                onClickItem = { _, entry ->
                    when (entry) {
                        FranchiseEntry.Info -> openCollection()
                        is FranchiseEntry.Film -> click(entry.item)
                        is FranchiseEntry.Iptv -> openIptv(entry.item)
                    }
                },
                onLongClickItem = { _, entry ->
                    when (entry) {
                        FranchiseEntry.Info -> openCollection()
                        is FranchiseEntry.Film -> longClick(entry.item)
                        is FranchiseEntry.Iptv -> openIptv(entry.item)
                    }
                },
                horizontalPadding = options.spacing.dp,
                modifier = focusModifier,
                cardContent = { index, entry, cardModifier, onClick, onLongClick ->
                    when (entry) {
                        is FranchiseEntry.Film -> {
                            HomePageCardContent(
                                index = index,
                                item = entry.item,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                viewOptions = options,
                                modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(entry.item, true) }.playKey(entry.item, play),
                            )
                        }

                        is FranchiseEntry.Iptv -> {
                            com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile(
                                item = entry.item,
                                width = options.heightDp.dp * 16f / 9f,
                                height = options.heightDp.dp,
                                poster = false,
                                onClick = onClick,
                                modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                            )
                        }

                        else -> {
                            SpecialCard(
                                width = RowTokens.FRANCHISE_INFO_W,
                                height = options.heightDp.dp,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(films.firstOrNull(), false) },
                            ) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = sizes.px(22)),
                                ) {
                                    Text(
                                        text = collection.name,
                                        color = Color.White,
                                        fontFamily = JellytipFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = sizes.sp(JellytipHomeTokens.TEXT_PX),
                                        style = TextStyle(shadow = SoftShadow),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (collection.overview.isNotBlank()) Caption(collection.overview, alpha = 0.85f, maxLines = 2)
                                    // Counted from the loaded films (fresh after playback), not the morning's manifest
                                    val watched = films.count { it.data.userData?.played == true }
                                    Caption("$watched of ${collection.total} watched  ·  OK for the collection", alpha = 0.6f)
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Collection page: series hero, blurb, progress, the films in series order; missing parts faded (admins: "Get it")

private sealed interface CollectionPageState {
    data object Loading : CollectionPageState

    data object Failed : CollectionPageState

    data class Loaded(
        val collection: JellytipCollection,
        val items: List<BaseItem>,
    ) : CollectionPageState
}

private sealed interface PartEntry {
    data class InLibrary(
        val item: BaseItem,
    ) : PartEntry

    data class Missing(
        val part: JellytipCollection.Missing,
    ) : PartEntry
}

@Composable
fun JellytipCollectionPage(
    tmdbId: Int,
    name: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    val sizes = rememberJellytipSizes()
    val isAdmin by rememberIsJellytipAdmin()
    val state by produceState<CollectionPageState>(CollectionPageState.Loading, tmdbId) {
        value = viewModel.rows.collection(tmdbId)?.let { CollectionPageState.Loaded(it.first, it.second) } ?: CollectionPageState.Failed
    }
    val loaded = state as? CollectionPageState.Loaded
    val collection = loaded?.collection
    var focused by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    // Series order: every part from partsOrder, in the library or not; parts without a match keep their slot
    val entries =
        remember(loaded) {
            if (loaded == null) {
                emptyList()
            } else {
                val byId = loaded.items.associateBy { it.id }
                val missingByTmdb = loaded.collection.missing.associateBy { it.tmdbId }
                val ordered =
                    loaded.collection.parts.mapNotNull { part ->
                        part.itemId?.let { byId[it] }?.let { PartEntry.InLibrary(it) }
                            ?: part.tmdbId?.let { missingByTmdb[it] }?.let { PartEntry.Missing(it) }
                    }
                val seen = ordered.mapNotNull { (it as? PartEntry.InLibrary)?.item?.id }.toSet()
                val seenMissing = ordered.mapNotNull { (it as? PartEntry.Missing)?.part?.tmdbId }.toSet()
                ordered + loaded.items.filter { it.id !in seen }.map { PartEntry.InLibrary(it) } +
                    loaded.collection.missing.filter { it.tmdbId !in seenMissing }.map { PartEntry.Missing(it) }
            }
        }
    val canRequest = collection?.canRequest == true && isAdmin
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty()) {
            kotlinx.coroutines.delay(50)
            firstFocus.tryRequestFocus()
        }
    }
    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        collection?.backdrop?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = focalAlignment(null),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to JellytipPalette.Night.copy(alpha = 0.15f),
                        0.45f to JellytipPalette.Night.copy(alpha = 0.55f),
                        0.75f to JellytipPalette.Night.copy(alpha = 0.92f),
                        1f to JellytipPalette.Night,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to JellytipPalette.Night.copy(alpha = 0.75f), 0.6f to Color.Transparent)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(bottom = sizes.px(56)),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = sizes.px(JellytipHomeTokens.DETAILS_LEFT_PX)).width(sizes.px(JellytipHomeTokens.DETAILS_WIDTH_PX)),
            ) {
                Text(
                    text = collection?.name ?: name,
                    color = Color.White,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = sizes.sp(JellytipHomeTokens.DETAILS_TITLE_PX),
                    style = TextStyle(shadow = SoftShadow),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when (state) {
                    CollectionPageState.Loading -> Caption("Loading…", alpha = 0.7f, px = JellytipHomeTokens.TEXT_PX)
                    CollectionPageState.Failed -> Caption("This collection isn't available right now.", alpha = 0.8f, px = JellytipHomeTokens.TEXT_PX)
                    is CollectionPageState.Loaded -> {
                        val c = (state as CollectionPageState.Loaded).collection
                        if (c.overview.isNotBlank()) Caption(c.overview, alpha = 0.85f, px = JellytipHomeTokens.TEXT_PX, maxLines = 3)
                        Caption(
                            c.progressText + "  ·  ${c.inLibrary} in the library",
                            alpha = 0.7f,
                            px = JellytipHomeTokens.TEXT_PX,
                        )
                    }
                }
                Caption(focused ?: " ", alpha = 0.9f, px = JellytipHomeTokens.TEXT_PX)
            }
            if (entries.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(SpecialTokens.SPACING),
                    contentPadding = PaddingValues(horizontal = sizes.px(JellytipHomeTokens.DETAILS_LEFT_PX), vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(entries) { index, entry ->
                        val first = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                        when (entry) {
                            is PartEntry.InLibrary -> {
                                val item = entry.item
                                val label = listOfNotNull(item.title, item.data.productionYear?.toString()).joinToString("  ·  ")
                                PosterCard(
                                    item,
                                    onClick = {
                                        viewModel.navigationManager.navigateTo(item.destination(), "collection part OK")
                                    },
                                    onLongClick = { viewModel.navigationManager.navigateTo(item.destination(), "collection part long-press") },
                                    modifier =
                                        first.onFocusChanged {
                                            if (it.isFocused) {
                                                focused = label + if (item.data.userData?.played == true) "  ·  watched" else ""
                                            }
                                        },
                                )
                            }

                            is PartEntry.Missing -> {
                                val part = entry.part
                                val label =
                                    listOfNotNull(part.title, part.year?.toString()).joinToString("  ·  ") +
                                        when {
                                            !part.released -> "  ·  not released yet"
                                            canRequest -> "  ·  not in the library · OK to get it"
                                            else -> "  ·  not in the library"
                                        }
                                val requestable = canRequest && part.released
                                val iptv = part.iptv
                                if (iptv != null) {
                                    // The person's IPTV has it: fills the gap, playable "via IPTV"
                                    com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile(
                                        item = if (iptv.poster == null) iptv.copy(poster = part.posterUrl) else iptv,
                                        width = SpecialTokens.POSTER_W,
                                        height = SpecialTokens.POSTER_H,
                                        poster = true,
                                        onClick = {
                                            viewModel.navigationManager.navigateTo(
                                                Destination.JellytipIptvDetails(iptv.id, iptv.title),
                                                "collection part OK (iptv)",
                                            )
                                        },
                                        modifier =
                                            first.onFocusChanged {
                                                if (it.isFocused) {
                                                    focused = listOfNotNull(part.title, part.year?.toString()).joinToString("  ·  ") + "  ·  via IPTV"
                                                }
                                            },
                                    )
                                } else if (requestable || index == 0) {
                                    SpecialCard(
                                        width = SpecialTokens.POSTER_W,
                                        height = SpecialTokens.POSTER_H,
                                        onClick = {
                                            if (requestable) {
                                                viewModel.navigationManager.navigateTo(
                                                    Destination.JellytipDiscover(
                                                        DiscoverTitle(
                                                            tmdbId = part.tmdbId,
                                                            mediaType = "movie",
                                                            title = part.title,
                                                            year = part.year,
                                                            posterUrl = part.posterUrl,
                                                            backdropUrl = collection?.backdrop,
                                                        ),
                                                    ),
                                                )
                                            }
                                        },
                                        onLongClick = {},
                                        modifier = first.onFocusChanged { if (it.isFocused) focused = label },
                                    ) { MissingPoster(part, showGetIt = requestable) }
                                } else {
                                    Box(
                                        Modifier
                                            .size(SpecialTokens.POSTER_W, SpecialTokens.POSTER_H)
                                            .background(Color(0xFF141414), RoundedCornerShape(8.dp)),
                                    ) { MissingPoster(part, showGetIt = false) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingPoster(
    part: JellytipCollection.Missing,
    showGetIt: Boolean,
) {
    val sizes = rememberJellytipSizes()
    var failed by remember(part.posterUrl) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().alpha(0.35f)) {
            if (part.posterUrl != null && !failed) {
                AsyncImage(
                    model = part.posterUrl,
                    contentDescription = part.title,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Caption(part.title, alpha = 1f, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
        }
        if (showGetIt) {
            Text(
                text = "Get it",
                color = Color.White,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = sizes.sp(JellytipHomeTokens.CARD_SUBTITLE_PX),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** One tile of a mixed (library + IPTV) row. */
private sealed interface MixedTile {
    val key: String

    data class Lib(
        val item: BaseItem,
    ) : MixedTile {
        override val key get() = "l:${item.id}"
    }

    data class Vod(
        val item: com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem,
    ) : MixedTile {
        override val key get() = "v:${item.id}"
    }
}

/** A row of library cards and IPTV tiles (the IPTV dot marks the latter), same height and spacing as a standard row. */
@Composable
private fun MixedRow(
    row: JellytipServerRow,
    library: List<BaseItem>,
    options: HomeRowViewOptions,
    onFocusItem: (item: BaseItem?, showHeader: Boolean) -> Unit,
    click: (BaseItem) -> Unit,
    longClick: (BaseItem) -> Unit,
    play: (BaseItem) -> Unit,
    focusModifier: Modifier,
    viewModel: JellytipHomeViewModel,
    onOpened: ((String) -> Unit)? = null,
) {
    val tiles =
        remember(row.id, row.order, library, row.iptvItems) {
            com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse
                .ordered<MixedTile>(row.order, library, row.iptvItems, { MixedTile.Lib(it) }, { MixedTile.Vod(it) })
        }
    if (tiles.isEmpty()) return
    val h = options.heightDp.dp
    val w = if (row.isPoster) h * 2f / 3f else h * 16f / 9f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = focusModifier) {
        ItemRowTitle(row.title)
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(options.spacing.dp),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(options.spacing.dp, 8.dp),
            modifier =
                Modifier
                    .jellytipRowBleed()
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(),
        ) {
            items(tiles.size, key = { tiles[it].key }) { i ->
                when (val t = tiles[i]) {
                    is MixedTile.Lib ->
                        HomePageCardContent(
                            index = i,
                            item = t.item,
                            onClick = { click(t.item) },
                            onLongClick = { longClick(t.item) },
                            viewOptions = options,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) onFocusItem(t.item, true) }.playKey(t.item, play),
                        )

                    is MixedTile.Vod ->
                        com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile(
                            item = t.item,
                            width = w,
                            height = h,
                            poster = row.isPoster,
                            onClick = {
                                JellytipTrailerControl.stop()
                                onOpened?.invoke(t.item.id)
                                viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(t.item.id, t.item.title), "my list IPTV OK")
                            },
                            modifier = Modifier.onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                        )
                }
            }
        }
    }
}

private sealed interface TrendTile {
    val rank: Int
    val key: String

    data class Lib(
        val item: BaseItem,
        override val rank: Int,
    ) : TrendTile {
        override val key get() = "l:${item.id}"
    }

    data class Vod(
        val item: com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem,
        override val rank: Int,
    ) : TrendTile {
        override val key get() = "v:${item.id}"
    }

    data class Missing(
        val m: JellytipServerRow.Missing,
    ) : TrendTile {
        override val rank get() = m.rank
        override val key get() = "m:${m.tmdbId}:${m.title}"
    }
}

/**
 * Trending Top 10 (plan "Trending Top 10"): library and IPTV titles plus "Not available" ones, ordered by rank, in the
 * Top 10 style (big numerals). A missing title opens Discover's Get it for admins and is not focusable for others.
 * Entries without a rank keep their display order after the ranked ones.
 */
@Composable
private fun TrendingTop10Row(
    row: JellytipServerRow,
    library: List<BaseItem>,
    onFocusItem: (item: BaseItem?, showHeader: Boolean) -> Unit,
    click: (BaseItem) -> Unit,
    longClick: (BaseItem) -> Unit,
    play: (BaseItem) -> Unit,
    focusModifier: Modifier,
    viewModel: JellytipHomeViewModel,
) {
    val isAdmin by rememberIsJellytipAdmin()
    val tiles =
        remember(row.id, row.order, row.ranks, row.missing, library, row.iptvItems) {
            val shown =
                com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse.ordered<TrendTile>(
                    row.order,
                    library,
                    row.iptvItems,
                    { TrendTile.Lib(it, row.ranks[JellytipServerRow.rankKey(it.id.toString())] ?: 0) },
                    { TrendTile.Vod(it, row.ranks[JellytipServerRow.rankKey(it.id)] ?: 0) },
                )
            val all = shown + row.missing.map { TrendTile.Missing(it) }
            val (ranked, unranked) = all.partition { it.rank > 0 }
            (ranked.sortedBy { it.rank } + unranked).distinctBy { it.key }
        }
    if (tiles.isEmpty()) return
    val mediaType = if (row.mediaType == "tv" || row.id.endsWith("series")) "tv" else "movie"
    ItemRow(
        title = row.title,
        items = tiles,
        onClickItem = { _, t ->
            when (t) {
                is TrendTile.Lib -> click(t.item)
                is TrendTile.Vod -> {
                    JellytipTrailerControl.stop()
                    viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(t.item.id, t.item.title), "trending IPTV OK")
                }
                is TrendTile.Missing ->
                    if (isAdmin && t.m.tmdbId > 0) {
                        JellytipTrailerControl.stop()
                        viewModel.navigationManager.navigateTo(
                            Destination.JellytipDiscover(
                                com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle(
                                    tmdbId = t.m.tmdbId,
                                    mediaType = mediaType,
                                    title = t.m.title,
                                    year = t.m.year,
                                    posterUrl = t.m.poster,
                                ),
                            ),
                            "trending missing OK",
                        )
                    }
            }
        },
        onLongClickItem = { _, t -> if (t is TrendTile.Lib) longClick(t.item) },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier,
        cardContent = { index, t, cardModifier, onClick, onLongClick ->
            if (t != null) {
                // Ranks the server did not send: the slot's position
                com.github.damontecres.wholphin.jellytip.specials.RankedPoster(if (t.rank > 0) t.rank else index + 1) { boxModifier ->
                    when (t) {
                        is TrendTile.Lib ->
                            PosterCard(
                                t.item,
                                onClick,
                                onLongClick,
                                cardModifier.then(boxModifier).onFocusChanged { if (it.isFocused) onFocusItem(t.item, true) }.playKey(t.item, play),
                            )

                        is TrendTile.Vod ->
                            com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile(
                                item = t.item,
                                width = SpecialTokens.POSTER_W,
                                height = SpecialTokens.POSTER_H,
                                poster = true,
                                onClick = onClick,
                                modifier = cardModifier.then(boxModifier).onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                            )

                        is TrendTile.Missing ->
                            if (isAdmin && t.m.tmdbId > 0) {
                                SpecialCard(
                                    width = SpecialTokens.POSTER_W,
                                    height = SpecialTokens.POSTER_H,
                                    onClick = onClick,
                                    onLongClick = onClick,
                                    modifier = cardModifier.then(boxModifier).onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                                ) { MissingTrendArt(t.m) }
                            } else {
                                Box(
                                    Modifier
                                        .then(boxModifier)
                                        .size(SpecialTokens.POSTER_W, SpecialTokens.POSTER_H)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF141414)),
                                ) { MissingTrendArt(t.m) }
                            }
                    }
                }
            }
        },
    )
}

/** A trending title that is nowhere: its poster dimmed, the title when there is none, and "Not available". */
@Composable
private fun BoxScope.MissingTrendArt(m: JellytipServerRow.Missing) {
    var failed by remember(m.poster) { mutableStateOf(false) }
    if (m.poster != null && !failed) {
        AsyncImage(
            model = m.poster,
            contentDescription = m.title,
            contentScale = ContentScale.Crop,
            alpha = SpecialTokens.NOT_YET_ALPHA * 0.6f,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Caption(m.title, alpha = 0.6f, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
    }
    Text(
        "Not available",
        color = Color.White.copy(alpha = 0.85f),
        fontFamily = JellytipFontFamily,
        fontSize = 11.sp,
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
