package com.github.damontecres.wholphin.jellytip.iptv

/**
 * Pure owner/volume/stream-generation rules for [JellytipIptvPlayback] (28-Nov "audio keeps going after Back").
 *
 * Volume is derived from who owns the stream, never from the last setVolume call: only the guide owning it = muted
 * preview; any other owner (full screen, film/recording page) = sound. So a full-screen page that goes away while the
 * guide stays composed can't leave 1f behind, and a late guide preview can't mute full screen.
 */
class IptvOwnerPolicy {
    private val owners = linkedSetOf<String>()

    /** Bumped on every stop and every new open: an in-flight open holding an older value must not touch the player. */
    var generation = 0L
        private set

    fun attach(owner: String) {
        owners += owner
    }

    /** True when no owner is left after removing [owner]. */
    fun detach(owner: String): Boolean {
        owners -= owner
        return owners.isEmpty()
    }

    val isEmpty: Boolean get() = owners.isEmpty()

    fun snapshot(): Set<String> = owners.toSet()

    fun volume(): Float = if (owners.isNotEmpty() && owners.all { it == GUIDE }) 0f else 1f

    /** New open or stop: returns the new generation. */
    fun bump(): Long = ++generation

    fun isCurrent(gen: Long): Boolean = gen == generation

    companion object {
        const val GUIDE = "guide"
    }
}
