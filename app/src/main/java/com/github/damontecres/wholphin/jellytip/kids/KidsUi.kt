package com.github.damontecres.wholphin.jellytip.kids

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.tryRequestFocus

// ---- test tags (set inside the composables that own them; callers never add a second tag to the same node) ----
object KidsTags {
    const val HERO_PLAY = "kids-hero-play"
    const val SEARCH = "kids-nav-search"
    const val SWITCH = "kids-nav-switch"
    const val DETAILS_PLAY = "kids-details-play"
    const val DETAILS_LIST = "kids-details-list"

    fun nav(section: KidsSection) = "kids-nav-${section.name.lowercase()}"

    fun tile(
        rowId: String,
        index: Int,
    ) = "kids-tile-$rowId-$index"

    fun bubble(index: Int) = "kids-bubble-$index"

    fun grid(index: Int) = "kids-grid-$index"

    fun episode(index: Int) = "kids-episode-$index"
}

/** Font Awesome glyphs the kids look uses (the app's bundled FA solid font). */
object KidsGlyphs {
    const val HOUSE = ""
    const val TV = ""
    const val FILM = ""
    const val HEART = ""
    const val SEARCH = ""
    const val USER = ""
    const val PLAY = ""
    const val STAR = ""
    const val REPLAY = ""

    fun forSection(section: KidsSection) =
        when (section) {
            KidsSection.HOME -> HOUSE
            KidsSection.SHOWS -> TV
            KidsSection.MOVIES -> FILM
            KidsSection.MY_LIST -> HEART
        }
}

@Composable
fun KidsText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = LocalKidsTokens.current.text,
    black: Boolean = false,
    maxLines: Int = 1,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
) {
    val t = LocalKidsTokens.current
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontFamily = t.fontFamily,
        fontWeight = if (black) FontWeight.Black else FontWeight.Bold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = lineHeight,
        textAlign = textAlign,
    )
}

@Composable
fun KidsGlyph(
    glyph: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(text = glyph, modifier = modifier, color = color, fontSize = size, fontFamily = FontAwesome, maxLines = 1)
}

// ---- focus memory: Back returns to the same tile ----

/**
 * The key of the last focused target, saved with the page (survives leaving the page for details and coming Back). On a fresh
 * page it is empty. [restoreKey] is what the page was left on: the target with that key asks for focus once, when the page is
 * composed again.
 */
@Stable
class KidsFocusMemory(
    initial: String,
) {
    var key by mutableStateOf(initial)
    val restoreKey: String = initial
}

@Composable
fun rememberKidsFocusMemory(): KidsFocusMemory =
    rememberSaveable(saver = listSaver<KidsFocusMemory, String>(save = { listOf(it.key) }, restore = { KidsFocusMemory(it[0]) })) { KidsFocusMemory("") }

/** Tracks focus into [memory] under [key]; the target that was focused when the page was left also carries [restore]. */
fun Modifier.kidsTrack(
    memory: KidsFocusMemory,
    key: String,
    restore: FocusRequester,
): Modifier =
    this
        .then(if (memory.restoreKey == key) Modifier.focusRequester(restore) else Modifier)
        .onFocusChanged { if (it.hasFocus) memory.key = key }

/** Asks [restore] for focus (page was left on it) for up to ~20 frames, else [fallback]. Call from a LaunchedEffect once content exists. */
suspend fun kidsPlaceFocus(
    memory: KidsFocusMemory,
    restore: FocusRequester,
    fallback: FocusRequester?,
) {
    withFrameNanos { }
    withFrameNanos { }
    if (memory.restoreKey.isNotEmpty()) {
        repeat(20) {
            if (restore.tryRequestFocus()) return
            withFrameNanos { }
        }
    }
    fallback?.tryRequestFocus()
}

// ---- canvas, mascot ----

/** The indigo canvas with its two soft colour blobs (the mock's blurred circles, as radial gradients). */
@Composable
fun KidsCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalKidsTokens.current
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.canvasTop, t.canvasMid, t.canvasBottom)))) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-70).dp, y = 20.dp)
                .size(330.dp)
                .background(Brush.radialGradient(listOf(t.blobCoral.copy(alpha = 0.34f), Color.Transparent)), CircleShape),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 90.dp, y = (-100).dp)
                .size(360.dp)
                .background(Brush.radialGradient(listOf(t.blobBlue.copy(alpha = 0.36f), Color.Transparent)), CircleShape),
        )
        content()
    }
}

