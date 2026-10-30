package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.snapshots.Snapshot

/**
 * Write app-wide snapshot state (the per-item registries: logo styles, tech badges, textless thumbs, scrims)
 * in the global snapshot, never in a composition's snapshot.
 *
 * 30-Oct P0: rows/specials payloads are parsed both during composition (the disk-cached answer read on the first
 * frame) and on IO threads (the fresh answer). A write from the composition and one from outside to the same map made
 * Compose throw "Unsupported concurrent change during composition" and the app crashed during the intro.
 */
inline fun <T> jellytipGlobalWrite(crossinline block: () -> T): T = Snapshot.global { block() }

/**
 * Per-key snapshot state for the app-wide registries. A `mutableStateMapOf` invalidates every reader on any write, so
 * each companion answer for one item (a tile's /backdrops call writes its tech, logo style, thumb kind) recomposed
 * every tile on screen: N tiles loading = N x N recompositions. Here a reader of one key only hears about that key.
 * Holders are created and written in the global snapshot (see [jellytipGlobalWrite]).
 */
class JellytipKeyedState<K : Any, V : Any> {
    private val holders = java.util.concurrent.ConcurrentHashMap<K, androidx.compose.runtime.MutableState<V?>>()

    private fun holder(key: K): androidx.compose.runtime.MutableState<V?> =
        holders[key] ?: jellytipGlobalWrite { holders.computeIfAbsent(key) { androidx.compose.runtime.mutableStateOf(null) } }

    /** Observed read: recomposes only when this key changes. */
    operator fun get(key: K): V? = holder(key).value

    /** Unobserved check (callbacks, parsers): does [key] hold a value? */
    fun has(key: K): Boolean = holders[key]?.let { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { it.value } } != null

    /** Write only when the value changes (null = forget). */
    operator fun set(
        key: K,
        value: V?,
    ) {
        if (value == null && !holders.containsKey(key)) return
        val h = holder(key)
        val current = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { h.value }
        if (current != value) jellytipGlobalWrite { h.value = value }
    }
}
