package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.github.damontecres.wholphin.jellytip.JellytipChip
import com.github.damontecres.wholphin.jellytip.JellytipExtras
import com.github.damontecres.wholphin.jellytip.JellytipSettings
import com.github.damontecres.wholphin.jellytip.WARM
import com.github.damontecres.wholphin.jellytip.fillFullBleed
import com.github.damontecres.wholphin.jellytip.myChipText
import com.github.damontecres.wholphin.jellytip.scoreChipText
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.components.rememberLogoUrl
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Palette from design/splash.html */
object JellytipPalette {
    val Night = Color(0xFF0E0B1C)
    val Jelly = Color(0xFFE2264D)
    val Vanilla = Color(0xFFF7E8C4)
}

/** True inside the Jellytip home page; other screens that reuse the shared row/card code stay stock. */
val LocalJellytipHome = compositionLocalOf { false }

@Composable
fun JellytipHomeTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme =
            base.copy(
                border = JellytipPalette.Jelly,
                onSurface = JellytipPalette.Vanilla,
                onBackground = JellytipPalette.Vanilla,
            ),
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalJellytipHome provides true, content = content)
    }
}

private const val LANDSCAPE_HEIGHT_DP = 132
private const val BACKDROP_DEBOUNCE_MS = 250L
private const val SETTLE_EXTRAS_MS = 600L
private const val SETTLE_TRAILER_MS = 4_000L
private const val KEN_BURNS_MS = 24_000

/**
 * Rows still on the stock defaults become landscape stills with titles; anything the user customised is untouched.
 */
fun jellytipRowOptions(options: HomeRowViewOptions): HomeRowViewOptions =
    if (options == HomeRowViewOptions()) {
        options.copy(
            heightDp = LANDSCAPE_HEIGHT_DP,
            spacing = 12,
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
    val url = remember(shown?.id) { shown?.let { viewModel.backdropUrl(it) } }
    if (url != null && url != lastUrl) lastUrl = url

    // Trailer: only after focus has rested on the same trailer-bearing card
    var trailerUrl by remember { mutableStateOf<String?>(null) }
    val trailerWanted = hasFocus && resumed && settings.trailerAutoplay
    LaunchedEffect(item?.id, trailerWanted) {
        trailerUrl = null
        if (!trailerWanted || item == null) return@LaunchedEffect
        if ((item.data.localTrailerCount ?: 0) <= 0) return@LaunchedEffect
        delay(SETTLE_TRAILER_MS)
        trailerUrl = viewModel.trailerUrl(item)
    }

    Box(modifier = modifier.fillMaxSize().fillFullBleed().background(JellytipPalette.Night)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.66f)) {
            Crossfade(
                targetState = lastUrl,
                animationSpec = tween(700),
                modifier = Modifier.fillMaxSize(),
                label = "jellytip-home-still",
            ) { u -> if (u != null) HomeKenBurns(u) }

            var usedPlayer by remember { mutableStateOf(false) }
            if (trailerUrl != null) usedPlayer = true
            if (usedPlayer) {
                HeroTrailer(
                    url = trailerUrl,
                    sound = settings.trailerSound,
                    httpClient = viewModel.httpClient,
                    onEnded = { trailerUrl = null },
                )
            }
            // Left scrim keeps the nav rail icons and text legible
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to JellytipPalette.Night.copy(alpha = 0.92f),
                            0.30f to JellytipPalette.Night.copy(alpha = 0.60f),
                            0.65f to Color.Transparent,
                        ),
                    ),
            )
            // Bottom fade into the rows
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to JellytipPalette.Night.copy(alpha = 0.45f),
                            0.25f to Color.Transparent,
                            0.55f to JellytipPalette.Night.copy(alpha = 0.55f),
                            0.9f to JellytipPalette.Night,
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun HomeKenBurns(url: String) {
    val progress = remember(url) { Animatable(0f) }
    LaunchedEffect(url) {
        progress.animateTo(1f, tween(KEN_BURNS_MS, easing = LinearEasing))
    }
    val direction = if (abs(url.hashCode()) % 2 == 0) 1f else -1f
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    val scale = 1.04f + 0.05f * p
                    scaleX = scale
                    scaleY = scale
                    translationX = direction * size.width * 0.010f * (p - 0.5f)
                    translationY = -size.height * 0.006f * p
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

/** Title or clearlogo, one metadata line, two-line overview, score chips (debounced, fail-soft). */
@Composable
fun JellytipHomeHeader(
    item: BaseItem?,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
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
    val chips =
        remember(extras, item?.id) {
            val e = extras?.takeIf { it.first == item?.id }?.second
            HeaderChips(
                scores = e?.scores?.mapNotNull { scoreChipText(it) }?.take(4).orEmpty(),
                mine = e?.letterboxd?.let { myChipText(it) },
            )
        }

    Box(
        modifier = modifier.fillMaxWidth().height((screenHeight * 0.5f).dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 8.dp, bottom = 16.dp).width(520.dp),
        ) {
            TitleOrLogo(
                title = title,
                logoImageUrl = rememberLogoUrl(item),
                showLogo = showLogo,
                modifier = Modifier.fillMaxWidth(),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = JellytipPalette.Vanilla,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QuickDetails(item?.ui?.quickDetails, item?.timeRemainingOrRuntime, endsAt = item?.data?.endDate)
            val overview = item?.data?.overview
            if (!overview.isNullOrBlank()) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chips.scores.isNotEmpty() || chips.mine != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.scores.forEach { JellytipChip(it, Color.White.copy(alpha = 0.14f), Color.White) }
                    chips.mine?.let { JellytipChip(it, WARM.copy(alpha = 0.22f), WARM) }
                }
            }
        }
    }
}
