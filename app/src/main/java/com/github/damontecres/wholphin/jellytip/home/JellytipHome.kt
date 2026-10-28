package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipLogoTint
import com.github.damontecres.wholphin.jellytip.JellytipScrim
import com.github.damontecres.wholphin.jellytip.JellytipFocal
import com.github.damontecres.wholphin.jellytip.cache.focalAlignment
import com.github.damontecres.wholphin.jellytip.cache.focalOrigin
import com.github.damontecres.wholphin.jellytip.JellytipExtras
import com.github.damontecres.wholphin.jellytip.JellytipSettings
import com.github.damontecres.wholphin.jellytip.cache.jellytipHeroItemId
import com.github.damontecres.wholphin.jellytip.fillFullBleed
import com.github.damontecres.wholphin.jellytip.LetterboxdInline
import com.github.damontecres.wholphin.jellytip.scoreLine as jellytipScoreLine
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import kotlin.math.abs

/** Palette from design/splash.html */
object JellytipPalette {
    val Night = Color(0xFF0A0A0A)
    val Jelly = Color(0xFFE2264D)
    val Vanilla = Color(0xFFF2F2F0)
}

/** True inside the Jellytip home page; other screens that reuse the shared row/card code stay stock. */
val LocalJellytipHome = compositionLocalOf { false }

@Composable
fun JellytipHomeTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme =
            base.copy(
                border = Color.White,
                onSurface = JellytipPalette.Vanilla,
                onBackground = JellytipPalette.Vanilla,
            ),
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalJellytipHome provides true, content = content)
    }
}

/** Design tokens for the home hero. Sizes are in physical px at 1080p; edit here only. */
object JellytipHomeTokens {
    const val TEXT_PX = 22
    const val TITLE_PX = 34
    const val LOGO_MAX_W_PX = 380
    const val LOGO_MAX_H_PX = 150
    const val TEXT_BLOCK_W_PX = 900
    const val START_PAD_PX = 16
    const val BOTTOM_PAD_PX = 28
    const val HEADER_HEIGHT_FRACTION = 0.56f // rows start here; text block sits at its bottom, above the first row
    const val HERO_HEIGHT_FRACTION = 0.70f

    /** Landscape tile height in dp (~20% smaller than the previous 132) and the gap between tiles; card image requests derive from these. */
    const val LANDSCAPE_CARD_HEIGHT_DP = 106
    const val LANDSCAPE_CARD_SPACING_DP = 10
    /** Hero/details clearlogo drawn as a solid off-white silhouette so it never repeats the coloured logo on the tile. Flip to false to restore. */
    const val WHITE_LOGO = true
    val LOGO_TINT = Color(0xFFF2F2F0)
    const val VIGNETTE_ALPHA = 0.78f
    const val RAIL_SCRIM_ALPHA = 0.55f

    /** Rest time before a muted trailer replaces the still (home hero and details). */
    const val TRAILER_DELAY_MS = 6_000L

    /** Rest time before the home hero starts its muted trailer (details keeps [TRAILER_DELAY_MS]). */
    const val HOME_TRAILER_DELAY_MS = 2_500L

    // Details page (shares TEXT_PX / TITLE-less logo box above)
    const val BADGE_PX = 19
    const val CARD_SUBTITLE_PX = 18
    const val DETAILS_TITLE_PX = 64
    const val DETAILS_GAP_PX = 14
    const val DETAILS_LEFT_PX = 96
    const val DETAILS_BOTTOM_PX = 84
    const val DETAILS_WIDTH_PX = 820
}

private const val BACKDROP_DEBOUNCE_MS = 250L
private const val SETTLE_EXTRAS_MS = 600L
/** Slow zoom about the focal point; capped at 5% so the hero is never heavily enlarged. */
const val KEN_BURNS_MAX_ZOOM = 0.05f
private const val KEN_BURNS_MS = 24_000

/**
 * Rows still on the stock defaults become landscape stills with titles; anything the user customised is untouched.
 */
fun jellytipRowOptions(options: HomeRowViewOptions): HomeRowViewOptions =
    if (options == HomeRowViewOptions()) {
        options.copy(
            heightDp = JellytipHomeTokens.LANDSCAPE_CARD_HEIGHT_DP,
            spacing = JellytipHomeTokens.LANDSCAPE_CARD_SPACING_DP,
            aspectRatio = AspectRatio.WIDE,
            contentScale = PrefContentScale.CROP,
            imageType = ViewOptionImageType.THUMB, // swapped for the backdrop in the card
            showTitles = true,
            useSeries = false,
            episodeAspectRatio = AspectRatio.WIDE,
            episodeContentScale = PrefContentScale.CROP,
        )
    } else {
        options
    }

