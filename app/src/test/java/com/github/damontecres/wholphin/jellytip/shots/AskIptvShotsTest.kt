package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.JellytipRowBleed
import com.github.damontecres.wholphin.jellytip.LocalJellytipRowBleed
import com.github.damontecres.wholphin.jellytip.ask.AskPicksGrid
import com.github.damontecres.wholphin.jellytip.ask.AskTile
import com.github.damontecres.wholphin.jellytip.cards.JellytipContinueInfo
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge
import com.github.damontecres.wholphin.jellytip.cards.JellytipTech
import com.github.damontecres.wholphin.jellytip.cards.JellytipTileTags
import com.github.damontecres.wholphin.jellytip.cards.JellytipTitleSources
import com.github.damontecres.wholphin.jellytip.cards.LocalJellytipTileInfo
import com.github.damontecres.wholphin.jellytip.cards.libRef
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTheme
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.LocalJellytipHome
import com.github.damontecres.wholphin.jellytip.home.jellytipRowOptions
import com.github.damontecres.wholphin.jellytip.iptv.IPTV_CORNER_MARK_TAG
import com.github.damontecres.wholphin.jellytip.iptv.IptvProgress
import com.github.damontecres.wholphin.jellytip.iptv.IptvResume
import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.jellytip.iptv.IptvTileMenu
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodRowContent
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile
import com.github.damontecres.wholphin.jellytip.iptv.JellytipIptvVodViewModel
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsService
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRowView
import com.github.damontecres.wholphin.jellytip.search.SearchAnswer
import com.github.damontecres.wholphin.jellytip.search.SearchResult
import com.github.damontecres.wholphin.jellytip.specials.PosterCard
import com.github.damontecres.wholphin.jellytip.watchlist.JellytipWatchlistMixedRow
import com.github.damontecres.wholphin.jellytip.watchlist.mergeWatchlist
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.main.HomePageCardContent
import com.github.damontecres.wholphin.ui.nav.CollapsedDrawerItemWidth
import com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Ask's picks grid with IPTV (source-neutral): library posters, then IPTV tiles drawn the same way, one tile per title (an
 * IPTV twin of a library pick never reaches the grid: JellytipSourceMerge in the view model). Synthetic art. For eyes.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class AskIptvShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private val photo by lazy {
        val b = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 480f, 270f, 0xFF3A6EA5.toInt(), 0xFFB5651D.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 480f, 270f, p)
        b.asImage()
    }

    private val imageUrlService by lazy {
        val api = mockk<ApiClient>()
        every { api.baseUrl } returns "http://localhost"
        every { api.createUrl(any(), any(), any(), any()) } answers { callOriginal() }
        every { api.imageApi } returns ImageApi(api)
        ImageUrlService(api)
    }

    private val keyboardMode =
        object : InputModeManager {
            override val inputMode = InputMode.Keyboard

            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray())

    private fun lib(
        name: String,
        year: Int,
        watched: Boolean = false,
    ): BaseItem {
        val id = uuid("movie:$name")
        return BaseItem(
            BaseItemDto(
                id = id,
                type = BaseItemKind.MOVIE,
                name = name,
                productionYear = year,
                runTimeTicks = 100 * 600_000_000L,
                imageTags = mapOf(ImageType.PRIMARY to "poster"),
                userData =
                    UserItemDataDto(
                        playedPercentage = null,
                        playbackPositionTicks = 0L,
                        playCount = if (watched) 1 else 0,
                        isFavorite = false,
                        lastPlayedDate = null,
                        played = watched,
                        key = "",
                        itemId = id,
                    ),
            ),
        )
    }

    private fun vod(
        name: String,
        year: Int,
        favourite: Boolean = false,
    ) = IptvVodItem(
        id = "vod-${name.lowercase().replace(' ', '-')}",
        kind = "movie",
        tmdbId = null,
        title = name,
        year = year,
        quality = listOf("4k"),
        poster = "https://h/p/$name",
        backdrop = "https://h/b/$name",
        overview = "",
        rating = 7.4,
        providerName = "Provider",
        progress = null,
        favourite = favourite,
        genreIds = listOf(10751, 12),
    )

    @Test
    fun mixedGrid() {
        val tiles =
            listOf(AskTile.Lib(lib("Paddington 2", 2017)), AskTile.Lib(lib("Hook", 1991, watched = true)), AskTile.Lib(lib("Jumanji", 1995))) +
                listOf(AskTile.Vod(vod("The Goonies", 1985, favourite = true)), AskTile.Vod(vod("Home Alone", 1990)), AskTile.Vod(vod("Babe", 1995)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val handler = AsyncImagePreviewHandler { _ -> photo }
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides handler,
                LocalImageUrlService provides imageUrlService,
                LocalInputModeManager provides keyboardMode,
            ) {
                MaterialTheme(colorScheme = PurpleThemeColors.darkScheme) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 96.dp, top = 40.dp)) {
                            AskPicksGrid(
                                tiles = tiles,
                                gridState = rememberLazyGridState(),
                                restoreKey = null,
                                restore = remember { FocusRequester() },
                                onOpenLibrary = {},
                                onOpenIptv = {},
                                onHoldLibrary = {},
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/ask-iptv-mixed-grid.png")
        // Six titles, six tiles (library then IPTV), keyed by source
        assertEquals(listOf("l:", "l:", "l:", "v:", "v:", "v:"), tiles.map { it.key.take(2) })
    }
}
