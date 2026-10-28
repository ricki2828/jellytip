package com.github.damontecres.wholphin.jellytip.splash

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.R
import kotlinx.coroutines.delay
import kotlin.math.hypot

val BungeeShade = FontFamily(Font(R.font.bungee_shade_regular))
val Bungee = FontFamily(Font(R.font.bungee_regular))
val Vt323 = FontFamily(Font(R.font.vt323_regular))

object JellytipColors {
    val Night = Color(0xFF0E0B1C)
    val Purple = Color(0xFF2A1A48)
    val Jelly = Color(0xFFE2264D)
    val Choc = Color(0xFF5B3421)
    val Vanilla = Color(0xFFF7E8C4)
    val Neon = Color(0xFF2BE3D2)
    val Sun = Color(0xFFFFB21A)
}

/** Timeline of the cold-start intro, in ms from the moment audio starts. Keep in sync with tools/jellytip-sounds/render.py. */
object JellytipTimings {
    const val BURST_MS = 350L
    const val SCENE_MS = 500L
    const val INTRO_LOGO_MS = 3100L // render.py INTRO_STING_AT
    const val INTRO_BITE1_MS = INTRO_LOGO_MS + 650
    const val INTRO_BITE2_MS = INTRO_LOGO_MS + 1000
    const val INTRO_END_MS = INTRO_LOGO_MS + 1300
    const val IDENT_BITE1_MS = 650L
    const val IDENT_BITE2_MS = 1000L
    const val IDENT_FADE_AT_MS = 2300L
    const val IDENT_FADE_MS = 700L
}

