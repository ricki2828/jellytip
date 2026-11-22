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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
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
import com.github.damontecres.wholphin.jellytip.cards.JellytipTech
import com.github.damontecres.wholphin.jellytip.cards.JellytipTileTags
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTheme
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.jellytip.home.LocalJellytipHome
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
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
import com.github.damontecres.wholphin.jellytip.cards.JellytipContinueInfo
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge
import com.github.damontecres.wholphin.jellytip.cards.LocalJellytipTileInfo
import com.github.damontecres.wholphin.jellytip.cards.libRef
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.main.HomePageCardContent
import com.github.damontecres.wholphin.ui.nav.CollapsedDrawerItemWidth
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import java.util.UUID

/*
 * Source-neutral shots of how a LIBRARY tile and an IPTV tile of the SAME synthetic titles look. Commit 0 shot today's state
 * (no asserts); commit 1 ("one shared tile") keeps the same shot names so before / after pair up and now ASSERTS (row-tiles,
 * poster-tiles, continue, series-pills): the IPTV tile has the library tile's size, its badges (tick, heart, progress bar,
 * corner pill, 4K / Atmos pills, title) at the same offsets inside the tile, and no IPTV corner mark. Bounds also go to the CI
 * log as "shot <name>: ..." lines. Harness as Audit3ShotsTest:
 * Robolectric + Roborazzi, sdk 34, w960dp-h540dp-land-xhdpi (a 1920x1080 TV at 2x), app theme + tv-material Surface, keyboard
 * input mode, fake image handler (every image is the same synthetic photo), full 960x540 dp frame with magenta safe-area
 * guides, the 72 dp start / 16 dp end page inset DestinationContent gives pages.
 *
 * Titles: Heat, Collateral, Ronin. State per tile index in the 3-tile shots: 0 plain, 1 watched, 2 in progress 40% + in My list
 * (favourite) + 4K + Atmos (tiles 0 and 1: 4K only). The library side gets its 4K / Atmos pills through JellytipTech.set (what
 * the companion's payloads would have supplied); the IPTV side through IptvVodItem.quality / audio.
 *
 * What is real: HomePageCardContent (BannerCard / BannerCardWithTitle), PosterCard, IptvVodTile, ItemRow, JellytipServerRowView
 * (Search library row, mixed row), ContextMenu (library) and IptvTileMenu. Reconstructions are named in each test.
 * Not drawn: library tiles with a Thumb image (the title is baked into that art) or a clearlogo: the synthetic items have a
 * backdrop only, so the library tile shows its "backdrop + title text" path.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class SourceNeutralShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    // ---- harness (as Audit3ShotsTest) ----

    private val photo by lazy {
        val b = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 480f, 270f, 0xFF3A6EA5.toInt(), 0xFFB5651D.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 480f, 270f, p)
        p.shader = null
        p.color = 0xFFEFE6D5.toInt()
        c.drawCircle(240f, 120f, 60f, p)
        p.color = 0xFF1B1B1B.toInt()
        c.drawRect(0f, 200f, 480f, 270f, p)
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

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        val handler = AsyncImagePreviewHandler { _ -> photo }
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides handler,
            LocalImageUrlService provides imageUrlService,
            // A TV is never in touch mode; Robolectric starts in it
            LocalInputModeManager provides keyboardMode,
        ) {
            MaterialTheme(colorScheme = PurpleThemeColors.darkScheme) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** The full 960x540 dp frame; [inset] = the page inset (72 dp start, 16 dp end). Magenta safe-area guides on top. */
    @Composable
    private fun Frame(
        inset: Boolean = true,
        content: @Composable BoxScope.() -> Unit,
    ) {
        val safe = Color(0xFFFF00FF)
        Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
            Box(Modifier.fillMaxSize().padding(start = if (inset) 72.dp else 0.dp, end = if (inset) 16.dp else 0.dp)) { content() }
            Box(Modifier.offset(x = 48.dp).fillMaxHeight().width(1.dp).background(safe))
            Box(Modifier.offset(x = 912.dp).fillMaxHeight().width(1.dp).background(safe))
            Box(Modifier.offset(y = 27.dp).fillMaxWidth().height(1.dp).background(safe))
            Box(Modifier.offset(y = 513.dp).fillMaxWidth().height(1.dp).background(safe))
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun logFocus(name: String) {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        val texts = nodes.map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { t -> t.text } ?: "(no text)" }
        println("shot $name: focused nodes=${nodes.size} texts=$texts")
    }

    /** Focus the [index]-th clickable node in tree order (a tile); a failure is only logged, the shot is still worth having. */
    private fun focusClickable(
        name: String,
        index: Int,
    ) {
        runCatching { compose.onAllNodes(hasClickAction())[index].requestFocus() }
            .onFailure { println("shot $name: focus on clickable #$index not set (${it.message?.lineSequence()?.firstOrNull()})") }
        advance(300)
    }

    /** Focus the first node [matcher] finds (a dialog item). */
    private fun focusNode(
        name: String,
        matcher: SemanticsMatcher,
    ) {
        runCatching { compose.onAllNodes(matcher).onFirst().requestFocus() }
            .onFailure { println("shot $name: focus not set (${it.message?.lineSequence()?.firstOrNull()})") }
        advance(300)
    }

    /** Bounds (dp from the screen's top-left, unmerged tree) of EVERY node [matcher] finds, to the CI log. */
    private fun logAll(
        name: String,
        what: String,
        matcher: SemanticsMatcher,
    ) {
        runCatching {
            val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
            val n = nodes.fetchSemanticsNodes().size
            for (i in 0 until n) {
                val b = nodes[i].getUnclippedBoundsInRoot()
                println(
                    "shot $name: $what #$i left=${b.left.value} right=${b.right.value} top=${b.top.value} bottom=${b.bottom.value} " +
                        "w=${b.right.value - b.left.value} h=${b.bottom.value - b.top.value}",
                )
            }
            if (n == 0) println("shot $name: $what none found")
        }.onFailure { println("shot $name: $what not measured (${it.message?.lineSequence()?.firstOrNull()})") }
    }

    /** Tiles (clickable nodes) and every badge text the two tile kinds draw: IPTV "4K" mark / "✓" tick / heart, library "4K" pill / heart. */
    private fun measureTiles(name: String) {
        val heart = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.fa_heart)
        logAll(name, "tile", hasClickAction())
        logAll(name, "text 4K (library pill bottom-end, IPTV mark top-end)", hasText("4K", substring = true))
        logAll(name, "text ATMOS", hasText("ATMOS", substring = true))
        logAll(name, "text tick (IPTV only)", hasText("✓"))
        logAll(name, "text heart", hasText(heart))
        logAll(name, "text E5 (library episode pill)", hasText("E5"))
    }

    // ---- asserts (unmerged tree, getUnclippedBoundsInRoot, 0.5 dp tolerance: as Audit3ShotsTest) ----

    private val tol = 0.5f

    private data class Rel(
        val dx: Float,
        val dy: Float,
        val w: Float,
        val h: Float,
    )

    private fun boundsOf(matcher: SemanticsMatcher): List<DpRect> {
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
        val n = nodes.fetchSemanticsNodes().size
        return (0 until n).map { nodes[it].getUnclippedBoundsInRoot() }
    }

    /** The tiles (clickable nodes) in tree order; [expected] of them or the shot's assert fails with a readable message. */
    private fun tilesOf(
        name: String,
        expected: Int,
    ): List<DpRect> {
        val tiles = boundsOf(hasClickAction())
        assertEquals("$name: tile count (clickable nodes)", expected, tiles.size)
        return tiles
    }

    private fun w(r: DpRect) = r.right.value - r.left.value

    private fun h(r: DpRect) = r.bottom.value - r.top.value

    /** Each (library, IPTV) pair of tiles has the same width and height. */
    private fun assertSameSizes(
        name: String,
        tiles: List<DpRect>,
        pairs: List<Pair<Int, Int>>,
    ) {
        for ((l, i) in pairs) {
            println("shot $name: tile $l vs $i size ${w(tiles[l])}x${h(tiles[l])} vs ${w(tiles[i])}x${h(tiles[i])}")
            assertEquals("$name: tile $i width = library tile $l", w(tiles[l]), w(tiles[i]), tol)
            assertEquals("$name: tile $i height = library tile $l", h(tiles[l]), h(tiles[i]), tol)
        }
    }

    /** Nodes [matcher] finds inside [tile], as offsets from the tile's top-left (and their size), top-to-bottom then left-to-right. */
    private fun insideTile(
        tile: DpRect,
        matcher: SemanticsMatcher,
    ): List<Rel> =
        boundsOf(matcher)
            .filter { b ->
                val cx = (b.left.value + b.right.value) / 2
                val cy = (b.top.value + b.bottom.value) / 2
                cx >= tile.left.value && cx <= tile.right.value && cy >= tile.top.value && cy <= tile.bottom.value
            }.map { b -> Rel(b.left.value - tile.left.value, b.top.value - tile.top.value, w(b), h(b)) }
            .sortedWith(compareBy({ it.dy }, { it.dx }))

    /**
     * For each (library, IPTV) pair: [matcher]'s nodes sit in the same number, at the same offsets, with the same size, in
     * both tiles (none in both is fine: "state without it"). [what] names the badge in the failure message.
     */
    private fun assertSameInside(
        name: String,
        what: String,
        tiles: List<DpRect>,
        pairs: List<Pair<Int, Int>>,
        matcher: SemanticsMatcher,
    ) {
        for ((l, i) in pairs) {
            val lib = insideTile(tiles[l], matcher)
            val iptv = insideTile(tiles[i], matcher)
            println("shot $name: $what tile $l (library) $lib vs tile $i (IPTV) $iptv")
            assertEquals("$name: $what count in tile $i vs library tile $l", lib.size, iptv.size)
            lib.zip(iptv).forEach { (a, b) ->
                assertEquals("$name: $what dx tile $i vs $l", a.dx, b.dx, tol)
                assertEquals("$name: $what dy tile $i vs $l", a.dy, b.dy, tol)
                assertEquals("$name: $what width tile $i vs $l", a.w, b.w, tol)
                assertEquals("$name: $what height tile $i vs $l", a.h, b.h, tol)
            }
        }
    }

    /** At least one of the pairs has the badge (so "same absence everywhere" cannot pass vacuously). */
    private fun assertSomewhere(
        name: String,
        what: String,
        matcher: SemanticsMatcher,
        atLeast: Int,
    ) {
        val n = boundsOf(matcher).size
        assertTrue("$name: $what found $n times, expected at least $atLeast (is the state not drawn at all?)", n >= atLeast)
    }

    /** No IPTV corner mark and no "●" anywhere in the frame. */
    private fun assertNoCornerMark(name: String) {
        compose.onAllNodes(hasTestTag(IPTV_CORNER_MARK_TAG), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("●", substring = true), useUnmergedTree = true).assertCountEquals(0)
        println("shot $name: no IPTV corner mark")
    }

    /** The shared badge checks for tiles in the same order in both rows: library tiles first, IPTV tiles after. */
    private fun assertBadgesMatch(
        name: String,
        tiles: List<DpRect>,
        pairs: List<Pair<Int, Int>>,
        titles: List<String> = emptyList(),
    ) {
        assertSameSizes(name, tiles, pairs)
        assertSameInside(name, "watched tick", tiles, pairs, hasTestTag(JellytipTileTags.TICK))
        assertSameInside(name, "heart", tiles, pairs, hasTestTag(JellytipTileTags.HEART))
        assertSameInside(name, "progress bar", tiles, pairs, hasTestTag(JellytipTileTags.BAR))
        assertSameInside(name, "corner pill", tiles, pairs, hasTestTag(JellytipTileTags.PILL))
        assertSameInside(name, "4K pill", tiles, pairs, hasText("4K"))
        assertSameInside(name, "ATMOS pill", tiles, pairs, hasText("ATMOS"))
        titles.forEach { assertSameInside(name, "title '$it'", tiles, pairs, hasText(it)) }
        assertNoCornerMark(name)
    }

    /** Render [content] in the [Frame], settle, run [steps] (focus), settle again, capture the whole screen, then measure. */
    private fun shot(
        name: String,
        inset: Boolean = true,
        steps: () -> Unit = {},
        measure: () -> Unit = { measureTiles(name) },
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Tv { Frame(inset) { content() } } }
        advance(1200)
        steps()
        advance(1800)
        logFocus(name)
        compose.onRoot().captureRoboImage(out(name))
        measure()
    }

    @Composable
    private fun Cap(text: String) = Text(text, color = Color(0xFF00FFFF), fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))

    /** Home's look: the Jellytip home theme (LocalJellytipHome = true) and the row bleed HomePageContent provides. */
    @Composable
    private fun HomeStyle(content: @Composable () -> Unit) {
        val bleed = JellytipRowBleed(CollapsedDrawerItemWidth + 8.dp + JellytipLayout.RowPageStart, 16.dp)
        JellytipHomeTheme { CompositionLocalProvider(LocalJellytipRowBleed provides bleed) { content() } }
    }

    // ---- synthetic data ----

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray())

    private fun minutes(m: Long) = m * 600_000_000L

    private class Ttl(
        val name: String,
        val year: Int,
        val tmdb: Int,
    )

    private val ttls = listOf(Ttl("Heat", 1995, 949), Ttl("Collateral", 2004, 1538), Ttl("Ronin", 1998, 1000))

    /** A library film in [state] (0 plain, 1 watched, 2 in progress 40% + 4K/Atmos); [favourite] = in My list. */
    private fun libFilm(
        t: Ttl,
        state: Int,
        favourite: Boolean = state == 2,
    ): BaseItem {
        val id = uuid("movie:${t.name}")
        JellytipTech.set(id, if (state == 2) listOf("4k", "atmos") else listOf("4k"))
        return BaseItem(
            BaseItemDto(
                id = id,
                type = BaseItemKind.MOVIE,
                name = t.name,
                productionYear = t.year,
                runTimeTicks = minutes(130),
                providerIds = mapOf("Tmdb" to t.tmdb.toString()),
                // Heat has a clearlogo (logo over the backdrop); Collateral and Ronin do not (title text set in the art)
                imageTags = if (t.name == "Heat") mapOf(ImageType.PRIMARY to "poster", ImageType.LOGO to "logo") else mapOf(ImageType.PRIMARY to "poster"),
                backdropImageTags = listOf("backdrop"),
                userData =
                    UserItemDataDto(
                        playedPercentage = if (state == 2) 40.0 else null,
                        playbackPositionTicks = if (state == 2) minutes(52) else 0L,
                        playCount = if (state == 1) 1 else 0,
                        isFavorite = favourite,
                        lastPlayedDate = null,
                        played = state == 1,
                        key = "",
                        itemId = id,
                    ),
            ),
        )
    }

    private fun seriesTmdb(name: String) = if (name == "Slow Horses") 95_000 else 95_001

    private fun libSeries(
        name: String,
        unplayed: Int,
    ): BaseItem {
        val id = uuid("series:$name")
        JellytipTech.set(id, listOf("4k"))
        return BaseItem(
            BaseItemDto(
                id = id,
                type = BaseItemKind.SERIES,
                name = name,
                productionYear = 2022,
                providerIds = mapOf("Tmdb" to seriesTmdb(name).toString()),
                imageTags = mapOf(ImageType.PRIMARY to "poster"),
                backdropImageTags = listOf("backdrop"),
                userData =
                    UserItemDataDto(
                        playedPercentage = null,
                        unplayedItemCount = unplayed,
                        playbackPositionTicks = 0L,
                        playCount = 0,
                        isFavorite = true,
                        lastPlayedDate = null,
                        played = false,
                        key = "",
                        itemId = id,
                    ),
            ),
        )
    }

    /** The Continue Watching library episode: Slow Horses S2 E5, 40% watched. */
    private fun libEpisode(): BaseItem {
        val id = uuid("episode:Slow Horses:2:5")
        val seriesId = uuid("series:Slow Horses")
        JellytipTech.set(seriesId, listOf("4k"))
        return BaseItem(
            BaseItemDto(
                id = id,
                type = BaseItemKind.EPISODE,
                name = "Failure's Contagious",
                seriesName = "Slow Horses",
                seriesId = seriesId,
                indexNumber = 5,
                parentIndexNumber = 2,
                runTimeTicks = minutes(48),
                imageTags = mapOf(ImageType.PRIMARY to "ep"),
                userData =
                    UserItemDataDto(
                        playedPercentage = 40.0,
                        playbackPositionTicks = minutes(19),
                        playCount = 0,
                        isFavorite = false,
                        lastPlayedDate = null,
                        played = false,
                        key = "",
                        itemId = id,
                    ),
            ),
        )
    }

    /** The IPTV copy of [t] in [state] (same meaning as [libFilm]); quality "4k" on all, Atmos on state 2. */
    private fun vodFilm(
        t: Ttl,
        state: Int,
        favourite: Boolean = state == 2,
    ) = IptvVodItem(
        id = "vod-${t.name.lowercase()}",
        kind = "movie",
        tmdbId = t.tmdb,
        title = t.name,
        year = t.year,
        quality = listOf("4k"),
        poster = "https://h/p/${t.name}",
        backdrop = "https://h/b/${t.name}",
        overview = "",
        rating = 7.8,
        providerName = "Provider",
        progress =
            when (state) {
                2 -> IptvProgress(3120.0, 7800.0, false)
                1 -> IptvProgress(7700.0, 7800.0, true)
                else -> null
            },
        tech = listOf("4k"),
        favourite = favourite,
        played = state == 1,
        audio = if (state == 2) "atmos" else null,
        // The server's new optional art fields: Heat has a logo + a text-free backdrop; Collateral and Ronin send none
        logo = if (t.name == "Heat") "https://h/logo/Heat" else null,
        textlessBackdrop = if (t.name == "Heat") "https://h/tl/Heat" else null,
    )

    private fun vodSeries(
        name: String,
        unplayedCount: Int? = null,
        tmdb: Int? = null,
        libraryId: String? = null,
    ) =
        IptvVodItem(
            id = "vod-series-${name.lowercase()}",
            kind = "series",
            tmdbId = tmdb,
            title = name,
            year = 2022,
            quality = listOf("4k"),
            poster = "https://h/p/$name",
            backdrop = "https://h/b/$name",
            overview = "",
            rating = 8.0,
            providerName = "Provider",
            progress = null,
            tech = listOf("4k"),
            favourite = true,
            unplayedCount = unplayedCount,
            libraryId = libraryId,
        )

    private val wide = jellytipRowOptions(HomeRowViewOptions())

    // ---- row builders: the call sites' own composables ----

    /** A library row as Home draws it: ItemRow + HomePageCardContent with the row's options. */
    @Composable
    private fun LibRow(
        title: String,
        items: List<BaseItem>,
        options: HomeRowViewOptions = wide,
    ) {
        ItemRow<BaseItem>(
            title = title,
            items = items,
            onClickItem = { _, _ -> },
            onLongClickItem = { _, _ -> },
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalPadding = options.spacing.dp,
            cardContent = { index, item, cardModifier, onClick, onLongClick ->
                HomePageCardContent(index, item, onClick, onLongClick, options, cardModifier)
            },
        )
    }

    /** A row of the library's 2:3 PosterCard (Ask, hub grids, Top 10, Closet, collection pages), in an ItemRow. */
    @Composable
    private fun PosterRow(
        title: String,
        items: List<BaseItem>,
    ) {
        ItemRow<BaseItem>(
            title = title,
            items = items,
            onClickItem = { _, _ -> },
            onLongClickItem = { _, _ -> },
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalPadding = wide.spacing.dp,
            cardContent = { _, item, cardModifier, onClick, onLongClick ->
                if (item != null) PosterCard(item, onClick, onLongClick, cardModifier)
            },
        )
    }

    /** An IPTV row as IptvVodRow (Search, Watchlist, person page, IPTV-only server rows) draws it. */
    @Composable
    private fun IptvRow(
        title: String,
        items: List<IptvVodItem>,
        poster: Boolean,
        heightDp: Int,
    ) {
        IptvVodRowContent(
            title = title,
            items = items,
            poster = poster,
            heightDp = heightDp,
            onFocusTile = {},
            onOpen = {},
            modifier = Modifier.fillMaxWidth().focusGroup(),
        )
    }

    // ---- 1. Home-style landscape row ----

    @Composable
    private fun RowTilesContent() {
        HomeStyle {
            Column(Modifier.fillMaxSize().padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LibRow("Library: HomePageCardContent (BannerCardWithTitle)", ttls.mapIndexed { i, t -> libFilm(t, i) })
                IptvRow("IPTV: IptvVodTile", ttls.mapIndexed { i, t -> vodFilm(t, i) }, poster = false, heightDp = wide.heightDp)
                Cap("Tiles left to right: plain / watched / 40% in progress + My list + 4K + Atmos. Same three titles in both rows.")
            }
        }
    }

    @Test
    fun rowTiles() =
        shot("srcneutral-row-tiles", measure = {
            measureTiles("srcneutral-row-tiles")
            val n = "srcneutral-row-tiles"
            val tiles = tilesOf(n, 6)
            assertBadgesMatch(n, tiles, listOf(0 to 3, 1 to 4, 2 to 5), titles = listOf("Collateral", "Ronin"))
            // the states the shot is built to show are drawn at all (watched tile 1, favourite + 40% tile 2)
            assertSomewhere(n, "watched tick", hasTestTag(JellytipTileTags.TICK), 2)
            assertSomewhere(n, "heart", hasTestTag(JellytipTileTags.HEART), 2)
            assertSomewhere(n, "progress bar", hasTestTag(JellytipTileTags.BAR), 2)
            assertSomewhere(n, "4K pill", hasText("4K"), 6)
            assertSomewhere(n, "ATMOS pill", hasText("ATMOS"), 2)
        }) { RowTilesContent() }

    @Test
    fun rowTilesFocusLibrary() = shot("srcneutral-row-tiles-focus-library", steps = { focusClickable("srcneutral-row-tiles-focus-library", 0) }) { RowTilesContent() }

    @Test
    fun rowTilesFocusIptv() = shot("srcneutral-row-tiles-focus-iptv", steps = { focusClickable("srcneutral-row-tiles-focus-iptv", 3) }) { RowTilesContent() }

    // ---- 2. 2:3 poster tiles ----

    @Test
    fun posterTiles() =
        shot("srcneutral-poster-tiles", measure = {
            val n = "srcneutral-poster-tiles"
            measureTiles(n)
            val tiles = tilesOf(n, 6)
            assertBadgesMatch(n, tiles, listOf(0 to 3, 1 to 4, 2 to 5))
            assertSomewhere(n, "watched tick", hasTestTag(JellytipTileTags.TICK), 2)
            assertSomewhere(n, "heart", hasTestTag(JellytipTileTags.HEART), 2)
            assertSomewhere(n, "progress bar", hasTestTag(JellytipTileTags.BAR), 2)
        }) {
            HomeStyle {
                Column(Modifier.fillMaxSize().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PosterRow("Library: PosterCard (specials)", ttls.mapIndexed { i, t -> libFilm(t, i) })
                    IptvRow("IPTV: IptvVodTile(poster = true)", ttls.mapIndexed { i, t -> vodFilm(t, i) }, poster = true, heightDp = 150)
                    Cap("Tiles left to right: plain / watched / 40% in progress + My list. Posters carry no 4K pills; both rows draw the same badges.")
                }
            }
        }

    // ---- 3. Continue Watching (a reconstruction of JellytipResumeRow's cardContent: the row itself takes a Hilt view model) ----

    private sealed interface CwTile {
        data class Lib(
            val item: BaseItem,
        ) : CwTile

        data class Iptv(
            val r: IptvResume,
        ) : CwTile
    }

    @Composable
    private fun CwRow(
        title: String,
        tiles: List<CwTile>,
    ) {
        val h = wide.heightDp.dp
        // As JellytipResumeRow: the library tiles of this row draw the info line too
        CompositionLocalProvider(LocalJellytipTileInfo provides JellytipContinueInfo::forLibrary) {
        ItemRow<CwTile>(
            title = title,
            items = tiles,
            onClickItem = { _, _ -> },
            onLongClickItem = { _, _ -> },
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalPadding = wide.spacing.dp,
            cardContent = { index, t, cardModifier, onClick, onLongClick ->
                when (t) {
                    is CwTile.Lib -> HomePageCardContent(index, t.item, onClick, onLongClick, wide, cardModifier)
                    // As JellytipResumeRow (JellytipIptvResume.kt): asTileItem (series title in the art, "E5" pill, bar)
                    is CwTile.Iptv ->
                        IptvVodTile(
                            item = t.r.asTileItem(),
                            width = h * 16f / 9f,
                            height = h,
                            poster = false,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            modifier = cardModifier,
                        )

                    null -> Box(Modifier)
                }
            },
        )
        }
    }

    /** The info pill's right / bottom distance to its tile's right / bottom edge (it is anchored bottom-end), and its text. */
    private fun infoInside(tile: DpRect): List<Triple<Float, Float, String>> =
        compose
            .onAllNodes(hasTestTag(JellytipTileTags.INFO), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { n ->
                val d = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
                val b = n.boundsInRoot
                val cx = (b.left + b.right) / 2 / d
                val cy = (b.top + b.bottom) / 2 / d
                if (cx < tile.left.value || cx > tile.right.value || cy < tile.top.value || cy > tile.bottom.value) return@mapNotNull null
                Triple(tile.right.value - b.right / d, tile.bottom.value - b.bottom / d, n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }.orEmpty())
            }

    /** Each (library, IPTV) pair shows ONE info line, at the same offset from the tile's bottom-end corner. */
    private fun assertInfoMatches(
        name: String,
        tiles: List<DpRect>,
        pairs: List<Pair<Int, Int>>,
    ) {
        for ((l, i) in pairs) {
            val a = infoInside(tiles[l])
            val b = infoInside(tiles[i])
            println("shot $name: info line tile $l (library) $a vs tile $i (IPTV) $b")
            assertEquals("$name: info line count in library tile $l", 1, a.size)
            assertEquals("$name: info line count in IPTV tile $i", 1, b.size)
            assertTrue("$name: info text library tile $l blank", a[0].third.isNotBlank())
            assertTrue("$name: info text IPTV tile $i blank", b[0].third.isNotBlank())
            assertEquals("$name: info right offset tile $i vs $l", a[0].first, b[0].first, tol)
            assertEquals("$name: info bottom offset tile $i vs $l", a[0].second, b[0].second, tol)
        }
    }

    @Test
    fun continueWatching() =
        shot("srcneutral-continue", measure = {
            val n = "srcneutral-continue"
            measureTiles(n)
            val tiles = tilesOf(n, 4)
            assertSameSizes(n, tiles, listOf(0 to 1, 2 to 3))
            assertSameInside(n, "watched tick", tiles, listOf(0 to 1, 2 to 3), hasTestTag(JellytipTileTags.TICK))
            assertSameInside(n, "heart", tiles, listOf(0 to 1, 2 to 3), hasTestTag(JellytipTileTags.HEART))
            assertSameInside(n, "progress bar", tiles, listOf(0 to 1, 2 to 3), hasTestTag(JellytipTileTags.BAR))
            assertSameInside(n, "corner pill (episode E5)", tiles, listOf(0 to 1), hasTestTag(JellytipTileTags.PILL))
            assertSomewhere(n, "corner pill", hasTestTag(JellytipTileTags.PILL), 2)
            assertSomewhere(n, "progress bar", hasTestTag(JellytipTileTags.BAR), 4)
            assertInfoMatches(n, tiles, listOf(0 to 1, 2 to 3))
            assertNoCornerMark(n)
            // The old two-line label under the IPTV tile is gone: no text starts at or just under an IPTV tile's bottom edge
            val ignore = listOf("Continue watching", "Same show")
            val texts =
                compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
                    val t = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text } ?: return@mapNotNull null
                    if (ignore.any { t.startsWith(it) }) return@mapNotNull null
                    t to node.boundsInRoot
                }
            val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
            for (idx in listOf(1, 3)) {
                val tile = tiles[idx]
                val below =
                    texts.filter { (_, b) ->
                        val top = b.top / density
                        val left = b.left / density
                        val right = b.right / density
                        top >= tile.bottom.value - tol && top <= tile.bottom.value + 16f && right > tile.left.value && left < tile.right.value
                    }
                if (below.isNotEmpty()) fail("$n: text under IPTV tile $idx: ${below.map { it.first }}")
            }
        }) {
            val episode =
                IptvResume(
                    kind = "episode",
                    id = "ep-slow-horses-s2e5",
                    seriesId = "ser-slow-horses",
                    title = "Failure's Contagious",
                    seriesTitle = "Slow Horses",
                    season = 2,
                    episode = 5,
                    pos = 1152.0,
                    dur = 2880.0,
                    at = 1_800_000_000L,
                    poster = "https://h/p/slow-horses",
                    backdrop = "https://h/b/slow-horses",
                    tech = listOf("4k"),
                    logo = "https://h/logo/slow-horses",
                    textlessBackdrop = "https://h/tl/slow-horses",
                )
            val film =
                IptvResume(
                    kind = "movie",
                    id = "vod-heat",
                    seriesId = null,
                    title = "Heat",
                    seriesTitle = null,
                    season = null,
                    episode = null,
                    pos = 3120.0,
                    dur = 7800.0,
                    at = 1_800_000_000L,
                    poster = "https://h/p/Heat",
                    backdrop = "https://h/b/Heat",
                    tech = listOf("4k"),
                )
            HomeStyle {
                Column(Modifier.fillMaxSize().padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CwRow("Continue watching: episode (library, IPTV)", listOf(CwTile.Lib(libEpisode()), CwTile.Iptv(episode)))
                    CwRow("Continue watching: film (library, IPTV)", listOf(CwTile.Lib(libFilm(ttls[0], 2, favourite = false)), CwTile.Iptv(film)))
                    Cap("Same show / film, 40% watched, in both sources. Library tile left, IPTV resume tile right.")
                }
            }
        }

    // ---- 3b. Series: the unplayed count in the corner pill ----

    @Test
    fun seriesPills() =
        shot("srcneutral-series-pills", measure = {
            val n = "srcneutral-series-pills"
            measureTiles(n)
            val tiles = tilesOf(n, 4)
            val pairs = listOf(0 to 2, 1 to 3)
            assertBadgesMatch(n, tiles, pairs)
            assertSameInside(n, "unplayed count '4'", tiles, pairs, hasText("4"))
            assertSomewhere(n, "corner pill", hasTestTag(JellytipTileTags.PILL), 2)
        }) {
            HomeStyle {
                Column(Modifier.fillMaxSize().padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LibRow("Library series: unplayed count in the corner pill (4, then none)", listOf(libSeries("Slow Horses", 4), libSeries("Severance", 0)))
                    IptvRow(
                        "IPTV series: unplayedCount (4, then none)",
                        listOf(vodSeries("Slow Horses", unplayedCount = 4), vodSeries("Severance", unplayedCount = 0)),
                        poster = false,
                        heightDp = wide.heightDp,
                    )
                    Cap("Same two series in both sources: the pill sits in the same corner with the same look.")
                }
            }
        }

    // ---- shared mock: a JellytipHomeViewModel that answers only what JellytipServerRowView asks (as LeftLineShotsTest) ----

    private fun vmFor(items: List<BaseItem>): JellytipHomeViewModel {
        val rows = mockk<JellytipRowsService>()
        every { rows.peekItems(any()) } returns items
        coEvery { rows.items(any()) } returns items
        val vm = mockk<JellytipHomeViewModel>()
        every { vm.rows } returns rows
        every { vm.navigationManager } returns mockk<NavigationManager>(relaxed = true)
        return vm
    }

    // ---- 4. Search results: ONE row, the server's rank order, a title in both sources once ----

    private fun assertNoSourceWording(name: String) {
        compose.onAllNodes(hasText("On IPTV", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("In your library", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("via IPTV", substring = true), useUnmergedTree = true).assertCountEquals(0)
        println("shot $name: no 'On IPTV' / 'In your library' / 'via IPTV' text")
    }

    @Test
    fun search() =
        shot("srcneutral-search", measure = {
            val n = "srcneutral-search"
            measureTiles(n)
            // Heat (library + IPTV via libraryId / sources), Collateral (IPTV first, library second: TMDB ids only), Ronin (IPTV only) = 3 tiles
            tilesOf(n, 3)
            assertNoSourceWording(n)
            assertNoCornerMark(n)
        }) {
            // JellytipSearchPage is NOT wrapped in JellytipHomeTheme: no HomeStyle here, as on the TV; ResultGroup provides LocalJellytipHome for the tiles.
            val libHeat = libFilm(ttls[0], 0)
            val libCollateral = libFilm(ttls[1], 2)
            val vodById = ttls.map { vodFilm(it, 0) }.associateBy { it.id }
            val results =
                listOf(
                    SearchResult(kind = "film", source = "library", title = "Heat", tmdbId = 949, id = libHeat.id),
                    // both sources know the link: libraryId + sources
                    SearchResult(
                        kind = "film",
                        source = "iptv",
                        title = "Heat",
                        tmdbId = 949,
                        iptvId = "vod-heat",
                        libraryId = libHeat.id.toString(),
                        sources = listOf(IptvSource("library", libHeat.id.toString()), IptvSource("iptv", "vod-heat", listOf("4k"), copies = 2)),
                    ),
                    // an older server: only TMDB ids (the IPTV copy ranks first, the library copy after it)
                    SearchResult(kind = "film", source = "iptv", title = "Collateral", tmdbId = 1538, iptvId = "vod-collateral"),
                    SearchResult(kind = "film", source = "library", title = "Collateral", tmdbId = 1538, id = libCollateral.id),
                    SearchResult(kind = "film", source = "iptv", title = "Ronin", tmdbId = 1000, iptvId = "vod-ronin"),
                )
            // The page's own grouping (SearchAnswer.rankedGroups) -> ResultGroup's JellytipServerRow
            val group = SearchAnswer("heat", "", emptyList(), emptyList(), emptyList(), results = results).rankedGroups.single()
            assertEquals("results", group.type)
            val vm = vmFor(listOf(libHeat, libCollateral))
            Column(
                Modifier.fillMaxSize().padding(start = 24.dp, top = JellytipLayout.PageTop),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Cap("Search results for \"heat\": ONE row (ResultGroup \"results\" via JellytipServerRowView): library + IPTV tiles in rank order, a title in both once")
                CompositionLocalProvider(LocalJellytipHome provides true) {
                    JellytipServerRowView(
                        row =
                            JellytipServerRow(
                                id = "search:results:1",
                                source = "search",
                                family = "search",
                                title = "Results",
                                layout = "standard",
                                mediaType = "movie",
                                itemIds = group.ids,
                                collection = null,
                                iptvItems = group.iptv.map { vodById.getValue(it.id) },
                                order = group.order,
                            ),
                        onFocusItem = { _, _ -> },
                        onLongClickItem = null,
                        focusModifier = Modifier.focusGroup(),
                        viewModel = vm,
                    )
                }
            }
        }

    // ---- 5. Watchlist (a reconstruction without the hero: JellytipWatchlistPage = HomePageContent + mergeWatchlist, needs Hilt) ----

    @Test
    fun watchlist() =
        shot("srcneutral-watchlist", measure = {
            val n = "srcneutral-watchlist"
            measureTiles(n)
            // Films: library Heat + Collateral, IPTV Heat (TMDB id only), IPTV Collateral (libraryId), IPTV Ronin -> 3 tiles.
            // Shows: library Slow Horses, IPTV Slow Horses (TMDB), IPTV Severance -> 2 tiles
            tilesOf(n, 5)
            assertNoSourceWording(n)
            assertNoCornerMark(n)
            compose.onAllNodes(hasText("Films (3)"), useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodes(hasText("Shows (2)"), useUnmergedTree = true).assertCountEquals(1)
        }) {
            val libHeat = libFilm(ttls[0], 0, favourite = true)
            val libCollateral = libFilm(ttls[1], 2, favourite = true)
            val libSlow = libSeries("Slow Horses", 4)
            val rows =
                listOf<HomeRowLoadingState>(
                    HomeRowLoadingState.Success(StringStringProvider("Films (2)"), listOf(libHeat, libCollateral), HomeRowViewOptions(), showViewMore = false),
                    HomeRowLoadingState.Success(StringStringProvider("Shows (1)"), listOf(libSlow), HomeRowViewOptions(), showViewMore = false),
                )
            val films =
                listOf(
                    vodFilm(ttls[0], 0, favourite = true), // TMDB id only
                    vodFilm(ttls[1], 2, favourite = true).copy(libraryId = libCollateral.id.toString()), // libraryId
                    vodFilm(ttls[2], 0, favourite = true), // IPTV only
                )
            val shows = listOf(vodSeries("Slow Horses", unplayedCount = 4, tmdb = seriesTmdb("Slow Horses")), vodSeries("Severance", tmdb = seriesTmdb("Severance")))
            val merged = mergeWatchlist(rows, films, shows)
            val vm = mockk<JellytipIptvVodViewModel>()
            every { vm.navigationManager } returns mockk<NavigationManager>(relaxed = true)
            HomeStyle {
                Column(Modifier.fillMaxSize().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    merged.rows.forEachIndexed { i, r ->
                        val row = r as HomeRowLoadingState.Success
                        val extra = merged.appended[i].orEmpty()
                        if (extra.isEmpty()) {
                            LibRow(row.title.let { (it as StringStringProvider).str }, row.items.filterNotNull())
                        } else {
                            // HomePageContent's Success branch for a row with IPTV-only titles joined
                            JellytipWatchlistMixedRow(
                                title = (row.title as StringStringProvider).str,
                                library = row.items,
                                iptv = extra,
                                viewOptions = wide,
                                onClickLibrary = { _, _ -> },
                                onLongClickLibrary = { _, _ -> },
                                onFocusLibrary = {},
                                onFocusIptv = {},
                                modifier = Modifier.fillMaxWidth().focusGroup(),
                                viewModel = vm,
                            )
                        }
                    }
                    merged.iptvRows.forEach { (label, list) -> IptvRow("$label (${list.size})", list, poster = false, heightDp = wide.heightDp) }
                    Cap("All favourites. Films (3) / Shows (2) are one row each: library tiles, then the IPTV-only ones. No hero (needs Hilt).")
                }
            }
        }

    // ---- 6. A server row mixing library and IPTV (MixedRow, via JellytipServerRowView) ----

    @Test
    fun mixedRow() =
        shot("srcneutral-mixed-row", measure = {
            val n = "srcneutral-mixed-row"
            measureTiles(n)
            // Heat (libraryId + sources), Collateral (IPTV tile first, library tile later: TMDB ids only) and Ronin once each, Thief IPTV-only
            tilesOf(n, 4)
            assertNoCornerMark(n)
            // Heat: one tile with the library's logo art, not two
            compose.onAllNodes(hasTestTag(JellytipTileTags.BAR), useUnmergedTree = true).assertCountEquals(1) // only Ronin (40%) has a bar
        }) {
            val libHeat = libFilm(ttls[0], 1)
            val libRonin = libFilm(ttls[2], 2)
            val libCollateral = libFilm(ttls[1], 0)
            val lib = listOf(libHeat, libRonin, libCollateral)
            val vm = vmFor(lib)
            val thief = Ttl("Thief", 1981, 1234)
            val vodCollateral = vodFilm(ttls[1], 0)
            val vodHeat = vodFilm(ttls[0], 1).copy(libraryId = libHeat.id.toString(), sources = listOf(IptvSource("library", libHeat.id.toString()), IptvSource("iptv", "vod-heat")))
            val vodThief = vodFilm(thief, 0)
            HomeStyle {
                Column(Modifier.fillMaxSize().padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    JellytipServerRowView(
                        row =
                            JellytipServerRow(
                                // (not the "mylist" family: that one adds a See all card, which is a 5th clickable node)
                                id = "foryou:movie",
                                source = "server",
                                family = "foryou",
                                title = "For you",
                                layout = "standard",
                                mediaType = "movie",
                                itemIds = lib.map { it.id },
                                collection = null,
                                iptvItems = listOf(vodCollateral, vodHeat, vodThief),
                                // display order: library Heat, IPTV Collateral, library Ronin, IPTV Heat (the duplicate), library Collateral, IPTV Thief
                                order =
                                    listOf(
                                        libHeat.id.toString().replace("-", ""),
                                        vodCollateral.id,
                                        libRonin.id.toString().replace("-", ""),
                                        vodHeat.id,
                                        libCollateral.id.toString().replace("-", ""),
                                        vodThief.id,
                                    ),
                                sourcesById = mapOf(JellytipSourceMerge.norm(libHeat.id.toString()) to listOf(IptvSource("iptv", "vod-heat", listOf("4k"), copies = 2))),
                            ),
                        onFocusItem = { _, _ -> },
                        onLongClickItem = null,
                        focusModifier = Modifier.focusGroup(),
                        viewModel = vm,
                    )
                    Cap("One row of 4 tiles: Heat, Collateral (its IPTV slot), Ronin, Thief. Heat and Collateral are in both sources, shown once.")
                }
            }
        }

    // ---- 6b. Continue Watching: the same film in both sources is one tile ----

    @Test
    fun continueSameFilm() =
        shot("srcneutral-continue-same-film", measure = {
            val n = "srcneutral-continue-same-film"
            measureTiles(n)
            // Heat (library 40%) + the IPTV resume of the same film (TMDB id 949) -> the library tile only; Ronin (IPTV only) stays
            tilesOf(n, 2)
            assertNoCornerMark(n)
        }) {
            fun resume(
                id: String,
                title: String,
                tmdb: Int?,
            ) = IptvResume(
                kind = "movie",
                id = id,
                seriesId = null,
                title = title,
                seriesTitle = null,
                season = null,
                episode = null,
                pos = 3120.0,
                dur = 7800.0,
                at = 1_800_000_000L,
                poster = "https://h/p/$title",
                backdrop = "https://h/b/$title",
                tech = listOf("4k"),
                tmdbId = tmdb,
            )
            val libHeat = libFilm(ttls[0], 2, favourite = false)
            val iptv = listOf(resume("vod-heat", "Heat", 949), resume("vod-ronin", "Ronin", 1000))
            // JellytipResumeRow's own step: the library row's films filter the IPTV entries
            val kept = JellytipSourceMerge.filterResume(listOf(libHeat.libRef()), iptv)
            assertEquals(listOf("vod-ronin"), kept.map { it.id })
            HomeStyle {
                Column(Modifier.fillMaxSize().padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CwRow("Continue watching: same film in both sources", listOf<CwTile>(CwTile.Lib(libHeat)) + kept.map { CwTile.Iptv(it) })
                    Cap("Heat is in the library and on IPTV (same TMDB id): one tile. Ronin is IPTV only: kept.")
                }
            }
        }

    // ---- 7. Hold-OK menus (Dialog windows: capture the screen, not the root) ----

    private fun menuShot(
        name: String,
        caption: String,
        focusLabel: String,
        dialog: @Composable () -> Unit,
    ) {
        compose.setContent {
            Tv {
                Frame(inset = false) { Text(caption, color = Color.White, modifier = Modifier.padding(start = 96.dp, top = 40.dp)) }
                dialog()
            }
        }
        compose.waitForIdle()
        // Items are disabled for ~1 s after a long press (waitToLoad)
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        focusNode(name, hasText(focusLabel))
        advance(500)
        val labels =
            compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { t -> t.text }
            }
        println("shot $name: menu texts=$labels")
        captureScreenRoboImage(out(name))
    }

    @Test
    fun menuLibrary() {
        // As HomePage.onLongClickItem builds it for a film tile in an ordinary row (not Continue Watching): Go to, Resume / Restart, ...
        val menu =
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = libFilm(ttls[0], 2),
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = false,
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions =
                    ContextMenuActions(
                        navigateTo = {},
                        onShowOverview = {},
                        onClickWatch = { _, _ -> },
                        onClickFavorite = { _, _ -> },
                        onClickAddPlaylist = {},
                        onSendMediaInfo = {},
                        onDeleteItem = {},
                        onChooseVersion = { _, _ -> },
                        onChooseTracks = {},
                        onClearChosenStreams = {},
                        onClickRemoveFromNextUp = {},
                    ),
            )
        menuShot("srcneutral-menu-library", "Hold OK on a library film tile: Home (Heat, 40%, in My list)", "Resume") {
            ContextMenuDialog(onDismissRequest = {}, getMediaSource = null, contextMenu = menu, preferredSubtitleLanguage = null)
        }
    }

    @Test
    fun menuIptv() =
        menuShot("srcneutral-menu-iptv", "Hold OK on an IPTV film tile: IptvTileMenu (Heat, 40%, in My list)", "Open") {
            IptvTileMenu(item = vodFilm(ttls[0], 2), onDismiss = {}, onOpen = {})
        }
}
