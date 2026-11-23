package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.fillFullBleed
import com.github.damontecres.wholphin.jellytip.home.JellytipHeroActionButtons
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.journeys.JourneyHeroBackdrop
import com.github.damontecres.wholphin.jellytip.journeys.JourneyHeroHeader
import com.github.damontecres.wholphin.jellytip.journeys.JourneyV1Content
import com.github.damontecres.wholphin.jellytip.journeys.JourneysV1
import com.github.damontecres.wholphin.jellytip.journeys.JourneysV1Content
import com.github.damontecres.wholphin.jellytip.journeys.JvActions
import com.github.damontecres.wholphin.jellytip.journeys.JvHome
import com.github.damontecres.wholphin.jellytip.journeys.JvJourney
import com.github.damontecres.wholphin.jellytip.journeys.JvJourneyState
import com.github.damontecres.wholphin.jellytip.journeys.JvLoad
import com.github.damontecres.wholphin.jellytip.journeys.JvPageState
import com.github.damontecres.wholphin.jellytip.journeys.JvSource
import com.github.damontecres.wholphin.jellytip.journeys.JvStep
import com.github.damontecres.wholphin.jellytip.journeys.JvSummary
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Journeys v1 (brief jellytip-journeys-v1-tv-brief.md): the page with tonight's step as the hero (Play focused), the path (See the
 * path, tonight's step focused), the rows, a kids profile (its own, smaller answer: the filtering is the server's), the Home hero
 * slide, a 99-step quest without a "why" (path window), and the failed state. Fixture resources/jellytip/journeys-v1-fixture.json,
 * synthetic art. Harness as ChatButtonShotsTest: 960x540 dp, page inset 72 / 16, magenta safe-area guides, keyboard input mode.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class JourneysShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val base = "https://api.example"

    private val fixture by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/journeys-v1-fixture.json")!!.bufferedReader().readText())
    }

    private val home by lazy { JourneysV1.home(fixture.getJSONObject("home").toString(), base)!! }
    private val kidsHome by lazy { JourneysV1.home(fixture.getJSONObject("kidsHome").toString(), base)!! }
    private val heat by lazy { JourneysV1.journey(fixture.getJSONObject("journey").toString(), base)!! }

    private val keyboardMode =
        object : InputModeManager {
            override val inputMode = InputMode.Keyboard

            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }

    private fun art(
        top: Int,
        bottom: Int,
        w: Int,
        h: Int,
    ) = run {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // A bright shape where a face would be: the scrim must keep the text readable over it
        p.shader = null
        p.color = 0xFFF5E6C8.toInt()
        Canvas(b).drawCircle(w * 0.7f, h * 0.4f, h * 0.22f, p)
        b.asImage()
    }

    private val backdrop by lazy { art(0xFF2B3A55.toInt(), 0xFFB5652B.toInt(), 960, 540) }
    private val poster by lazy { art(0xFF5A2E6E.toInt(), 0xFF1E3B5A.toInt(), 200, 300) }

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        val handler = AsyncImagePreviewHandler { request -> if ("w1280" in request.data.toString()) backdrop else poster }
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides handler,
            LocalInputModeManager provides keyboardMode,
        ) {
            MaterialTheme(colorScheme = com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors.darkScheme) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    @Composable
    private fun Frame(content: @Composable BoxScope.() -> Unit) {
        val safe = Color(0xFFFF00FF)
        Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
            Box(Modifier.fillMaxSize().padding(start = 72.dp, end = 16.dp)) { content() }
            Box(Modifier.offset(x = 48.dp).fillMaxHeight().width(1.dp).background(safe))
            Box(Modifier.offset(x = 912.dp).fillMaxHeight().width(1.dp).background(safe))
            Box(Modifier.offset(y = 27.dp).fillMaxWidth().height(1.dp).background(safe))
            Box(Modifier.offset(y = 513.dp).fillMaxWidth().height(1.dp).background(safe))
        }
    }

    private val noop =
        object : JvActions {
            override fun play(
                j: JvJourney,
                index: Int,
            ) {}

            override fun open(id: String) {}

            override fun getIt(j: JvJourney) {}

            override fun retry() {}

            override fun focused(id: String?) {}
        }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun logFocus(name: String) {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        val texts = nodes.map { n -> n.config.getOrNull(SemanticsProperties.TestTag) ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { t -> t.text } ?: "(none)" }
        println("shot $name: focused nodes=${nodes.size} ids=$texts")
    }

    private fun bounds(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    /** Inside the safe area horizontally (left >= 48, right <= 912) and below the top guide. */
    private fun inSafe(
        name: String,
        what: String,
        matcher: SemanticsMatcher,
    ) {
        val b = bounds(matcher)
        println("shot $name: $what left=${b.left.value} right=${b.right.value} top=${b.top.value} bottom=${b.bottom.value}")
        assertTrue("$name: $what left ${b.left.value} < 48", b.left.value >= 48f)
        assertTrue("$name: $what right ${b.right.value} > 912", b.right.value <= 912f)
        assertTrue("$name: $what top ${b.top.value} < 27", b.top.value >= 27f)
        assertTrue("$name: $what bottom ${b.bottom.value} > 513", b.bottom.value <= 513f)
    }

    private fun shot(
        name: String,
        steps: () -> Unit = {},
        measure: () -> Unit = {},
        content: @Composable BoxScope.() -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Tv { Frame { content() } } }
        advance(1200)
        steps()
        advance(1500)
        logFocus(name)
        measure()
        compose.onRoot().captureRoboImage(out(name))
    }

    private fun page(
        h: JvHome,
        hero: JvJourney?,
        isAdmin: Boolean = true,
    ) = JvPageState(home = JvLoad.Ok(h), hero = hero)

    @Test
    fun heroFocused() {
        shot("jv-1-hero", steps = {
            compose.onNode(hasTestTag("jv-play")).requestFocus()
            advance(300)
            compose.onNode(hasTestTag("jv-play")).assertIsFocused()
        }, measure = {
            inSafe("jv-1-hero", "kicker", hasTestTag("jv-kicker"))
            inSafe("jv-1-hero", "Curated", hasTestTag("jv-curated"))
            inSafe("jv-1-hero", "Play", hasTestTag("jv-play"))
            inSafe("jv-1-hero", "Get it", hasTestTag("jv-get"))
            inSafe("jv-1-hero", "why", hasTestTag("jv-why"))
            inSafe("jv-1-hero", "path strip", hasTestTag("jv-path-strip"))
            // 6 steps, window of 5 around tonight's: "+1 ✓" then steps 2-6
            val w = JourneysV1.window(heat.steps.size, 2)
            assertEquals(1, w.from)
            heat.steps.drop(1).forEach { assertEquals("strip shows ${it.title}", 1, count(hasText(it.title, substring = false))) }
            assertEquals(1, count(hasText("+1 ✓ ——")))
            assertEquals(1, count(hasText("THE ROAD TO HEAT · STEP 3 OF 6")))
            assertEquals(1, count(hasText("▶  Play Thief")))
            assertEquals(1, count(hasText("Get Manhunter")))
        }) { JourneysV1Content(page(home, heat), noop, isAdmin = true, restoreId = null) }
    }

    @Test
    fun heroNotAdminHasNoGetIt() {
        shot("jv-1b-hero-not-admin", measure = {
            assertEquals(0, count(hasTestTag("jv-get")))
        }) { JourneysV1Content(page(home, heat), noop, isAdmin = false, restoreId = null) }
    }

    @Test
    fun rows() {
        shot("jv-3-rows", steps = {
            // The first "Start something new" card (after the 3 of "Your journeys")
            compose.onAllNodes(hasTestTag("jv-card")).fetchSemanticsNodes()
            compose.onAllNodes(hasTestTag("jv-card"))[3].requestFocus()
            advance(600)
        }, measure = {
            assertEquals(1, count(hasText("Your journeys")))
            assertEquals(1, count(hasText("Start something new")))
            assertEquals(1, count(hasText("Finish what you started")))
            println("shot jv-3-rows: cards=${count(hasTestTag("jv-card"))}")
        }) { JourneysV1Content(page(home, heat), noop, isAdmin = true, restoreId = null) }
    }

    @Test
    fun path() {
        shot("jv-2-path", measure = {
            inSafe("jv-2-path", "steps", hasTestTag("jv-steps"))
            inSafe("jv-2-path", "why", hasTestTag("jv-step-why"))
            assertEquals(1, count(hasText("Tonight")))
        }) {
            JourneyV1Content(
                state = JvJourneyState(load = JvLoad.Ok(heat)),
                isAdmin = true,
                restoreStep = null,
                onPlay = { _, _ -> },
                onGet = {},
                onAction = { _, _ -> },
                onFocusStep = {},
                onRetry = {},
            )
        }
    }

    private val ghibli by lazy {
        val s = kidsHome.journeys.getValue("cur:ghibli-first")
        fun st(
            n: Int,
            title: String,
            year: Int,
            seen: Boolean,
            why: String,
        ) = JvStep(n, 1000 + n, title, year, 90, 7.9, why, JvSource.LIBRARY, "%032x".format(n), null, seen, false, false, "https://image.tmdb.org/t/p/w342/$n.jpg", "https://image.tmdb.org/t/p/w1280/$n.jpg")
        JvJourney(
            s,
            "Five gentle Studio Ghibli films, smallest adventures first.",
            "PG",
            listOf(
                st(1, "My Neighbor Totoro", 1988, false, "Two sisters, a new house and a forest spirit. No villains at all."),
                st(2, "Kiki's Delivery Service", 1989, false, "A young witch starts her first job."),
                st(3, "Ponyo", 2008, false, "A goldfish who wants to be a girl."),
                st(4, "The Secret World of Arrietty", 2010, false, "Tiny people under the floor."),
                st(5, "Spirited Away", 2001, false, "The big one, for when they're ready."),
            ),
        )
    }

    @Test
    fun kidsProfile() {
        shot("jv-4-kids", steps = {
            compose.onNode(hasTestTag("jv-play")).requestFocus()
            advance(300)
        }, measure = {
            // Only what the kids answer holds: no Heat, no Sight & Sound
            assertEquals(0, count(hasText("The road to Heat", substring = true)))
            assertEquals(0, count(hasText("Your journeys")))
            assertEquals(2, count(hasTestTag("jv-card")))
            inSafe("jv-4-kids", "Play", hasTestTag("jv-play"))
        }) { JourneysV1Content(page(kidsHome, ghibli), noop, isAdmin = false, restoreId = null) }
    }

    @Test
    fun homeHeroSlide() {
        val s: JvSummary = home.journeys.getValue("cur:road-to-heat")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Tv {
                Frame {
                    // As HomePage draws a journey slide: backdrop full bleed, header bottom left, the shared hero buttons
                    Box(Modifier.fillMaxSize().fillFullBleed()) {
                        JourneyHeroBackdrop(s, Modifier.fillMaxWidth().fillMaxHeight(JellytipHomeTokens.HERO_HEIGHT_FRACTION))
                    }
                    Column(Modifier.fillMaxSize().padding(start = JellytipLayout.RowPageStart)) {
                        Box {
                            JourneyHeroHeader(s)
                            JellytipHeroActionButtons(
                                buttons = listOf("▶  Play Thief" to {}, "See the path" to {}),
                                soundOn = false,
                                pager = "‹  2 / 6  ›",
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        advance(1200)
        compose.onAllNodes(hasText("▶  Play Thief")).onFirst().requestFocus()
        advance(1200)
        logFocus("jv-5-home-slide")
        inSafe("jv-5-home-slide", "slide text", hasTestTag("journey-slide"))
        assertEquals(1, count(hasText("THE ROAD TO HEAT · STEP 3 OF 6")))
        compose.onRoot().captureRoboImage(out("jv-5-home-slide"))
    }

    @Test
    fun longQuestWithoutWhy() {
        val s = home.journeys.getValue("l:sight-and-sound")
        val titles = (1..99).map { "Film number $it" }
        val steps =
            titles.mapIndexed { i, t ->
                JvStep(i + 1, 2000 + i, t, 1950 + i % 70, 100, 7.0, null, if (i % 7 == 3) JvSource.MISSING else JvSource.LIBRARY, "%032x".format(i + 1), null, i < 41, false, false, null, if (i == 41) "https://image.tmdb.org/t/p/w1280/v.jpg" else null)
            }
        val j = JvJourney(s, null, null, steps)
        shot("jv-6-long-quest", steps = {
            compose.onNode(hasTestTag("jv-play")).requestFocus()
            advance(300)
        }, measure = {
            inSafe("jv-6-long-quest", "path strip", hasTestTag("jv-path-strip"))
            assertEquals(0, count(hasTestTag("jv-why")))
            assertEquals(0, count(hasTestTag("jv-curated")))
            assertEquals(1, count(hasText("SIGHT & SOUND 100 · STEP 42 OF 99")))
        }) { JourneysV1Content(page(home, j), noop, isAdmin = true, restoreId = null) }
    }

    @Test
    fun failedHasAFocusedRetry() {
        shot("jv-7-failed", measure = {
            compose.onNode(hasTestTag("jv-retry")).assertIsFocused()
        }) { JourneysV1Content(JvPageState(home = JvLoad.Failed(503)), noop, isAdmin = false, restoreId = null) }
    }
}
