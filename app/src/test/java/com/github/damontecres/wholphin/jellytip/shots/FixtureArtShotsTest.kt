package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.iptv.ColourEdgeFixtureTile
import com.github.damontecres.wholphin.jellytip.iptv.FxChannel
import com.github.damontecres.wholphin.jellytip.iptv.FxItem
import com.github.damontecres.wholphin.jellytip.iptv.LiveMatchHero
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Fixture art (FixtureArt) on the Game Centre / Home hero and tiles, with synthetic photos: a bright stadium-like
 * picture (the hard case for contrast) and a small badge. "broken" art = the photo failed: today's look. For eyes.
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class FixtureArtShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val now = 1_800_000_000L

    /** Bright sky over a green pitch with white lines: close to the worst case behind white text. */
    private val photo by lazy {
        val b = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 300f, 0xFFE8F4FF.toInt(), 0xFF8CC4F0.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 960f, 300f, p)
        p.shader = LinearGradient(0f, 300f, 0f, 540f, 0xFF5DBB4A.toInt(), 0xFF2E7D32.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 300f, 960f, 540f, p)
        p.shader = null
        p.color = 0xFFFFFFFF.toInt()
        p.strokeWidth = 8f
        c.drawLine(480f, 300f, 480f, 540f, p)
        c.drawLine(0f, 420f, 960f, 420f, p)
        p.color = 0xFFFAFAFA.toInt()
        c.drawRect(120f, 140f, 840f, 230f, p) // a white stand right behind the title
        b.asImage()
    }

    private val badge by lazy {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFC72C.toInt()
        c.drawCircle(48f, 48f, 44f, p)
        p.color = 0xFF0B2265.toInt()
        c.drawCircle(48f, 48f, 26f, p)
        b.asImage()
    }

    private fun ch(id: String) = FxChannel(id, "Sky Sport 1", null, null, null, 1.0, false, false, now - 1800, now + 5400, true, null)

    private fun fx(
        fid: String,
        home: String,
        away: String,
        hc: String?,
        ac: String?,
        art: String?,
        badge: String? = null,
        status: String = "in",
        league: String = "Rugby Test",
    ) = FxItem(
        fid, league, "rugby", "rugby", home, away, null, null, home.take(3).uppercase(), away.take(3).uppercase(), null, null,
        now - 1200, status, null, null, 1.0, 1.0, true, false, listOf(ch("c1")), "c1", null, null, "m$fid",
        homeColor = hc,
        awayColor = ac,
        art = art,
        badge = badge,
    )

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        val handler =
            AsyncImagePreviewHandler { request ->
                val d = request.data.toString()
                when {
                    "broken" in d -> null
                    "badge" in d -> badge
                    else -> photo
                }
            }
        CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides handler) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(JellytipPalette.Night).padding(24.dp)) { content() }
            }
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
    fun heroWithArt() =
        shot("fixture-art-hero") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Rugby Test: art, no badge; black v white both fail the guard -> neutral team colours
                LiveMatchHero(fx("1", "New Zealand", "England", "#000000", "#ffffff", "https://h/v1/iptv/img/art/1.sig"), now, {}, {}, {}, Modifier.width(900.dp), motion = false)
                // Club game: art + league badge, red v blue
                LiveMatchHero(
                    fx("2", "Crusaders", "Blues", "#c8102e", "#0b2265", "https://h/v1/iptv/img/art/2.sig", "https://h/v1/iptv/img/badge/9.sig", league = "Super Rugby"),
                    now,
                    {},
                    {},
                    {},
                    Modifier.width(900.dp),
                    motion = false,
                )
            }
        }

    @Test
    fun tilesWithArt() =
        shot("fixture-art-tiles") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ColourEdgeFixtureTile(fx("3", "Crusaders", "Blues", "#c8102e", "#0b2265", "https://h/a/3"), now, reminder = false, revealed = false, onClick = {})
                    ColourEdgeFixtureTile(fx("4", "Gold Coast", "Storm", "#ffd100", "#632390", "https://h/a/4", status = "pre"), now, reminder = true, revealed = false, onClick = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Art, no team colours (event)
                    ColourEdgeFixtureTile(fx("5", "Monaco GP", "", null, null, "https://h/a/5", status = "pre"), now, reminder = false, revealed = false, onClick = {})
                    // No art: today's look
                    ColourEdgeFixtureTile(fx("6", "Crusaders", "Blues", "#c8102e", "#0b2265", null, status = "pre"), now, reminder = false, revealed = false, onClick = {})
                }
            }
        }

    @Test
    fun brokenArtFallsBackToTodaysLook() =
        shot("fixture-art-broken") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LiveMatchHero(fx("7", "Crusaders", "Blues", "#c8102e", "#0b2265", "https://h/broken/7"), now, {}, {}, {}, Modifier.width(900.dp), motion = false)
                ColourEdgeFixtureTile(fx("8", "Crusaders", "Blues", "#c8102e", "#0b2265", "https://h/broken/8", status = "pre"), now, reminder = false, revealed = false, onClick = {})
            }
        }
}
