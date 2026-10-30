package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
 *
 * 30-Oct P0 (cold-start crash in the intro): a holder created in the global snapshot after a composition's snapshot
 * was taken is in the plain map but not in that snapshot, and reading it threw "Reading a state that was created
 * after the snapshot was taken". So [get] never creates holders, every state read is guarded, and a reader that gets
 * null reads [created] so it recomposes once a holder appears and its snapshot has caught up.
 */
class JellytipKeyedState<K : Any, V : Any> {
    private val holders = java.util.concurrent.ConcurrentHashMap<K, MutableState<V?>>()

    /** Bumped (globally) whenever a holder is created. */
    private val created: MutableState<Int> = jellytipGlobalWrite { mutableStateOf(0) }

    private fun holder(key: K): MutableState<V?> =
        holders[key] ?: jellytipGlobalWrite {
            var made = false
            val h =
                holders.computeIfAbsent(key) {
                    made = true
                    mutableStateOf(null)
                }
            if (made) created.value = (readOrNull(created) ?: 0) + 1
            h
        }

    /** Observed read: recomposes only when this key changes (or, while it has no readable holder, when one appears). */
    operator fun get(key: K): V? {
        val h = holders[key]
        if (h != null) {
            try {
                return h.value
            } catch (_: IllegalStateException) {
                // Created after this snapshot: fall through and wait on [created]
            }
        }
        readOrNull(created)
        return null
    }

    /** Unobserved check (callbacks, parsers): does [key] hold a value? */
    fun has(key: K): Boolean = holders[key]?.let { Snapshot.withoutReadObservation { readOrNull(it) } } != null

    /** Write only when the value changes (null = forget). */
    operator fun set(
        key: K,
        value: V?,
    ) {
        if (value == null && !holders.containsKey(key)) return
        val h = holder(key)
        val current = jellytipGlobalWrite { Snapshot.withoutReadObservation { readOrNull(h) } }
        if (current != value) jellytipGlobalWrite { h.value = value }
    }

    private fun <T> readOrNull(s: MutableState<T>): T? =
        try {
            s.value
        } catch (_: IllegalStateException) {
            null
        }
}

/**
 * Create the app-wide registries' snapshot state on the main thread before the first composition (called from
 * Application.onCreate), so no composition can start before their state exists. Belt and braces for the guarded reads
 * in [JellytipKeyedState]; objects with plain `mutableStateOf` fields have no such guard.
 */
fun jellytipInitGlobalState() {
    val touched =
        listOf<Any>(
            JellytipScrim,
            JellytipLogoTint,
            com.github.damontecres.wholphin.jellytip.cards.JellytipThumbTitles,
            com.github.damontecres.wholphin.jellytip.cards.JellytipTech,
            com.github.damontecres.wholphin.jellytip.me.JellytipMe,
            com.github.damontecres.wholphin.jellytip.home.JellytipHeroMix,
            com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl,
            com.github.damontecres.wholphin.jellytip.library.JellytipSizes,
        )
    timber.log.Timber.d("jellytip global state ready (%d registries)", touched.size)
}
