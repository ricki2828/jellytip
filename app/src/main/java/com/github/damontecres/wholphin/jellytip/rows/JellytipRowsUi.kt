package com.github.damontecres.wholphin.jellytip.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    val click: (BaseItem) -> Unit = { item -> if (!JellytipTrailerControl.onOk(item)) open(item) }
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
    val entries = remember(films) { listOf(FranchiseEntry.Info) + films.map { FranchiseEntry.Film(it) } }
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
                    }
                },
                onLongClickItem = { _, entry ->
                    when (entry) {
                        FranchiseEntry.Info -> openCollection()
                        is FranchiseEntry.Film -> longClick(entry.item)
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
                                if (requestable || index == 0) {
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
