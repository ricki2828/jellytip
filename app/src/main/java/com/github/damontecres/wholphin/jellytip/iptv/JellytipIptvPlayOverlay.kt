package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackButton
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * IPTV films / episodes / recordings: one overlay like the library player (title, progress with elapsed / time left /
 * ends at, then [Play/Pause] [Audio & subtitles] [More]). Hidden: OK / Up / Down show it, Left / Right seek (progress only).
 * Shown: the D-pad moves between its buttons, Back hides it, and it hides itself after [IptvPlayControls.HIDE_MS].
 */
object IptvPlayControls {
    const val HIDE_MS = 5_000L

    /** What a key does while the overlay is hidden. */
    enum class Hidden { SHOW, SEEK, NONE }

    enum class Dir { OK, UP, DOWN, LEFT, RIGHT, OTHER }

    /**
     * Overlay shown with the scrub bar focused (Ricki 22-Nov: "can't reach the scrub bar"): Left / Right seek like the hidden
     * overlay does (ramped); every other key stays with the overlay's focus.
     */
    fun scrubSeeks(
        overlay: Boolean,
        scrubFocused: Boolean,
        dir: Dir,
    ): Boolean = overlay && scrubFocused && (dir == Dir.LEFT || dir == Dir.RIGHT)

    fun hidden(dir: Dir): Hidden =
        when (dir) {
            Dir.OK, Dir.UP, Dir.DOWN -> Hidden.SHOW
            Dir.LEFT, Dir.RIGHT -> Hidden.SEEK
            Dir.OTHER -> Hidden.NONE
        }

    enum class More(
        val label: String,
    ) {
        START_OVER("Start from beginning"),
        JUMP_TO_LIVE("Jump to live"),
        DELETE_RECORDING("Delete recording"),
    }

    /** The ⋯ More list: only what applies to this stream. */
    fun more(
        seekable: Boolean,
        recording: Boolean,
        inProgressWithChannel: Boolean,
    ): List<More> =
        buildList {
            if (seekable) add(More.START_OVER)
            if (inProgressWithChannel) add(More.JUMP_TO_LIVE)
            if (recording) add(More.DELETE_RECORDING)
        }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    /** "-12:34", or null without a length. */
    fun left(
        positionMs: Long,
        durationMs: Long,
    ): String? = if (durationMs > 0) "-" + clock((durationMs - positionMs).coerceAtLeast(0)) else null

    /** "Ends 21:42" (local time), or null without a length. */
    fun endsAt(
        nowMs: Long,
        positionMs: Long,
        durationMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (durationMs <= 0) return null
        val end = Instant.ofEpochMilli(nowMs + (durationMs - positionMs).coerceAtLeast(0))
        return "Ends " + DateTimeFormatter.ofPattern("H:mm").withZone(zone).format(end)
    }
}

/**
 * The overlay. [buttons] false = the progress strip alone (seeking, media keys). [shownMs] is where the bar draws (a
 * pending seek target while seeking, in gold).
 */
@Composable
internal fun IptvPlayOverlay(
    title: String,
    shownMs: Long,
    durationMs: Long,
    seeking: Boolean,
    playing: Boolean,
    nowMs: Long,
    buttons: Boolean,
    subtitlesOn: Boolean,
    tracksEnabled: Boolean,
    playFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onTracks: () -> Unit,
    onMore: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    /** The stream can seek: the bar takes focus (Up from the buttons), Left / Right on it seek. */
    scrubbable: Boolean = false,
    onScrubFocus: (Boolean) -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 28.dp),
    ) {
        Text(
            text = title,
            color = JellytipPalette.Vanilla,
            fontFamily = JellytipFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val frac = if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val scrubSource = remember { MutableInteractionSource() }
        val scrubF by scrubSource.collectIsFocusedAsState()
        val bar = @Composable { h: androidx.compose.ui.unit.Dp ->
            Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h / 2)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (seeking) Color(0xFFFFC72C) else JellytipPalette.Jelly))
            }
        }
        if (buttons && scrubbable) {
            // Focusable scrub bar above the buttons: Up reaches it, Left / Right seek (the page runs the ramp), Down goes back
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .testTag("iptv-scrub")
                        .focusProperties { down = playFocus } // (2D search from a full-width bar would land on the rightmost button)
                        .onFocusChanged { onScrubFocus(it.isFocused) }
                        .focusable(interactionSource = scrubSource)
                        .then(if (scrubF) Modifier.border(2.dp, JellytipPalette.Vanilla, RoundedCornerShape(8.dp)).padding(horizontal = 4.dp) else Modifier),
            ) { bar(if (scrubF) 8.dp else 4.dp) }
        } else {
            bar(4.dp)
        }
        val dim = JellytipPalette.Vanilla.copy(alpha = 0.8f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                IptvPlayControls.clock(shownMs),
                color = if (seeking) Color(0xFFFFC72C) else dim,
                fontFamily = JellytipFontFamily,
                fontSize = 14.sp,
            )
            Box(Modifier.weight(1f))
            IptvPlayControls.endsAt(nowMs, shownMs, durationMs)?.let {
                Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.55f), fontFamily = JellytipFontFamily, fontSize = 14.sp, modifier = Modifier.padding(end = 16.dp))
            }
            Text(IptvPlayControls.left(shownMs, durationMs) ?: "--:--", color = dim, fontFamily = JellytipFontFamily, fontSize = 14.sp)
        }
        if (buttons) {
            // One line always reserved while the buttons show, so the bar doesn't jump when it takes focus
            Text(
                when {
                    scrubbable && scrubF -> "◀ ▶  Seek  ·  hold to go faster"
                    !scrubbable && durationMs > 0 -> "This stream can't be skipped through"
                    else -> ""
                },
                color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                fontFamily = JellytipFontFamily,
                fontSize = 13.sp,
            )
        }
        if (buttons) {
            val playSource = remember { MutableInteractionSource() }
            val tracksSource = remember { MutableInteractionSource() }
            val moreSource = remember { MutableInteractionSource() }
            val playF by playSource.collectIsFocusedAsState()
            val tracksF by tracksSource.collectIsFocusedAsState()
            val moreF by moreSource.collectIsFocusedAsState()
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.focusGroup(),
            ) {
                PlaybackButton(
                    iconRes = if (playing) R.drawable.jt_pause else R.drawable.jt_play,
                    onClick = onPlayPause,
                    onControllerInteraction = onInteraction,
                    interactionSource = playSource,
                    modifier = Modifier.focusRequester(playFocus),
                )
                PlaybackButton(
                    iconRes = R.drawable.jt_cc,
                    onClick = onTracks,
                    onControllerInteraction = onInteraction,
                    enabled = tracksEnabled,
                    badge = subtitlesOn,
                    interactionSource = tracksSource,
                )
                PlaybackButton(
                    iconRes = R.drawable.jt_settings,
                    onClick = onMore,
                    onControllerInteraction = onInteraction,
                    interactionSource = moreSource,
                )
                // The focused button's name (the icons alone are not obvious from the sofa)
                val name =
                    when {
                        playF -> if (playing) "Pause" else "Play"
                        tracksF -> "Audio & subtitles"
                        moreF -> "More"
                        else -> ""
                    }
                Text(name, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
