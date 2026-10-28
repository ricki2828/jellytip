package com.github.damontecres.wholphin.jellytip

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Stops a long-press OK from leaking into whatever opens as a result of it: the key-up (and the held key's repeats) used
 * to land on the new page's focused button and click it (a film started after "hold OK -> details").
 * Long-press handlers call [afterRelease] so the page/dialog opens only once the OK key is released.
 * MainActivity feeds every key event through [onKey].
 */
object JellytipInputGuard {
    private const val HELD_FRESH_MS = 1_000L // repeats arrive every ~50-100 ms; older than this means we missed the key-up
    private const val FALLBACK_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())
    private var lastCenterEvent = 0L
    private var held = false
    private var pending: (() -> Unit)? = null
    private val fallback = Runnable { runPending() }

    private fun isCenter(code: Int) =
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    /** Observe only; never consumes. Call first thing in dispatchKeyEvent. */
    fun onKey(event: KeyEvent) {
        if (!isCenter(event.keyCode)) return
        lastCenterEvent = SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> held = true
            KeyEvent.ACTION_UP -> {
                held = false
                // Let this key-up finish dispatching to the card first, then open the destination
                if (pending != null) handler.post { runPending() }
            }
        }
    }

    /** Run [action] now, or, if OK is currently held, right after it is released (at most [FALLBACK_MS] later). */
    fun afterRelease(action: () -> Unit) {
        val stillHeld = held && SystemClock.uptimeMillis() - lastCenterEvent < HELD_FRESH_MS
        if (!stillHeld) {
            action()
            return
        }
        pending = action
        handler.removeCallbacks(fallback)
        handler.postDelayed(fallback, FALLBACK_MS)
    }

    private fun runPending() {
        handler.removeCallbacks(fallback)
        val action = pending ?: return
        pending = null
        held = false
        action()
    }
}
