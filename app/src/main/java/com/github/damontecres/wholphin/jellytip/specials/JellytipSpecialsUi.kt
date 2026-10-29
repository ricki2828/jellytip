package com.github.damontecres.wholphin.jellytip.specials

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.media3.common.VideoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipFocus
import com.github.damontecres.wholphin.jellytip.JellytipSettings
import com.github.damontecres.wholphin.jellytip.cache.focalAlignment
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitledImage
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.jellytipSiblingDim
import com.github.damontecres.wholphin.jellytip.rememberIsJellytipAdmin
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * "Play both" hand-off from a Double Feature tile to the details page of film A: that page adds a Play both button that
 * plays A and queues B. Set when the pair is opened, cleared when Home opens anything else.
 */
object JellytipDoubleBill {
    @Volatile
    var pair: Pair<UUID, UUID>? = null

    /** B, when [a] was opened from a double feature. */
    fun nextAfter(a: UUID): UUID? = pair?.takeIf { it.first == a }?.second
}

/** Sizes in dp; every special fits the ~230 dp the home leaves below the hero text. Shared with the image prefetcher. */
internal object SpecialTokens {
    val POSTER_H = 150.dp
    val POSTER_W = 100.dp
    val WIDE_H = JellytipHomeTokens.LANDSCAPE_CARD_HEIGHT_DP.dp
    val WIDE_W = (JellytipHomeTokens.LANDSCAPE_CARD_HEIGHT_DP * 16 / 9).dp
    /** Trailer Break video panel height: as tall as a row can be under the hero text (title + padding included). */
    val BAND_H = 190.dp
    val FEATURE_H = 170.dp
    val FEATURE_W = 480.dp
    val VIDEO_H = 150.dp
    val VIDEO_W = 267.dp
    val PORTRAIT_W = 120.dp
    val PAIR_H = 96.dp
    val PAIR_W = 171.dp
    val QUOTE_W = 300.dp
    val SPACING = JellytipHomeTokens.LANDSCAPE_CARD_SPACING_DP.dp
    const val VIDEO_DELAY_MS = 1_500L
    const val NUMERAL_PX = 250
    const val FADED_ALPHA = 0.35f
    const val NOT_YET_ALPHA = 0.55f
}

/**
 * One special as a home row. [focusModifier] carries the page's focus group + requester (like a normal row);
 * [onFocusItem] tells the page which film the hero should show and whether the hero's text block should show it too
 * (false for tiles that carry their own title, e.g. Trailer Break, Tonight's Pick, the Closet video).
 */
@Composable
fun JellytipSpecialRow(
    special: JellytipSpecial,
    page: JellytipSpecialsPage,
    onFocusItem: (item: BaseItem?, showHeader: Boolean) -> Unit,
    focusModifier: Modifier,
    modifier: Modifier = Modifier,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    val actions =
        remember(viewModel) {
            SpecialActions(
                open = { item ->
                    // Free the hero player/decoder before the details page starts
                    JellytipTrailerControl.stop()
                    JellytipDoubleBill.pair = null
                    viewModel.navigationManager.navigateTo(item.destination())
                },
                navigate = { destination ->
                    JellytipTrailerControl.stop()
                    viewModel.navigationManager.navigateTo(destination)
                },
            )
        }
    val items = page.items
    Box(modifier = modifier.fillMaxWidth()) {
        when (special) {
            is JellytipSpecial.TrailerBreak -> TrailerBreakRow(special, items, onFocusItem, focusModifier, actions, viewModel)
            is JellytipSpecial.PersonSpotlight -> PersonSpotlightRow(special, items, onFocusItem, focusModifier, actions)
            is JellytipSpecial.TonightsPick -> TonightsPickRow(special, items, onFocusItem, focusModifier, actions, viewModel)
            is JellytipSpecial.Top10 -> Top10Row(special, items, onFocusItem, focusModifier, actions)
            is JellytipSpecial.DoubleFeature -> DoubleFeatureRow(special, items, onFocusItem, focusModifier, actions)
            is JellytipSpecial.BecauseLoved -> BecauseLovedRow(special, items, onFocusItem, focusModifier, actions)
            is JellytipSpecial.AroundHouse -> AroundHouseRow(special, items, onFocusItem, focusModifier, actions)
            is JellytipSpecial.ClosetPicks -> ClosetPicksRow(special, items, onFocusItem, focusModifier, actions, viewModel)
        }
    }
}

