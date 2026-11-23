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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.jellytip.channels.ChanItem
import com.github.damontecres.wholphin.jellytip.channels.ChanType
import com.github.damontecres.wholphin.jellytip.channels.ChannelDef
import com.github.damontecres.wholphin.jellytip.channels.ChannelRow
import com.github.damontecres.wholphin.jellytip.channels.MyChannelCard
import com.github.damontecres.wholphin.jellytip.channels.Sched
import com.github.damontecres.wholphin.jellytip.channels.Slot
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.specials.JellytipHomeEntry
import com.github.damontecres.wholphin.jellytip.specials.jellytipHomeEntries
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
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
 * Home's new row order (Ricki 23-Nov via t3x): hero → Continue watching → My Channels → Next up → the rest. The list order comes
 * from the real jellytipHomeEntries(); the My Channels tiles are the real MyChannelCard (synthetic channels and art), the other
 * rows are labelled placeholders. 960x540 dp, keyboard input mode, the first channel focused.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class MyChannelsRowShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private val keyboardMode =
        object : InputModeManager {
            override val inputMode = InputMode.Keyboard

            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }

    private val art by lazy {
        val b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 640f, 360f, 0xFF2B3A55.toInt(), 0xFFB5652B.toInt(), Shader.TileMode.CLAMP)
        Canvas(b).drawRect(0f, 0f, 640f, 360f, p)
        b.asImage()
    }

    private val now = 1_800_000_000_000L

    private fun chan(
        n: Int,
        name: String,
        now1: String,
        sub: String?,
        next: String,
    ): ChannelRow {
        val a = ChanItem(id = "a$n", name = now1, year = sub?.toIntOrNull())
        val b = ChanItem(id = "b$n", name = next)
        return ChannelRow(
            ChannelDef("c$n", name, "", ChanType.MOVIE),
            100 + n,
            Sched(listOf(Slot(a, now - 40 * 60_000L, now + 23 * 60_000L), Slot(b, now + 23 * 60_000L, now + 140 * 60_000L)), 2400, 0.6),
        )
    }

    private val channels =
        listOf(
            chan(1, "90s Action", "Heat", "1995", "Point Break"),
            chan(2, "Sunday Sitcoms", "The Office", null, "Parks and Recreation"),
            chan(3, "Late Night Thrillers", "Collateral", "2004", "Drive"),
        )

    @Composable
    private fun Placeholder(title: String) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemRowTitle(title)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { Box(Modifier.width(200.dp).height(112.dp).background(Color(0xFF2A2A2A), RoundedCornerShape(10.dp))) }
            }
        }
    }

    @Test
    fun homeOrderWithMyChannelsUnderContinueWatching() {
        val rows =
            listOf(
                HomeRowLoadingState.Success(StringStringProvider("Continue watching"), listOf(null), rowType = HomeRowConfig.ContinueWatching()),
                HomeRowLoadingState.Success(StringStringProvider("Next up"), listOf(null), rowType = HomeRowConfig.NextUp()),
                HomeRowLoadingState.Success(StringStringProvider("Recently added films"), listOf(null)),
            )
        val entries = jellytipHomeEntries(rows, emptyList(), leadingRows = 2, myChannels = true)
        assertEquals(listOf("row-0", "my-channels", "row-1", "row-2"), entries.map { it.key })
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { art },
                LocalInputModeManager provides keyboardMode,
            ) {
                MaterialTheme(colorScheme = com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors.darkScheme) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 72.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("HERO", color = JellytipPalette.Vanilla.copy(alpha = 0.5f), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            entries.forEach { e ->
                                when (e) {
                                    is JellytipHomeEntry.MyChannels ->
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ItemRowTitle("My Channels")
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                                items(channels, key = { it.def.id }) { r ->
                                                    MyChannelCard(r, now, art = "https://x/w1280.jpg", onClick = {}, onLongClick = {})
                                                }
                                            }
                                        }
                                    is JellytipHomeEntry.Row -> Placeholder((rows[e.index] as HomeRowLoadingState.Success).title.let { (it as StringStringProvider).str })
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onAllNodes(hasClickAction() and hasText("101")).onFirst().requestFocus()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home-my-channels-order.png")
    }
}
