package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import androidx.tv.material3.surfaceColorAtElevation
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.cache.ImagePick
import com.github.damontecres.wholphin.jellytip.channels.ChanItem
import com.github.damontecres.wholphin.jellytip.channels.ChanType
import com.github.damontecres.wholphin.jellytip.channels.ChannelDef
import com.github.damontecres.wholphin.jellytip.channels.ChannelRow
import com.github.damontecres.wholphin.jellytip.channels.ChannelTile
import com.github.damontecres.wholphin.jellytip.channels.Sched
import com.github.damontecres.wholphin.jellytip.channels.Slot
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTile
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle
import com.github.damontecres.wholphin.jellytip.discover.RequestState
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.iptv.ColourEdgeFixtureTile
import com.github.damontecres.wholphin.jellytip.iptv.FocusInfo
import com.github.damontecres.wholphin.jellytip.iptv.FxChannel
import com.github.damontecres.wholphin.jellytip.iptv.FxGroup
import com.github.damontecres.wholphin.jellytip.iptv.FxItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvChannel
import com.github.damontecres.wholphin.jellytip.iptv.IptvOnNow
import com.github.damontecres.wholphin.jellytip.iptv.IptvPlayState
import com.github.damontecres.wholphin.jellytip.iptv.IptvQuality
import com.github.damontecres.wholphin.jellytip.iptv.JellytipGameCentreContent
import com.github.damontecres.wholphin.jellytip.iptv.LivePreview
import com.github.damontecres.wholphin.jellytip.iptv.livePreviewShown
import com.github.damontecres.wholphin.jellytip.iptv.MoreTile
import com.github.damontecres.wholphin.jellytip.iptv.SportFixtures
import com.github.damontecres.wholphin.jellytip.iptv.WayTile
import com.github.damontecres.wholphin.jellytip.movienight.MnFilm
import com.github.damontecres.wholphin.jellytip.movienight.MnVote
import com.github.damontecres.wholphin.jellytip.movienight.VotePoster
import com.github.damontecres.wholphin.jellytip.rows.HubCollection
import com.github.damontecres.wholphin.jellytip.rows.HubSection
import com.github.damontecres.wholphin.jellytip.rows.JellytipCollectionsHubContent
import com.github.damontecres.wholphin.jellytip.search.SearchResult
import com.github.damontecres.wholphin.jellytip.search.SearchLiveCard
import com.github.damontecres.wholphin.jellytip.search.TopHit
import com.github.damontecres.wholphin.jellytip.specials.TrailerBreakBand
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.nav.CollapsedDrawerItemWidth
import com.github.damontecres.wholphin.ui.nav.IconNavItem
import com.github.damontecres.wholphin.ui.nav.ModalNavigationDrawer
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.NavItem
import com.github.damontecres.wholphin.ui.nav.ProfileIcon
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.preferences.ClickPreference
import com.github.damontecres.wholphin.ui.preferences.SwitchPreference
import com.github.damontecres.wholphin.ui.spacedByWithFooter
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import com.github.damontecres.wholphin.jellytip.movienight.Button as MnButton

