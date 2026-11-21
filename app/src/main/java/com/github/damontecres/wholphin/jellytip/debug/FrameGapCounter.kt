package com.github.damontecres.wholphin.jellytip.debug

import kotlin.math.roundToLong

/**
 * Counts missed display refreshes from a stream of frame-callback times (Choreographer / withFrameNanos).
 * With a TextureView the video is composited by the app's own frames, so a missed UI frame is a dropped video
 * frame that ExoPlayer's counters never see. Used by the cold-start intro trace.
 */
class FrameGapCounter(
    private val periodNs: Long,
) {
    var frames = 0
        private set
    var missed = 0L
        private set
    var longestGapNs = 0L
        private set
    private var last = -1L

    fun onFrame(timeNs: Long) {
        if (last >= 0 && timeNs > last) {
            val gap = timeNs - last
            if (gap > longestGapNs) longestGapNs = gap
            // A gap of ~2 periods = 1 missed refresh; rounding absorbs vsync jitter
            missed += ((gap.toDouble() / periodNs).roundToLong() - 1).coerceAtLeast(0)
        }
        if (timeNs > last) last = timeNs
        frames++
    }

    fun summary(): String = "uiFrames=$frames missedVsyncs=$missed longestGap=${longestGapNs / 1_000_000}ms"

    companion object {
        /** Refresh period from the display rate; 60 Hz when unknown. */
        fun periodNs(refreshHz: Float): Long = (1_000_000_000.0 / (if (refreshHz in 20f..240f) refreshHz else 60f)).roundToLong()
    }
}
