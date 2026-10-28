package com.github.damontecres.wholphin.jellytip.cache

import kotlinx.coroutines.CompletableDeferred

/**
 * Coil's singleton loader is only configured once the first composition has the user's preferences
 * ([com.github.damontecres.wholphin.ui.CoilConfig]). Touching it earlier would install a default loader
 * and break that configuration, so prefetching waits here until CoilConfig has registered its factory.
 */
object JellytipImageGate {
    private val ready = CompletableDeferred<Unit>()

    fun open() {
        ready.complete(Unit)
    }

    suspend fun await() = ready.await()
}
