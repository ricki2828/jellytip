package com.github.damontecres.wholphin.jellytip.shots

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.jellytip.iptv.BoxScoreSheet
import com.github.damontecres.wholphin.jellytip.iptv.Broadcast
import com.github.damontecres.wholphin.jellytip.iptv.FullHint
import com.github.damontecres.wholphin.jellytip.iptv.LiveParse
import com.github.damontecres.wholphin.jellytip.iptv.MatchLive
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/* NFL / basketball box score (Ricki 27-Nov): the Stats-tab hint and the sheet on each tab, plus scrolled to the foot. For eyes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class BoxScoreShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun live(name: String) = LiveParse.live(javaClass.classLoader!!.getResourceAsStream("jellytip/$name")!!.bufferedReader().readText())

    private val nfl = live("box-nfl-live.json")
    private val nba = live("box-nba-live.json")

    @Composable
    private fun Backdrop(content: @Composable BoxScope.() -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B4A5A), Color(0xFF1B1E24), Color(0xFF2E5A3B)))), content = content)
        }
    }

    private fun sheet(
        name: String,
        d: MatchLive,
        tab: Int,
        scrollToEnd: Boolean = false,
    ) {
        val scroll = ScrollState(0)
        compose.setContent { Backdrop { BoxScoreSheet(d.boxScore!!, tab, scroll, Modifier.align(Alignment.Center)) } }
        compose.waitForIdle()
        if (scrollToEnd) {
            compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun nflTeamStats() = sheet("box-score-nfl-1-team-stats", nfl, tab = 0)

    @Test
    fun nflAwayTeam() = sheet("box-score-nfl-2-away", nfl, tab = 1)

    @Test
    fun nflHomeTeamScrolled() = sheet("box-score-nfl-3-home-bottom", nfl, tab = 2, scrollToEnd = true)

    @Test
    fun nbaAwayTeam() = sheet("box-score-nba-1-away", nba, tab = 0)

    @Test
    fun nbaHomeTeamScrolled() = sheet("box-score-nba-2-home-bottom", nba, tab = 1, scrollToEnd = true)

    @Test
    fun statsTabHint() {
        compose.setContent {
            Backdrop {
                Column(Modifier.fillMaxHeight().fillMaxWidth(0.28f).align(Alignment.CenterEnd).background(Broadcast.Card).padding(10.dp)) {
                    FullHint("Full box score")
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/box-score-0-stats-hint.png")
    }
}
