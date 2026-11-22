package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipFocus
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/**
 * The hero's own buttons (Ricki 31-Oct: the hero item couldn't be selected): Play (resume when started; a series
 * opens its page, where Play picks the next episode) and More info. They sit at the bottom right of the hero, above
 * the rows; Up from the first row lands here. [modifier] carries the page's focus wiring.
 */
@Composable
fun JellytipHeroButtons(
    item: BaseItem,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    /** Hero trailer sound (plan "TV hero trailer: unmute"); null hides the toggle. */
    soundOn: Boolean? = null,
    onSound: () -> Unit = {},
    /** Up from the first row lands on Play through this (on the button itself, not the row). */
    firstRequester: FocusRequester? = null,
    /** False while the buttons are faded out: they never take focus then. */
    focusable: Boolean = true,
    /** Carousel arrows: Left on the first button (false) / Right on the last (true); true = consumed. */
    onEdge: ((right: Boolean) -> Boolean)? = null,
    /** "2 / 9" after the buttons while the hero carousel runs. */
    pager: String? = null,
) {
    JellytipHeroActionButtons(
        buttons = heroPlayButtons(item, onPlay, onInfo),
        modifier = modifier,
        soundOn = soundOn,
        onSound = onSound,
        firstRequester = firstRequester,
        focusable = focusable,
        onEdge = onEdge,
        pager = pager,
    )
}

/** A film / show slide's buttons: Play (Resume when started; Watch opens a series' page) and More info. */
fun heroPlayButtons(
    item: BaseItem,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    /** The slide's trailer with sound (an explicit OK); null = no trailer found. */
    onTrailer: (() -> Unit)? = null,
): List<Pair<String, () -> Unit>> {
    val label = if (!item.type.playable) "\u25B6  Watch" else if (item.resumeMs > 0) "\u25B6  Resume" else "\u25B6  Play"
    // Trailer last: it appears ~2.5 s into a slide, and must not take the place of the button that has focus
    return listOfNotNull(label to onPlay, "More info" to onInfo, onTrailer?.let { "Trailer" to it })
}

/**
 * The hero button row for any slide (a film's Play / More info, a live game's Watch / From kick-off / More): the first
 * is primary and takes [firstRequester]; Sound last. Keyed by place, so a slide change keeps focus where it was.
 */
@Composable
fun JellytipHeroActionButtons(
    buttons: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    soundOn: Boolean? = null,
    onSound: () -> Unit = {},
    firstRequester: FocusRequester? = null,
    focusable: Boolean = true,
    onEdge: ((right: Boolean) -> Boolean)? = null,
    pager: String? = null,
) {
    val canFocus = Modifier.focusProperties { this.canFocus = focusable }
    // Which action button holds focus (-1 = none / Sound). Buttons are keyed by position, so a slide with other labels
    // keeps focus in place; a slide (or rebuild) with fewer buttons moves it to the last one left instead of dropping it
    var focusedAt by remember { mutableIntStateOf(-1) }
    // When the last focused action button lost focus: a removed button loses it just before the list shrinks; long ago =
    // focus went elsewhere on purpose (the rows), and it is never pulled back
    val lostAt = remember { longArrayOf(0L) }
    val requesters = remember { ArrayList<FocusRequester>() }
    while (requesters.size < buttons.size) requesters.add(FocusRequester())
    fun requesterAt(i: Int) = if (i == 0) firstRequester ?: requesters[0] else requesters[i]
    LaunchedEffect(buttons.size) {
        val justLost = android.os.SystemClock.uptimeMillis() - lostAt[0] < REFOCUS_WINDOW_MS
        if (buttons.isNotEmpty() && focusedAt >= buttons.size && justLost) {
            val to = buttons.size - 1
            focusedAt = to
            requesterAt(to).tryRequestFocus()
        }
    }
    val all =
        buttons +
            listOfNotNull(
                // Sound for the trailer playing now, or for the next ones when none is playing; again mutes
                soundOn?.let { on -> (if (on) "\uD83D\uDD0A  Sound on" else "\uD83D\uDD07  Sound off") to onSound },
            )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        all.forEachIndexed { i, (label, action) ->
            val first = i == 0
            val last = i == all.lastIndex
            val isSound = soundOn != null && i == all.lastIndex
            // Keyed by place (Sound: its own key, its label flips): a slide change keeps the focused node
            androidx.compose.runtime.key(if (isSound) "sound" else "b$i") {
                // Removed while focused: note it here too (the blur may come before or after the removal)
                if (!isSound) {
                    DisposableEffect(Unit) {
                        onDispose { if (focusedAt == i) lostAt[0] = android.os.SystemClock.uptimeMillis() }
                    }
                }
                HeroButton(
                    label,
                    primary = first,
                    onClick = action,
                    modifier =
                        canFocus
                            .let { m -> if (isSound) m else m.focusRequester(requesterAt(i)) }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focusedAt = if (isSound) -1 else i
                                } else if (focusedAt == i && !isSound) {
                                    lostAt[0] = android.os.SystemClock.uptimeMillis()
                                }
                            }
                            .onPreviewKeyEvent { e ->
                                if (onEdge == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    first && e.key == Key.DirectionLeft -> onEdge(false)
                                    last && e.key == Key.DirectionRight -> onEdge(true)
                                    else -> false
                                }
                            },
                )
            }
        }
        pager?.let {
            Text(
                it,
                fontFamily = JellytipFontFamily,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun HeroButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cream = Color(0xFFF2F2F0)
    val ink = Color(0xFF0A0A0A)
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(4.dp)),
        scale = ButtonDefaults.scale(focusedScale = JellytipFocus.SCALE),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, cream), shape = RoundedCornerShape(4.dp))),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        colors =
            ButtonDefaults.colors(
                containerColor = if (primary) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White,
                focusedContainerColor = cream,
                focusedContentColor = ink,
            ),
    ) {
        Text(label, fontFamily = JellytipFontFamily, fontSize = 15.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

private const val REFOCUS_WINDOW_MS = 500L
