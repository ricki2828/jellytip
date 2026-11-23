package com.github.damontecres.wholphin.jellytip.search

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * OK press / hold on a focused control ([SearchPressHold]): [onShort] on a quick press (key-up), [onHold] once while OK is held.
 * Every OK event of a press that started here is consumed, so the control's own onClick never also fires from the remote (touch
 * and mouse clicks still go to onClick). Focus leaving mid-press forgets the press.
 */
fun Modifier.jellytipPressHold(
    onShort: () -> Unit,
    onHold: () -> Unit,
): Modifier =
    composed {
        val ph = remember { SearchPressHold() }
        val handler = remember { Handler(Looper.getMainLooper()) }
        // A remote that sends no repeats: the hold fires from a timer while the key is still down (so Chat opens while OK is held)
        val holdTimer = remember { Runnable { if (ph.tick(SystemClock.uptimeMillis()) == SearchPressHold.Press.LONG) onHold() } }
        DisposableEffect(Unit) { onDispose { handler.removeCallbacks(holdTimer) } }
        this
            .onFocusChanged {
                if (!it.isFocused) {
                    handler.removeCallbacks(holdTimer)
                    ph.reset()
                }
            }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.ButtonA
                if (!ok) return@onPreviewKeyEvent false
                val n = e.nativeKeyEvent
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (ph.down(n.eventTime, n.repeatCount) == SearchPressHold.Press.LONG) onHold()
                        if (n.repeatCount == 0) {
                            handler.removeCallbacks(holdTimer)
                            handler.postDelayed(holdTimer, SearchPressHold.HOLD_MS + 20)
                        }
                        ph.inPress
                    }

                    KeyEventType.KeyUp -> {
                        handler.removeCallbacks(holdTimer)
                        val ours = ph.inPress
                        when (ph.up(n.eventTime)) {
                            SearchPressHold.Press.SHORT -> onShort()
                            SearchPressHold.Press.LONG -> onHold()
                            SearchPressHold.Press.NONE -> Unit
                        }
                        ours
                    }

                    else -> false
                }
            }
    }