/** Full-bleed hero behind the top of the home page; draws its own night fill so the stock backdrop never shows. */
@Composable
fun JellytipHomeHero(
    item: BaseItem?,
    hasFocus: Boolean,
    modifier: Modifier = Modifier,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState(JellytipSettings())

    var resumed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    // Backdrop follows focus with a short debounce; keep the last image when the new item has none
    var shown by remember { mutableStateOf<BaseItem?>(null) }
    LaunchedEffect(item?.id) {
        if (item != null) {
            if (shown != null) delay(BACKDROP_DEBOUNCE_MS)
            shown = item
        }
    }
    var lastUrl by remember { mutableStateOf<String?>(null) }
    var lastFocal by remember { mutableStateOf<JellytipFocal?>(null) }
    // Hero photo comes from the companion (differs from the tile art); until it answers, show a non-tile backdrop and upgrade
    val heroIndex by produceState<Int?>(null, shown?.id) {
        val target = shown
        value = null
        if (target != null) value = JellytipApi.shared?.peekHeroIndex(jellytipHeroItemId(target)) ?: viewModel.heroIndex(target)
    }
    val pick = remember(shown?.id, heroIndex) { shown?.let { viewModel.heroPick(it, heroIndex) } }
    val url = pick?.url
    if (url != null && (url != lastUrl || pick.focal != lastFocal)) {
        lastUrl = url
        lastFocal = pick.focal
    }

    // Trailer: only after focus has rested on the same card. State lives in JellytipTrailerControl so OK presses can drive it.
    val trailerWanted = hasFocus && resumed && settings.trailerAutoplay
    LaunchedEffect(item?.id, trailerWanted) {
        JellytipTrailerControl.stop()
        if (!trailerWanted || item == null) return@LaunchedEffect
        delay(JellytipHomeTokens.HOME_TRAILER_DELAY_MS)
        viewModel.localTrailer(item)?.let { (url, trailerId) ->
            JellytipTrailerControl.start(item, url, trailerId, sound = settings.trailerSound)
        }
    }
    DisposableEffect(Unit) { onDispose { JellytipTrailerControl.stop() } }
    val trailerUrl = JellytipTrailerControl.url

    val scrim by animateFloatAsState(JellytipScrim.strength(lastUrl), tween(400), label = "jellytip-scrim")
    Box(modifier = modifier.fillMaxSize().fillFullBleed().background(JellytipPalette.Night)) {
        // Clip: the Ken Burns zoom would otherwise bleed unfaded past the hero's bottom edge (the stray grey line)
        Box(Modifier.fillMaxWidth().fillMaxHeight(JellytipHomeTokens.HERO_HEIGHT_FRACTION).clipToBounds()) {
            Crossfade(
                targetState = lastUrl,
                animationSpec = tween(700),
                modifier = Modifier.fillMaxSize(),
                label = "jellytip-home-still",
            ) { u -> if (u != null) HomeKenBurns(u, lastFocal) }

            var usedPlayer by remember { mutableStateOf(false) }
            if (trailerUrl != null) usedPlayer = true
            if (usedPlayer) {
                HeroTrailer(
                    url = trailerUrl,
                    sound = JellytipTrailerControl.sound,
                    httpClient = viewModel.httpClient,
                    onEnded = { JellytipTrailerControl.stop() },
                )
            }
            // "Sound on" hint, 2 s, bottom-right just above the first row
            var hintVisible by remember { mutableStateOf(false) }
            LaunchedEffect(JellytipTrailerControl.hintTick) {
                if (JellytipTrailerControl.hintTick == 0L) return@LaunchedEffect
                hintVisible = true
                delay(2_000)
                hintVisible = false
            }
            AnimatedVisibility(
                visible = hintVisible,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(300)),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            end = 48.dp,
                            top = (LocalConfiguration.current.screenHeightDp * JellytipHomeTokens.HEADER_HEIGHT_FRACTION - 40).dp,
                        ),
            ) {
                Text(
                    text = "Sound on  \u00B7  OK for details",
                    fontSize = rememberJellytipSizes().sp(JellytipHomeTokens.TEXT_PX),
                    color = JellytipPalette.Vanilla,
                    modifier =
                        Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // Narrow left strip keeps the nav rail legible
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(96.dp)
                    .background(
                        Brush.horizontalGradient(
                            0f to JellytipPalette.Night.copy(alpha = JellytipHomeTokens.RAIL_SCRIM_ALPHA),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            // Soft vignette in the bottom-left corner behind the text block
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to JellytipPalette.Night.copy(alpha = JellytipScrim.lerp(JellytipHomeTokens.VIGNETTE_ALPHA, 0.94f, scrim)),
                            0.55f to JellytipPalette.Night.copy(alpha = JellytipScrim.lerp(0.40f, 0.80f, scrim)),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
                            radius = JellytipScrim.lerp(1400f, 2100f, scrim),
                        ),
                    ),
            )
            // Bottom fade into the rows
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to JellytipPalette.Night.copy(alpha = 0.30f),
                            0.30f to Color.Transparent,
                            0.60f to JellytipPalette.Night.copy(alpha = 0.40f),
                            0.9f to JellytipPalette.Night,
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun HomeKenBurns(
    url: String,
    focal: JellytipFocal?,
) {
    val progress = remember(url) { Animatable(0f) }
    LaunchedEffect(url) {
        progress.animateTo(1f, tween(KEN_BURNS_MS, easing = LinearEasing))
    }
    val direction = if (abs(url.hashCode()) % 2 == 0) 1f else -1f
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = focalAlignment(focal),
        onSuccess = { JellytipScrim.inspect(url, it.result.image) },
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = focalOrigin(focal)
                    val p = progress.value
                    val scale = 1f + KEN_BURNS_MAX_ZOOM * p
                    scaleX = scale
                    scaleY = scale
                    translationX = direction * size.width * 0.010f * (p - 0.5f)
                },
    )
}