@Composable
fun KidsMascot(
    dp: Dp,
    modifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    Canvas(modifier.size(dp)) {
        val w = size.width
        drawOval(t.jelly, topLeft = Offset(w * 0.12f, w * 0.24f), size = Size(w * 0.76f, w * 0.68f))
        drawCircle(Color.White, radius = w * 0.085f, center = Offset(w * 0.37f, w * 0.54f))
        drawCircle(Color.White, radius = w * 0.085f, center = Offset(w * 0.63f, w * 0.54f))
        drawCircle(t.ink, radius = w * 0.042f, center = Offset(w * 0.385f, w * 0.55f))
        drawCircle(t.ink, radius = w * 0.042f, center = Offset(w * 0.645f, w * 0.55f))
        val smile = Path().apply {
            moveTo(w * 0.41f, w * 0.70f)
            quadraticBezierTo(w * 0.50f, w * 0.78f, w * 0.59f, w * 0.70f)
        }
        drawPath(smile, t.ink, style = Stroke(width = w * 0.034f, cap = StrokeCap.Round))
    }
}

// ---- tiles ----

private val Placeholders =
    listOf(Color(0xFF6B5AD6), Color(0xFF2F9C7A), Color(0xFFD65A7A), Color(0xFF3D7BD6), Color(0xFFC8962A), Color(0xFF8A6BD6))

/** What a tile says under its art: an episode shows its series (the page it opens), everything else its own name. */
fun kidsTileTitle(tile: KidsTile): String = if (tile.kind == KidsKind.EPISODE) tile.seriesName ?: tile.name else tile.name

@Composable
private fun transparentSurfaceColors() =
    ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent,
        focusedContainerColor = Color.Transparent,
        pressedContainerColor = Color.Transparent,
    )

/** Art or, with no picture, a colour block with the first letter. Fills its parent. */
@Composable
private fun KidsArt(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    letterSize: TextUnit = 34.sp,
) {
    val t = LocalKidsTokens.current
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(Placeholders[(name.hashCode() and 0x7fffffff) % Placeholders.size])) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            KidsText(name.take(1).uppercase(), letterSize, Modifier.align(Alignment.Center), color = t.text.copy(alpha = 0.85f), black = true)
        }
    }
}

@Composable
private fun BoxScope.KidsProgress(
    progress: Float,
    inset: Dp = 9.dp,
    height: Dp = 5.dp,
) {
    val t = LocalKidsTokens.current
    if (progress <= 0f || progress >= 1f) return
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = inset, end = inset, bottom = inset)
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Color(0x38FFFFFF)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(t.accent, CircleShape))
    }
}

/** One 16:9 tile: art with a focus ring and grow, the title under it, a progress bar, and "x5" when watched again. */
@Composable
fun KidsTileCard(
    tile: KidsTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    focusModifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) t.focusScale else 1f, tween(t.focusMillis), label = "kids-tile-scale")
    val ring by animateColorAsState(if (focused) t.ring else t.borderColor, tween(t.focusMillis), label = "kids-tile-ring")
    val shape = RoundedCornerShape(t.tileRadius)
    Column(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Box {
            Surface(
                onClick = onClick,
                interactionSource = src,
                modifier = focusModifier.fillMaxWidth().aspectRatio(16f / 9f).then(if (tag != null) Modifier.testTag(tag) else Modifier),
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = transparentSurfaceColors(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Box(Modifier.fillMaxSize().clip(shape).border(t.tileBorder + if (focused) 1.dp else 0.dp, ring, shape)) {
                    KidsArt(tile.imageUrl, kidsTileTitle(tile), Modifier.fillMaxSize())
                    KidsProgress(tile.progress)
                }
            }
            if (tile.playCount > 1) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-7).dp)
                        .rotate(6f)
                        .background(t.accent, CircleShape)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    KidsGlyph(KidsGlyphs.REPLAY, 10.sp, t.ink)
                    KidsText("x${tile.playCount}", 11.sp, color = t.ink, black = true)
                }
            }
        }
        KidsText(kidsTileTitle(tile), 13.5.sp, Modifier.padding(top = 5.dp), black = true)
    }
}

