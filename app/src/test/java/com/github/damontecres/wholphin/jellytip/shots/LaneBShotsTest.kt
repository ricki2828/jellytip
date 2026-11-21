package com.github.damontecres.wholphin.jellytip.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.iptv.ColourEdgeFixtureTile
import com.github.damontecres.wholphin.jellytip.iptv.FxChannel
import com.github.damontecres.wholphin.jellytip.iptv.FxItem
import com.github.damontecres.wholphin.jellytip.iptv.LiveMatchHero
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Lane B shots (same harness as PolishShotsTest; for eyes, not compared): Game Centre tiles and hero in the fixture's own
 * team colours after the contrast / clash guard, and the For you reason line, whose title block is measured against
 * the 50 dp Home keeps above a focused card (HomePage spaceAbovePx).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LaneBShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val now = 1_800_000_000L

    private fun ch(id: String) = FxChannel(id, "Fox League", null, null, null, 1.0, false, false, now - 1800, now + 5400, true, null)

    private fun fx(
        fid: String,
        home: String,
        away: String,
        hc: String?,
        ha: String?,
        ac: String?,
        aa: String?,
        status: String = "pre",
    ) = FxItem(
        fid, "NRL", "nrl", "rugby-league", home, away, null, null, home.take(3).uppercase(), away.take(3).uppercase(), null, null,
        now + 1700, status, "Round 12", null, 1.0, 1.0, true, false, listOf(ch("c1")), "c1", null, null, "m$fid",
        homeColor = hc,
        homeAltColor = ha,
        awayColor = ac,
        awayAltColor = aa,
    )

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(JellytipPalette.Night).padding(24.dp)) { content() }
        }
    }

    private fun shot(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { Tv(content) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(out(name))
    }

    @Test
    fun gcTilesTeamColours() =
        shot("laneb-gc-tiles-colours") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Plain: navy v red
                    ColourEdgeFixtureTile(fx("1", "Bulldogs", "Dragons", "#0b2265", null, "#e03a3e", null), now, reminder = false, revealed = false, onClick = {})
                    // Clash: red v red, away takes its navy alt
                    ColourEdgeFixtureTile(fx("2", "Rabbitohs", "Roosters", "#c8102e", null, "#c4122f", "#002244"), now, reminder = false, revealed = false, onClick = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Near-black home: its alt; white away with a white alt: neutral
                    ColourEdgeFixtureTile(fx("3", "Panthers", "Tigers", "#0d0f14", "#c8102e", "#ffffff", "#fafafa"), now, reminder = false, revealed = false, onClick = {})
                    // No colours (F1-like): neutral, as before
                    ColourEdgeFixtureTile(fx("4", "Monaco GP", "", null, null, null, null), now, reminder = false, revealed = false, onClick = {})
                }
            }
        }

    @Test
    fun gcHeroTeamColours() =
        shot("laneb-gc-hero-colours") {
            LiveMatchHero(
                fx("5", "Rabbitohs", "Roosters", "#c8102e", null, "#c4122f", "#002244", status = "in"),
                now,
                onWatch = {},
                onFromStart = {},
                onMore = {},
                modifier = Modifier.width(900.dp),
                focusRequester = null,
            )
        }

    /** Title block (title top -> first card top) of a row, in dp. */
    private fun titleBlock(subtitle: String?): Float {
        compose.setContent {
            Tv {
                Box(Modifier.testTag("row")) {
                    ItemRow(
                        title = "For you",
                        subtitle = subtitle,
                        items = List(6) { it },
                        onClickItem = { _, _ -> },
                        onLongClickItem = { _, _ -> },
                        cardContent = { index, _, mod, _, _ ->
                            Box(mod.size(220.dp, 124.dp).background(Color(0xFF2A303B)).then(if (index == 0) Modifier.testTag("card0") else Modifier))
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        if (subtitle != null) compose.onRoot().captureRoboImage(out("laneb-foryou-reason"))
        val row = compose.onNodeWithTag("row").getBoundsInRoot()
        val card = compose.onNodeWithTag("card0", useUnmergedTree = true).getBoundsInRoot()
        return (card.top - row.top).value
    }

    @Test
    fun plainTitleBlock() {
        println("For you title block: plain=${titleBlock(null)} dp")
    }

    @Test
    fun reasonLineFitsHomesTitleRoom() {
        val withReason = titleBlock("Because you like Martin Scorsese and 2010s drama")
        println("For you title block: with reason=$withReason dp (Home keeps 50 dp above the focused card)")
        assertTrue("reason line block $withReason dp exceeds Home's 50 dp title room", withReason <= 50f)
    }
}