/** One ExoPlayer for the whole home page, created on first use and re-pointed at each trailer. */
@Composable
private fun HeroTrailer(
    url: String?,
    sound: Boolean,
    httpClient: okhttp3.OkHttpClient,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    var firstFrame by remember { mutableStateOf(false) }
    val player =
        remember {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(httpClient)))
                .build()
                .apply { repeatMode = Player.REPEAT_MODE_OFF }
        }
    val currentOnEnded by rememberUpdatedState(onEnded)
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) currentOnEnded()
                }

                override fun onRenderedFirstFrame() {
                    firstFrame = true
                }

                override fun onPlayerError(error: PlaybackException) {
                    currentOnEnded()
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(url) {
        firstFrame = false
        if (url != null) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.playWhenReady = true
            player.prepare()
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }
    LaunchedEffect(sound) { player.volume = if (sound) 1f else 0f }
    val alpha by animateFloatAsState(
        targetValue = if (url != null && firstFrame) 1f else 0f,
        animationSpec = tween(if (url != null) 800 else 150),
        label = "jellytip-home-trailer-alpha",
    )
    if (alpha > 0f || url != null) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
        )
    }
}

private data class HeaderChips(
    val scores: List<String>,
    val mine: String?,
)

/** Small, quiet text block, bottom-left: logo or title, one metadata line, one-line overview, one score line. */
@Composable
fun JellytipHomeHeader(
    item: BaseItem?,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current
    val isEpisodic = item?.type == BaseItemKind.EPISODE || item?.type == BaseItemKind.SEASON
    val title = if (isEpisodic) item?.data?.seriesName else item?.title
    val subtitle = if (isEpisodic) item?.name else null

    val extras by produceState<Pair<UUID, JellytipExtras?>?>(null, item?.id) {
        val target = item
        if (target == null || (target.type != BaseItemKind.MOVIE && target.type != BaseItemKind.SERIES)) {
            value = null
        } else {
            delay(SETTLE_EXTRAS_MS)
            value = target.id to viewModel.getExtras(target)
        }
    }
    val scoreLine =
        remember(extras, item?.id) { jellytipScoreLine(extras?.takeIf { it.first == item?.id }?.second) }

    with(density) {
        val textSize = JellytipHomeTokens.TEXT_PX.toSp()
        val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        // Soft shadow on every hero text line so it stays legible over bright art
        val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.dp.toPx()), 6.dp.toPx())
        Box(
            modifier = modifier.fillMaxWidth().height((screenHeight * JellytipHomeTokens.HEADER_HEIGHT_FRACTION).dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .padding(
                            start = JellytipHomeTokens.START_PAD_PX.toDp(),
                            bottom = JellytipHomeTokens.BOTTOM_PAD_PX.toDp(),
                        ).width(JellytipHomeTokens.TEXT_BLOCK_W_PX.toDp()),
            ) {
                val logoUrl = rememberLogoUrl(item)
                var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                if (showLogo && logoUrl != null && !logoFailed) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        colorFilter = JellytipLogoTint.filter(logoUrl),
                        onSuccess = { JellytipLogoTint.inspect(logoUrl, it.result.image) },
                        onError = { logoFailed = true },
                        modifier =
                            Modifier
                                .widthIn(max = JellytipHomeTokens.LOGO_MAX_W_PX.toDp())
                                .heightIn(max = JellytipHomeTokens.LOGO_MAX_H_PX.toDp())
                                .height(JellytipHomeTokens.LOGO_MAX_H_PX.toDp()),
                    )
                } else {
                    Text(
                        text = title ?: "",
                        style = MaterialTheme.typography.headlineMedium.copy(shadow = shadow),
                        fontSize = JellytipHomeTokens.TITLE_PX.toSp(),
                        fontWeight = FontWeight.Medium,
                        color = JellytipPalette.Vanilla,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                        fontSize = textSize,
                        color = JellytipPalette.Vanilla,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                QuickDetails(
                    item?.ui?.quickDetails,
                    item?.timeRemainingOrRuntime,
                    endsAt = item?.data?.endDate,
                    showRatings = scoreLine == null, // the companion's quiet score line replaces the stock star/tomato ratings
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = textSize, shadow = shadow),
                )
                val overview = item?.data?.overview
                if (!overview.isNullOrBlank()) {
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                        fontSize = textSize,
                        color = quiet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (scoreLine != null) {
                    Text(
                        text = scoreLine,
                        inlineContent = LetterboxdInline,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                        fontSize = textSize,
                        color = JellytipPalette.Vanilla.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
