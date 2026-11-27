package com.github.damontecres.wholphin.jellytip.home

/**
 * Process-wide "something Home shows may have changed" counter. Bumped when playback stops (any player) and when a
 * watched / favourite / Continue Watching change is made anywhere; Home compares it with the value it saw when its
 * last load started (see [HomeRefreshPolicy]).
 */
object HomeStaleSignal {
    @Volatile
    var version: Long = 0L
        private set

    @Synchronized
    fun bump() {
        version++
    }
}
