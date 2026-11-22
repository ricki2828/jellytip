package com.github.damontecres.wholphin.jellytip.debug

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.damontecres.wholphin.jellytip.iptv.BarlowCondensed
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/*
 * Blank text on the first Home frames after a screensaver wake (Fable 22-Nov, live t01-t03, not reproduced on cold starts):
 * text that existed at the wake stayed blank (even the clock as it ticked), text drawn later was fine, images and
 * shapes were fine. Until it is caught again, Home leaves evidence in the trace ("[app]" lines):
 *  - lifecycle with the time spent stopped, window focus, hardware acceleration and display state at each start;
 *  - every configuration change (density / font scale / size / ui mode) and memory trim;
 *  - the in-app screensaver and its dim overlay showing / going (no lifecycle event fires for them);
 *  - window draws in the 3 s after each wake (real draws, not vsync: "not redrawn" vs "drawn blank");
 *  - on every Home resume and in-app screensaver wake, sample text in both app fonts measured AND drawn into a software
 *    bitmap (inked pixels): 0 = fonts / metrics broken; normal while the screen shows blank text = the GPU side.
 */
object JellytipTextProbe {
    private var stoppedAt = 0L

    /** Bumped when the in-app screensaver goes: the Home probe runs again (no resume happens then). */
    var wakeTick by mutableIntStateOf(0)
        private set

    private var screensaverShown = false
    private var dimShown = false

    /** The activity, for its window (draw counting after an in-app screensaver wake). */
    private var activityRef: java.lang.ref.WeakReference<Activity>? = null

    fun stopped() {
        stoppedAt = SystemClock.uptimeMillis()
        JellytipTrace.log("app", "stop")
    }

    fun started(activity: Activity) {
        val away = if (stoppedAt == 0L) "-" else "${(SystemClock.uptimeMillis() - stoppedAt) / 1000}s"
        val hw = runCatching { activity.window.decorView.isHardwareAccelerated }.getOrNull()
        val display = runCatching { activity.window.decorView.display?.state }.getOrNull()
        activityRef = java.lang.ref.WeakReference(activity)
        JellytipTrace.log("app", "start (stopped for $away) hw=$hw display=${displayState(display)}")
        countDraws("start")
    }

    fun focus(hasFocus: Boolean) = JellytipTrace.log("app", "window focus $hasFocus")

    fun config(c: Configuration) = JellytipTrace.log("app", "config ${describe(c)}")

    fun trim(level: Int) {
        val name =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                else -> level.toString()
            }
        JellytipTrace.log("app", "trim memory $name")
    }

    /** The in-app screensaver (ScreensaverState.show) and its dim overlay (showDim); either ending counts as a wake. */
    fun screensaver(
        show: Boolean,
        dim: Boolean,
    ) {
        if (show != screensaverShown) {
            screensaverShown = show
            JellytipTrace.log("app", "in-app screensaver ${if (show) "on" else "off"}")
            if (!show) wake("screensaver off")
        }
        if (dim != dimShown) {
            dimShown = dim
            JellytipTrace.log("app", "screensaver dim ${if (dim) "on" else "off"}")
            if (!dim) wake("dim off")
        }
    }

    private fun wake(what: String) {
        wakeTick++
        countDraws(what)
    }

    /** Real window draws in the next 3 s (OnDrawListener; vsync ticks would count with nothing drawn), main thread. */
    private fun countDraws(what: String) {
        val view = activityRef?.get()?.window?.decorView ?: return
        runCatching {
            var draws = 0
            val listener = ViewTreeObserver.OnDrawListener { draws++ }
            view.viewTreeObserver.addOnDrawListener(listener)
            // Removed from a posted runnable: removing inside onDraw is not allowed
            view.postDelayed({
                runCatching { view.viewTreeObserver.removeOnDrawListener(listener) }
                JellytipTrace.log("app", "draws after $what: $draws in 3 s")
            }, 3_000)
        }
    }

    private fun displayState(s: Int?): String =
        when (s) {
            null -> "-"
            android.view.Display.STATE_ON -> "on"
            android.view.Display.STATE_OFF -> "off"
            android.view.Display.STATE_DOZE -> "doze"
            android.view.Display.STATE_DOZE_SUSPEND -> "doze-suspend"
            else -> s.toString()
        }

    fun describe(c: Configuration): String =
        "dpi=${c.densityDpi} font=${c.fontScale} ${c.screenWidthDp}x${c.screenHeightDp}dp uiMode=0x${Integer.toHexString(c.uiMode)}"

    /** Measured size and inked pixels of [text] in [style], drawn into a small software bitmap (no GPU involved). */
    fun sample(
        measurer: TextMeasurer,
        style: TextStyle,
        density: Density,
        text: String = "Jellytip 12",
    ): String =
        runCatching {
            val layout = measurer.measure(text, style.copy(color = Color.White))
            val w = layout.size.width.coerceAtLeast(1)
            val h = layout.size.height.coerceAtLeast(1)
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) { drawText(layout) }
            val pixels = IntArray(w * h)
            bitmap.asAndroidBitmap().getPixels(pixels, 0, w, 0, 0, w, h)
            val inked = pixels.count { (it ushr 24) != 0 }
            "${layout.size.width}x${layout.size.height} ink=$inked"
        }.getOrElse { "error ${it.javaClass.simpleName}" }
}

/** On every resume of the page it sits on, and after an in-app screensaver: sample text measured and drawn in both fonts. */
@Composable
fun JellytipTextProbeOnResume(where: String) {
    // No cache: every run really lays the text out again
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val styles = remember { listOf("sans" to TextStyle(fontFamily = JellytipFontFamily, fontSize = 12.sp), "cond" to TextStyle(fontFamily = BarlowCondensed, fontSize = 12.sp)) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(where) {
        resumes++
        onPauseOrDispose { }
    }
    val wake = JellytipTextProbe.wakeTick
    LaunchedEffect(resumes, wake) {
        if (resumes == 0) return@LaunchedEffect
        val sizes = styles.joinToString(" ") { (name, style) -> "$name=${JellytipTextProbe.sample(measurer, style, density)}" }
        JellytipTrace.log("app", "text probe $where $sizes density=${density.density} fontScale=${density.fontScale} ${JellytipTextProbe.describe(config)}")
    }
}
