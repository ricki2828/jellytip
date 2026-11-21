package com.github.damontecres.wholphin.jellytip.iptv

/**
 * "Hide this channel" from the guide (the TV slice of gap row "IPTV settings", web 77d514b0 channel pruning): owner only,
 * one channel at a time, with an Undo. Server (owner only, 404 otherwise):
 * PUT v1/iptv/providers/{pid}/groups/{catId}/channels {"hide": {sid: true|false}}. It hides the channel in that provider
 * for everyone who uses the provider, like the web's pruning screen.
 */
data class HideTarget(
    val pid: String,
    val cat: String,
    val sid: String,
)

/** A channel just hidden: where it sat in the list, for Undo. */
data class HiddenChannel(
    val ch: IptvChannel,
    val index: Int,
)

object HideChannelLogic {
    const val UNDO_MS = 8_000L

    /** The call's ids for [ch] when the person owns its provider; null = not offered (shared provider, unknown group). */
    fun target(
        ch: IptvChannel,
        owned: Set<String>?,
    ): HideTarget? {
        val pid = ch.providerId.takeIf { it.isNotBlank() } ?: return null
        if (owned == null || pid !in owned) return null
        val cat = ch.group.takeIf { it.startsWith("$pid:") }?.removePrefix("$pid:")?.takeIf { it.isNotBlank() } ?: return null
        val sid = ch.id.takeIf { it.startsWith("$pid:") }?.removePrefix("$pid:")?.takeIf { it.isNotBlank() } ?: return null
        return HideTarget(pid, cat, sid)
    }

    /** Put [h] back where it was (Undo); unchanged when it is already there. */
    fun restore(
        list: List<IptvChannel>,
        h: HiddenChannel,
    ): List<IptvChannel> {
        if (list.any { it.id == h.ch.id }) return list
        val out = list.toMutableList()
        out.add(h.index.coerceIn(0, out.size), h.ch)
        return out
    }

    /** Where focus goes when [id] leaves the list: the next row, else the one above; null = none left. */
    fun neighbour(
        ids: List<String>,
        id: String,
    ): String? {
        val i = ids.indexOf(id)
        if (i < 0) return null
        return ids.getOrNull(i + 1) ?: ids.getOrNull(i - 1)
    }
}