/** A round "friend" (a series or collection), name under it. */
@Composable
fun KidsBubble(
    tile: KidsTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    focusModifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(t.focusMillis), label = "kids-bubble-scale")
    val ring by animateColorAsState(if (focused) t.ring else Color(0x38FFFFFF), tween(t.focusMillis), label = "kids-bubble-ring")
    Column(
        modifier
            .width(t.bubble)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = onClick,
            interactionSource = src,
            modifier = focusModifier.size(t.bubble).then(if (tag != null) Modifier.testTag(tag) else Modifier),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = transparentSurfaceColors(),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Box(Modifier.fillMaxSize().clip(CircleShape).border(t.tileBorder + if (focused) 1.dp else 0.dp, ring, CircleShape)) {
                KidsArt(tile.imageUrl, tile.name, Modifier.fillMaxSize(), letterSize = 30.sp)
            }
        }
        KidsText(tile.name, 13.sp, Modifier.padding(top = 4.dp), black = true, textAlign = TextAlign.Center)
    }
}

// ---- nav ----

@Composable
private fun KidsNavItem(
    section: KidsSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused || selected) 1.12f else 1f, tween(t.focusMillis), label = "kids-nav-scale")
    val ring by animateColorAsState(if (focused) t.ring else Color.Transparent, tween(t.focusMillis), label = "kids-nav-ring")
    Surface(
        onClick = onClick,
        interactionSource = src,
        modifier = Modifier.testTag(KidsTags.nav(section)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(30.dp)),
        colors = transparentSurfaceColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp).padding(vertical = 3.dp)) {
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }.size(52.dp)
                    .border(3.dp, ring, CircleShape)
                    .padding(3.dp)
                    .background(t.sectionColor(section), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                KidsGlyph(KidsGlyphs.forSection(section), 24.sp, t.ink)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.then(if (selected) Modifier.background(Color.White, CircleShape) else Modifier).padding(horizontal = 7.dp),
            ) {
                KidsText(section.label, 12.5.sp, color = if (selected) t.ink else t.textDim, black = true)
            }
        }
    }
}

@Composable
private fun KidsRoundButton(
    glyph: String,
    tag: String,
    onClick: () -> Unit,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) t.ring else Color.Transparent, tween(t.focusMillis), label = "kids-round-ring")
    Surface(
        onClick = onClick,
        interactionSource = src,
        modifier = Modifier.testTag(tag),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = transparentSurfaceColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.size(42.dp).border(3.dp, ring, CircleShape).padding(3.dp).background(t.chipColor, CircleShape), contentAlignment = Alignment.Center) {
            KidsGlyph(glyph, 18.sp, t.text)
        }
    }
}

@Composable
private fun KidsPillButton(
    glyph: String,
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyphColor: Color = LocalKidsTokens.current.text,
    fontSize: TextUnit = 14.sp,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) t.ring else Color.Transparent, tween(t.focusMillis), label = "kids-pill-ring")
    Surface(
        onClick = onClick,
        interactionSource = src,
        modifier = modifier.testTag(tag),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = transparentSurfaceColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.border(3.dp, ring, CircleShape).padding(3.dp).background(t.chipColor, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            KidsGlyph(glyph, fontSize, glyphColor)
            KidsText(label, fontSize, black = true)
        }
    }
}

/**
 * The top bar: who is watching, four round picture buttons (icon + colour + word), search, and a plain "Switch profile" button.
 * [selected] null = none of the four is the current page (Search). No drawer, no settings, no PIN: the switch button opens the
 * existing switch-user screen (decision B1).
 */
@Composable
fun KidsNavBar(
    userName: String?,
    selected: KidsSection?,
    onSection: (KidsSection) -> Unit,
    onSearch: () -> Unit,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    Row(
        modifier.fillMaxWidth().padding(start = t.pagePad, end = t.pagePad, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.width(160.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KidsMascot(46.dp)
            Column {
                KidsText("JELLYTIP KIDS", 9.5.sp, color = Color(0xFFFFB74D), black = true)
                KidsText(if (userName.isNullOrBlank()) "Hi!" else "Hi $userName!", 19.sp, black = true)
            }
        }
        KidsSection.entries.forEach { s -> KidsNavItem(s, selected == s) { onSection(s) } }
        Spacer(Modifier.weight(1f))
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KidsRoundButton(KidsGlyphs.SEARCH, KidsTags.SEARCH, onSearch)
            KidsPillButton(KidsGlyphs.USER, "Switch profile", KidsTags.SWITCH, onSwitch)
        }
    }
}

// ---- hero ----

