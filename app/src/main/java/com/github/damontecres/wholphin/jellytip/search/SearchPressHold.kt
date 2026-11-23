package com.github.damontecres.wholphin.jellytip.search

/**
 * Short press vs hold for the one Search button (23-Nov, Ricki: "push button for search and hold for chat"): press = Search,
 * hold [holdMs] = Chat. Pure (event times in, a verdict out) so it is JVM-tested; the drawer's Search item and the remote's
 * Search key feed it their key events.
 *
 * - The hold fires once, while the key is still down (on a repeat at or past [holdMs]), so Chat opens without waiting for the
 *   release; the key-up after a hold is nothing.
 * - A short press fires on the key-up only, and only if no hold fired, so a hold never also opens Search.
 * - Repeats and key-ups that belong to no press we saw (the press began on another control, or before the screen opened) are
 *   nothing, and [inPress] stays false so the caller lets them through.
 * - A fresh down (repeat 0) while a press is open means the old key-up was missed: the new press starts over.
 */
class SearchPressHold(
    private val holdMs: Long = HOLD_MS,
) {
    enum class Press { NONE, SHORT, LONG }

    private var downAt = -1L
    private var longFired = false

    /** A press we saw the key-down of is in progress: the caller consumes its repeats and key-up. */
    val inPress: Boolean get() = downAt >= 0

    /** Key down at [now] (ms, any monotonic clock); [repeatCount] 0 = the first down of a press. */
    fun down(
        now: Long,
        repeatCount: Int,
    ): Press {
        if (repeatCount == 0) {
            downAt = now
            longFired = false
            return Press.NONE
        }
        if (downAt < 0 || longFired) return Press.NONE
        if (now - downAt >= holdMs) {
            longFired = true
            return Press.LONG
        }
        return Press.NONE
    }

    /** Key up at [now]. SHORT for a quick press, LONG for a hold whose repeats never arrived, NONE after a hold already fired. */
    fun up(now: Long): Press {
        if (downAt < 0) return Press.NONE
        val r =
            when {
                longFired -> Press.NONE
                now - downAt >= holdMs -> Press.LONG
                else -> Press.SHORT
            }
        reset()
        return r
    }

    /** Forget the press in progress (focus left the control, the screen went away). */
    fun reset() {
        downAt = -1L
        longFired = false
    }

    companion object {
        const val HOLD_MS = 500L
    }
}
