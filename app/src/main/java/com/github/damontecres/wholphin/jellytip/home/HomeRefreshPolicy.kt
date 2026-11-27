package com.github.damontecres.wholphin.jellytip.home

/**
 * Whether Home should re-fetch its rows when it comes back to the screen (ON_START). Before 27-Nov every resume
 * re-ran the whole row load (even a one-second permission dialog or screensaver wake). Now a resume re-fetches only when
 * the rows are old, or the user was away long enough for watch progress to have changed (player, details). Explicit
 * changes (mark watched, favourite, remove from Next Up) force a refresh and bypass this.
 */
object HomeRefreshPolicy {
    /** Rows older than this (measured from when their load STARTED) are re-fetched on resume whatever the away time. */
    const val MAX_AGE_MS = 3 * 60_000L

    /** Away at least this long = progress may have moved even without a playback signal: re-fetch. */
    const val MIN_AWAY_MS = 15_000L

    /**
     * @param loadedBefore rows have been shown at least once
     * @param lastLoadStartedAtMs when the last row load STARTED (monotonic clock), 0 = never. Start, not end: a load
     *   that finished while the player ran still holds pre-playback data.
     * @param stoppedAtMs when Home last stopped (same clock), 0 = it has not stopped since
     * @param changeVersion [HomeStaleSignal.version] now; [loadedAtVersion] = its value when the last load started.
     *   A difference (playback ended, watched/favourite changed anywhere) always refreshes, however short.
     */
    fun shouldRefresh(
        loadedBefore: Boolean,
        nowMs: Long,
        lastLoadStartedAtMs: Long,
        stoppedAtMs: Long,
        changeVersion: Long = 0L,
        loadedAtVersion: Long = 0L,
        force: Boolean = false,
    ): Boolean {
        if (force || !loadedBefore || lastLoadStartedAtMs <= 0L) return true
        if (changeVersion != loadedAtVersion) return true
        if (nowMs - lastLoadStartedAtMs >= MAX_AGE_MS) return true
        return stoppedAtMs > 0L && stoppedAtMs >= lastLoadStartedAtMs && nowMs - stoppedAtMs >= MIN_AWAY_MS
    }

    /**
     * After a held refresh lands, the focused item may sit at another column. Returns the column to move the saved
     * position to, or null when it is unchanged or the item is gone (nothing to re-derive).
     */
    fun <K> refocusColumn(
        newKeys: List<K?>,
        focusedKey: K?,
        currentColumn: Int,
        refocus: Boolean = true,
    ): Int? {
        // Only the idle apply may move the saved position; when focus just left the row it already moved on
        if (!refocus || focusedKey == null) return null
        val at = newKeys.indexOf(focusedKey)
        return at.takeIf { it >= 0 && it != currentColumn }
    }

    /**
     * True when swapping in [candidate] entry keys would move the entry the user has focus on (index shift), so the
     * late arrival (companion rows, live sport, ...) must wait instead of stealing focus. A hero/row focus that is not
     * held, or an entry that is not in the current list, never blocks.
     */
    fun <K> focusWouldMove(
        hasFocus: Boolean,
        focusedKey: K?,
        current: List<K>,
        candidate: List<K>,
    ): Boolean = hasFocus && focusedKey != null && candidate.indexOf(focusedKey) != current.indexOf(focusedKey)
}
