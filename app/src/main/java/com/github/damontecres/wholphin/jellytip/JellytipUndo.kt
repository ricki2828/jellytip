package com.github.damontecres.wholphin.jellytip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A short "done — undo" notice at the bottom of the screen (e.g. "Not interested in NBL"): shows for a few seconds
 * without taking focus; while it shows, OK = Undo ([onKey], wired at the app root). A plain StateFlow, collected in
 * composition (snapshot rule).
 */
object JellytipUndo {
    data class Notice(
        val text: String,
        val undo: () -> Unit,
        val at: Long = System.nanoTime(),
    )

    private val _notice = MutableStateFlow<Notice?>(null)

    /** The OK that chose the action may still be held (its key-up lands here): only a fresh press counts. */
    @Volatile
    private var downSeen = false
    val notice: StateFlow<Notice?> = _notice

    fun show(
        text: String,
        undo: () -> Unit,
    ) {
        downSeen = false
        _notice.value = Notice(text, undo)
    }

    fun dismiss(n: Notice) {
        if (_notice.value == n) _notice.value = null
    }

    /** OK runs the undo (a full press while the notice shows; down and up are swallowed); other keys pass. */
    fun onKey(e: android.view.KeyEvent): Boolean {
        val n = _notice.value ?: return false
        return when (e.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                    downSeen = true
                } else if (e.action == android.view.KeyEvent.ACTION_UP && downSeen) {
                    downSeen = false
                    _notice.value = null
                    n.undo()
                }
                true
            }

            else -> false
        }
    }
}

@Composable
fun JellytipUndoBar(modifier: Modifier = Modifier) {
    val current by JellytipUndo.notice.collectAsState()
    val n = current ?: return
    LaunchedEffect(n) {
        delay(UNDO_MS)
        JellytipUndo.dismiss(n)
    }
    Box(modifier.fillMaxWidth().padding(bottom = 32.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(n.text, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "OK  Undo",
                color = Color.Black,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.background(Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private const val UNDO_MS = 6_000L
