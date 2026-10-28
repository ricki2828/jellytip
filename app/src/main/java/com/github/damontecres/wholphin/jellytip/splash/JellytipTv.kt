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

/** Timeline of the before-content ident, in ms from the moment audio starts. */
object JellytipTimings {
    const val IDENT_BITE1_MS = 650L
    const val IDENT_BITE2_MS = 1000L
    const val IDENT_FADE_AT_MS = 2300L
    const val IDENT_FADE_MS = 700L
}

@Composable
private fun logoBackground(): Brush =
    Brush.radialGradient(
        0f to JellytipColors.Purple,
        0.6f to JellytipColors.Night,
        1f to JellytipColors.Night,
    )

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
        // Cancelled before start (Back, skip): free the prepared player; a started one is already tracked/released
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { JellytipSound.discard(player) }
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
