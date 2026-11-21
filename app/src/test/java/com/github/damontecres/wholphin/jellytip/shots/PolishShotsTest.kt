package com.github.damontecres.wholphin.jellytip.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.jellytip.ask.AskChipButton
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.iptv.CatchUpChoiceCard
import com.github.damontecres.wholphin.jellytip.iptv.ColourEdgeFixtureTile
import com.github.damontecres.wholphin.jellytip.iptv.FullTimeCard
import com.github.damontecres.wholphin.jellytip.iptv.FullTimeNext
import com.github.damontecres.wholphin.jellytip.iptv.FxChannel
import com.github.damontecres.wholphin.jellytip.iptv.FxItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvMatch
import com.github.damontecres.wholphin.jellytip.iptv.LiveMatchHero
import com.github.damontecres.wholphin.jellytip.iptv.MatchTeam
import com.github.damontecres.wholphin.jellytip.journeys.FilmRow
import com.github.damontecres.wholphin.jellytip.journeys.JourneyCard
import com.github.damontecres.wholphin.jellytip.journeys.JourneyEntry
import com.github.damontecres.wholphin.jellytip.journeys.JourneyKind
import com.github.damontecres.wholphin.jellytip.journeys.JourneySummary
import com.github.damontecres.wholphin.jellytip.movienight.JoinCard
import com.github.damontecres.wholphin.jellytip.movienight.MnFilm
import com.github.damontecres.wholphin.jellytip.movienight.MnVote
import com.github.damontecres.wholphin.jellytip.movienight.VotePoster
import com.github.damontecres.wholphin.jellytip.rows.HubChip
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/*
 * Design-polish screenshots (mock harness, no TV needed): the new pages' building blocks rendered with synthetic data at the
 * TV's 960x540 dp. One shot per test; the element tagged "focus" gets focus through the test framework (focus asked for
 * from inside the content doesn't take under Robolectric). CI uploads app/build/outputs/roborazzi/ as jellytip-shots-<sha>.
 * Not golden tests: nothing is compared, they are for eyes (before / after a design change).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class PolishShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val now = 1_800_000_000L

    private val focus = Modifier.testTag("focus")

    private fun ch(id: String) = FxChannel(id, "Fox League", null, null, null, 1.0, false, false, now - 1800, now + 5400, true, null)

    private fun fx(
        fid: String,
        status: String = "in",
        favourite: Boolean = true,
    ) = FxItem(
        fid, "NRL", "nrl", "rugby-league", "Penrith Panthers", "New Zealand Warriors", null, null, "Panthers", "Warriors", null, null,
        now - 1700, status, "Round 12", null, 1.0, 1.0, favourite, false, listOf(ch("c1")), "c1", null, null, "m$fid",
    )

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

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(JellytipPalette.Night).padding(24.dp)) { content() }
        }
    }

    /** Render [content], focus the "focus"-tagged element (if any), let motion settle, capture the window. */
    private fun shot(
        name: String,
        focused: Boolean = true,
        screen: Boolean = false,
        focusText: String? = null,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { Tv(content) }
        compose.waitForIdle()
        // A shot without its focus is still worth having: say so in the log rather than failing the build
        runCatching {
            if (focusText != null) {
                compose.onNode(hasText(focusText)).requestFocus()
            } else if (focused) {
                compose.onNodeWithTag("focus").requestFocus()
            }
        }.onFailure { println("shot $name: focus not set (${it.message?.lineSequence()?.firstOrNull()})") }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        if (screen) captureScreenRoboImage(out(name)) else compose.onRoot().captureRoboImage(out(name))
    }

    @Test
    fun gcHero() =
        shot("gc-hero", focused = false) {
            LiveMatchHero(fx("1"), now, onWatch = {}, onFromStart = {}, onMore = {}, modifier = Modifier.width(900.dp), focusRequester = null)
        }

    @Test
    fun gcTiles() =
        shot("gc-tiles") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ColourEdgeFixtureTile(fx("2"), now, reminder = false, revealed = false, onClick = {}, modifier = focus)
                ColourEdgeFixtureTile(fx("3", status = "pre", favourite = false), now, reminder = true, revealed = false, onClick = {})
            }
        }

    @Test
    fun cardFullTime() = shot("cards-fulltime", focused = false) { FullTimeCard(match, "24", "18", FullTimeNext(game = fx("9")), now, Modifier.width(900.dp)) }

    @Test
    fun cardCatchUp() =
        shot("cards-catchup", focused = false) {
            CatchUpChoiceCard(match, "You're joining 27 minutes in", canKickOff = true, cursor = 2, secondsLeft = { 19L }, modifier = Modifier.width(900.dp))
        }

    @Test
    fun askChip() =
        shot("focus-ask-chip") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AskChipButton("Comedy  ✕", on = true, modifier = focus) {}
                AskChipButton("under 90 min  ✕", on = true) {}
                AskChipButton("Put back", on = false) {}
            }
        }

    @Test
    fun hubChip() =
        shot("focus-hub-chip") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubChip("Start a journey  ›", selected = false, onClick = {}, modifier = focus)
                HubChip("Movies", selected = true, onClick = {})
            }
        }

    @Test
    fun journeyCard() =
        shot("focus-journey-card") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JourneyCard(JourneySummary("d:1", JourneyKind.DIRECTOR, "Denis Villeneuve", "Director", 0), modifier = focus) {}
                JourneyCard(JourneySummary("f:1", JourneyKind.FRANCHISE, "Alien in order · 6 films", null, 6)) {}
            }
        }

    @Test
    fun filmRow() =
        shot("focus-film-row") {
            Column(Modifier.width(700.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilmRow(JourneyEntry(1, "a", "Alien", 1979, playable = true, watched = true, inProgress = false), isNext = false) {}
                FilmRow(JourneyEntry(2, "b", "Aliens", 1986, playable = true, watched = false, inProgress = false), isNext = true, modifier = focus) {}
                FilmRow(JourneyEntry(3, null, "Alien 3", 1992, playable = false, watched = false, inProgress = false), isNext = false) {}
            }
        }

    @Test
    fun movieNight() =
        shot("movienight-vote-join") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val f = MnFilm("a", UUID.randomUUID(), "Heat", 1995, null, null)
                VotePoster(f, 140.dp, MnVote.YES, focus) {}
                VotePoster(f.copy(name = "Collateral"), 140.dp, null) {}
                JoinCard("K7M2QX", "host.example/jellytip  →  Movie night  →  Join", "https://host.example/jellytip/#/movienight?join=K7M2QX")
            }
        }

    private val items =
        listOf(
            DialogItem(headlineContent = { Text("Watch live on Fox League") }, onClick = {}),
            DialogItem(headlineContent = { Text("From kick-off") }, onClick = {}),
            DialogItem(headlineContent = { Text("Remind me") }, onClick = {}),
        )

    @Test
    fun dialogStock() =
        shot("dialog-hold-ok", screen = true, focusText = "Watch live on Fox League") {
            Text("Game Centre", color = Color.White)
            DialogPopup(showDialog = true, title = "Penrith Panthers v New Zealand Warriors", dialogItems = items, onDismissRequest = {}, waitToLoad = false)
        }
}
