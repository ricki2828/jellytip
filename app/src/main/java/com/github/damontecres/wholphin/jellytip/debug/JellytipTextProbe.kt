package com.github.damontecres.wholphin.jellytip.debug

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.damontecres.wholphin.jellytip.iptv.BarlowCondensed
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/*
 * Blank text on the first Home frames after a screensaver wake (Fable 22-Nov, live t01-t03, not reproduced on cold starts):
 * text that existed at the wake stayed blank (even the clock as it ticked), text drawn later was fine, images and
 * shapes were fine. Until it is caught again, Home leaves evidence in the trace: lifecycle with the time spent stopped,
 * every configuration change (density / font scale / size / ui mode), memory trims, and on every Home resume a
 * measurement of sample text in both app fonts through Compose's own text path (a 0 width or height = broken text).
 */
object JellytipTextProbe {
    private var stoppedAt = 0L

    fun stopped() {
        stoppedAt = android.os.SystemClock.uptimeMillis()
        JellytipTrace.log("app", "stop")
    }

    fun started() {
        val away = if (stoppedAt == 0L) "-" else "${(android.os.SystemClock.uptimeMillis() - stoppedAt) / 1000}s"
        JellytipTrace.log("app", "start (stopped for $away)")
    }

    fun config(c: Configuration) = JellytipTrace.log("app", "config ${describe(c)}")

    fun trim(level: Int) {
        val name =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                else -> level.toString()
            }
        JellytipTrace.log("app", "trim memory $name")
    }

    fun describe(c: Configuration): String =
        "dpi=${c.densityDpi} font=${c.fontScale} ${c.screenWidthDp}x${c.screenHeightDp}dp uiMode=0x${Integer.toHexString(c.uiMode)}"
}

/** On every resume of the page it sits on: the sample text's measured size in both fonts, with the configuration. */
@Composable
fun JellytipTextProbeOnResume(where: String) {
    // No cache: every resume really lays the text out again
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val styles = remember { listOf("sans" to TextStyle(fontFamily = JellytipFontFamily, fontSize = 12.sp), "cond" to TextStyle(fontFamily = BarlowCondensed, fontSize = 12.sp)) }
    LifecycleResumeEffect(where) {
        val sizes =
            styles.joinToString(" ") { (name, style) ->
                val r = runCatching { measurer.measure("Jellytip 12", style).size }.getOrNull()
                "$name=${r?.let { "${it.width}x${it.height}" } ?: "error"}"
            }
        JellytipTrace.log("app", "text probe $where $sizes density=${density.density} fontScale=${density.fontScale} ${JellytipTextProbe.describe(config)}")
        onPauseOrDispose { }
    }
}
