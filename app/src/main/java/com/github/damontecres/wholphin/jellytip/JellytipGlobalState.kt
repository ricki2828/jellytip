package com.github.damontecres.wholphin.jellytip

import androidx.compose.runtime.snapshots.Snapshot

/**
 * Write app-wide snapshot state (the per-item registries: logo styles, tech badges, textless thumbs, scrims, kids
 * ratings, reactions) in the global snapshot, never in a composition's snapshot.
 *
 * 30-Oct P0: rows/specials payloads are parsed both during composition (the disk-cached answer read on the first
 * frame) and on IO threads (the fresh answer). A write from the composition and one from outside to the same map made
 * Compose throw "Unsupported concurrent change during composition" and the app crashed during the intro.
 */
inline fun <T> jellytipGlobalWrite(crossinline block: () -> T): T = Snapshot.global { block() }