private class SpecialActions(
    val open: (BaseItem) -> Unit,
    val navigate: (Destination) -> Unit,
) {
    /** OK on a film tile: unmute the hero trailer if it plays muted for this film, else open it. */
    fun click(item: BaseItem) {
        if (!JellytipTrailerControl.onOk(item)) open(item)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Trailer Break: full-width band, muted trailer on focus in the band (the one home player), OK = sound, OK again = details

@Composable
private fun TrailerBreakRow(
    special: JellytipSpecial.TrailerBreak,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
    viewModel: JellytipHomeViewModel,
) {
    val item = items[special.itemId] ?: return
    val owner = special.id
    val settings by viewModel.settings.collectAsState(JellytipSettings())
    var active by remember { mutableStateOf(false) }
    val resolve: suspend () -> String? = {
        special.trailerItemId?.let { viewModel.specials.trailerStreamUrl(it) } ?: viewModel.localTrailer(item)?.first
    }
    SpecialVideoDriver(owner, active, settings.trailerAutoplay, item.id, resolve)
    val scope = rememberCoroutineScope()
    val pick = remember(item.id, special.heroIndex) { viewModel.heroPick(item, special.heroIndex) }
    val sizes = rememberJellytipSizes()
    val meta =
        remember(item.id) {
            listOfNotNull(
                item.data.productionYear?.toString(),
                item.data.genres?.take(2)?.takeIf { it.isNotEmpty() }?.joinToString(" / "),
                item.data.officialRating?.takeIf { it.isNotBlank() },
            ).joinToString("  \u00B7  ")
        }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bandWidth = maxWidth - SpecialTokens.SPACING * 2
        val panelHeight = SpecialTokens.BAND_H
        val panelWidth = panelHeight * 16f / 9f
        ItemRow(
            title = special.title,
            items = listOf(item),
            onClickItem = { _, _ ->
                when {
                    JellytipTrailerControl.onOkOwner(owner) -> {}
                    JellytipTrailerControl.isPlayingForOwner(owner) -> actions.open(item)
                    else ->
                        scope.launch {
                            // Not playing yet (autoplay off, or still waiting): start now with sound
                            val url = resolve() ?: return@launch actions.open(item)
                            JellytipTrailerControl.startSpecial(owner, url, sound = true, itemId = item.id)
                            JellytipTrailerControl.hintTick = System.nanoTime()
                        }
                }
            },
            onLongClickItem = { _, _ -> actions.open(item) },
            horizontalPadding = SpecialTokens.SPACING,
            modifier = focusModifier.onFocusChanged { active = it.hasFocus },
            cardContent = { _, _, cardModifier, onClick, onLongClick ->
                // Plain night band: text on the left, the trailer in a true 16:9 panel on the right (only the panel takes focus)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.width(bandWidth).height(panelHeight),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f).padding(start = sizes.px(16), end = sizes.px(40), bottom = sizes.px(6)),
                    ) {
                        Kicker("Trailer break")
                        LogoOrTitle(item, maxWidthPx = 380, maxHeightPx = 120)
                        if (special.reason.isNotBlank()) Caption(special.reason, alpha = 0.9f, px = JellytipHomeTokens.TEXT_PX)
                        if (meta.isNotBlank()) Caption(meta, alpha = 0.6f)
                        SoundHint(owner, "Sound on \u00B7 OK for details", Modifier)
                    }
                    SpecialCard(
                        width = panelWidth,
                        height = panelHeight,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(item, false) },
                        corner = 6.dp,
                    ) {
                        // The panel is 16:9, so cropping the still to it is fine
                        if (pick.url != null) {
                            AsyncImage(
                                model = pick.url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = focalAlignment(pick.focal),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        SpecialVideoSurface(owner, Modifier.fillMaxSize())
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Person spotlight: portrait tile (opens the person page = filmography) + their films as 2:3 posters

private sealed interface PersonEntry {
    data object Portrait : PersonEntry

    data class Film(
        val item: BaseItem,
    ) : PersonEntry
}

@Composable
private fun PersonSpotlightRow(
    special: JellytipSpecial.PersonSpotlight,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
) {
    val entries = remember(special, items) { listOf(PersonEntry.Portrait) + special.films.mapNotNull { items[it] }.map { PersonEntry.Film(it) } }
    val sizes = rememberJellytipSizes()
    val service = LocalImageUrlService.current
    val density = LocalDensity.current
    ItemRow(
        title = special.title,
        items = entries,
        onClickItem = { _, entry ->
            when (entry) {
                PersonEntry.Portrait -> actions.navigate(Destination.MediaItem(special.personId, BaseItemKind.PERSON))
                is PersonEntry.Film -> actions.click(entry.item)
            }
        },
        onLongClickItem = { _, entry -> if (entry is PersonEntry.Film) actions.open(entry.item) },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier,
        cardContent = { _, entry, cardModifier, onClick, onLongClick ->
            when (entry) {
                is PersonEntry.Film -> {
                    PosterCard(entry.item, onClick, onLongClick, cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(entry.item, true) })
                }

                else -> {
                    val url =
                        remember(special.personId, density) {
                            with(density) {
                                service.getItemImageUrl(
                                    itemId = special.personId,
                                    imageType = ImageType.PRIMARY,
                                    fillWidth = SpecialTokens.PORTRAIT_W.roundToPx(),
                                    fillHeight = SpecialTokens.POSTER_H.roundToPx(),
                                    tag = special.imageTag,
                                )
                            }
                        }
                    var failed by remember(url) { mutableStateOf(false) }
                    SpecialCard(
                        width = SpecialTokens.PORTRAIT_W,
                        height = SpecialTokens.POSTER_H,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                    ) {
                        if (url != null && !failed) {
                            AsyncImage(
                                model = url,
                                contentDescription = special.name,
                                contentScale = ContentScale.Crop,
                                onError = { failed = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        BottomScrim(0.75f)
                        Column(Modifier.align(Alignment.BottomStart).padding(sizes.px(14))) {
                            Text(
                                text = special.name,
                                color = Color.White,
                                fontFamily = JellytipFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = sizes.sp(JellytipHomeTokens.TEXT_PX),
                                style = TextStyle(shadow = SoftShadow),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val films = if (special.filmCount == 1) "1 film" else "${special.filmCount} films"
                            val unwatched = if (special.unwatchedCount > 0) " · ${special.unwatchedCount} unseen" else ""
                            Caption(films + unwatched, alpha = 0.8f)
                        }
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Tonight's pick: one large feature card, backdrop + logo + italic reason

@Composable
private fun TonightsPickRow(
    special: JellytipSpecial.TonightsPick,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
    viewModel: JellytipHomeViewModel,
) {
    val item = items[special.itemId] ?: return
    val pick = remember(item.id, special.heroIndex) { viewModel.heroPick(item, special.heroIndex) }
    val sizes = rememberJellytipSizes()
    ItemRow(
        title = special.title,
        items = listOf(item),
        onClickItem = { _, it -> actions.click(it) },
        onLongClickItem = { _, it -> actions.open(it) },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier,
        cardContent = { _, _, cardModifier, onClick, onLongClick ->
            SpecialCard(
                width = SpecialTokens.FEATURE_W,
                height = SpecialTokens.FEATURE_H,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(item, false) },
            ) {
                if (pick.url != null) {
                    AsyncImage(
                        model = pick.url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = focalAlignment(pick.focal),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                LeftScrim()
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(sizes.px(26)).widthIn(max = SpecialTokens.FEATURE_W * 0.8f),
                ) {
                    if (special.kicker.isNotBlank() && special.kicker != special.title) Kicker(special.kicker)
                    LogoOrTitle(item, maxWidthPx = 340, maxHeightPx = 110)
                    if (special.reason.isNotBlank()) {
                        Caption(special.reason, alpha = 0.9f, italic = true, px = JellytipHomeTokens.TEXT_PX, maxLines = 2)
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Top 10: ranked posters with big outlined numerals behind

@Composable
private fun Top10Row(
    special: JellytipSpecial.Top10,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
) {
    val ranked = remember(special, items) { special.ranked.mapNotNull { items[it] } }
    val sizes = rememberJellytipSizes()
    ItemRow(
        title = special.title,
        items = ranked,
        onClickItem = { _, it -> actions.click(it) },
        onLongClickItem = { _, it -> actions.open(it) },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier,
        cardContent = { index, item, cardModifier, onClick, onLongClick ->
            if (item != null) {
                val numeral = (index + 1).toString()
                val numeralWidth = if (numeral.length > 1) 104.dp else 58.dp
                Box(Modifier.width(numeralWidth + SpecialTokens.POSTER_W).height(SpecialTokens.POSTER_H)) {
                    Text(
                        text = numeral,
                        color = JellytipPalette.Vanilla.copy(alpha = 0.85f),
                        fontFamily = JellytipFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = sizes.sp(SpecialTokens.NUMERAL_PX),
                        letterSpacing = (-0.06).em,
                        maxLines = 1,
                        softWrap = false,
                        style = TextStyle(drawStyle = Stroke(width = 4f, join = StrokeJoin.Round)),
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                    PosterCard(
                        item,
                        onClick,
                        onLongClick,
                        cardModifier.align(Alignment.CenterEnd).onFocusChanged { if (it.isFocused) onFocusItem(item, true) },
                    )
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Double feature: two 16:9 thumbs joined + reason; OK = details of A with "Play both"

@Composable
private fun DoubleFeatureRow(
    special: JellytipSpecial.DoubleFeature,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
) {
    ItemRow(
        title = special.title,
        items = special.pairs,
        onClickItem = { _, pair ->
            items[pair.a]?.let { a ->
                JellytipDoubleBill.pair = pair.a to pair.b
                actions.navigate(a.destination())
            }
        },
        onLongClickItem = { _, pair -> items[pair.a]?.let(actions.open) },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier,
        cardContent = { _, pair, cardModifier, onClick, onLongClick ->
            val a = pair?.let { items[it.a] }
            val b = pair?.let { items[it.b] }
            if (pair != null && a != null && b != null) {
                val width = SpecialTokens.PAIR_W * 2 + 3.dp
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpecialCard(
                        width = width,
                        height = SpecialTokens.PAIR_H,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(a, true) },
                    ) {
                        Row(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
                            Box(Modifier.width(SpecialTokens.PAIR_W).fillMaxHeight()) {
                                JellytipTitledImage(a, SpecialTokens.PAIR_W, SpecialTokens.PAIR_H, fetchRanking = false)
                            }
                            Spacer(Modifier.width(3.dp))
                            Box(Modifier.width(SpecialTokens.PAIR_W).fillMaxHeight()) {
                                JellytipTitledImage(b, SpecialTokens.PAIR_W, SpecialTokens.PAIR_H, fetchRanking = false)
                            }
                        }
                    }
                    Column(Modifier.width(width).padding(start = 2.dp)) {
                        if (pair.reason.isNotBlank()) Caption(pair.reason, alpha = 0.8f)
                        pair.totalRuntimeMin?.let { Caption("${runtime(it)} together", alpha = 0.55f) }
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Because you loved: Letterboxd quote card for the anchor film, then similar unwatched films

private sealed interface LovedEntry {
    data class Anchor(
        val item: BaseItem,
    ) : LovedEntry

    data class Film(
        val item: BaseItem,
    ) : LovedEntry
}

@Composable
private fun BecauseLovedRow(
    special: JellytipSpecial.BecauseLoved,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
) {
    val anchor = items[special.anchorItemId] ?: return
    val entries = remember(special, items) { listOf(LovedEntry.Anchor(anchor)) + special.similar.mapNotNull { items[it] }.map { LovedEntry.Film(it) } }
    val sizes = rememberJellytipSizes()
    ItemRow(
        title = special.title,
        items = entries,
        onClickItem = { _, entry ->
            when (entry) {
                is LovedEntry.Anchor -> actions.click(entry.item)
                is LovedEntry.Film -> actions.click(entry.item)
            }
        },
        onLongClickItem = { _, entry ->
            when (entry) {
                is LovedEntry.Anchor -> actions.open(entry.item)
                is LovedEntry.Film -> actions.open(entry.item)
            }
        },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier,
        cardContent = { _, entry, cardModifier, onClick, onLongClick ->
            when (entry) {
                is LovedEntry.Anchor -> {
                    SpecialCard(
                        width = SpecialTokens.QUOTE_W,
                        height = SpecialTokens.WIDE_H,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(entry.item, true) },
                    ) {
                        Box(Modifier.fillMaxSize().alpha(0.28f)) {
                            JellytipTitledImage(entry.item, SpecialTokens.QUOTE_W, SpecialTokens.WIDE_H, fetchRanking = false)
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = sizes.px(22)),
                        ) {
                            special.rating?.let {
                                Text(
                                    text = stars(it),
                                    color = JellytipPalette.Jelly,
                                    fontFamily = JellytipFontFamily,
                                    fontSize = sizes.sp(JellytipHomeTokens.CARD_SUBTITLE_PX),
                                )
                            }
                            val quote = special.quote
                            if (!quote.isNullOrBlank()) {
                                Caption("“$quote”", alpha = 0.95f, italic = true, maxLines = 3)
                                Caption("— you on Letterboxd", alpha = 0.6f)
                            } else {
                                Caption("You loved ${entry.item.title ?: ""}", alpha = 0.95f, italic = true, maxLines = 2)
                            }
                        }
                    }
                }

                is LovedEntry.Film -> {
                    WideCard(entry.item, onClick, onLongClick, cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(entry.item, true) })
                }

                null -> {}
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Around the house (admins only; the server sends it only to them): 16:9 tiles + who did what

@Composable
private fun AroundHouseRow(
    special: JellytipSpecial.AroundHouse,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
) {
    val isAdmin by rememberIsJellytipAdmin()
    if (!isAdmin) return
    ItemRow(
        title = special.title,
        items = special.entries,
        onClickItem = { _, entry -> items[entry.itemId]?.let(actions::click) },
        onLongClickItem = { _, entry -> items[entry.itemId]?.let(actions.open) },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier,
        cardContent = { _, entry, cardModifier, onClick, onLongClick ->
            val item = entry?.let { items[it.itemId] }
            if (entry != null && item != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WideCard(item, onClick, onLongClick, cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(item, true) })
                    Column(Modifier.width(SpecialTokens.WIDE_W).padding(start = 2.dp)) {
                        val verb =
                            when (entry.what) {
                                "finished" -> "finished"
                                "watching" -> "is watching"
                                "requested" -> "requested"
                                else -> entry.what
                            }
                        Caption("${entry.who} $verb", alpha = 0.8f)
                        ago(entry.whenText)?.let { Caption(it, alpha = 0.55f) }
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Closet picks: the guest's video (muted on focus, same hand-off) + their picks, which light up as the video reaches them

private sealed interface ClosetEntry {
    data object Video : ClosetEntry

    data class Pick(
        val pick: JellytipSpecial.ClosetPicks.Pick,
        val item: BaseItem?,
    ) : ClosetEntry
}

@Composable
private fun ClosetPicksRow(
    special: JellytipSpecial.ClosetPicks,
    items: Map<UUID, BaseItem>,
    onFocusItem: (BaseItem?, Boolean) -> Unit,
    focusModifier: Modifier,
    actions: SpecialActions,
    viewModel: JellytipHomeViewModel,
) {
    val owner = special.id
    val settings by viewModel.settings.collectAsState(JellytipSettings())
    val isAdmin by rememberIsJellytipAdmin()
    val canRequest = special.canRequest && isAdmin
    var active by remember { mutableStateOf(false) }
    val resolve: suspend () -> String? = { viewModel.specials.companionUrl(special.videoUrl) }
    // The video keeps playing while focus moves along the picks, so they can light up as it reaches them
    SpecialVideoDriver(owner, active, settings.trailerAutoplay, null, resolve)
    val scope = rememberCoroutineScope()
    val playing = JellytipTrailerControl.isPlayingForOwner(owner)
    val positionSec by produceState(-1, playing) {
        if (!playing) {
            value = -1
            return@produceState
        }
        while (true) {
            value = ((JellytipTrailerControl.player?.currentPosition ?: 0L) / 1000L).toInt()
            delay(500)
        }
    }
    // The pick being talked about now: the latest one whose moment has passed
    val nowIndex =
        remember(special.picks, positionSec) {
            if (positionSec < 0) -1 else special.picks.indices.filter { (special.picks[it].atSec ?: Int.MAX_VALUE) <= positionSec }.maxByOrNull { special.picks[it].atSec ?: 0 } ?: -1
        }
    val posterUrl by produceState<String?>(null, special.posterUrl) { value = special.posterUrl?.let { viewModel.specials.companionUrl(it) } }
    val entries = remember(special, items) { listOf(ClosetEntry.Video) + special.picks.map { ClosetEntry.Pick(it, it.itemId?.let(items::get)) } }
    val sizes = rememberJellytipSizes()
    ItemRow(
        title = special.title,
        items = entries,
        onClickItem = { _, entry ->
            when (entry) {
                ClosetEntry.Video ->
                    when {
                        JellytipTrailerControl.onOkOwner(owner) -> {}
                        // OK again: there are no details for the video itself, so OK toggles the sound back off
                        JellytipTrailerControl.isPlayingForOwner(owner) -> JellytipTrailerControl.sound = false
                        else ->
                            scope.launch {
                                val url = resolve() ?: return@launch
                                JellytipTrailerControl.startSpecial(owner, url, sound = true)
                                JellytipTrailerControl.hintTick = System.nanoTime()
                            }
                    }

                is ClosetEntry.Pick -> {
                    val item = entry.item
                    val tmdb = entry.pick.tmdbId
                    if (item != null) {
                        actions.open(item)
                    } else if (canRequest && tmdb != null) {
                        actions.navigate(
                            Destination.JellytipDiscover(
                                DiscoverTitle(
                                    tmdbId = tmdb,
                                    mediaType = entry.pick.mediaType,
                                    title = entry.pick.title,
                                    year = entry.pick.year,
                                    posterUrl = entry.pick.posterUrl,
                                ),
                            ),
                        )
                    }
                }
            }
        },
        onLongClickItem = { _, entry -> (entry as? ClosetEntry.Pick)?.item?.let(actions.open) },
        horizontalPadding = SpecialTokens.SPACING,
        modifier = focusModifier.onFocusChanged { active = it.hasFocus },
        cardContent = { index, entry, cardModifier, onClick, onLongClick ->
            when (entry) {
                ClosetEntry.Video -> {
                    SpecialCard(
                        width = SpecialTokens.VIDEO_W,
                        height = SpecialTokens.VIDEO_H,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                    ) {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A2A2A), JellytipPalette.Night))))
                        posterUrl?.let {
                            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        SpecialVideoSurface(owner, Modifier.fillMaxSize())
                        BottomScrim(0.7f)
                        Column(Modifier.align(Alignment.BottomStart).padding(sizes.px(18))) {
                            Kicker("Closet picks")
                            Text(
                                text = special.guest,
                                color = Color.White,
                                fontFamily = JellytipFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = sizes.sp(JellytipHomeTokens.TEXT_PX),
                                style = TextStyle(shadow = SoftShadow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Caption(if (special.picks.size == 1) "1 pick" else "${special.picks.size} picks", alpha = 0.7f)
                        }
                        SoundHint(owner, "Sound on \u00B7 OK to mute", Modifier.align(Alignment.TopEnd).padding(sizes.px(14)))
                    }
                }

                is ClosetEntry.Pick -> {
                    val pickIndex = index - 1
                    val reached = entry.pick.atSec != null && positionSec >= 0 && positionSec >= entry.pick.atSec
                    // While the video plays, picks it has not reached yet sit back; the one being discussed gets a dot
                    val lightAlpha by animateFloatAsState(
                        if (positionSec >= 0 && entry.pick.atSec != null && !reached) SpecialTokens.NOT_YET_ALPHA else 1f,
                        tween(400),
                        label = "jellytip-closet-light",
                    )
                    val item = entry.item
                    Box {
                        if (item != null) {
                            PosterCard(
                                item,
                                onClick,
                                onLongClick,
                                cardModifier.alpha(lightAlpha).onFocusChanged { if (it.isFocused) onFocusItem(item, true) },
                            )
                        } else if (canRequest && entry.pick.tmdbId != null) {
                            SpecialCard(
                                width = SpecialTokens.POSTER_W,
                                height = SpecialTokens.POSTER_H,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                modifier = cardModifier.alpha(lightAlpha).onFocusChanged { if (it.isFocused) onFocusItem(null, false) },
                            ) { FadedPick(entry.pick, showGetIt = true) }
                        } else {
                            // Not in the library and not requestable here: drawn, never focusable
                            Box(
                                Modifier
                                    .size(SpecialTokens.POSTER_W, SpecialTokens.POSTER_H)
                                    .alpha(lightAlpha)
                                    .background(Color(0xFF141414), RoundedCornerShape(8.dp)),
                            ) { FadedPick(entry.pick, showGetIt = false) }
                        }
                        if (pickIndex == nowIndex) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(8.dp)
                                    .background(JellytipPalette.Jelly, CircleShape),
                            )
                        }
                    }
                }

                null -> {}
            }
        },
    )
}

@Composable
private fun BoxScope.FadedPick(
    pick: JellytipSpecial.ClosetPicks.Pick,
    showGetIt: Boolean,
) {
    val sizes = rememberJellytipSizes()
    var failed by remember(pick.posterUrl) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().alpha(SpecialTokens.FADED_ALPHA)) {
        if (pick.posterUrl != null && !failed) {
            AsyncImage(
                model = pick.posterUrl,
                contentDescription = pick.title,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Caption(
                pick.title + (pick.year?.let { " ($it)" } ?: ""),
                alpha = 1f,
                maxLines = 4,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
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

// ---------------------------------------------------------------------------------------------------------------------
// Video hand-off: the hero owns the one home ExoPlayer; a focused video special borrows it and draws it in its own frame

@Composable
private fun SpecialVideoDriver(
    owner: String,
    active: Boolean,
    autoplay: Boolean,
    itemId: UUID?,
    resolve: suspend () -> String?,
) {
    val currentResolve by rememberUpdatedState(resolve)
    LaunchedEffect(owner, active) {
        if (!active) {
            if (JellytipTrailerControl.focusedSpecial == owner) JellytipTrailerControl.focusedSpecial = null
            JellytipTrailerControl.stopIfOwner(owner)
            return@LaunchedEffect
        }
        JellytipTrailerControl.focusedSpecial = owner
        if (!autoplay || JellytipTrailerControl.isPlayingForOwner(owner)) return@LaunchedEffect
        delay(SpecialTokens.VIDEO_DELAY_MS)
        val url = currentResolve() ?: return@LaunchedEffect
        // Focus may have moved on while the URL was resolved
        if (JellytipTrailerControl.focusedSpecial == owner) JellytipTrailerControl.startSpecial(owner, url, sound = false, itemId = itemId)
    }
    DisposableEffect(owner) {
        onDispose {
            if (JellytipTrailerControl.focusedSpecial == owner) JellytipTrailerControl.focusedSpecial = null
            JellytipTrailerControl.stopIfOwner(owner)
        }
    }
}

/** Draws the shared home player while [owner] holds it; fades in on the first rendered frame. */
@Composable
private fun SpecialVideoSurface(
    owner: String,
    modifier: Modifier,
) {
    val mine = JellytipTrailerControl.isPlayingForOwner(owner)
    val player = JellytipTrailerControl.player
    var firstFrame by remember { mutableStateOf(false) }
    LaunchedEffect(mine) { if (!mine) firstFrame = false }
    DisposableEffect(player, mine) {
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrame = true
                }
            }
        if (mine) player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    // The TextureView stretches to whatever box it gets: size it to the video's own shape (black bars, never distorted)
    var videoRatio by remember { mutableStateOf(16f / 9f) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }
            }
        player?.addListener(listener)
        player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { videoRatio = it.width * it.pixelWidthHeightRatio / it.height }
        onDispose { player?.removeListener(listener) }
    }
    val alpha by animateFloatAsState(if (mine && firstFrame) 1f else 0f, tween(if (mine) 600 else 150), label = "jellytip-special-video")
    if (mine && player != null) {
        Box(modifier.graphicsLayer { this.alpha = alpha }.background(Color.Black), contentAlignment = Alignment.Center) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.aspectRatio(videoRatio.coerceIn(0.5f, 3f)),
            )
        }
    }
}

/**
 * The special's own hint (the hero's chip stays hidden while a special holds the player): "OK for sound" while its video
 * plays muted, then [soundOnText] for 2 s after OK turns the sound on.
 */
@Composable
private fun SoundHint(
    owner: String,
    soundOnText: String,
    modifier: Modifier,
) {
    val playing = JellytipTrailerControl.isPlayingForOwner(owner)
    val sound = JellytipTrailerControl.sound
    var soundOnVisible by remember { mutableStateOf(false) }
    LaunchedEffect(JellytipTrailerControl.hintTick, playing, sound) {
        soundOnVisible = false
        if (!playing || !sound || JellytipTrailerControl.hintTick == 0L) return@LaunchedEffect
        soundOnVisible = true
        delay(2_000)
        soundOnVisible = false
    }
    val text =
        when {
            !playing -> return
            !sound -> "OK for sound"
            soundOnVisible -> soundOnText
            else -> return
        }
    Caption(
        text,
        alpha = 0.9f,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared pieces

internal val SoftShadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 8f)

@Composable
internal fun SpecialCard(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    /** Corner radius; null = the theme's card shape. */
    corner: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = corner?.let { RoundedCornerShape(it) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(width, height).jellytipSiblingDim(focused),
        interactionSource = source,
        shape = if (shape != null) CardDefaults.shape(shape) else CardDefaults.shape(),
        colors = CardDefaults.colors(containerColor = Color(0xFF141414)),
        scale = JellytipFocus.scale(),
        border = if (shape != null) JellytipFocus.border(shape) else JellytipFocus.border(),
        glow = JellytipFocus.glow(),
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/** 2:3 Primary poster (posters carry their own title, so no overlay). */
@Composable
internal fun PosterCard(
    item: BaseItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val service = LocalImageUrlService.current
    val density = LocalDensity.current
    val url =
        remember(item.id, density) {
            with(density) {
                service.getItemImageUrl(
                    item,
                    ImageType.PRIMARY,
                    fillWidth = SpecialTokens.POSTER_W.roundToPx(),
                    fillHeight = SpecialTokens.POSTER_H.roundToPx(),
                )
            }
        }
    var failed by remember(url) { mutableStateOf(false) }
    SpecialCard(SpecialTokens.POSTER_W, SpecialTokens.POSTER_H, onClick, onLongClick, modifier) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Caption(item.title ?: "", alpha = 0.9f, maxLines = 4, modifier = Modifier.align(Alignment.Center).padding(8.dp))
        }
    }
}

/** 16:9 titled art (Thumb, or backdrop + logo/title), the same as the normal rows; never asks the companion per tile. */
@Composable
private fun WideCard(
    item: BaseItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    SpecialCard(SpecialTokens.WIDE_W, SpecialTokens.WIDE_H, onClick, onLongClick, modifier) {
        JellytipTitledImage(item, SpecialTokens.WIDE_W, SpecialTokens.WIDE_H, fetchRanking = false)
    }
}

@Composable
private fun LogoOrTitle(
    item: BaseItem,
    maxWidthPx: Int,
    maxHeightPx: Int,
) {
    val sizes = rememberJellytipSizes()
    val logoUrl = rememberLogoUrl(item)
    var failed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !failed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.widthIn(max = sizes.px(maxWidthPx)).heightIn(max = sizes.px(maxHeightPx)),
        )
    } else {
        Text(
            text = item.title ?: "",
            color = Color.White,
            fontFamily = JellytipFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = sizes.sp(JellytipHomeTokens.TITLE_PX),
            style = TextStyle(shadow = SoftShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun Kicker(text: String) {
    val sizes = rememberJellytipSizes()
    Text(
        text = text.uppercase(),
        color = JellytipPalette.Jelly,
        fontFamily = JellytipFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = sizes.sp(16),
        letterSpacing = 0.12.em,
        maxLines = 1,
    )
}

@Composable
internal fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = 0.75f,
    italic: Boolean = false,
    px: Int = JellytipHomeTokens.CARD_SUBTITLE_PX,
    maxLines: Int = 1,
) {
    val sizes = rememberJellytipSizes()
    Text(
        text = text,
        color = JellytipPalette.Vanilla.copy(alpha = alpha),
        fontFamily = JellytipFontFamily,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontSize = sizes.sp(px),
        style = TextStyle(shadow = SoftShadow),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun BoxScope.LeftScrim() {
    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.horizontalGradient(
                    0f to JellytipPalette.Night.copy(alpha = 0.85f),
                    0.45f to JellytipPalette.Night.copy(alpha = 0.35f),
                    0.75f to Color.Transparent,
                ),
            ),
    )
    BottomScrim(0.5f)
}

@Composable
internal fun BoxScope.BottomScrim(strength: Float) {
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = strength)))),
    )
}

private fun runtime(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

private fun stars(rating: Double): String {
    val full = rating.toInt().coerceIn(0, 5)
    return "★".repeat(full) + if (rating - full >= 0.5) "½" else ""
}

/** "2h ago", "yesterday", "3d ago" from an ISO timestamp; the raw text if it is not one; null if blank. */
private fun ago(text: String): String? {
    if (text.isBlank()) return null
    val instant =
        runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: return text
    val d = Duration.between(instant, Instant.now())
    return when {
        d.toHours() < 1 -> "just now"
        d.toHours() < 24 -> "${d.toHours()}h ago"
        d.toDays() < 2 -> "yesterday"
        else -> "${d.toDays()}d ago"
    }
}
