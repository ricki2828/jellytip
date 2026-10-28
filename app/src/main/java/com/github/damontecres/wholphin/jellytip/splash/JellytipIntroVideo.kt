package com.github.damontecres.wholphin.jellytip.splash

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.damontecres.wholphin.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

private val CardBackground = Color(0xFF0A0A0A)
private val InstrumentSans = FontFamily(Font(R.font.instrument_sans))

/** Where [com.github.damontecres.wholphin.jellytip.JellytipApi.refreshIntro] keeps the downloaded intro. */
object JellytipIntroCache {
    fun dir(context: Context): File = File(context.filesDir, "jellytip-intro")

    /** The cached intro video, or null on first run / after a failed download. Do the disk check off the main thread. */
    fun cached(context: Context): File? = File(dir(context), "intro.mp4").takeIf { it.isFile && it.length() > 100_000 }
}

/** The video's own end card starts fading in at about 5.2 s and is fully up by 5.8 s; the chord lands as it settles. */
private const val CARD_SOUND_AT_MS = 5300L
private const val HOLD_AFTER_END_MS = 400L
private const val START_TIMEOUT_MS = 3000L

/**
 * Cold-start intro: the nightly film-snippet montage from the companion (cached by the previous launch), ending on
 * the Jellytip card. Without a cached video (first run, failed download, reduced motion, decoder trouble) the end card
 * alone is shown for about 1.5 s. Video is muted; the soft Rhodes chord is the app's own asset.
 */
@Composable
fun JellytipIntroClassy(
    file: File?,
    soundEnabled: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var useVideo by remember { mutableStateOf(file != null) }
    CardSurface(modifier) {
        if (useVideo && file != null) {
            IntroVideo(file, soundEnabled, onFailed = { useVideo = false }, onFinished = onFinished)
        } else {
            IntroEndCard(soundEnabled, onFinished)
        }
    }
}

@Composable
private fun CardSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) = Box(modifier.fillMaxSize().background(CardBackground)) { content() }

@OptIn(UnstableApi::class)
@Composable
private fun IntroVideo(
    file: File,
    soundEnabled: Boolean,
    onFailed: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed by rememberUpdatedState(onFailed)
    val finished by rememberUpdatedState(onFinished)
    val player =
        remember {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        scope.launch {
                            delay(HOLD_AFTER_END_MS)
                            finished()
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Jellytip: intro video failed, falling back to the end card")
                    failed()
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        val sound = if (soundEnabled) JellytipSound.prepare(context, R.raw.jellytip_intro_card) else null
        var soundStarted = false
        val t0 = SystemClock.uptimeMillis()
        player.playWhenReady = true
        try {
            while (isActive) {
                delay(40)
                if (!soundStarted && player.currentPosition >= CARD_SOUND_AT_MS) {
                    JellytipSound.start(sound)
                    soundStarted = true
                }
                if (player.playbackState != Player.STATE_READY && player.currentPosition == 0L &&
                    SystemClock.uptimeMillis() - t0 > START_TIMEOUT_MS
                ) {
                    failed() // never started: don't hold the user on a black screen
                    break
                }
            }
        } finally {
            if (!soundStarted) JellytipSound.discard(sound)
        }
    }
    val exoPlayer = player
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PlayerView(it).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
                this.player = exoPlayer
            }
        },
    )
}

@Composable
private fun IntroEndCard(
    soundEnabled: Boolean,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val sound = if (soundEnabled) JellytipSound.prepare(context, R.raw.jellytip_intro_card) else null
        withFrameNanos { }
        JellytipSound.start(sound)
        alpha.animateTo(1f, tween(500))
        delay(1000)
        onFinished()
    }
    CardSurface(Modifier.graphicsLayer { this.alpha = alpha.value }) { EndCardContent() }
}

/** Matches assets/endcard.png of the server's intro (icon, wordmark, slogan on #0A0A0A), scaled to the screen height. */
@Composable
private fun EndCardContent() {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val h = maxHeight
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            JellyTipLogo(bites = 2, animate = false, modifier = Modifier.height(h * 0.231f), biteColor = CardBackground)
            Spacer(Modifier.height(h * 0.03f))
            BasicText(
                "jellytip",
                style =
                    TextStyle(
                        color = Color(0xFFF0ECE4),
                        fontFamily = InstrumentSans,
                        fontSize = with(density) { (h * 0.085f).toSp() },
                        letterSpacing = 0.065.em,
                        textAlign = TextAlign.Center,
                    ),
            )
            Spacer(Modifier.height(h * 0.012f))
            BasicText(
                "World famous in your lounge",
                style =
                    TextStyle(
                        color = Color(0xFF8C8882),
                        fontFamily = InstrumentSans,
                        fontSize = with(density) { (h * 0.028f).toSp() },
                        letterSpacing = 0.1.em,
                        textAlign = TextAlign.Center,
                    ),
            )
        }
    }
}