/*
 * Audit 3, commit 0: "before" shots for the layout fixes the next commit makes (Trailer Break band, Game Centre scroll clip,
 * one-off focus styles, page titles, empty guide preview, Subtitle / User-profile settings panels, open sidebar pushing the
 * page off screen). Documents today's state: NO asserts (the fix commit adds them to the same shot names); measured numbers go to
 * the CI log as "shot <name>: ..." lines. Harness as Audit2ShotsTest: Robolectric + Roborazzi, sdk 34, w960dp-h540dp-land-xhdpi
 * (a 1920x1080 TV at 2x), the app theme + a tv-material Surface, keyboard input mode, fake image handler, a full 960x540 dp
 * frame with magenta safe-area guides (x = 48 / 912, y = 27 / 513), a yellow guide at x = 80 and a cyan one at x = 96 dp.
 * Pages are drawn inside the 72 dp start / 16 dp end inset DestinationContent gives them. Real composables everywhere except
 * where a test says "stand-in" (named in the report).
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class Audit3ShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    // ---- harness (as Audit2ShotsTest) ----

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
            MaterialTheme(colorScheme = com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors.darkScheme) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** The full 960x540 dp frame; [inset] = the page inset (72 dp start, 16 dp end) of DestinationContent. Guides on top. */
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
            Box(Modifier.offset(x = 80.dp).fillMaxHeight().width(1.dp).background(Color(0xFFFFFF00)))
            Box(Modifier.offset(x = 96.dp).fillMaxHeight().width(1.dp).background(Color(0xFF00FFFF)))
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** Focus the first node [matcher] finds; a shot is still worth having without its focus, so a failure is only logged. */
    private fun focusNode(
        name: String,
        matcher: SemanticsMatcher,
    ) {
        runCatching { compose.onAllNodes(matcher).onFirst().requestFocus() }
            .onFailure { println("shot $name: focus not set (${it.message?.lineSequence()?.firstOrNull()})") }
        advance(300)
    }

    private fun logFocus(name: String) {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        val texts = nodes.map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { t -> t.text } ?: "(no text)" }
        println("shot $name: focused nodes=${nodes.size} texts=$texts")
    }

    /** Layout bounds (dp from the screen's top-left) of the first node [matcher] finds in the unmerged tree, to the CI log. */
    private fun logBox(
        name: String,
        what: String,
        matcher: SemanticsMatcher,
    ) {
        runCatching {
            val b = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
            println(
                "shot $name: $what left=${b.left.value} right=${b.right.value} top=${b.top.value} bottom=${b.bottom.value} " +
                    "w=${b.right.value - b.left.value} h=${b.bottom.value - b.top.value}",
            )
        }.onFailure { println("shot $name: $what not measured (${it.message?.lineSequence()?.firstOrNull()})") }
    }

    // ---- asserts (audit 3, commit 1): bounds are dp from the screen's top-left, on the unmerged tree, tolerance 0.5 dp ----

    private fun bounds(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

    private fun allBounds(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).let { nodes ->
            (0 until nodes.fetchSemanticsNodes().size).map { nodes[it].getUnclippedBoundsInRoot() }
        }

    private fun near(
        what: String,
        actual: Float,
        expected: Float,
    ) = assertTrue("$what: $actual dp, expected $expected +/- 0.5", kotlin.math.abs(actual - expected) <= 0.5f)

    private fun atMost(
        what: String,
        actual: Float,
        max: Float,
    ) = assertTrue("$what: $actual dp, expected <= $max", actual <= max)

    private fun atLeast(
        what: String,
        actual: Float,
        min: Float,
    ) = assertTrue("$what: $actual dp, expected >= $min", actual >= min)

    /** Bounds of EVERY node [matcher] finds (unmerged tree), for rows of tiles. */
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
                println("shot $name: $what #$i left=${b.left.value} right=${b.right.value} top=${b.top.value} bottom=${b.bottom.value} w=${b.right.value - b.left.value} h=${b.bottom.value - b.top.value}")
            }
            if (n == 0) println("shot $name: $what none found")
        }.onFailure { println("shot $name: $what not measured (${it.message?.lineSequence()?.firstOrNull()})") }
    }

    /** Render [content] in the [Frame], settle, run [steps] (focus), settle again, capture the whole screen. */
    private fun shot(
        name: String,
        inset: Boolean = true,
        steps: () -> Unit = {},
        measure: () -> Unit = {},
        content: @Composable BoxScope.() -> Unit,
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

    private val cyan = Color(0xFF00FFFF)

    @Composable
    private fun Cap(text: String) = Text(text, color = cyan, fontSize = 11.sp)

    // ---- synthetic data ----

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray())

    private fun minutes(m: Long) = m * 600_000_000L

    private fun movie(
        title: String,
        year: Int = 1995,
    ): BaseItem {
        val id = uuid("movie:$title")
        return BaseItem(
            BaseItemDto(
                id = id,
                type = BaseItemKind.MOVIE,
                name = title,
                productionYear = year,
                officialRating = "R",
                genres = listOf("Crime", "Drama"),
                runTimeTicks = minutes(130),
                imageTags = mapOf(ImageType.PRIMARY to "poster"),
                backdropImageTags = listOf("backdrop", "backdrop2"),
                userData =
                    UserItemDataDto(
                        playedPercentage = null,
                        playbackPositionTicks = 0,
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

    private val now = 1_800_000_000L

    private fun fxCh(id: String) = FxChannel(id, "Fox League", null, null, null, 1.0, false, false, now - 1800, now + 5400, true, null)

    private fun fx(
        fid: String,
        home: String,
        away: String,
        hc: String?,
        ac: String?,
        status: String = "pre",
        league: String = "NRL",
    ) = FxItem(
        fid, league, league.lowercase(), "rugby-league", home, away, null, null, home.take(3).uppercase(), away.take(3).uppercase(), null, null,
        now + 1700, status, "Round 12", null, 1.0, 1.0, true, false, listOf(fxCh("c1")), "c1", null, null, "m$fid",
        homeColor = hc,
        homeAltColor = null,
        awayColor = ac,
        awayAltColor = null,
    )

    private fun ch(
        id: String,
        name: String,
    ) = IptvChannel(
        id = id,
        providerId = "p1",
        num = 101,
        name = name,
        logo = "https://h/logo/$id",
        group = "Sport",
        epgId = null,
        catchupDays = 3,
        favourite = false,
        quality = IptvQuality("fhd50", 1080, 50.0, null),
        alternateChannels = emptyList(),
    )

    // ---- 1. Trailer Break band (Home) ----

    private fun trailerBreakShot(
        name: String,
        focus: Boolean,
    ) {
        val item = movie("The Italian Job")
        shot(
            name,
            steps = { if (focus) focusNode(name, hasClickAction()) },
            measure = {
                logBox(name, "band", hasTestTag("tb-band"))
                // the panel tile is the only clickable node of the band
                logBox(name, "tile (16:9 panel)", hasClickAction())
                logBox(name, "kicker", hasText("TRAILER BREAK"))
                logBox(name, "title text (no logo)", hasText("The Italian Job"))
                logBox(name, "reason", hasText("Because you watched Heat"))
                logBox(name, "meta", hasText("1995", substring = true))
                val band = runCatching { compose.onAllNodes(hasTestTag("tb-band"), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot() }.getOrNull()
                val tile = runCatching { compose.onAllNodes(hasClickAction(), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot() }.getOrNull()
                // asserts: the band ends inside the safe area; the title area uses its width; the overview line shows
                atMost("trailer break band right", bounds(hasTestTag("trailer-break-band")).right.value, 912.5f)
                atLeast("trailer break title area width", bounds(hasTestTag("trailer-break-title")).let { it.right.value - it.left.value }, 200f)
                atMost("trailer break overview right", bounds(hasText("A crew of thieves", substring = true)).right.value, 912.5f)
                if (band != null && tile != null) {
                    println("shot $name: band left ${band.left.value} (screen edge to left ${band.left.value}), band right ${band.right.value} (screen edge to right ${960f - band.right.value})")
                    println("shot $name: info column (band left + 12 inset .. tile left) = ${tile.left.value - band.left.value - 12f} dp wide before its own 20 px / 40 px paddings; tile right ${tile.right.value}, gap tile-to-band-right ${band.right.value - tile.right.value}")
                }
            },
        ) {
            // HomePage: the Home column is padded RowPageStart (16) inside the 72 / 16 page inset; the row is a Box(fillMaxWidth) in the LazyColumn
            Box(Modifier.fillMaxWidth().padding(start = JellytipLayout.RowPageStart, top = 120.dp)) {
                TrailerBreakBand(
                    item = item,
                    kicker = "Trailer break",
                    reason = "Because you watched Heat",
                    meta = "1995  ·  R  ·  2h 10m  ·  Crime, Drama",
                    owner = "tb-1",
                    pick = ImagePick(url = "https://h/still/1", focal = null),
                    onOk = {},
                    onLongClick = {},
                    onTileFocused = {},
                    modifier = Modifier.testTag("tb-band"),
                    overview = "A crew of thieves plans one last job in Turin, and nothing about the getaway goes as planned.",
                )
            }
        }
    }

    @Test
    fun trailerBreak() = trailerBreakShot("audit3-trailer-break", focus = false)

    @Test
    fun trailerBreakFocused() = trailerBreakShot("audit3-trailer-break-focused", focus = true)

    // ---- 2. Game Centre scrolled ----

    @Test
    fun gameCentreScrolled() {
        val name = "audit3-game-centre-scrolled"
        fun row(
            id: String,
            label: String,
            first: Int,
            status: String = "pre",
        ) = FxGroup(
            id,
            label,
            listOf(
                fx("$id-$first", "Bulldogs", "Dragons", "#0b2265", "#e03a3e", status),
                fx("$id-${first + 1}", "Rabbitohs", "Roosters", "#c8102e", "#002244", status),
                fx("$id-${first + 2}", "Panthers", "Tigers", "#0d0f14", "#f68e1f", status),
                fx("$id-${first + 3}", "Storm", "Broncos", "#4b1e78", "#6d1a36", status),
            ),
        )
        val groups = listOf(row("yours", "Your teams", 1), row("big", "Upcoming", 10), row("later", "Later this week", 20), row("post", "Finished", 30, "post"))
        val hero = fx("hero", "Warriors", "Sharks", "#0f2e75", "#1c8da8", status = "in")
        shot(
            name,
            // Clean frame: the page runs full width inside the 72 / 16 inset
            measure = {
                logBox(name, "page title", hasText("Game Centre"))
                logAll(name, "clickable tile", hasClickAction())
                near("game centre title left", bounds(hasText("Game Centre")).left.value, 96f)
                atLeast("game centre title top", bounds(hasText("Game Centre")).top.value, 27.5f)
                // fixture tiles are 320 dp wide: group them by row (top); every row's first tile is on the 96 dp line
                val tiles = allBounds(hasClickAction()).filter { kotlin.math.abs(it.right.value - it.left.value - 320f) <= 1f }
                assertTrue("no fixture tiles found", tiles.isNotEmpty())
                tiles.groupBy { Math.round(it.top.value) }.forEach { (top, row) -> near("first tile of the row at y=$top: left", row.minOf { it.left.value }, 96f) }
                // the hero's buttons (the other clickable nodes, not tiles) stop at the safe area
                allBounds(hasClickAction()).filter { kotlin.math.abs(it.right.value - it.left.value - 320f) > 1f && kotlin.math.abs(it.right.value - it.left.value - 220f) > 1f }
                    .forEach { atMost("hero control right", it.right.value, 912.5f) }
                // rows bleed to the screen's right edge (the LazyRow itself, not its tiles)
                val rows = allBounds(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                assertTrue("no LazyRow found", rows.isNotEmpty())
                rows.forEach { atLeast("row right edge", it.right.value, 959f) }
            },
        ) {
            val listState = rememberLazyListState()
            val px = with(LocalDensity.current) { 140.dp.roundToPx() }
            // Scroll the hero up so its lower part is under the page title row: the LazyColumn starts below the title and clips hard
            LaunchedEffect(Unit) { listState.scrollToItem(0, px) }
            JellytipGameCentreContent(
                modifier = Modifier,
                degraded = false,
                loaded = true,
                failed = false,
                groups = groups,
                more = listOf(SportFixtures.MoreRow("NBA", "nba", 12)),
                hero = hero,
                heroIndex = 0,
                heroCount = 1,
                now = now,
                reminderOverride = emptyMap(),
                revealed = emptySet(),
                lastFid = null,
                heroFocus = FocusRequester(),
                restore = FocusRequester(),
                first = FocusRequester(),
                onOpen = { _, _, _ -> },
                onLongClick = {},
                onMoreInRow = { _, _ -> },
                onMoreAlone = {},
                onHeroWatch = {},
                onHeroFromStart = null,
                onHeroMore = {},
                onHeroCycle = {},
                onHeroShown = {},
                listState = listState,
            )
        }
    }

    // ---- 3. One-off focus styles: each focused tile next to its unfocused twin ----

    /** [first] is drawn focused (first clickable node), [second] is the unfocused twin; [caption] names the composable and its values. */
    private fun focusShot(
        name: String,
        caption: List<String>,
        vertical: Boolean = false,
        tile: @Composable (Modifier) -> Unit,
    ) {
        shot(
            name,
            steps = { focusNode(name, hasClickAction()) },
            measure = { logAll(name, "tile", hasClickAction()) },
        ) {
            Column(Modifier.padding(start = 24.dp, top = 56.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (line in caption) Cap(line)
                if (vertical) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        tile(Modifier)
                        tile(Modifier)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Top) {
                        tile(Modifier)
                        tile(Modifier)
                    }
                }
                Cap("left / top = FOCUSED (first clickable), right / bottom = unfocused twin")
            }
        }
    }

    @Test
    fun focusGcFixture() =
        focusShot(
            "audit3-focus-gc-fixture",
            listOf(
                "ColourEdgeFixtureTile (JellytipLiveHero.kt:337-366), 320 x 140: scale 1.04, border 3 dp F2F2F0 (= JellytipFocus.Cream), fill 141414 -> 1E1E1E, no glow, no sibling dim",
                "shared style: card (JellytipFocus: 1.04 from 150 dp, 3 dp cream, glow, siblings dimmed)",
            ),
        ) { m -> ColourEdgeFixtureTile(fx("1", "Bulldogs", "Dragons", "#0b2265", "#e03a3e"), now, reminder = false, revealed = false, onClick = {}, modifier = m) }

    @Test
    fun focusGcMore() =
        focusShot(
            "audit3-focus-gc-more",
            listOf(
                "MoreTile (JellytipGameCentre.kt:418-437, tileBorder / tileColors :401-415), 220 x 140: scale 1.03, border 2 dp White (rest 1 dp White 8%), fill 141414 -> 242424, no glow",
                "shared style: card (JellytipFocus), it sits in the same row as the fixture tiles",
            ),
        ) { m -> MoreTile(SportFixtures.MoreRow("NBA", "nba", 12), m) {} }

    @Test
    fun focusDiscover() =
        focusShot(
            "audit3-focus-discover",
            listOf(
                "DiscoverTile (JellytipDiscoverUi.kt:116-139), 234 x 132: scale 1.06, border 2 dp Vanilla (no rest border), fill unchanged 141414, no glow",
                "shared style: card (JellytipFocus 1.08 under 150 dp / 1.04 above; 234 dp wide = 1.04)",
            ),
        ) { m -> DiscoverTile(DiscoverTitle(tmdbId = 1, mediaType = "movie", title = "Dune: Part Two", backdropUrl = "https://h/b"), RequestState("requested"), onClick = {}) }

    @Test
    fun focusMyChannels() {
        val t = System.currentTimeMillis()
        val row =
            ChannelRow(
                def = ChannelDef("90s-action", "90s Action", "Action films from 1990 to 1999", ChanType.MOVIE),
                number = 101,
                sched = Sched(listOf(Slot(ChanItem("a", "Heat", type = "Movie", year = 1995), t - 1_800_000, t + 3_600_000)), 0, 0.3),
            )
        focusShot(
            "audit3-focus-my-channels",
            listOf(
                "ChannelTile (JellytipMyChannelsPage.kt:277-326), full width x 84: scale 1.02, fill 1B1B1B -> Pastel, border 2 dp Color.White (the shared row border is 2 dp cream), text Ink",
                "shared style: row (JellytipSurfaceFocus.border + scale(row = true) 1.01 + colors())",
            ),
            vertical = true,
        ) { m -> ChannelTile(row, t, onClick = {}, onLongClick = {}, modifier = m.width(560.dp)) }
    }

    @Test
    fun focusMovieNightPoster() {
        val f = MnFilm("1", uuid("f1"), "The Italian Job", 1969, "https://h/p/1", null)
        focusShot(
            "audit3-focus-movienight-poster",
            listOf(
                "VotePoster (JellytipMovieNightPage.kt:624-660; Poster :732 has the same treatment), 120 x 180: scale 1.04, border 3 dp Color.White (rest 1 dp White 6%), fill 1A1A1A both",
                "shared style: card (JellytipFocus 1.08 under 150 dp wide, 3 dp cream, glow); a 120 dp poster should be 1.08",
            ),
        ) { m -> VotePoster(f, 120.dp, MnVote.YES, m, onClick = {}) }
    }

    @Test
    fun focusMovieNightButton() =
        focusShot(
            "audit3-focus-movienight-button",
            listOf(
                "Button (JellytipMovieNightPage.kt:799-826), 48 tall: scale 1.04, fill 242424 (primary EDEDED) -> Pastel, border 2 dp JellytipPalette.Jelly (the shared border is 2 dp cream)",
                "shared style: chip / button (JellytipSurfaceFocus.border + scale() + colors())",
            ),
        ) { m -> MnButton("Details & trailer", modifier = m) {} }

    @Test
    fun focusSearchTopHit() {
        val r = SearchResult(kind = "movie", source = "discover", title = "The Italian Job", year = 1969, rating = 7.2, tmdbId = 1, posterUrl = "https://h/p/top")
        focusShot(
            "audit3-focus-search-tophit",
            listOf(
                "TopHit (JellytipSearchPage.kt:655-700), 520 x 190: scale 1.03, border 2 dp Color.White, fill 141414 both, no glow",
                "shared style: card (JellytipFocus: 1.04 for a wide tile, 3 dp cream, glow)",
            ),
            vertical = true,
        ) { m -> TopHit(r) {} }
    }

    @Test
    fun focusSearchLive() {
        val o =
            IptvOnNow(
                channel = ch("c1", "Sky Sport 1"),
                title = "Warriors v Sharks",
                start = now - 1800,
                stop = now + 3600,
                sport = true,
                kind = "live",
                league = "NRL",
            )
        focusShot(
            "audit3-focus-search-live",
            listOf(
                "SearchLiveCard (JellytipSearchLive.kt:158-175; liveTileColors / liveTileBorder JellytipLiveBadges.kt:195-222), 300 x 84: scale 1.0 (none), fill 7% white -> Pastel, no border (fanart variant: black + 2 dp white outline)",
                "shared style: row / chip (JellytipSurfaceFocus: 1.04 (1.01 for a row), 2 dp cream border); the Channels row tile at :244 has scale 1.0 too",
            ),
            vertical = true,
        ) { m -> SearchLiveCard(o, now, onClick = {}, onLongClick = {}) }
    }

    @Test
    fun focusWaysToWatch() =
        focusShot(
            "audit3-focus-ways-to-watch",
            listOf(
                "WayTile (JellytipIptvSeasons.kt:376-402), 260 x 72: scale 1.03, JellytipSurfaceFocus border (2 dp cream) and Pastel fill; the library copy picker (JellytipIptvVodUi.kt:862-874) is the same with scale 1.03",
                "shared style: chip / row: only the scale differs (JellytipSurfaceFocus.SCALE 1.04, ROW_SCALE 1.01)",
            ),
        ) { m -> WayTile("Provider A", "1080p  ·  English", onClick = {}) }

    // ---- 4. Page titles (only the pages whose title is plain content; the rest are in the report's MAP) ----

    @Test
    fun pageTitlesGameCentre() {
        val name = "audit3-page-titles"
        shot(
            name,
            measure = {
                logBox(name, "Game Centre title", hasText("Game Centre"))
                logBox(name, "first line under the title", hasText("No games in the next few days."))
                near("game centre title left", bounds(hasText("Game Centre")).left.value, 96f)
                atLeast("game centre title top", bounds(hasText("Game Centre")).top.value, 27.5f)
            },
        ) {
            // loaded, nothing to list: the title row and the empty line show; the title row is the page's real code
            JellytipGameCentreContent(
                modifier = Modifier,
                degraded = false,
                loaded = true,
                failed = false,
                groups = emptyList(),
                more = emptyList(),
                hero = null,
                heroIndex = 0,
                heroCount = 0,
                now = now,
                reminderOverride = emptyMap(),
                revealed = emptySet(),
                lastFid = null,
                heroFocus = FocusRequester(),
                restore = FocusRequester(),
                first = FocusRequester(),
                onOpen = { _, _, _ -> },
                onLongClick = {},
                onMoreInRow = { _, _ -> },
                onMoreAlone = {},
                onHeroWatch = null,
                onHeroFromStart = null,
                onHeroMore = {},
                onHeroCycle = {},
                onHeroShown = {},
            )
        }
    }

    @Test
    fun pageTitlesCollectionsHub() {
        val name = "audit3-page-titles-collections-hub"
        fun c(
            n: Int,
            title: String,
        ) = HubCollection(id = "c$n", title = title, poster = null, backdrop = "https://h/hub/$n", total = 10 + n, libCount = 10 + n, iptvCount = 0, ranked = false, mediaType = "movie")
        shot(
            name,
            measure = {
                logBox(name, "Collections title", hasText("Collections"))
                logBox(name, "Start a journey chip", hasText("Start a journey", substring = true))
                logBox(name, "Movies tab chip", hasText("Movies"))
                logBox(name, "section title", hasText("COLLECTIONS", ignoreCase = false))
                logBox(name, "first tile", hasTestTag("hub-tile0"))
                near("collections title left", bounds(hasText("Collections")).left.value, 96f)
                atLeast("collections title top", bounds(hasText("Collections")).top.value, 27.5f)
            },
        ) {
            JellytipCollectionsHubContent(
                sections = listOf(HubSection("collections", "Collections", listOf(c(1, "Heist films"), c(2, "Neo-noir"), c(3, "Studio Ghibli")))),
                failed = false,
                onJourneys = {},
                onOpen = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // ---- 5. Live TV guide: top block with nothing focused ----

    @Test
    fun guidePreviewEmpty() {
        val name = "audit3-guide-preview-empty"
        shot(
            name,
            measure = {
                logBox(name, "preview", hasTestTag("guide-preview"))
                logBox(name, "focus info hint line", hasText("OK  watch full screen", substring = true))
                // no preview box (no 224 dp + 20 dp gap): FocusInfo starts at the row's start, the page line 96
                assertTrue("empty preview must not be drawn", compose.onAllNodes(hasTestTag("guide-preview"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                near("focus info left", bounds(hasText("OK  watch full screen", substring = true)).left.value, 96f)
            },
        ) {
            // JellytipLiveTvPage.kt:846-851: Column(padding start PageStart, top PageTop, end PageEnd) { Row(spacedBy 20, height PREVIEW_H) { LivePreview, FocusInfo } }
            // (PREVIEW_W 224 x PREVIEW_H 126 are private there: copied)
            Column(Modifier.fillMaxSize().padding(start = JellytipLayout.PageStart, top = JellytipLayout.PageTop, end = JellytipLayout.PageEnd)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.height(126.dp)) {
                    // the real decision (livePreviewShown); the Row itself is copied from the page
                    if (livePreviewShown(IptvPlayState.Idle, null)) LivePreview({ null }, IptvPlayState.Idle, null, Modifier.size(224.dp, 126.dp).testTag("guide-preview"))
                    FocusInfo(null, null, now, null, Modifier.weight(1f))
                }
            }
        }
    }

    @Test
    fun guidePreviewChannelNoProgramme() {
        val name = "audit3-guide-preview-channel-noprog"
        shot(name) {
            Column(Modifier.fillMaxSize().padding(start = JellytipLayout.PageStart, top = JellytipLayout.PageTop, end = JellytipLayout.PageEnd)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.height(126.dp)) {
                    if (livePreviewShown(IptvPlayState.Idle, ch("c9", "Sky Sport 9"))) LivePreview({ null }, IptvPlayState.Idle, ch("c9", "Sky Sport 9"), Modifier.size(224.dp, 126.dp))
                    FocusInfo(ch("c9", "Sky Sport 9"), null, now, null, Modifier.weight(1f))
                }
            }
        }
    }

    // ---- 6. Subtitle and User-profile settings panels (STAND-INS with the real geometry, real preference rows) ----

    @Composable
    private fun PanelStandIn(
        modifier: Modifier,
        title: String,
    ) {
        // Copy of SubtitlePreferencesContent.kt:94-116 / UserProfilePreferencesPage.kt:112-124: Column(background surface elevation 1)
        // { Text(title, headlineSmall, centred, fillMaxWidth, padding vertical 8); LazyColumn(contentPadding 16) { ... } }
        // UPDATED for audit 3 commit 1 to the new geometry the two real panels now have (as PreferencesContent.kt:222-231):
        // Column padding(top = PageTop, end = 16), title padding(end = 16) then vertical 8. Still a stand-in (Hilt).
        Column(modifier.testTag("panel").background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)).padding(top = JellytipLayout.PageTop, end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(end = 16.dp).padding(vertical = 8.dp),
            )
            LazyColumn(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    Text(
                        text = "Appearance",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                item { ClickPreference(title = "Font size", onClick = {}, summary = "24") }
                item { SwitchPreference(title = "Bold text", value = true, onClick = {}, summaryOn = "On", summaryOff = null) }
                item { ClickPreference(title = "Preferred audio language", onClick = {}, summary = "English") }
                item { SwitchPreference(title = "Always show subtitles", value = false, onClick = {}, summaryOn = null, summaryOff = "Off") }
            }
        }
    }

    private fun panelMeasure(name: String) {
        logBox(name, "panel", hasTestTag("panel"))
        logBox(name, "panel title", hasText("Subtitle style").or(hasText("Profile settings")))
        for (row in listOf("Font size", "Bold text", "Preferred audio language", "Always show subtitles")) logBox(name, "row '$row'", hasText(row))
        // asserts (on the stand-in, updated to the new geometry): rows end inside the safe area, the title starts below the overscan
        atLeast("panel title top", bounds(hasText("Subtitle style").or(hasText("Profile settings"))).top.value, 27.5f)
        val clickable = allBounds(hasClickAction())
        assertTrue("no rows found", clickable.isNotEmpty())
        clickable.forEach { atMost("panel row right", it.right.value, 912.5f) }
    }

    @Test
    fun settingsSubtitlePanel() {
        val name = "audit3-settings-subpanels"
        shot(name, steps = { focusNode(name, hasText("Bold text")) }, measure = { panelMeasure(name) }) {
            // STAND-IN page: SubtitleStylePage.kt:88-91 / 203-250: Row(modifier) { preview Box(weight 1f, fillMaxSize); SubtitlePreferencesContent(Modifier.fillMaxHeight().fillMaxWidth(.25f)) }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().weight(1f).background(Color(0xFF2A2A2E)), contentAlignment = Alignment.Center) {
                    Text("stand-in: subtitle preview", color = Color.White.copy(alpha = 0.5f))
                }
                PanelStandIn(Modifier.fillMaxHeight().fillMaxWidth(.25f), "Subtitle style")
            }
        }
    }

    @Test
    fun settingsUserProfilePanel() {
        val name = "audit3-settings-subpanels-user-profile"
        shot(name, steps = { focusNode(name, hasText("Bold text")) }, measure = { panelMeasure(name) }) {
            // STAND-IN page: UserProfilePreferencesPage.kt:67-77: Box { UserProfilePreferencesContent(Modifier.fillMaxWidth(.4f).fillMaxHeight().align(TopEnd)) }
            Box(Modifier.fillMaxSize()) {
                PanelStandIn(Modifier.fillMaxWidth(.4f).fillMaxHeight().align(Alignment.TopEnd), "Profile settings")
            }
        }
    }

    // ---- 7. Open sidebar with page content behind it ----

    private val drawerUser = JellyfinUser(id = uuid("user"), name = "Ricki Hendersonville-Longname", serverId = uuid("server"), accessToken = null)

    /** The items NavDrawer's drawerContent draws (as Audit2ShotsTest, without the Inbox item). */
    @Composable
    private fun NavigationDrawerScope.DrawerItems(isOpen: Boolean) {
        val libraries =
            listOf(
                ServerNavDrawerItem(uuid("lib-movies"), "Movies", com.github.damontecres.wholphin.ui.nav.Destination.Home(), CollectionType.MOVIES),
                ServerNavDrawerItem(uuid("lib-shows"), "TV Shows", com.github.damontecres.wholphin.ui.nav.Destination.Home(), CollectionType.TVSHOWS),
            )
        ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(fontSize = com.github.damontecres.wholphin.jellytip.rememberJellytipSizes().sp(24))) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxHeight(),
            ) {
                ProfileIcon(user = drawerUser, imageUrl = null, serverName = "my-server.example.com", drawerOpen = isOpen, onClick = {})
                LazyColumn(
                    contentPadding = PaddingValues(0.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedByWithFooter(4.dp),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    item { IconNavItem(text = stringResource(R.string.search), icon = Icons.Default.Search, selected = false, drawerOpen = isOpen, onClick = {}) }
                    item { IconNavItem(text = stringResource(R.string.home), icon = Icons.Default.Home, selected = true, drawerOpen = isOpen, onClick = {}) }
                    itemsIndexed(libraries) { _, lib -> NavItem(library = lib, selected = false, moreExpanded = false, drawerOpen = isOpen, onClick = {}) }
                    item { NavItem(library = NavDrawerItem.Collections, selected = false, moreExpanded = false, drawerOpen = isOpen, onClick = {}) }
                    item { IconNavItem(text = stringResource(R.string.settings), icon = Icons.Default.Settings, selected = false, drawerOpen = isOpen, onClick = {}) }
                }
            }
        }
    }

    @Test
    fun sidebarOpenPage() {
        val name = "audit3-sidebar-open-page"
        fun c(
            n: Int,
            title: String,
        ) = HubCollection(id = "c$n", title = title, poster = null, backdrop = "https://h/hub/$n", total = 10 + n, libCount = 10 + n, iptvCount = 0, ranked = false, mediaType = "movie")
        val sections =
            listOf(
                HubSection(
                    "collections",
                    "Collections",
                    listOf(c(1, "Heist films"), c(2, "Neo-noir"), c(3, "Studio Ghibli"), c(4, "Cold War thrillers"), c(5, "Space opera"), c(6, "A24")),
                ),
            )
        shot(
            name,
            inset = false,
            steps = { focusNode(name, hasText("Home")) },
            measure = {
                logAll(name, "hub tile", hasTestTag("hub-tile0").or(hasTestTag("hub-tile1")).or(hasTestTag("hub-tile2")))
                logBox(name, "Collections title", hasText("Collections"))
                logBox(name, "Start a journey chip", hasText("Start a journey", substring = true))
                // asserts, drawer OPEN: no 160 dp shift. The page box (72 start / 16 end padding, as NavDrawer.kt DestinationContent) is where
                // it is with the drawer closed, and its content is still on screen. The page composable is real; NavDrawer needs Hilt,
                // so the page box is the stand-in with the copied expression (offset removed), inside the REAL ModalNavigationDrawer.
                val page = bounds(hasTestTag("sidebar-page"))
                near("page left (drawer open)", page.left.value, 72f)
                atMost("page right (drawer open)", page.right.value, 944.5f)
                near("collections title left (drawer open)", bounds(hasText("Collections")).left.value, 96f)
            },
        ) {
            val state = rememberDrawerState(DrawerValue.Open)
            ModalNavigationDrawer(
                modifier = Modifier.fillMaxSize(),
                drawerState = state,
                drawerContent = { v -> DrawerItems(v == DrawerValue.Open) },
            ) {
                // NavDrawer.kt DestinationContent copied: Modifier.fillMaxSize().padding(start = closedDrawerWidth + 8.dp, end = 16.dp) (audit 3 commit 1: the
                // offset is gone, the open drawer overlays the page); the real page here: JellytipCollectionsHubContent
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = CollapsedDrawerItemWidth + 8.dp, end = 16.dp)
                        .testTag("sidebar-page"),
                ) {
                    JellytipCollectionsHubContent(sections = sections, failed = false, onJourneys = {}, onOpen = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