/** The line under the hero title. Pure. */
fun kidsHeroSub(hero: KidsHero): String {
    val tile = hero.tile
    val episode = if (tile.kind == KidsKind.EPISODE) tile.name else null
    val tail = if (hero.resume) (if (tile.progress >= 0.75f) "nearly finished!" else "keep watching!") else "today's pick for you"
    return listOfNotNull(episode, tail).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

@Composable
fun KidsPlayDisc(
    dp: Dp,
    glyphSize: TextUnit,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) t.focusScale else 1f, tween(t.focusMillis), label = "kids-play-scale")
    val ring by animateColorAsState(if (focused) t.ring else Color.Transparent, tween(t.focusMillis), label = "kids-play-ring")
    Surface(
        onClick = onClick,
        interactionSource = src,
        modifier =
            modifier
                .size(dp)
                .testTag(tag)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = transparentSurfaceColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize().border(4.dp, ring, CircleShape).padding(4.dp).background(t.jelly, CircleShape), contentAlignment = Alignment.Center) {
            KidsGlyph(KidsGlyphs.PLAY, glyphSize, Color.White, Modifier.padding(start = 5.dp))
        }
    }
}

@Composable
private fun KidsHeroBackdrop(
    imageUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    Box(modifier.background(Brush.linearGradient(listOf(t.heroFallbackA, t.heroFallbackB)))) {
        if (imageUrl != null) {
            var failed by remember(imageUrl) { mutableStateOf(false) }
            if (!failed) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to t.heroScrim.copy(alpha = 0.82f),
                        0.46f to t.heroScrim.copy(alpha = 0.35f),
                        0.68f to Color.Transparent,
                    ),
                ),
        )
    }
}

/** The one big Play (Keep watching, else today's pick). Always the first thing on Home. */
@Composable
fun KidsHeroCard(
    hero: KidsHero,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    val shape = RoundedCornerShape(t.heroRadius)
    Box(modifier.fillMaxWidth().height(186.dp).clip(shape).border(t.heroBorder, t.heroBorderColor, shape)) {
        KidsHeroBackdrop(hero.imageUrl, hero.tile.name, Modifier.fillMaxSize())
        Row(Modifier.fillMaxHeight().padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            KidsPlayDisc(88.dp, 40.sp, KidsTags.HERO_PLAY, onPlay, focusModifier)
            Column(Modifier.width(430.dp)) {
                KidsText(kidsTileTitle(hero.tile), 43.sp, black = true, maxLines = 1)
                KidsText(kidsHeroSub(hero), 15.sp, Modifier.padding(top = 3.dp, bottom = 10.dp), color = t.sub)
                if (hero.resume && hero.tile.progress > 0f) {
                    val done = (hero.tile.progress * 8).toInt().coerceIn(0, 7)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 0 until 8) {
                            val color = if (i < done) t.accent else if (i == done) Color.White else Color(0x47FFFFFF)
                            Box(Modifier.size(15.dp).then(if (i == done) Modifier.border(2.5.dp, Color(0x59FFFFFF), CircleShape) else Modifier).background(color, CircleShape))
                        }
                    }
                }
            }
        }
    }
}

// ---- Home ----

sealed interface KidsHomeUi {
    data object Loading : KidsHomeUi

    data class Ready(
        val data: KidsHomeData,
    ) : KidsHomeUi

    /** 403 / error / old server: no rows, no hero, never the adult Home. */
    data object Unavailable : KidsHomeUi
}

private fun rowGlyph(id: String): Pair<String, Color> =
    when (id) {
        "friends" -> KidsGlyphs.STAR to Color(0xFFFF9FB5)
        "watch_again" -> KidsGlyphs.REPLAY to Color(0xFF7FE0B8)
        "movie_night" -> KidsGlyphs.FILM to Color(0xFFB79CF2)
        else -> KidsGlyphs.STAR to Color(0xFFFFD24D)
    }

@Composable
private fun KidsSectionTitle(
    glyph: String,
    glyphColor: Color,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KidsGlyph(glyph, 16.sp, glyphColor)
        KidsText(title, 19.sp, black = true)
    }
}

/** Space the LazyRows leave for the focus ring and the 6% grow, so the row's clip never cuts them. */
private val RowInset = 8.dp

