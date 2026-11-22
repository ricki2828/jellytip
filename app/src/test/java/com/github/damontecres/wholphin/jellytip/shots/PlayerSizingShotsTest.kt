package com.github.damontecres.wholphin.jellytip.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.jellytip.iptv.Broadcast
import com.github.damontecres.wholphin.jellytip.iptv.CatchUpChoiceCard
import com.github.damontecres.wholphin.jellytip.iptv.CatchUpPolicy
import com.github.damontecres.wholphin.jellytip.iptv.CatchUpRecapCard
import com.github.damontecres.wholphin.jellytip.iptv.DelayChipText
import com.github.damontecres.wholphin.jellytip.iptv.FullTimeCard
import com.github.damontecres.wholphin.jellytip.iptv.FullTimeNext
import com.github.damontecres.wholphin.jellytip.iptv.IptvMatch
import com.github.damontecres.wholphin.jellytip.iptv.JellytipLivePanel
import com.github.damontecres.wholphin.jellytip.iptv.MatchLive
import com.github.damontecres.wholphin.jellytip.iptv.MatchTeam
import com.github.damontecres.wholphin.jellytip.iptv.PlayerCornerOverlays
import com.github.damontecres.wholphin.jellytip.iptv.PlayerToast
import com.github.damontecres.wholphin.jellytip.iptv.ScorebugPill
import com.github.damontecres.wholphin.jellytip.iptv.matchPalette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Live player sizing (audit ~/ai/state/jellytip-tv-design-audit-20261122.md W1, W2): the player's cards and corner
 * overlays at full screen, 960x540 dp, with NO harness padding (the polish harness's 24 dp pad hid W1), over a mid-grey
 * "picture", placed exactly as the player page places them. A magenta outline marks the 5% safe area (48 dp left /
 * right, 27 dp top / bottom); it is drawn by the test only. Not golden tests: shots for before / after review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class PlayerSizingShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private val now = 1_800_000_000L

    private val match =
        IptvMatch(
            id = "m1",
            league = "NRL",
            home = MatchTeam("Penrith Panthers", "Panthers", null, null, color = "#2A2A2A", altColor = "#E4003B"),
            away = MatchTeam("New Zealand Warriors", "Warriors", null, null, color = "#0B2A4A", altColor = "#9CA3AF"),
            startsAt = now - 1700,
            venue = null,
            status = "in",
            inferred = false,
            score = null,
        )

    /** The player at full screen: grey picture, [content] in a full-size Box, then the safe-area outline on top. */
    private fun shot(
        name: String,
        content: @Composable BoxScope.() -> Unit,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF7A7A7A))) {
                    content()
                    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp).border(1.dp, Color.Magenta))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun fullTimeCard() =
        shot("player-fulltime") {
            FullTimeCard(match, "24", "18", FullTimeNext(game = null, pickTitle = "Heat", pickId = null, pickKind = null), now, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        }

    @Test
    fun catchUpChoiceCard() =
        shot("player-catchup-choice") {
            CatchUpChoiceCard(match, CatchUpPolicy.joiningLine(now - 1700, now), listOf(0, 1, 2), CatchUpPolicy.catchUpLine(5), 2, { 19L }, Modifier.align(Alignment.Center))
        }

    @Test
    fun catchUpRecapCard() =
        shot("player-catchup-recap") {
            CatchUpRecapCard(match, CatchUpPolicy.Card("2 12:40", "Try Warriors: Dallin Watene-Zelezniak in the corner after a long break", "away", 4), 2, 7, Modifier.align(Alignment.Center))
        }

    @Test
    fun cornerOverlays() =
        shot("player-corners") {
            PlayerCornerOverlays(
                delayChip = { DelayChipText(replay = { false }, hold = { null }, delaySec = { 30 }) },
                scorebug = {
                    ScorebugPill("Panthers", "Warriors", "12", "6", "45:10", "2nd", matchPalette(match), holdText = null)
                },
                toast = PlayerToast(main = "Warriors now favourites · 54%", kicker = "Polymarket flip", sub = "$1.2M traded", edge = Broadcast.Gold),
                toastProgress = { 1f },
                fantasy = "🏈  CCP 87.4 – 79.2",
                hint = listOf("◀  From kick-off", "→  Companion", "▼  Other channels", "Hold OK: match info"),
                trackMessage = "Audio: English (AAC 5.1)",
            )
        }

    /** Teams with no short name: the scorebug ellipsizes the names inside its 428 dp column, the clock stays. */
    @Test
    fun cornerOverlaysLongNames() =
        shot("player-corners-longnames") {
            PlayerCornerOverlays(
                delayChip = null,
                scorebug = {
                    ScorebugPill("Penrith Panthers", "New Zealand Warriors", "12", "6", "45:10", "2nd Half", matchPalette(match), holdText = null)
                },
                toast = null,
                toastProgress = { 1f },
                fantasy = null,
                hint = listOf("◀  From kick-off", "→  Companion", "▼  Other channels", "Hold OK: match info"),
                trackMessage = null,
            )
        }

    private val live = MatchLive("in", "45:10", "2nd Half", "12", "6", emptyList(), emptyList(), emptyList(), null, null, null, emptyList(), updatedAt = null)

    /** Docked live panel with the delay chip as its first row (the chip never sits on the score strip; P1 insets). */
    @Test
    fun cornersWithDockedPanel() =
        shot("player-corners-panel") {
            JellytipLivePanel(
                match,
                tab = 0,
                spoilerNotice = false,
                data = live,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.28f),
                topRow = { DelayChipText(replay = { false }, hold = { null }, delaySec = { 30 }) },
            )
        }
}
