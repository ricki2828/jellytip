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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
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
import com.github.damontecres.wholphin.jellytip.journeys.JvAsk
import com.github.damontecres.wholphin.jellytip.journeys.JvAskState
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
 * Letterboxd Top 100 genre journeys (lt:<genre>, server lane lbtop 24-Nov): no client change, these are list quests whose premise
 * is the Letterboxd credit. Shots of the hero (official + community list with its long title), the rows and the path. Fixture
 * resources/jellytip/journeys-lbtop-fixture.json is built from the committed list data (sources synthetic), art synthetic.
 * Harness copied from JourneysShotsTest.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LetterboxdJourneysShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val base = "https://api.example"

    private val fixture by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/journeys-lbtop-fixture.json")!!.bufferedReader().readText())
    }

    private val home by lazy { JourneysV1.home(fixture.getJSONObject("home").toString(), base)!! }
    private val horror by lazy { JourneysV1.journey(fixture.getJSONObject("horror").toString(), base)!! }
    private val comedy by lazy { JourneysV1.journey(fixture.getJSONObject("comedy").toString(), base)!! }

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

            override fun getIt(
                j: JvJourney,
                index: Int?,
            ) {}

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
    fun heroOfficialList() {
        shot("lt-1-hero-horror", steps = {
            compose.onNode(hasTestTag("jv-play")).requestFocus()
            advance(300)
            compose.onNode(hasTestTag("jv-play")).assertIsFocused()
        }, measure = {
            inSafe("lt-1-hero-horror", "kicker", hasTestTag("jv-kicker"))
            inSafe("lt-1-hero-horror", "credit", hasTestTag("jv-why"))
            inSafe("lt-1-hero-horror", "path strip", hasTestTag("jv-path-strip"))
            // the credit is the premise (no invented copy); a list is not "Curated"
            assertEquals(1, count(hasText("Source: Letterboxd · Top 250 Horror Films")))
            assertEquals(0, count(hasTestTag("jv-curated")))
        }) { JourneysV1Content(page(home, horror), noop, isAdmin = true, restoreId = null) }
    }

    @Test
    fun heroCommunityListLongTitle() {
        shot("lt-2-hero-comedy-community", steps = {
            compose.onNode(hasTestTag("jv-play")).requestFocus()
            advance(300)
        }, measure = {
            inSafe("lt-2-hero-comedy-community", "kicker", hasTestTag("jv-kicker"))
            inSafe("lt-2-hero-comedy-community", "credit", hasTestTag("jv-why"))
            assertEquals(1, count(hasText("Source: Letterboxd · TOP 100 COMEDY by Scott Derrickson")))
        }) { JourneysV1Content(page(home, comedy), noop, isAdmin = true, restoreId = null) }
    }

    @Test
    fun rowsWithLetterboxdCards() {
        shot("lt-3-rows", steps = {
            compose.onNode(hasTestTag("jv-list")).performScrollToKey("row:Start something new")
            advance(400)
            compose.onAllNodes(hasTestTag("jv-card") and (hasText("Letterboxd Top 100: Horror") or hasAnyDescendant(hasText("Letterboxd Top 100: Horror")))).onFirst().requestFocus()
            advance(600)
        }, measure = {
            assertEquals(1, count(hasText("Start something new")))
            println("shot lt-3-rows: cards=${count(hasTestTag("jv-card"))}")
        }) { JourneysV1Content(page(home, horror), noop, isAdmin = true, restoreId = null) }
    }

    @Test
    fun pathOfficialList() {
        shot("lt-4-path-horror", measure = {
            inSafe("lt-4-path-horror", "steps", hasTestTag("jv-steps"))
            assertEquals(1, count(hasText("Tonight")))
        }) {
            JourneyV1Content(
                state = JvJourneyState(load = JvLoad.Ok(horror)),
                isAdmin = true,
                restoreStep = null,
                onPlay = { _, _ -> },
                onGet = { _, _ -> },
                onAction = { _, _ -> },
                onFocusStep = {},
                onRetry = {},
            )
        }
    }

    @Test
    fun pathCommunityList() {
        shot("lt-5-path-comedy-community", measure = {
            inSafe("lt-5-path-comedy-community", "steps", hasTestTag("jv-steps"))
        }) {
            JourneyV1Content(
                state = JvJourneyState(load = JvLoad.Ok(comedy)),
                isAdmin = true,
                restoreStep = null,
                onPlay = { _, _ -> },
                onGet = { _, _ -> },
                onAction = { _, _ -> },
                onFocusStep = {},
                onRetry = {},
            )
        }
    }
}
