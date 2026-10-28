package com.github.damontecres.wholphin.jellytip

import androidx.compose.ui.BiasAlignment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
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
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val KEN_BURNS_MS = 20_000

/**
 * Full-bleed backdrop for movie & series details: slow Ken Burns stills which cross-fade,
 * replaced by a muted trailer when the button row has been focused for a few seconds.
 *
 * Draws nothing if there are no images at all, so the page looks like stock Wholphin.
 *
 * @param buttonRowFocused true while focus is somewhere in the play/buttons row
 */
/** Extends this node over the nav-drawer inset (64dp + 8dp) on the left and the 16dp end padding. */
internal fun Modifier.fillFullBleed(): Modifier =
    layout { measurable, constraints ->
        val start = 72.dp.roundToPx()
        val end = 16.dp.roundToPx()
        val w = constraints.maxWidth + start + end
        val p = measurable.measure(Constraints.fixed(w, constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) { p.place(-start, 0) }
    }

@Composable
fun JellytipDetailsBackground(
    item: BaseItem,
    trailers: List<Trailer>,
    buttonRowFocused: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    viewModel: JellytipDetailsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState(JellytipSettings())
    val extras by produceState<JellytipExtras?>(null, item.id) {
        value = viewModel.getExtras(item)
    }
    val allStills = remember(item.id, extras) { viewModel.stillUrls(item, extras) }
    val choice by viewModel.stillChoice(item.id).collectAsState(0)
    // 0 = automatic slideshow, k = pinned to the k-th still ("Change still" cycles through these)
    val pinned = if (allStills.isEmpty()) 0 else choice % (allStills.size + 1)
    val stills = if (pinned > 0) listOf(allStills[pinned - 1]) else listOfNotNull(detailsImageFor(allStills))
    val trailer = remember(trailers) { trailers.filterIsInstance<LocalTrailer>().firstOrNull() }
    val trailerUrl = remember(trailer) { trailer?.let { viewModel.trailerUrl(it) } }

    var resumed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    var trailerPlaying by remember { mutableStateOf(false) }
    var trailerDone by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(buttonRowFocused) {
        if (!buttonRowFocused) trailerDone = false
    }
    LaunchedEffect(buttonRowFocused, trailerUrl, settings.trailerAutoplay, trailerDone, resumed) {
        if (buttonRowFocused && resumed && trailerUrl != null && settings.trailerAutoplay && !trailerDone) {
            delay(JellytipHomeTokens.TRAILER_DELAY_MS)
            trailerPlaying = true
        } else {
            trailerPlaying = false
        }
    }

    if (stills.isEmpty() && trailerUrl == null) return

    // The page content is inset by the collapsed nav drawer (+ padding); bleed back out so the still covers
    // the whole screen behind the drawer, whose icons sit on top of the left scrim.
    Box(modifier = modifier.fillFullBleed().background(Color.Black)) {
        stills.firstOrNull()?.let { url -> KenBurnsImage(url) }
        AnimatedVisibility(
            visible = trailerPlaying && trailerUrl != null,
            enter = fadeIn(tween(800)),
            exit = fadeOut(tween(800)),
        ) {
            if (trailerUrl != null) {
                TrailerLayer(
                    url = trailerUrl,
                    sound = settings.trailerSound,
                    httpClient = viewModel.httpClient,
                    onEnded = {
                        trailerDone = true
                        trailerPlaying = false
                    },
                )
            }
        }
        // Soft vignette from the bottom-left corner only, behind the text block
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val rx = size.width * 1.2f
                    val ry = size.height * 0.95f
                    val k = ry / rx
                    val brush =
                        Brush.radialGradient(
                            0f to NIGHT.copy(alpha = 0.88f),
                            0.32f to NIGHT.copy(alpha = 0.55f),
                            0.62f to Color.Transparent,
                            center = Offset(0f, size.height),
                            radius = rx,
                        )
                    onDrawBehind {
                        withTransform({ scale(1f, k, pivot = Offset(0f, size.height)) }) {
                            drawRect(
                                brush,
                                topLeft = Offset(0f, size.height - size.height / k),
                                size = Size(size.width, size.height / k),
                            )
                        }
                    }
                },
        )
        // Focus is down in the rows below the header: calm the picture right down
        val dim by animateFloatAsState(if (dimmed) 0.75f else 0f, tween(350), label = "jellytip-dim")
        if (dim > 0f) Box(Modifier.fillMaxSize().background(NIGHT.copy(alpha = dim)))
    }
}

private val NIGHT = Color(0xFF08070C)

/**
 * The single details-page image. [ranked] is best-first (companion stills, then Jellyfin backdrops; for episodes
 * just the episode still). The best one is reserved for the home hero, so details uses the second-best when
 * there is one. Single swap point for the hero agent's `JellytipImagePick.forDetails`.
 */
