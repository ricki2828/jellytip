package com.github.damontecres.wholphin.jellytip.iptv

/*
 * Remote seeking (web b662f0bc, lib/seekRamp.ts): a press adds a step to a pending target, repeated or held presses ramp
 * up (10 -> 30 -> 60 s), and the real seek is committed once presses stop for a moment (never while the arrow is held).
 * Seconds throughout. Pure; tested in SeekRampTest.
 */
object SeekRamp {
    const val SETTLE_MS = 700L
    const val BURST_GAP_MS = 1000L
    const val REPEAT_THROTTLE_MS = 150L
    private val TIERS = listOf(10.0, 30.0, 60.0)

    data class State(
        val target: Double,
        val dir: Int,
        val presses: Int,
        val burstStart: Long,
        val lastAt: Long,
    )

    private fun clamp(
        t: Double,
        duration: Double,
    ) = t.coerceIn(0.0, maxOf(0.0, duration - 1))

    /** Step for the n-th press of a burst (discrete presses), or for a hold of [holdMs]. */
    fun stepFor(
        presses: Int,
        holdMs: Long,
        repeat: Boolean,
    ): Double =
        if (repeat) {
            TIERS[if (holdMs < 500) 0 else if (holdMs < 1500) 1 else 2]
        } else {
            TIERS[if (presses <= 2) 0 else if (presses <= 4) 1 else 2]
        }

    /** Applies a press; the same object when a held-key repeat is throttled. */
    fun advance(
        prev: State?,
        dir: Int,
        now: Long,
        repeat: Boolean,
        base: Double,
        duration: Double,
    ): State {
        val live = prev != null && now - prev.lastAt <= BURST_GAP_MS
        val sameRun = live && prev!!.dir == dir
        if (sameRun && repeat && now - prev!!.lastAt < REPEAT_THROTTLE_MS) return prev
        val presses = if (sameRun) prev!!.presses + 1 else 1
        val burstStart = if (sameRun) prev!!.burstStart else now
        val from = if (live) prev!!.target else base // reversing keeps the pending target, only the ramp restarts
        val step = stepFor(presses, now - burstStart, repeat)
        return State(clamp(from + dir * step, duration), dir, presses, burstStart, now)
    }

    fun settled(
        s: State?,
        now: Long,
    ) = s != null && now - s.lastAt >= SETTLE_MS

    /** A held key repeats at least every [REPEAT_THROTTLE_MS]: this long without a press means the release was missed. */
    const val STALE_HOLD_MS = 1500L

    /** Never while the arrow is held, else once settled; a "hold" that stopped repeating counts as released. */
    fun commitDue(
        s: State?,
        now: Long,
        held: Boolean,
    ) = settled(s, now) && (!held || now - s!!.lastAt >= STALE_HOLD_MS)
}
