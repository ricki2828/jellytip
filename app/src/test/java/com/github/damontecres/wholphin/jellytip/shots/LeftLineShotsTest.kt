package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.github.damontecres.wholphin.jellytip.JellytipRowBleed
import com.github.damontecres.wholphin.jellytip.LocalJellytipRowBleed
import com.github.damontecres.wholphin.jellytip.fillFullBleed
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTheme
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.jellytipRowOptions
import com.github.damontecres.wholphin.jellytip.rows.HubCollection
import com.github.damontecres.wholphin.jellytip.rows.HubSection
import com.github.damontecres.wholphin.jellytip.rows.JellytipCollectionsHubContent
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsService
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRowView
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabbedPage
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.main.HomePageCardContent
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.nav.CollapsedDrawerItemWidth
import com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/*
 * Left-line shots (commit 0 of the 80 dp -> 96 dp left-line move; for eyes, not compared). Home, the Movies / Shows
 * Recommended tab, the Watchlist and the Collections hub share Home's row code (HomePageContent / ItemRow /
 * ItemRowTitle), so one set of "before" pictures covers all of them. Harness as DetailsShotsTest: Robolectric +
 * Roborazzi, sdk 34, w960dp-h540dp-land-xhdpi (a 1920x1080 TV at 2x), full 960x540 dp frame, page content inset 72 dp
 * at the start and 16 dp at the end like the collapsed nav drawer, magenta safe-area guides at 48/912/27/513, plus a
 * cyan 1 dp guide at x = 96 dp and a yellow one at x = 80 dp so the left line can be read off the PNG.
 *
 * What is real: HomePageContent (rows, bleed, hero buttons), ItemRow, ItemRowTitle, HomePageCardContent, TabbedPage /
 * TabRow, JellytipServerRowView, JellytipCollectionsHubContent. What is a stand-in: the hero art and the header text
 * block (JellytipHomeHero / JellytipHomeHeader take a Hilt view model; the stand-ins copy their geometry: full-bleed
 * art via fillFullBleed, text block at JellytipHomeTokens.START_PAD_PX from the page start). The clock is driven by
 * hand (autoAdvance = false).
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LeftLineShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    // ---- harness ----

    private val photoBitmap by lazy {
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
        b
    }

    private val photo by lazy { photoBitmap.asImage() }

    private val heroBitmap by lazy {
        val b = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 960f, 540f, 0xFF7A2E8C.toInt(), 0xFFE0703A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 960f, 540f, p)
        p.shader = null
        p.color = 0xFFEFE6D5.toInt()
        c.drawCircle(520f, 200f, 110f, p)
        b.asImageBitmap()
    }

    /** Image urls are built from a mocked api client, as ImageUrlServiceTest does. */
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
            MaterialTheme(colorScheme = PurpleThemeColors.darkScheme) { Surface(Modifier.fillMaxSize()) { content() } }
        }
    }

    /** Page inset 72 / 16 like the collapsed nav drawer; safe-area guides magenta, 96 dp cyan, 80 dp yellow. */
    @Composable
    private fun Frame(content: @Composable BoxScope.() -> Unit) {
        val safe = Color(0xFFFF00FF)
        Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
            Box(Modifier.fillMaxSize().padding(start = 72.dp, end = 16.dp)) { content() }
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

    /** One D-pad key through the test framework (focus moves the way the remote moves it). */
    private fun pressKey(
        name: String,
        key: Key,
    ) {
        runCatching {
            compose.onRoot().performKeyInput {
                keyDown(key)
                keyUp(key)
            }
        }.onFailure { println("shot $name: key $key not sent (${it.message?.lineSequence()?.firstOrNull()})") }
        advance(500)
    }

    /** Focus the hero's primary button (its label starts with the play triangle); only logs when it cannot. */
    private fun focusHeroButton(name: String) {
        runCatching {
            compose.onAllNodes(hasClickAction() and hasText("▶", substring = true)).onFirst().requestFocus()
        }.onFailure { println("shot $name: hero button focus not set (${it.message?.lineSequence()?.firstOrNull()})") }
        advance(300)
    }

    /** What holds focus, for the CI log (a picture with the wrong focus is only readable with this). */
    private fun logFocus(name: String) {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        val texts =
            nodes.map { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { t -> t.text } ?: "(no text)"
            }
        println("shot $name: focused nodes=${nodes.size} texts=$texts")
    }

    /** Render [content] in the frame, settle, run [steps] (focus / keys), settle again, capture the whole screen. */
    private fun shot(
        name: String,
        steps: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Tv { Frame { content() } } }
        advance(1200)
        steps()
        advance(1800)
        logFocus(name)
        compose.onRoot().captureRoboImage(out(name))
    }

    // ---- synthetic data ----

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray())

    private fun minutes(m: Long) = m * 600_000_000L

    private fun userData(
        itemId: UUID,
        positionMin: Long = 0,
        percent: Double? = null,
    ) = UserItemDataDto(
        playedPercentage = percent,
        playbackPositionTicks = minutes(positionMin),
        playCount = 0,
        isFavorite = false,
        lastPlayedDate = null,
        played = false,
        key = "",
        itemId = itemId,
    )

    private val overview =
        "A group of high-end professional thieves start to feel the heat from the LAPD when they unknowingly leave a clue " +
            "at their latest heist. An obsessive detective makes it his mission to bring them down."

    private fun movie(
        title: String,
        percent: Double? = null,
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
                overview = overview,
                runTimeTicks = minutes(130),
                imageTags = mapOf(ImageType.PRIMARY to "poster"),
                backdropImageTags = listOf("backdrop"),
                userData = userData(id, positionMin = if (percent != null) 40 else 0, percent = percent),
            ),
        )
    }

    private fun episode(
        series: String,
        title: String,
        number: Int,
        percent: Double? = null,
    ): BaseItem {
        val id = uuid("episode:$series:$number")
        return BaseItem(
            BaseItemDto(
                id = id,
                type = BaseItemKind.EPISODE,
                name = title,
                seriesName = series,
                seriesId = uuid("series:$series"),
                indexNumber = number,
                overview = overview,
                runTimeTicks = minutes(48),
                imageTags = mapOf(ImageType.PRIMARY to "ep"),
                userData = userData(id, positionMin = if (percent != null) 20 else 0, percent = percent),
            ),
        )
    }

    private val longTitle = "The Assassination of Jesse James by the Coward Robert Ford"

    /** Continue watching: 8 wide tiles, the first five part-watched, one episode, one long title. */
    private fun continueItems(): List<BaseItem> =
        listOf(
            movie("Heat", 35.0),
            episode("Slow Horses", "Failure's Contagious", 3, 60.0),
            movie(longTitle, 20.0, 2007),
            movie("Collateral", 80.0, 2004),
            movie("Ronin", 45.0, 1998),
            movie("The Town", year = 2010),
            movie("Blackhat", year = 2015),
            movie("Thief", year = 1981),
        )

    private fun filmItems(): List<BaseItem> =
        listOf("Akira", "Dune: Part Two", "Alien", "Blade Runner", "Paprika", "Ran", "Stalker", "Solaris").map { movie(it, year = 1990) }

    private fun recentItems(): List<BaseItem> =
        listOf("Anora", "Perfect Days", "Past Lives", "The Zone of Interest", "Aftersun", "Drive My Car").map { movie(it, year = 2023) }

    private fun showEpisodes(): List<BaseItem> =
        listOf(
            episode("Slow Horses", "Failure's Contagious", 1, 40.0),
            episode("Severance", "Hello, Ms. Cobel", 2, 15.0),
            episode("Andor", "Aldhani", 5, 70.0),
            episode("The Bear", "Review", 7),
            episode("Shogun", "Anjin", 1),
            episode("Fargo", "The Crocodile's Dilemma", 1),
        )

    /** Poster tiles as a customised row draws them (not the stock options, so the Jellytip wide default does not apply). */
    private val posterOptions =
        HomeRowViewOptions(
            heightDp = 150,
            spacing = 12,
            aspectRatio = AspectRatio.TALL,
            imageType = ViewOptionImageType.PRIMARY,
            showTitles = false,
        )

    private fun row(
        title: String,
        items: List<BaseItem>,
        options: HomeRowViewOptions = HomeRowViewOptions(),
    ): HomeRowLoadingState = HomeRowLoadingState.Success(StringStringProvider(title), items, options)

    private fun homeRows(): List<HomeRowLoadingState> =
        listOf(
            row("Continue watching", continueItems()),
            row("My list · Films", filmItems(), posterOptions),
            row("Recently added", recentItems()),
        )

    // ---- stand-ins for the two Hilt pieces of the hero ----

    /** Full-bleed art like JellytipHomeHero: fillFullBleed, 70% tall, the 96 dp rail scrim strip, the bottom fade. */
    @Composable
    private fun HeroStandIn() {
        Box(Modifier.fillMaxSize().fillFullBleed().background(JellytipPalette.Night)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(JellytipHomeTokens.HERO_HEIGHT_FRACTION).clipToBounds()) {
                Image(heroBitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(96.dp)
                        .background(
                            Brush.horizontalGradient(
                                0f to JellytipPalette.Night.copy(alpha = JellytipHomeTokens.RAIL_SCRIM_ALPHA),
                                1f to Color.Transparent,
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to JellytipPalette.Night.copy(alpha = 0.30f),
                                0.30f to Color.Transparent,
                                0.60f to JellytipPalette.Night.copy(alpha = 0.40f),
                                0.9f to JellytipPalette.Night,
                            ),
                        ),
                )
            }
        }
    }

    /** The header block of JellytipHomeHeader: bottom-left, START_PAD_PX / BOTTOM_PAD_PX from the page, 900 px wide. */
    @Composable
    private fun HeaderStandIn(item: BaseItem?) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        with(LocalDensity.current) {
            Box(
                modifier = Modifier.fillMaxWidth().height((screenHeight * JellytipHomeTokens.HEADER_HEIGHT_FRACTION).dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier
                            .padding(
                                start = JellytipHomeTokens.START_PAD_PX.toDp(),
                                bottom = JellytipHomeTokens.BOTTOM_PAD_PX.toDp(),
                            ).width(JellytipHomeTokens.TEXT_BLOCK_W_PX.toDp()),
                ) {
                    Text(
                        text = item?.title ?: "",
                        fontSize = JellytipHomeTokens.TITLE_PX.toSp(),
                        fontWeight = FontWeight.Medium,
                        color = JellytipPalette.Vanilla,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "1995  ·  R  ·  2h 50m",
                        fontSize = JellytipHomeTokens.TEXT_PX.toSp(),
                        color = JellytipPalette.Vanilla,
                    )
                    Text(
                        text = item?.data?.overview ?: "",
                        fontSize = JellytipHomeTokens.TEXT_PX.toSp(),
                        color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /**
     * HomePageContent with the parameters Home / the Recommended tabs / the Watchlist pass (hero + header + hero
     * buttons, jellytip home theme, no specials: they need the companion). takeFocus = false: the tests move focus.
     */
    @Composable
    private fun HomeLike(
        rows: List<HomeRowLoadingState>,
        showViewMore: Boolean = true,
    ) {
        var position by remember { mutableStateOf(RowColumn(0, 0)) }
        JellytipHomeTheme {
            HomePageContent(
                homeRows = rows,
                position = position,
                onFocusPosition = { position = it },
                onClickItem = { _, _ -> },
                onLongClickItem = { _, _ -> },
                onClickPlay = { _, _ -> },
                showClock = false,
                onUpdateBackdrop = {},
                showLogo = false,
                showViewMore = showViewMore,
                modifier = Modifier.fillMaxSize(),
                takeFocus = false,
                heroComposable = { _, _ -> HeroStandIn() },
                headerComposable = { item -> HeaderStandIn(item) },
            )
        }
    }

    private fun tabs(vararg ids: Int) = ids.map { TabDetails(it) }

    @Composable
    private fun TabbedHome(
        tabs: List<TabDetails>,
        rows: List<HomeRowLoadingState>,
    ) {
        TabbedPage(
            selectedTabIndex = 0,
            tabs = tabs,
            updateSelectedTabIndex = {},
            isShowClock = true,
            modifier = Modifier.fillMaxSize(),
        ) { _, _ -> HomeLike(rows) }
    }

    // ---- shots ----

    @Test
    fun homeHero() {
        shot("leftline-home-hero", steps = { focusHeroButton("leftline-home-hero") }) { HomeLike(homeRows()) }
    }

    /** Down from the hero's primary button: the real path (the buttons' `down` focus property into the first row). */
    private fun toFirstTile(name: String) {
        focusHeroButton(name)
        pressKey(name, Key.DirectionDown)
    }

    @Test
    fun homeRowsFocused() {
        shot("leftline-home-rows", steps = { toFirstTile("leftline-home-rows") }) { HomeLike(homeRows()) }
    }

    @Test
    fun homeRowsScrolled() {
        // Five Rights (not three): with 188 dp tiles at a 12 dp gap the row only scrolls once focus passes the fourth tile
        shot(
            "leftline-home-rows-scrolled",
            steps = {
                toFirstTile("leftline-home-rows-scrolled")
                repeat(5) { pressKey("leftline-home-rows-scrolled", Key.DirectionRight) }
            },
        ) { HomeLike(homeRows()) }
    }

    @Test
    fun movies() {
        // CollectionFolderMovie tabs; the Recommended tab = RecommendedContent -> HomePageContent (Continue watching /
        // Recently added / Top unwatched). RecommendedContent itself needs a Hilt view model: not drawn, its parameters are.
        val rows =
            listOf(
                row("Continue watching", continueItems()),
                row("Recently added", recentItems()),
                row("Top unwatched", filmItems()),
            )
        shot(
            "leftline-movies",
            steps = { focusHeroButton("leftline-movies") },
        ) { TabbedHome(tabs(R.string.recommended, R.string.library, R.string.collections, R.string.genres), rows) }
    }

    @Test
    fun shows() {
        // Not the same call as Movies: other tab list (no Collections, Studios) and episode rows; same HomePageContent shape
        val rows =
            listOf(
                row("Continue watching", showEpisodes()),
                row("Next up", showEpisodes().reversed()),
                row("Recently released", showEpisodes()),
            )
        shot(
            "leftline-shows",
            steps = { focusHeroButton("leftline-shows") },
        ) { TabbedHome(tabs(R.string.recommended, R.string.library, R.string.genres, R.string.studios), rows) }
    }

    @Test
    fun watchlist() {
        // JellytipWatchlist: HomePageContent, showViewMore = false, rows titled "Films (n)" / "Shows (n)", stock view options
        val rows = listOf(row("Films (8)", filmItems()), row("Shows (6)", showEpisodes()))
        shot("leftline-watchlist", steps = { focusHeroButton("leftline-watchlist") }) { HomeLike(rows, showViewMore = false) }
    }

    @Test
    fun collections() {
        fun c(
            n: Int,
            title: String,
            total: Int,
            iptv: Int = 0,
        ) = HubCollection(
            id = "c$n",
            title = title,
            poster = null,
            backdrop = "https://h/hub/$n",
            total = total,
            libCount = total - iptv,
            iptvCount = iptv,
            ranked = false,
            mediaType = "movie",
        )
        val sections =
            listOf(
                HubSection(
                    "collections",
                    "Collections",
                    listOf(c(1, "Heist films", 24, 5), c(2, "Neo-noir", 18), c(3, "Studio Ghibli", 22, 3), c(4, "Cold War thrillers", 15), c(5, "Space opera", 31, 8), c(6, "A24", 40)),
                ),
                HubSection("franchises", "Franchises", listOf(c(7, "Alien", 7), c(8, "Mad Max", 5, 1))),
                HubSection("decades", "Decades", listOf(c(9, "The 1970s", 60), c(10, "The 1980s", 75))),
            )
        shot("leftline-collections") {
            JellytipCollectionsHubContent(sections = sections, failed = false, onJourneys = {}, onOpen = {}, modifier = Modifier.fillMaxSize())
        }
    }

    // ---- shared row parts in isolation ----

    @Composable
    private fun RowParts(
        caption: String,
        bleed: JellytipRowBleed,
        viewModel: JellytipHomeViewModel?,
    ) {
        val wide = jellytipRowOptions(HomeRowViewOptions())
        val serverItems = recentItems()
        JellytipHomeTheme {
            CompositionLocalProvider(LocalJellytipRowBleed provides bleed) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(caption, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                    ItemRowTitle("ItemRowTitle on its own")
                    ItemRow<BaseItem>(
                        title = "Continue watching (wide, 6 cards)",
                        items = continueItems().take(6),
                        onClickItem = { _, _ -> },
                        onLongClickItem = { _, _ -> },
                        modifier = Modifier.fillMaxWidth().focusGroup(),
                        horizontalPadding = wide.spacing.dp,
                        cardContent = { index, item, cardModifier, onClick, onLongClick ->
                            HomePageCardContent(index, item, onClick, onLongClick, wide, cardModifier)
                        },
                    )
                    ItemRow<BaseItem>(
                        title = "My list · Films (posters)",
                        items = filmItems().take(8),
                        onClickItem = { _, _ -> },
                        onLongClickItem = { _, _ -> },
                        modifier = Modifier.fillMaxWidth().focusGroup(),
                        horizontalPadding = posterOptions.spacing.dp,
                        cardContent = { index, item, cardModifier, onClick, onLongClick ->
                            HomePageCardContent(index, item, onClick, onLongClick, posterOptions.copy(heightDp = 110), cardModifier)
                        },
                    )
                    if (viewModel != null) {
                        JellytipServerRowView(
                            row =
                                JellytipServerRow(
                                    id = "shot-server-row",
                                    source = "server",
                                    family = "foryou",
                                    title = "Server row",
                                    layout = "standard",
                                    mediaType = "movie",
                                    itemIds = serverItems.map { it.id },
                                    collection = null,
                                    reason = "Because you like heist films",
                                ),
                            onFocusItem = { _, _ -> },
                            onLongClickItem = null,
                            focusModifier = Modifier.focusGroup(),
                            viewModel = viewModel,
                        )
                    } else {
                        Text("(server row not drawn: view model mock failed)", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    /** A JellytipHomeViewModel that answers only what JellytipServerRowView asks (rows.peekItems / items, navigationManager). */
    private fun serverRowViewModel(): JellytipHomeViewModel? =
        runCatching {
            val items = recentItems()
            val rows = mockk<JellytipRowsService>()
            every { rows.peekItems(any()) } returns items
            coEvery { rows.items(any()) } returns items
            val vm = mockk<JellytipHomeViewModel>()
            every { vm.rows } returns rows
            every { vm.navigationManager } returns mockk<NavigationManager>(relaxed = true)
            vm
        }.onFailure { println("shot row-parts: view model mock failed: $it") }.getOrNull()

    @Test
    fun rowPartsWithPageBleed() {
        val vm = serverRowViewModel()
        // Exactly as HomePageContent provides it
        val bleed = JellytipRowBleed(CollapsedDrawerItemWidth + 8.dp, 16.dp)
        shot("leftline-row-parts") { RowParts("LocalJellytipRowBleed = ${bleed.start} / ${bleed.end} (as HomePageContent provides)", bleed, vm) }
    }

    @Test
    fun rowPartsWithoutBleed() {
        val vm = serverRowViewModel()
        shot("leftline-row-parts-zerobleed") { RowParts("LocalJellytipRowBleed = default (0 / 0)", JellytipRowBleed(0.dp, 0.dp), vm) }
    }
}
