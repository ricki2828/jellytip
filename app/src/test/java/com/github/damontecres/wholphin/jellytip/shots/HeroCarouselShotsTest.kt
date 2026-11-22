package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.jellytip.home.JellytipHeroActionButtons
import com.github.damontecres.wholphin.jellytip.home.JellytipHeroCarousel
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.iptv.FxChannel
import com.github.damontecres.wholphin.jellytip.iptv.FxItem
import com.github.damontecres.wholphin.jellytip.iptv.HeroMatchBackdrop
import com.github.damontecres.wholphin.jellytip.iptv.HeroMatchHeader
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Home hero carousel (Ricki 22-Nov): his live game as a slide of the normal hero (full-bleed art + team colours, text
 * bottom left, the hero's own button row with the "1 / 3" pager), and the arrows stepping through the slides with the
 * real button row: Right on the last button = next (wraps at the end), Left on the first button of the first slide is
 * not taken. Synthetic art. The film slides need the Hilt hero, so the rotation uses three game slides.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class HeroCarouselShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val now = 1_800_000_000L

    private val photo by lazy {
        val b = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 300f, 0xFFE8F4FF.toInt(), 0xFF8CC4F0.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 960f, 300f, p)
        p.shader = LinearGradient(0f, 300f, 0f, 540f, 0xFF5DBB4A.toInt(), 0xFF2E7D32.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 300f, 960f, 540f, p)
        p.shader = null
        p.color = 0xFFFAFAFA.toInt()
        c.drawRect(60f, 330f, 600f, 470f, p) // a white stand right behind the text block
        b.asImage()
    }

    private val badge by lazy {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFC72C.toInt()
        c.drawCircle(48f, 48f, 44f, p)
        b.asImage()
    }

    private fun fx(
        fid: String,
        home: String,
        away: String,
        hc: String?,
        ac: String?,
        art: String?,
        badge: String? = null,
        league: String = "Rugby Test",
    ) = FxItem(
        fid, league, "rugby", "rugby", home, away, null, null, home.take(3).uppercase(), away.take(3).uppercase(), null, null,
        now - 1200, "in", null, null, 1.0, 1.0, true, false,
        listOf(FxChannel("c1", "Sky Sport 1", null, null, null, 1.0, false, false, now - 1800, now + 5400, true, null)),
        "c1", null, null, "m$fid",
        homeColor = hc,
        awayColor = ac,
        art = art,
        badge = badge,
    )

    private val slides =
        listOf(
            fx("1", "New Zealand", "England", "#000000", "#ffffff", "https://h/v1/iptv/img/art/1.sig"),
            fx("2", "Crusaders", "Blues", "#c8102e", "#0b2265", "https://h/v1/iptv/img/art/2.sig", "https://h/badge/9", league = "Super Rugby"),
            fx("3", "Gold Coast", "Storm", "#ffd100", "#632390", null, league = "NRL"),
        )

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        val handler = AsyncImagePreviewHandler { request -> if ("badge" in request.data.toString()) badge else photo }
        CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides handler) {
            MaterialTheme(colorScheme = darkColorScheme()) { content() }
        }
    }

    /** The hero layer as Home draws a game slide: backdrop, text block, the shared button row with the carousel arrows. */
    @Composable
    private fun Carousel(
        start: Int,
        first: FocusRequester,
        onIndex: (Int) -> Unit,
    ) {
        var index by remember { mutableIntStateOf(start) }
        onIndex(index)
        val f = slides[index]
        Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
            HeroMatchBackdrop(f, motion = false, modifier = Modifier.fillMaxWidth().fillMaxHeight(JellytipHomeTokens.HERO_HEIGHT_FRACTION))
            Box(Modifier.padding(start = 48.dp)) {
                HeroMatchHeader(f, now)
                JellytipHeroActionButtons(
                    buttons = listOf("▶  Watch" to {}, "From kick-off" to {}, "More" to {}),
                    soundOn = false,
                    firstRequester = first,
                    onEdge = { right ->
                        val to = JellytipHeroCarousel.arrow(index, slides.size, right)
                        if (to != null) index = to
                        to != null
                    },
                    pager = "‹  ${index + 1} / ${slides.size}  ›",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 6.dp),
                )
            }
        }
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    @Test
    fun sportSlideInTheHero() {
        val first = FocusRequester()
        compose.setContent { Tv { Carousel(0, first) {} } }
        compose.runOnIdle { first.requestFocus() }
        settle()
        compose.onRoot().captureRoboImage(out("hero-sport-slide"))
    }

    @Test
    fun sportSlideWithoutArt() {
        val first = FocusRequester()
        compose.setContent { Tv { Carousel(2, first) {} } }
        settle()
        compose.onRoot().captureRoboImage(out("hero-sport-slide-noart"))
    }

    @Test
    fun focusOnAMiddleButtonSurvivesAShorterSlide() {
        // A game slide (Watch / From kick-off / More) turns into a film slide (Play / More info) under focus on "More":
        // focus moves to the last button left, not into nowhere
        val first = FocusRequester()
        var game by mutableStateOf(true)
        compose.setContent {
            Tv {
                Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
                    JellytipHeroActionButtons(
                        buttons =
                            if (game) {
                                listOf("▶  Watch" to {}, "From kick-off" to {}, "More" to {})
                            } else {
                                listOf("▶  Play" to {}, "More info" to {})
                            },
                        soundOn = false,
                        firstRequester = first,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        settle()
        repeat(2) { press(Key.DirectionRight) }
        compose.onNodeWithText("More").assertIsFocused()
        game = false
        settle()
        compose.onNodeWithText("More info").assertIsFocused()
        // Same place, other label: focus stays put
        game = true
        settle()
        compose.onNodeWithText("From kick-off").assertIsFocused()
    }

    @Test
    fun arrowsRotateTheCarousel() {
        val first = FocusRequester()
        var index = -1
        compose.setContent { Tv { Carousel(0, first) { index = it } } }
        compose.runOnIdle { first.requestFocus() }
        settle()
        // Left on the first button of the first slide: not taken by the carousel (Home's old Left stands)
        press(Key.DirectionLeft)
        assertEquals(0, index)
        compose.runOnIdle { first.requestFocus() }
        settle()
        // Watch -> From kick-off -> More -> Sound: the buttons' own Right; Right on Sound = the next slide
        repeat(3) { press(Key.DirectionRight) }
        assertEquals(0, index)
        compose.onRoot().captureRoboImage(out("hero-carousel-arrow-1-on-sound"))
        press(Key.DirectionRight)
        assertEquals(1, index)
        compose.onRoot().captureRoboImage(out("hero-carousel-arrow-2"))
        // Focus stays on Sound (keyed): Right again, then wrap from the last slide to the first
        press(Key.DirectionRight)
        assertEquals(2, index)
        compose.onRoot().captureRoboImage(out("hero-carousel-arrow-3"))
        press(Key.DirectionRight)
        assertEquals(0, index)
        compose.onRoot().captureRoboImage(out("hero-carousel-arrow-wrap"))
        // On slide 2, back along the row to Watch (the buttons' own Left), then Left on Watch = the previous slide
        press(Key.DirectionRight)
        assertEquals(1, index)
        repeat(3) { press(Key.DirectionLeft) }
        assertEquals(1, index)
        press(Key.DirectionLeft)
        assertEquals(0, index)
        compose.onRoot().captureRoboImage(out("hero-carousel-arrow-left-back"))
    }
}
