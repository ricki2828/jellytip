package com.github.damontecres.wholphin.jellytip.debug

import android.app.Activity
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.launch

/**
 * Frame stats for the TV (release and debug builds): every frame's total duration from FrameMetrics, summarised into
 * a `[perf]` trace line every 10 s while the app is in the foreground (janky %, p90/p99 frame ms, the current
 * destination, GC count). The last 5 minutes of those lines also go into the debug report. Cold-start marks
 * (process start → intro → first Home frame → rows shown) are traced once each as `[perf] startup <mark> +<ms>`.
 */
object JellytipPerf {
    private const val TICK_MS = 10_000L
    private const val KEEP_LINES = 30 // 5 min of 10 s lines

    private val frameMs = ArrayList<Float>(1024)

    /** Per screen since the process started: every window's frames attributed to the screen at its end. */
    private class DestStats {
        var windows = 0
        var frames = 0
        var janky = 0
        var gc = 0L
        var max = 0f

        /** p90 of each 10 s window (the screen's p90 is reported as their median, its worst as the max). */
        val p90s = ArrayList<Float>()
    }

    private val byDest = LinkedHashMap<String, DestStats>()

    @Volatile
    private var lastUpload = 0L
    private val lines = ArrayDeque<String>(KEEP_LINES)
    private val main = Handler(Looper.getMainLooper())
    private val marks = HashSet<String>()

    @Volatile
    private var refreshHz = 60f
    private var lastGc = gcCount()
    private var ticking = false
    private var destination: () -> String = { "?" }
    private var metricsThread: HandlerThread? = null

    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000f
            synchronized(frameMs) { frameMs += ms }
        }

    private val tick =
        object : Runnable {
            override fun run() {
                summarise()
                if (ticking) main.postDelayed(this, TICK_MS)
            }
        }

    /** From MainActivity.onCreate: frame metrics for its window; [currentDestination] names the page on screen. */
    fun attach(
        activity: Activity,
        currentDestination: () -> String,
    ) {
        destination = currentDestination
        refreshHz =
            runCatching {
                @Suppress("DEPRECATION")
                (if (Build.VERSION.SDK_INT >= 30) activity.display?.refreshRate else activity.windowManager.defaultDisplay.refreshRate)
            }.getOrNull()?.takeIf { it > 1f } ?: 60f
        val thread = metricsThread ?: HandlerThread("jellytip-perf").also {
            it.start()
            metricsThread = it
        }
        val ok = runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        // One line per activity start, so a trace without [perf] lines is "idle", never "not installed"
        JellytipTrace.log("perf", "frame stats ${if (ok.isSuccess) "on" else "unavailable: ${ok.exceptionOrNull()?.javaClass?.simpleName}"} (${refreshHz.toInt()} Hz)")
    }

    fun detach(activity: Activity) {
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
    }

    /** Foreground only (onStart / onStop). */
    fun setForeground(on: Boolean) {
        if (on == ticking) return
        ticking = on
        main.removeCallbacks(tick)
        if (on) {
            synchronized(frameMs) { frameMs.clear() }
            lastGc = gcCount()
            main.postDelayed(tick, TICK_MS)
        }
    }

    private fun summarise() {
        val sorted = synchronized(frameMs) { frameMs.sorted().also { frameMs.clear() } }
        val gc = gcCount()
        val gcDelta = gc - lastGc
        lastGc = gc
        if (sorted.isEmpty()) return // idle screen: nothing drawn, nothing to say
        val jankMs = 1000f / refreshHz * 1.5f
        val janky = sorted.count { it > jankMs } * 100f / sorted.size
        fun pct(p: Float) = sorted[((sorted.size - 1) * p).toInt()]
        val line =
            String.format(
                java.util.Locale.US,
                "frames=%d janky=%.1f%% p50=%.1fms p90=%.1fms p99=%.1fms max=%.0fms gc+%d dest=%s",
                sorted.size,
                janky,
                pct(0.5f),
                pct(0.9f),
                pct(0.99f),
                sorted.last(),
                gcDelta,
                runCatching { destination() }.getOrDefault("?").take(80),
            )
        JellytipTrace.log("perf", line)
        val dest = runCatching { destination() }.getOrDefault("?").take(80)
        synchronized(byDest) {
            val d = byDest.getOrPut(dest) { DestStats() }
            d.windows++
            d.frames += sorted.size
            d.janky += sorted.count { it > jankMs }
            d.gc += gcDelta
            d.max = maxOf(d.max, sorted.last())
            if (d.p90s.size < 500) d.p90s += pct(0.9f)
        }
        synchronized(lines) {
            if (lines.size >= KEEP_LINES) lines.removeFirst()
            lines.addLast("${System.currentTimeMillis()} $line")
        }
    }

    /** Cold start: milliseconds since the process started, traced once per [name]. */
    fun mark(name: String) {
        synchronized(marks) { if (!marks.add(name)) return }
        val since = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        JellytipTrace.log("perf", "startup $name +${since}ms")
        synchronized(lines) { lines.addLast("${System.currentTimeMillis()} startup $name +${since}ms") }
    }

    /**
     * Per-screen totals since the process started, worst janky % first: {dest: {frames, jankyPct, p90Median, max, gc,
     * windows}}. In every debug report, so screens can be ranked (plan "8h Block 5").
     */
    fun byDestination(): org.json.JSONObject {
        val out = org.json.JSONObject()
        synchronized(byDest) {
            byDest.entries
                .filter { it.value.frames > 0 }
                .sortedByDescending { it.value.janky * 100f / it.value.frames }
                .forEach { (dest, d) ->
                    val p90 = d.p90s.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
                    out.put(
                        dest,
                        org.json.JSONObject()
                            .put("frames", d.frames)
                            .put("jankyPct", Math.round(d.janky * 1000f / d.frames) / 10.0)
                            .put("p90Median", Math.round(p90 * 10f) / 10.0)
                            .put("max", Math.round(d.max))
                            .put("gc", d.gc)
                            .put("windows", d.windows),
                    )
                }
        }
        return out
    }

    /**
     * Going to the background: at most every 6 h, and only after real use (2 000+ frames since the last one), send a
     * "perf" debug report so frame stats reach the server without anyone pressing "Send debug report".
     */
    fun maybeUpload(context: android.content.Context) {
        val frames = synchronized(byDest) { byDest.values.sumOf { it.frames } }
        val now = System.currentTimeMillis()
        if (frames < UPLOAD_MIN_FRAMES || now - lastUpload < UPLOAD_EVERY_MS) return
        lastUpload = now
        val app = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { JellytipDebugReport.send(app, reason = "perf") }
        }
    }

    private const val UPLOAD_MIN_FRAMES = 2_000
    private const val UPLOAD_EVERY_MS = 6 * 3_600_000L

    /** The last 5 minutes of [perf] lines (and startup marks), for the debug report. */
    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    private fun gcCount(): Long = runCatching { Debug.getRuntimeStat("art.gc.gc-count").toLong() }.getOrDefault(0L)
}
