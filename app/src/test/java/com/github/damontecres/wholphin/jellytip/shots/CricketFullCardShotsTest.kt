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
import com.github.damontecres.wholphin.jellytip.iptv.Broadcast
import com.github.damontecres.wholphin.jellytip.iptv.FullScorecardSheet
import com.github.damontecres.wholphin.jellytip.iptv.LiveParse
import com.github.damontecres.wholphin.jellytip.iptv.ScorecardBlocks
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/* Cricket full scorecard (Ricki 27-Nov): the Stats-tab hint, the sheet on each innings tab, and scrolled to the bowling table. For eyes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class CricketFullCardShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private val live = LiveParse.live(javaClass.classLoader!!.getResourceAsStream("jellytip/cricket-fullcard-live.json")!!.bufferedReader().readText())

    @Composable
    private fun Backdrop(content: @Composable BoxScope.() -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3B4A5A), Color(0xFF1B1E24), Color(0xFF2E5A3B)))), content = content)
        }
    }

    private fun sheet(
        name: String,
        tab: Int,
        scrollToEnd: Boolean = false,
    ) {
        val scroll = ScrollState(0)
        compose.setContent { Backdrop { FullScorecardSheet(live.fullCard!!, tab, scroll, Modifier.align(Alignment.Center)) } }
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
    fun currentInnings() = sheet("cricket-full-card-1-current", tab = 0)

    @Test
    fun currentInningsScrolledToBowling() = sheet("cricket-full-card-2-current-bottom", tab = 0, scrollToEnd = true)

    @Test
    fun completedInnings() = sheet("cricket-full-card-3-completed", tab = 1)

    @Test
    fun completedInningsScrolled() = sheet("cricket-full-card-4-completed-bottom", tab = 1, scrollToEnd = true)

    @Test
    fun statsTabHint() {
        compose.setContent {
            Backdrop {
                Column(Modifier.fillMaxHeight().fillMaxWidth(0.28f).align(Alignment.CenterEnd).background(Broadcast.Card).padding(10.dp)) {
                    ScorecardBlocks(live.scorecard!!, "HOM", "AWY", fullHint = true)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cricket-full-card-0-stats-hint.png")
    }
}