internal fun detailsImageFor(ranked: List<String>): String? = ranked.getOrNull(1) ?: ranked.firstOrNull()

@Composable
private fun KenBurnsImage(url: String) {
    val progress = remember(url) { Animatable(0f) }
    LaunchedEffect(url) {
        progress.animateTo(1f, tween(KEN_BURNS_MS + 4_000, easing = LinearEasing))
    }
    // Alternate pan direction per image, deterministically
    val direction = if (abs(url.hashCode()) % 2 == 0) 1f else -1f
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(0f, -0.4f),
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    val scale = 1.04f + 0.035f * p
                    scaleX = scale
                    scaleY = scale
                    translationX = direction * size.width * 0.008f * (p - 0.5f)
                    translationY = -size.height * 0.005f * p
                },
    )
}

@Composable
private fun TrailerLayer(
    url: String,
    sound: Boolean,
    httpClient: okhttp3.OkHttpClient,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    var firstFrame by remember(url) { mutableStateOf(false) }
    val player =
        remember(url) {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(OkHttpDataSource.Factory(httpClient)),
                ).build()
                .apply {
                    volume = if (sound) 1f else 0f
                    repeatMode = Player.REPEAT_MODE_OFF
                    setMediaItem(MediaItem.fromUri(url))
                    playWhenReady = true
                    prepare()
                }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }

                override fun onRenderedFirstFrame() {
                    firstFrame = true
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    onEnded()
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Texture view so that it layers correctly under the scrim and can fade
    PlayerSurface(
        player = player,
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        modifier =
            Modifier
                .fillMaxSize()
                .alpha(if (firstFrame) 1f else 0f),
    )
}

/**
 * Compact chips for each score source plus a warm chip for Ricki's own Letterboxd activity.
 * Renders nothing when there is no data.
 */
@Composable
fun JellytipScoreRow(
    item: BaseItem,
    modifier: Modifier = Modifier,
    viewModel: JellytipDetailsViewModel = hiltViewModel(),
) {
    val extras by produceState<JellytipExtras?>(null, item.id) {
        value = viewModel.getExtras(item)
    }
    val e = extras ?: return
    val chips = e.scores.mapNotNull { scoreChipText(it) }
    val mine = e.letterboxd?.let { myChipText(it) }
    if (chips.isEmpty() && mine == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(start = HeaderUtils.startPadding, top = 6.dp, bottom = 6.dp),
    ) {
        chips.forEach { JellytipChip(it, Color.White.copy(alpha = 0.14f), Color.White) }
        mine?.let { JellytipChip(it, WARM.copy(alpha = 0.22f), WARM) }
    }
}

internal val WARM = Color(0xFFFFB74D)

@Composable
internal fun JellytipChip(
    text: String,
    background: Color,
    content: Color,
) {
    Box(
        Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

internal fun scoreChipText(score: JellytipScore): String? {
    val v = score.value
    val whole = v.toLong().toDouble() == v
    val num = if (whole) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)
    return when (score.source) {
        "imdb" -> "IMDb $num"
        "rt_critic" -> "RT $num%"
        "rt_audience" -> "Audience $num%"
        "metacritic" -> "MC $num"
        "letterboxd" -> "LB ★$num"
        "trakt" -> "Trakt $num%"
        else -> "${score.label} $num"
    }
}

internal fun myChipText(lb: JellytipLetterboxd): String? {
    val parts = mutableListOf<String>()
    lb.myRating?.let { parts += "You ★${starText(it)}" }
    if (lb.liked) parts += "♥"
    lb.watchedDates.lastOrNull()?.let { raw ->
        val date =
            try {
                LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
            } catch (e: Exception) {
                null
            }
        if (date != null) parts += "watched $date"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
}

private fun starText(rating: Double): String {
    val whole = rating.toInt()
    val half = rating - whole >= 0.25
    return when {
        whole == 0 && half -> "½"
        half -> "$whole½"
        else -> "$whole"
    }
}

/** Quiet one-line scores, "IMDb 8.1 · RT 95% · You ★4½", with Ricki's own rating in the warm accent. */
internal fun scoreLine(extras: JellytipExtras?): AnnotatedString? {
    val scores = extras?.scores.orEmpty().mapNotNull { scoreChipText(it) }.take(4)
    val mine = extras?.letterboxd?.let { myLineText(it) }
    if (scores.isEmpty() && mine == null) return null
    return buildAnnotatedString {
        append(scores.joinToString("  \u00B7  "))
        if (mine != null) {
            if (scores.isNotEmpty()) append("  \u00B7  ")
            pushStyle(SpanStyle(color = WARM, fontWeight = FontWeight.SemiBold))
            append(mine)
            pop()
        }
    }
}

internal fun myLineText(lb: JellytipLetterboxd): String? {
    val parts = mutableListOf<String>()
    lb.myRating?.let { parts += "You \u2605${starText(it)}" }
    if (lb.liked) parts += "\u2665"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}
