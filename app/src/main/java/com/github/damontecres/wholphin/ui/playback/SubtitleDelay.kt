package com.github.damontecres.wholphin.ui.playback

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val maxDelay = 60.seconds

/** Step size for a held Left/Right key: 100 ms, then 500 ms after 1 s, then 1 s after 3 s. */
private fun stepForHold(heldMs: Long): Duration =
    when {
        heldMs < 1_000 -> 100.milliseconds
        heldMs < 3_000 -> 500.milliseconds
        else -> 1.seconds
    }

/** "+0.3 s" / "-0.5 s" */
fun formatSubtitleDelay(delay: Duration): String {
    val seconds = delay.inWholeMilliseconds / 1000.0
    val sign = if (seconds > 0) "+" else if (seconds < 0) "−" else ""
    return sign + String.format("%.1f", abs(seconds)) + " s"
}

/**
 * Subtitle timing control: Left/Right step (hold to accelerate), Up/Down jump 5 s, OK resets.
 */
@Composable
fun SubtitleDelay(
    delay: Duration,
    onChangeDelay: (Duration) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    val holdStart = remember { longArrayOf(0L) }
    val current = rememberUpdatedDelay(delay)

    fun change(by: Duration) {
        val target = (current.value + by).coerceIn(-maxDelay, maxDelay)
        val applied = target - current.value
        if (applied != Duration.ZERO) {
            current.value = target
            onChangeDelay(applied)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .background(Color(0xF2141414), RoundedCornerShape(12.dp))
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val key = native.keyCode
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                if (native.repeatCount == 0) holdStart[0] = native.eventTime
                                val step = stepForHold(native.eventTime - holdStart[0])
                                change(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                            }
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                change(if (key == KeyEvent.KEYCODE_DPAD_UP) 5.seconds else (-5).seconds)
                            }
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                change(-current.value)
                            }
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }.focusable(),
    ) {
        Text(
            text = stringResource(R.string.jellytip_subtitle_timing),
            fontSize = 12.sp,
            color = Color(0xFFA3A3A3),
        )
        Text(
            text = formatSubtitleDelay(delay),
            fontSize = 34.sp,
            color = Color(0xFFF2F2F0),
        )
        Text(
            text =
                stringResource(
                    when {
                        delay > Duration.ZERO -> R.string.jellytip_subtitles_later
                        delay < Duration.ZERO -> R.string.jellytip_subtitles_earlier
                        else -> R.string.jellytip_subtitles_in_sync
                    },
                ),
            fontSize = 13.sp,
            color = Color(0xFFF2F2F0),
        )
        Text(
            text = stringResource(R.string.jellytip_subtitle_timing_hint),
            fontSize = 11.sp,
            color = Color(0xFFA3A3A3),
        )
    }
}

/** Local mutable mirror so rapid key repeats compound before the parent recomposes. */
@Composable
private fun rememberUpdatedDelay(delay: Duration): DelayHolder {
    val holder = remember { DelayHolder(delay) }
    holder.value = delay
    return holder
}

private class DelayHolder(
    var value: Duration,
)

@PreviewTvSpec
@Composable
private fun SubtitleDelayPreview() {
    WholphinTheme {
        SubtitleDelay(
            delay = 1.5.seconds,
            onChangeDelay = {},
            modifier = Modifier,
        )
    }
}
