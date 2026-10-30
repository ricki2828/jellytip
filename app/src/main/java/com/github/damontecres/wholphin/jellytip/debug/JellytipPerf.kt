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
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
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

    /** The last 5 minutes of [perf] lines (and startup marks), for the debug report. */
    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    private fun gcCount(): Long = runCatching { Debug.getRuntimeStat("art.gc.gc-count").toLong() }.getOrDefault(0L)
}