@Composable
fun KidsHomeContent(
    ui: KidsHomeUi,
    memory: KidsFocusMemory,
    onOpen: (KidsTile) -> Unit,
    onPlayHero: (KidsHero) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val t = LocalKidsTokens.current
    val heroFocus = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val data = (ui as? KidsHomeUi.Ready)?.data
    LaunchedEffect(data != null) {
        if (data != null) kidsPlaceFocus(memory, restore, if (data.hero != null) heroFocus else null)
    }
    when {
        ui is KidsHomeUi.Loading -> Box(modifier.fillMaxSize())
        data == null || (data.hero == null && data.rows.isEmpty()) ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KidsMascot(84.dp)
                    KidsText("Nothing to watch here yet", 20.sp, black = true)
                }
            }

        else ->
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = t.pagePad - RowInset, end = t.pagePad - RowInset, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier.fillMaxSize(),
            ) {
                data.hero?.let { hero ->
                    item(key = "hero") {
                        KidsHeroCard(
                            hero = hero,
                            onPlay = { onPlayHero(hero) },
                            modifier = Modifier.padding(horizontal = RowInset),
                            focusModifier = Modifier.focusRequester(heroFocus).kidsTrack(memory, "hero", restore),
                        )
                    }
                }
                items(data.rows.size, key = { data.rows[it].id }) { r ->
                    val row = data.rows[r]
                    val (glyph, gc) = rowGlyph(row.id)
                    Column {
                        KidsSectionTitle(glyph, gc, row.title, Modifier.padding(start = RowInset, top = 6.dp))
                        LazyRow(
                            state = rememberLazyListState(),
                            contentPadding = PaddingValues(horizontal = RowInset, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(t.tileGap),
                        ) {
                            itemsIndexed(row.items, key = { i, tile -> "${tile.id}#$i" }) { i, tile ->
                                val key = "${row.id}:${tile.id}:$i"
                                val track = Modifier.kidsTrack(memory, key, restore)
                                if (row.bubbles) {
                                    KidsBubble(tile, onClick = { onOpen(tile) }, tag = KidsTags.bubble(i), focusModifier = track)
                                } else {
                                    KidsTileCard(tile, onClick = { onOpen(tile) }, modifier = Modifier.width(t.tileWidth), tag = KidsTags.tile(row.id, i), focusModifier = track)
                                }
                            }
                        }
                    }
                }
            }
    }
}

// ---- Shows / Movies / My list: art-first grids ----

/** `tiles` null = still loading. */
@Composable
fun KidsGridContent(
    section: KidsSection,
    tiles: List<KidsTile>?,
    memory: KidsFocusMemory,
    onOpen: (KidsTile) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState,
) {
    val t = LocalKidsTokens.current
    val restore = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    LaunchedEffect(tiles != null) {
        if (!tiles.isNullOrEmpty()) kidsPlaceFocus(memory, restore, first)
    }
    Column(modifier.fillMaxSize().padding(horizontal = t.pagePad - RowInset)) {
        Row(Modifier.padding(start = RowInset, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).background(t.sectionColor(section), CircleShape), contentAlignment = Alignment.Center) {
                KidsGlyph(KidsGlyphs.forSection(section), 14.sp, t.ink)
            }
            KidsText(section.label, 24.sp, black = true)
        }
        when {
            tiles == null -> Unit
            tiles.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KidsText(if (section == KidsSection.MY_LIST) "Press the heart on a show or film to keep it here" else "Nothing here yet", 18.sp, black = true)
                }

            else ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = RowInset, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(t.tileGap),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(tiles, key = { i, tile -> "${tile.source}:${tile.id}#$i" }) { i, tile ->
                        val track = Modifier.kidsTrack(memory, "grid:${tile.id}:$i", restore).then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        KidsTileCard(tile, onClick = { onOpen(tile) }, modifier = Modifier.fillMaxWidth(), tag = KidsTags.grid(i), focusModifier = track)
                    }
                }
        }
    }
}

// ---- Details ----

@Composable
private fun KidsChipView(chip: KidsChip) {
    val t = LocalKidsTokens.current
    val bg = if (chip.rating) t.showsColor else t.chipColor
    val fg = if (chip.rating) Color(0xFF0A2A1E) else t.text
    Box(Modifier.background(bg, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)) { KidsText(chip.text, 12.sp, color = fg, black = true) }
}

@Composable
private fun KidsSeasonChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) t.ring else Color.Transparent, tween(t.focusMillis), label = "kids-season-ring")
    Surface(
        onClick = onClick,
        interactionSource = src,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = transparentSurfaceColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.border(3.dp, ring, CircleShape).padding(3.dp).background(if (selected) t.accent else t.chipColor, CircleShape).padding(horizontal = 11.dp, vertical = 4.dp)) {
            KidsText(label, 13.sp, color = if (selected) t.ink else t.text, black = true)
        }
    }
}

