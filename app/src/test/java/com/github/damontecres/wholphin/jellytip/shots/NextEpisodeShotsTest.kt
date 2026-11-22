package com.github.damontecres.wholphin.jellytip.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.jellytip.iptv.IptvPlayOverlay
import com.github.damontecres.wholphin.jellytip.iptv.JellytipUpNextCard
import com.github.damontecres.wholphin.jellytip.iptv.NextEpisode
import com.github.damontecres.wholphin.jellytip.iptv.NextEpisodeLoader
import com.github.damontecres.wholphin.jellytip.iptv.UP_NEXT_OVER_OVERLAY
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Next episode in the players (t5n 22-Nov): the IPTV overlay with the Next episode button focused, the Up next card with
 * its countdown (alone and over the overlay), and the library-to-IPTV crossover card, at full 960x540 dp with the 5% safe
 * area outlined in magenta (drawn by the test only). Placed exactly as the pages place them. Shots for eyes, not goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class NextEpisodeShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private val now = java.time.Instant.parse("2026-11-22T20:00:00Z").toEpochMilli()

    private val iptvNext =
        NextEpisodeLoader.UpNext(
            NextEpisode.Ep(NextEpisode.Source.IPTV, "p1:e:2", 2, 2, "The Round Table"),
            seriesTitle = "The Celebrity Traitors",
            iptvSeriesId = "p1:s:1",
        )

    private fun shot(
        name: String,
        focusTag: String? = null,
        content: @Composable BoxScope.() -> Unit,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B4A5A), Color(0xFF1B1E24), Color(0xFF5A3B2E))))) {
                    content()
                    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp).border(1.dp, Color.Magenta))
                }
            }
        }
        compose.waitForIdle()
        if (focusTag != null) compose.onNodeWithTag(focusTag).requestFocus()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun BoxScope.Overlay(
        focus: FocusRequester,
        title: String = "The Celebrity Traitors · Episode 1",
    ) {
        IptvPlayOverlay(
            title = title,
            shownMs = 52 * 60_000L,
            durationMs = 58 * 60_000L,
            seeking = false,
            playing = true,
            nowMs = now,
            buttons = true,
            subtitlesOn = false,
            tracksEnabled = true,
            playFocus = focus,
            onPlayPause = {},
            onTracks = {},
            onMore = {},
            onInteraction = {},
            modifier = Modifier.align(Alignment.BottomStart),
            scrubbable = true,
            nextLabel = "Next: S2 E2",
            onNext = {},
        )
    }

    @Test
    fun overlayNextFocused() {
        val focus = FocusRequester()
        shot("nextep-overlay-next-focused", focusTag = "iptv-next") { Overlay(focus) }
    }

    /** The scrub bar (integrate) above the buttons, focused, with the Next episode button in the row. */
    @Test
    fun overlayScrubFocusedWithNext() {
        val focus = FocusRequester()
        shot("nextep-overlay-scrub-focused", focusTag = "iptv-scrub") { Overlay(focus) }
    }

    @Test
    fun upNextCardCountdown() =
        shot("nextep-card-countdown") {
            JellytipUpNextCard(iptvNext, secondsLeft = 12, crossover = false, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 32.dp))
        }

    @Test
    fun upNextCardOverOverlay() {
        val focus = FocusRequester()
        shot("nextep-card-over-overlay") {
            // A title long enough to run the full width: the card must sit clear of it
            Overlay(focus, title = "The Celebrity Traitors · Episode 1 · The Castle Doors Open And Twenty Famous Faces Arrive In The Highlands Tonight")
            JellytipUpNextCard(iptvNext, secondsLeft = 9, crossover = false, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = UP_NEXT_OVER_OVERLAY))
        }
    }

    /** Library player at the end of its last library episode: the next is only on IPTV, with a long title. */
    @Test
    fun libraryToIptvCrossoverCard() =
        shot("nextep-card-library-to-iptv") {
            JellytipUpNextCard(
                iptvNext.copy(ep = iptvNext.ep.copy(season = 3, episode = 1, title = "A Very Long Episode Title That Has To Wrap Onto A Second Line Here")),
                secondsLeft = 15,
                crossover = true,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 32.dp),
            )
        }

    /** OK-only card (auto play next off): the play badge, no number. */
    @Test
    fun upNextCardNoCountdown() =
        shot("nextep-card-ok-only") {
            JellytipUpNextCard(iptvNext, secondsLeft = null, crossover = false, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 32.dp))
        }
}
