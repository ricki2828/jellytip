package com.github.damontecres.wholphin.jellytip

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Process-wide state for the splash/ident features.
 */
object JellytipSession {
    /** Set once the cold-start intro has been shown (or skipped) in this process. */
    @Volatile
    var introHandled = false

    /** Elapsed-realtime of the last time a playback page was left; 0 if none this process. */
    @Volatile
    var lastPlaybackExitMs = 0L

    /** Re-entering playback within this window of leaving it counts as the same viewing session. */
    private const val SESSION_WINDOW_MS = 5 * 60 * 1000L

    fun inViewingSession(): Boolean =
        lastPlaybackExitMs != 0L && SystemClock.elapsedRealtime() - lastPlaybackExitMs < SESSION_WINDOW_MS

    fun markPlaybackExit() {
        lastPlaybackExitMs = SystemClock.elapsedRealtime()
    }

    /** True when the user has turned system animations off (animator duration scale 0). */
    fun reducedMotion(context: Context): Boolean =
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
}

/**
 * Lets a full-screen overlay (intro, ident) swallow "any key skips" at the activity level, so it
 * works no matter where Compose focus currently is.
 */
object JellytipOverlayKeys {
    @Volatile
    var onKey: (() -> Unit)? = null

    /** When set, Back during the overlay calls this (cancel) instead of [onKey] (skip). */
    @Volatile
    var onBack: (() -> Unit)? = null

    /** @return true if the event was consumed by an active overlay */
    fun intercept(event: KeyEvent): Boolean {
        val handler = onKey ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val back = onBack
            if (event.keyCode == KeyEvent.KEYCODE_BACK && back != null) back.invoke() else handler.invoke()
        }
        return true
    }
}
