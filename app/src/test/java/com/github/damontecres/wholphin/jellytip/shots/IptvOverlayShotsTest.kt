package com.github.damontecres.wholphin.jellytip.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.jellytip.iptv.IptvPlayOverlay
import com.github.damontecres.wholphin.jellytip.iptv.IptvSkipButton
import com.github.damontecres.wholphin.jellytip.iptv.UP_NEXT_OVER_OVERLAY
import com.github.damontecres.wholphin.jellytip.iptv.IptvChapterSkip
import com.github.damontecres.wholphin.jellytip.iptv.IptvChapter
import com.github.damontecres.wholphin.jellytip.iptv.IptvTitleBlock
import com.github.damontecres.wholphin.ui.tryRequestFocus
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/* IPTV player overlay (Ricki 21-Nov): the full overlay with Play/Pause focused, and the seek-only progress strip. For eyes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class IptvOverlayShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private val now = java.time.Instant.parse("2026-11-21T20:00:00Z").toEpochMilli()

    @Composable
    private fun Frame(
        buttons: Boolean,
        seeking: Boolean,
        playing: Boolean,
        focus: FocusRequester,
        scrubbable: Boolean = false,
        titleBlock: Boolean = false,
        chapters: Boolean = false,
        skip: String? = null,
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            // A stand-in picture behind the overlay
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B4A5A), Color(0xFF1B1E24), Color(0xFF5A3B2E))))) {
                IptvPlayOverlay(
                    title = if (titleBlock) "Black Doves" else "Black Doves · S01E01 · To Love Then",
                    subtitle = if (titleBlock) IptvTitleBlock.episodeLine(1, 1, "To Love Then") else null,
                    marks =
                        if (chapters) {
                            IptvChapterSkip.marks(
                                listOf(IptvChapter(0.0, 62.0, "Intro"), IptvChapter(62.0, 1500.0, null), IptvChapter(1500.0, 3300.0, null), IptvChapter(3300.0, 3480.0, "Credits")),
                                58 * 60_000L,
                            )
                        } else {
                            emptyList()
                        },
                    shownMs = if (seeking) 31 * 60_000L else 23 * 60_000L + 14_000,
                    durationMs = 58 * 60_000L,
                    seeking = seeking,
                    playing = playing,
                    nowMs = now,
                    buttons = buttons,
                    subtitlesOn = true,
                    tracksEnabled = true,
                    playFocus = focus,
                    onPlayPause = {},
                    onTracks = {},
                    onMore = {},
                    onInteraction = {},
                    modifier = Modifier.align(Alignment.BottomStart),
                    scrubbable = scrubbable,
                )
                if (skip != null) {
                    // as the page places it while the seek strip shows
                    IptvSkipButton(skip, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = UP_NEXT_OVER_OVERLAY))
                }
            }
        }
    }

    private fun shot(
        name: String,
        buttons: Boolean,
        seeking: Boolean,
        playing: Boolean = true,
        scrubbable: Boolean = false,
        scrubFocused: Boolean = false,
        titleBlock: Boolean = false,
        chapters: Boolean = false,
        skip: String? = null,
    ) {
        val focus = FocusRequester()
        compose.setContent { Frame(buttons, seeking, playing, focus, scrubbable, titleBlock, chapters, skip) }
        compose.waitForIdle()
        if (buttons) compose.runOnIdle { focus.tryRequestFocus() }
        if (scrubFocused) {
            compose.waitForIdle()
            compose.onNodeWithTag("iptv-scrub").requestFocus()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun overlayPlayFocused() = shot("iptv-overlay-buttons", buttons = true, seeking = false)

    @Test
    fun overlayPaused() = shot("iptv-overlay-paused", buttons = true, seeking = false, playing = false)

    /** Ricki 22-Nov: Up from the buttons reaches the scrub bar; Left / Right seek there. */
    @Test
    fun overlayScrubFocused() = shot("iptv-overlay-scrub-focused", buttons = true, seeking = false, scrubbable = true, scrubFocused = true)

    @Test
    fun overlayScrubSeeking() = shot("iptv-overlay-scrub-seeking", buttons = true, seeking = true, scrubbable = true, scrubFocused = true)

    /** A stream that can't seek says so instead of a bar that silently does nothing. */
    @Test
    fun overlayNotSeekable() = shot("iptv-overlay-not-seekable", buttons = true, seeking = false, scrubbable = false)

    @Test
    fun seekStripOnly() = shot("iptv-overlay-seek-strip", buttons = false, seeking = true)

    /** Parity §5: the show as the title, "S1 E1 · To Love Then" under it (the logo is drawn by the page, not in this shot). */
    @Test
    fun overlayTitleBlock() = shot("iptv-overlay-title-block", buttons = true, seeking = false, scrubbable = true, titleBlock = true)

    /** Parity §5: chapter marks on the bar (Intro, two parts, Credits). */
    @Test
    fun overlayChapterMarks() = shot("iptv-overlay-chapters", buttons = true, seeking = false, scrubbable = true, titleBlock = true, chapters = true)

    /** Parity §5: the Skip intro button with the overlay hidden (the seek strip only). */
    @Test
    fun skipIntroButton() = shot("iptv-skip-intro", buttons = false, seeking = false, skip = IptvChapterSkip.label("intro"))
}