@Composable
private fun StaticNoise(modifier: Modifier = Modifier) {
    val w = 160
    val h = 90
    val bitmap = remember { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    val pixels = remember { IntArray(w * h) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val rnd = java.util.Random()
        while (true) {
            for (i in pixels.indices) {
                val v = rnd.nextInt(256)
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            frame = bitmap.asImageBitmap()
            delay(40)
        }
    }
    Canvas(modifier) {
        frame?.let {
            drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
        }
    }
}

@Composable
private fun ScanlinesAndVignette(
    modifier: Modifier = Modifier,
    scanlines: Boolean = true,
) {
    Canvas(modifier) {
        if (scanlines) {
            var y = 0f
            while (y < size.height) {
                drawRect(Color(0x38000000), Offset(0f, y), Size(size.width, 2f))
                y += 4f
            }
        }
        drawRect(
            Brush.radialGradient(
                0.55f to Color.Transparent,
                1f to Color(0xD9000000),
                center = center,
                radius = hypot(size.width, size.height) / 2f,
            ),
        )
    }
}

@Composable
private fun logoBackground(): Brush =
    Brush.radialGradient(
        0f to JellytipColors.Purple,
        0.6f to JellytipColors.Night,
        1f to JellytipColors.Night,
    )

/**
 * The 4-second cold-start intro: VHS static burst, hard cuts through five scenes, then the logo frame.
 * Calls [onFinished] when the picture is done (audio may keep ringing).
 */
@Composable
fun JellytipIntroTv(
    reduced: Boolean,
    soundEnabled: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scene by remember { mutableIntStateOf(-1) } // 0..4 scenes, 5 logo
    var staticOn by remember { mutableStateOf(false) }
    var bites by remember { mutableIntStateOf(0) }
    var elapsedS by remember { mutableIntStateOf(0) }
    var jitter by remember { mutableStateOf(false) }
    val fade = remember { Animatable(0f) }
    val loadBar = remember { Animatable(if (reduced) 1f else 0f) }

    LaunchedEffect(Unit) {
        val player = if (soundEnabled) JellytipSound.prepare(context, R.raw.jellytip_intro) else null
        // Let the first frames (class verification of the noise/scene drawing is slow on 32-bit devices)
        // render before the clock starts, so the timeline isn't spent while the main thread is stalled.
        scene = 0
        staticOn = true
        withFrameNanos { }
        withFrameNanos { }
        staticOn = false
        scene = -1
        val start = android.os.SystemClock.uptimeMillis()
        android.util.Log.i("Jellytip", "intro start")

        suspend fun at(ms: Long) {
            val d = start + ms - android.os.SystemClock.uptimeMillis()
            if (d > 0) delay(d)
        }
        JellytipSound.start(player)
        if (reduced) {
            scene = 5
            bites = 2
            delay(1500)
        } else {
            staticOn = true
            at(JellytipTimings.BURST_MS)
            staticOn = false
            for (i in 0..4) {
                scene = i
                jitter = true
                delay(90)
                jitter = false
                at(JellytipTimings.BURST_MS + JellytipTimings.SCENE_MS * (i + 1))
            }
            staticOn = true
            at(JellytipTimings.INTRO_LOGO_MS)
            staticOn = false
            scene = 5
            at(JellytipTimings.INTRO_BITE1_MS)
            bites = 1
            at(JellytipTimings.INTRO_BITE2_MS)
            bites = 2
            at(JellytipTimings.INTRO_END_MS)
        }
        fade.animateTo(1f, tween(250))
        android.util.Log.i("Jellytip", "intro finished")
        onFinished()
    }
    LaunchedEffect(scene == 5) {
        if (scene == 5 && !reduced) {
            loadBar.animateTo(1f, tween(JellytipTimings.INTRO_END_MS.toInt() - JellytipTimings.INTRO_LOGO_MS.toInt(), easing = LinearOutSlowInEasing))
        }
    }
    LaunchedEffect(Unit) {
        val t0 = android.os.SystemClock.uptimeMillis()
        while (true) {
            elapsedS = ((android.os.SystemClock.uptimeMillis() - t0) / 1000).toInt().coerceAtMost(59)
            delay(200)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val wPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        fun cqw(v: Float) = with(density) { (wPx * v / 100f).toSp() }

        if (scene in 0..4) {
            val sc = IntroScenes[scene]
            val measurer = rememberTextMeasurer()
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = if (jitter) -0.014f * size.width else 0f; translationY = if (jitter) 0.006f * size.height else 0f }
                    .background(sc.background),
            ) {
                Canvas(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.46f)
                        .aspectRatio(200f / 130f),
                ) {
                    val s = size.width / 200f
                    withTransform({ scale(s, s, Offset.Zero) }) { drawIntroScene(scene, measurer, Vt323) }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = with(density) { (wPx * 0.06f).toDp() }, bottom = with(density) { (wPx * 0.04f).toDp() }),
                ) {
                    sc.captions.forEach { line ->
                        BasicText(
                            text = line.text,
                            style =
                                TextStyle(
                                    color = if (line.key) JellytipColors.Sun else Color.White,
                                    fontFamily = Vt323,
                                    fontSize = cqw(3.2f),
                                ),
                            modifier = Modifier.background(Color.Black).padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        } else if (scene == 5) {
            val slam = remember { Animatable(if (reduced) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (!reduced) {
                    delay(150)
                    slam.animateTo(1f, tween(500, easing = CubicBezierEasing(0.2f, 1.4f, 0.4f, 1f)))
                }
            }
            Box(Modifier.fillMaxSize().background(logoBackground()), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(with(density) { (wPx * 0.012f).toDp() })) {
                    JellyTipLogo(
                        bites = bites,
                        animate = !reduced,
                        modifier = Modifier.height(with(density) { (wPx * 0.26f).toDp() }),
                    )
                    val fontPx = wPx * 0.09f
                    Box(
                        Modifier.graphicsLayer {
                            scaleX = 1f + 1.4f * (1f - slam.value)
                            scaleY = 1f + 1.4f * (1f - slam.value)
                            alpha = slam.value.coerceIn(0f, 1f)
                        },
                    ) {
                        val style = TextStyle(fontFamily = BungeeShade, fontSize = cqw(9f))
                        BasicText("jellytip", style = style.copy(color = JellytipColors.Choc), modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((fontPx * 0.12f).toInt(), (fontPx * 0.12f).toInt()) })
                        BasicText("jellytip", style = style.copy(color = JellytipColors.Jelly), modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((fontPx * 0.06f).toInt(), (fontPx * 0.06f).toInt()) })
                        BasicText("jellytip", style = style.copy(color = JellytipColors.Vanilla))
                    }
                    BasicText(
                        "WORLD FAMOUS IN YOUR LOUNGE",
                        style = TextStyle(color = JellytipColors.Neon, fontFamily = Vt323, fontSize = cqw(2.6f), letterSpacing = 1.sp),
                    )
                    Box(
                        Modifier
                            .width(with(density) { (wPx * 0.34f).toDp() })
                            .height(8.dp)
                            .background(Color(0xFF2E2650), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(loadBar.value)
                                .background(Brush.horizontalGradient(listOf(JellytipColors.Jelly, JellytipColors.Sun)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }

        if (staticOn) StaticNoise(Modifier.fillMaxSize())

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = with(density) { (wPx * 0.05f).toDp() }, vertical = with(density) { (wPx * 0.03f).toDp() }),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val osd = TextStyle(color = Color(0xFF7DFF8A), fontFamily = Vt323, fontSize = cqw(2.6f), shadow = Shadow(Color(0xFF2BFF5A), blurRadius = 6f))
            BasicText(if (scene == 5) "STOP ■" else "PLAY ▶", style = osd)
            BasicText("SP 0:00:" + elapsedS.toString().padStart(2, '0'), style = osd)
        }
        ScanlinesAndVignette(Modifier.fillMaxSize())
        if (fade.value > 0f) Box(Modifier.fillMaxSize().background(JellytipColors.Night.copy(alpha = fade.value)))
    }
}

/**
 * The short "before content" ident: only the Jelly Tip picture, no wordmark, slogan or bar. Fades to
 * black at the end; [onFinished] is called when the black is complete.
 */
@Composable
fun JellytipIdentTv(
    reduced: Boolean,
    soundEnabled: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bites by remember { mutableIntStateOf(if (reduced) 2 else 0) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val player = if (soundEnabled) JellytipSound.prepare(context, R.raw.jellytip_ident) else null
        val start = android.os.SystemClock.uptimeMillis()

        suspend fun at(ms: Long) {
            val d = start + ms - android.os.SystemClock.uptimeMillis()
            if (d > 0) delay(d)
        }
        JellytipSound.start(player)
        if (reduced) {
            delay(2000)
        } else {
            at(JellytipTimings.IDENT_BITE1_MS)
            bites = 1
            at(JellytipTimings.IDENT_BITE2_MS)
            bites = 2
            at(JellytipTimings.IDENT_FADE_AT_MS)
        }
        fade.animateTo(1f, tween(JellytipTimings.IDENT_FADE_MS.toInt()))
        onFinished()
    }
    BoxWithConstraints(modifier.fillMaxSize().background(logoBackground()), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        JellyTipLogo(
            bites = bites,
            animate = !reduced,
            modifier = Modifier.height(with(density) { (constraints.maxWidth * 0.30f).toDp() }),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade.value)))
    }
}