@Composable
private fun KidsEpisodeCard(
    ep: KidsEpisode,
    nextUp: Boolean,
    onClick: () -> Unit,
    tag: String,
    focusModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) t.focusScale else 1f, tween(t.focusMillis), label = "kids-ep-scale")
    val ring by animateColorAsState(if (focused) t.ring else t.borderColor, tween(t.focusMillis), label = "kids-ep-ring")
    val shape = RoundedCornerShape(t.tileRadius)
    Column(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Box {
            Surface(
                onClick = onClick,
                interactionSource = src,
                modifier = focusModifier.fillMaxWidth().aspectRatio(16f / 9f).testTag(tag),
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = transparentSurfaceColors(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Box(Modifier.fillMaxSize().clip(shape).border(t.tileBorder + if (focused) 1.dp else 0.dp, ring, shape)) {
                    KidsArt(ep.imageUrl, ep.title, Modifier.fillMaxSize())
                    KidsProgress(ep.progress)
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-5).dp, y = (-6).dp)
                    .size(30.dp)
                    .background(if (ep.played) t.showsColor else if (nextUp) t.accent else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                KidsText("${ep.number}", 15.sp, color = t.ink, black = true)
            }
        }
        KidsText(ep.title, 13.sp, Modifier.padding(top = 5.dp), black = true)
    }
}

/**
 * Details for a movie or series in the kids look: title, chips, one blurb, a big Play, My list, and (series) the episodes.
 * No cast or crew, reviews, trailers or source picker. A pure content composable: the host passes plain data.
 */
@Composable
fun KidsDetailsContent(
    details: KidsDetails,
    seasonIndex: Int,
    favourite: Boolean,
    onSeason: (Int) -> Unit,
    onPlay: () -> Unit,
    onToggleList: () -> Unit,
    onEpisode: (KidsEpisode) -> Unit,
    memory: KidsFocusMemory,
    modifier: Modifier = Modifier,
) {
    val t = LocalKidsTokens.current
    val playFocus = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) { kidsPlaceFocus(memory, restore, playFocus) }
    val shape = RoundedCornerShape(t.heroRadius)
    val season = details.seasons.getOrNull(seasonIndex)
    val nextUpId = details.playTarget?.itemId
    Column(modifier.fillMaxSize().padding(horizontal = t.pagePad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(262.dp).padding(top = 22.dp).clip(shape).border(t.heroBorder, t.heroBorderColor, shape)) {
            KidsHeroBackdrop(details.imageUrl, details.title, Modifier.fillMaxSize())
            Column(Modifier.padding(start = 30.dp, top = 22.dp).width(450.dp)) {
                KidsText(details.title, 46.sp, black = true, maxLines = 1)
                Row(Modifier.padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { details.chips.forEach { KidsChipView(it) } }
                if (!details.overview.isNullOrBlank()) {
                    KidsText(details.overview, 15.5.sp, color = Color(0xFFF4EFFF), maxLines = 2, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    KidsPlayDisc(
                        68.dp,
                        32.sp,
                        KidsTags.DETAILS_PLAY,
                        onPlay,
                        Modifier.focusRequester(playFocus).kidsTrack(memory, "play", restore),
                    )
                    Column {
                        KidsText(details.playLabel, 20.sp, black = true)
                        if (details.playSub != null) KidsText(details.playSub, 13.sp, color = t.sub)
                    }
                    KidsPillButton(
                        glyph = KidsGlyphs.HEART,
                        label = if (favourite) "On my list" else "My list",
                        tag = KidsTags.DETAILS_LIST,
                        onClick = onToggleList,
                        modifier = Modifier.padding(start = 8.dp),
                        glyphColor = t.listColor,
                        fontSize = 15.sp,
                    )
                }
            }
        }
        if (season != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                KidsText("Episodes", 19.sp, black = true)
                if (details.seasons.size > 1) {
                    details.seasons.forEachIndexed { i, s -> KidsSeasonChip(s.label, i == seasonIndex) { onSeason(i) } }
                }
            }
            LazyRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = RowInset, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(t.tileGap),
            ) {
                itemsIndexed(season.episodes, key = { _, e -> e.id }) { i, ep ->
                    KidsEpisodeCard(
                        ep = ep,
                        nextUp = ep.id == nextUpId,
                        onClick = { onEpisode(ep) },
                        tag = KidsTags.episode(i),
                        focusModifier = Modifier.kidsTrack(memory, "ep:${ep.id}", restore),
                        modifier = Modifier.width(170.dp),
                    )
                }
            }
        }
    }
}
