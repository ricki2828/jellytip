package com.github.damontecres.wholphin.jellytip.iptv

import kotlin.math.max
import kotlin.math.min

/*
 * Recording playback (web lib/recPlay.ts, fb816e09): a recording is a raw ffmpeg copy, so the player often can't read its
 * length (MPEG-TS has no index). The length comes from the recording's own times; one still recording grows with the
 * wall clock. Tested in RecPlayTest.
 */
object RecPlay {
    /** Seconds playable now: a usable player length wins; else from the recording. */
    fun lengthSec(
        m: IptvRecording?,
        nowSec: Long,
        mediaDurSec: Double,
    ): Double {
        if (mediaDurSec.isFinite() && mediaDurSec > 0) return mediaDurSec
        m ?: return 0.0
        if (m.state == "recording") return max(0L, nowSec - (m.began ?: m.start)).toDouble()
        m.durationSecs?.takeIf { it > 0 }?.let { return it }
        val began = m.began
        val ended = m.ended
        if (began != null && ended != null && ended > began) return (ended - began).toDouble()
        return max(0L, (m.stopPadded ?: m.stop) - m.start).toDouble()
    }

    /** Where a seek may land: inside what's there (in progress: a few seconds behind the end), never negative. */
    fun clampSeek(
        to: Double,
        length: Double,
        growing: Boolean,
    ): Double {
        if (!(length > 0)) return max(0.0, to)
        return min(max(0.0, to), max(0.0, length - if (growing) 3 else 1))
    }
}
