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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.test.core.app.ApplicationProvider
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
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.inbox.Inbox
import com.github.damontecres.wholphin.jellytip.inbox.JellytipInboxNavItem
import com.github.damontecres.wholphin.jellytip.inbox.JellytipInboxService
import com.github.damontecres.wholphin.jellytip.iptv.IptvChannel
import com.github.damontecres.wholphin.jellytip.iptv.IptvOnNow
import com.github.damontecres.wholphin.jellytip.iptv.IptvQuality
import com.github.damontecres.wholphin.jellytip.iptv.IptvRecording
import com.github.damontecres.wholphin.jellytip.iptv.IptvUpcoming
import com.github.damontecres.wholphin.jellytip.iptv.OnNowCard
import com.github.damontecres.wholphin.jellytip.iptv.RecordingCard
import com.github.damontecres.wholphin.jellytip.iptv.UpcomingTile
import com.github.damontecres.wholphin.jellytip.rows.HubCollection
import com.github.damontecres.wholphin.jellytip.rows.HubSection
import com.github.damontecres.wholphin.jellytip.rows.JellytipCollectionsHubContent
import com.github.damontecres.wholphin.jellytip.rows.JellytipHubCollectionContent
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
import com.github.damontecres.wholphin.jellytip.search.Chip
import com.github.damontecres.wholphin.jellytip.search.SuggestionsContent
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.nav.ExpandedDrawerItemWidth
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
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/*
 * Audit 2, commit 0: "before" shots for the layout fixes the next commit makes (hub grid right edge, collection detail left
 * line, Home item menu Details / Go To, event tile sizes, Search RECENT / TRY rows, nav drawer open, Settings panel). Not
 * compared, for eyes. Commit 1 (the fixes) adds position / content asserts to the same shots (same names, so before / after
 * pair up); measured positions still go to the CI log as "shot <name>: ..." lines. Harness as LeftLineShotsTest: Robolectric + Roborazzi, sdk 34, w960dp-h540dp-land-xhdpi (a
 * 1920x1080 TV at 2x), the app theme + a tv-material Surface, keyboard input mode, fake image handler, a full 960x540 dp
 * frame with magenta safe-area guides (x = 48 / 912, y = 27 / 513), a yellow guide at x = 80 and a cyan one at x = 96 dp.
 *
 * Pages are drawn inside the 72 dp start / 16 dp end inset that DestinationContent gives them (the collapsed drawer is 64 dp
 * wide + 8); the drawer shots and the Home menu dialogs use the whole frame. Real composables everywhere except where a test
 * says "stand-in" (named in the report).
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class Audit2ShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    // ---- harness ----

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

    private fun leftOf(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot().left.value

    private fun tagStartsWith(prefix: String) =
        SemanticsMatcher("test tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    private fun assertLeft(
        shot: String,
        what: String,
        expected: Float,
        matcher: SemanticsMatcher,
    ) = assertEquals("$shot: $what left edge (dp)", expected, leftOf(matcher), 0.5f)

    /** Every node whose test tag starts with [prefix] (at least [min] of them) ends at or before [limit] dp. */
    private fun assertRightsAtMost(
        shot: String,
        prefix: String,
        limit: Float,
        min: Int,
    ) {
        val nodes = compose.onAllNodes(tagStartsWith(prefix), useUnmergedTree = true)
        val n = nodes.fetchSemanticsNodes().size
        assertTrue("$shot: expected at least $min '$prefix*' nodes, found $n", n >= min)
        var maxRight = 0f
        for (i in 0 until n) {
            val r = nodes[i].getUnclippedBoundsInRoot().right.value
            maxRight = maxOf(maxRight, r)
            assertTrue("$shot: '$prefix' node $i right edge $r dp must be <= $limit", r <= limit)
        }
        println("shot $shot: $n '$prefix*' nodes, max right=$maxRight (limit $limit)")
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
                runTimeTicks = minutes(48),
                imageTags = mapOf(ImageType.PRIMARY to "ep"),
                userData = userData(id, positionMin = if (percent != null) 20 else 0, percent = percent),
            ),
        )
    }

    private val longTitle = "The Extraordinarily Long Title Of A Film That Will Not Fit On One Line"
    private val longChannel = "Sky Sport Premier League Extra HD Channel With A Very Long Name"

    // ---- 1. Collections hub ----

    @Test
    fun collectionsHub() {
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
        // 9 cards in the first section: three full rows of the 3-column grid, so the right edge shows against x = 912
        val sections =
            listOf(
                HubSection(
                    "collections",
                    "Collections",
                    listOf(
                        c(1, "Heist films", 24, 5),
                        c(2, "Neo-noir", 18),
                        c(3, "Studio Ghibli", 22, 3),
                        c(4, "Cold War thrillers", 15),
                        c(5, "Space opera", 31, 8),
                        c(6, "A24", 40),
                        c(7, "Heist films 2", 12),
                        c(8, "Slow burns", 9),
                        c(9, "Midnight movies", 14, 2),
                    ),
                ),
                HubSection("franchises", "Franchises", listOf(c(10, "Alien", 7), c(11, "Mad Max", 5, 1))),
            )
        shot(
            "audit2-collections-hub",
            // second card focused: the first stays unscaled, so its left edge is the layout's
            steps = { focusNode("audit2-collections-hub", hasTestTag("hub-tile1")) },
            measure = {
                logBox("audit2-collections-hub", "first tile", hasTestTag("hub-tile0"))
                assertLeft("audit2-collections-hub", "title", 96f, hasText("Collections"))
                assertLeft("audit2-collections-hub", "first tile", 96f, hasTestTag("hub-tile0"))
                // 48 dp from the right screen edge: the grid's cards (focus growth of the focused one included) end by x = 912
                assertRightsAtMost("audit2-collections-hub", "hub-tile", 912.5f, min = 3)
            },
        ) {
            JellytipCollectionsHubContent(sections = sections, failed = false, onJourneys = {}, onOpen = {}, modifier = Modifier.fillMaxSize())
        }
    }

    // ---- 2. Collection detail (what a hub card opens: JellytipHubCollectionPage -> JellytipHubCollectionContent) ----

    @Test
    fun collectionsDetail() {
        val library =
            listOf(
                "Heat", "Collateral", "Ronin", "The Town", "Blackhat", "Thief", "Sicario", "Drive", "Baby Driver", "Logan Lucky",
                "Widows", "Den of Thieves", "Ocean's Eleven", "Inside Man", "The Italian Job", "Dog Day Afternoon",
            ).map { movie(it, year = 2000) }
        val row =
            JellytipServerRow(
                id = "c1",
                source = "collections",
                family = "collection",
                title = "Heist films",
                layout = "grid",
                mediaType = "movie",
                itemIds = library.map { it.id },
                collection = null,
                missing = listOf(JellytipServerRow.Missing(tmdbId = 1, title = "Rififi", year = 1955, poster = "https://h/p/missing", rank = 0)),
            )
        shot(
            "audit2-collections-detail",
            // second tile focused: the first stays unscaled, so its left edge is the layout's
            steps = { focusNode("audit2-collections-detail", hasTestTag("collection-tile1")) },
            measure = {
                logBox("audit2-collections-detail", "title", hasText("HEIST FILMS"))
                assertLeft("audit2-collections-detail", "title", 96f, hasText("HEIST FILMS"))
                assertLeft("audit2-collections-detail", "first tile", 96f, hasTestTag("collection-tile0"))
                assertRightsAtMost("audit2-collections-detail", "collection-tile", 912.5f, min = 6)
            },
        ) {
            JellytipHubCollectionContent(
                id = "c1",
                title = "Heist films",
                r = row,
                library = library,
                failed = false,
                isAdmin = true,
                onOpenLib = {},
                onOpenVod = {},
                onOpenMissing = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // ---- 3. Home item menu (ContextMenuDialog, built like HomePage.onLongClickItem for a Continue Watching tile) ----

    private fun noopActions() =
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
        )

    private fun homeMenu(
        item: BaseItem,
        withDetails: Boolean,
    ) = ContextMenu.ForBaseItem(
        fromLongClick = true,
        item = item,
        chosenStreams = null,
        // HomePage: "Details" and "Go To" open the same page, so Details replaces Go To
        showGoTo = !withDetails,
        showStreamChoices = false,
        canDelete = false,
        canRemoveContinueWatching = true,
        canRemoveNextUp = false,
        // HomePage: homeTileHasDetailsItem(inContinueOrNextUpRow, type) = an EPISODE or film tile in Continue Watching / Next Up
        leadingItems = if (withDetails) listOf(DialogItem("Details", dismissOnClick = true) {}) else emptyList(),
        actions = noopActions(),
    )

    /** The menu is a Dialog window: capture the screen, not the root. Items are disabled for ~1 s after a long press (waitToLoad). */
    private fun menuShot(
        name: String,
        item: BaseItem,
        withDetails: Boolean,
        focusLabel: String,
    ) {
        val menu = homeMenu(item, withDetails)
        compose.setContent {
            Tv {
                Frame(inset = false) {
                    Text("Home (Continue watching)", color = Color.White, modifier = Modifier.padding(start = 96.dp, top = 40.dp))
                }
                ContextMenuDialog(onDismissRequest = {}, getMediaSource = null, contextMenu = menu, preferredSubtitleLanguage = null)
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        focusNode(name, hasText(focusLabel))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // The labels the dialog shows, in order, for the CI log
        val labels =
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { t -> t.text } ?: "(no text)"
            }
        println("shot $name: menu items=$labels")
        captureScreenRoboImage(out(name))
        val goTo = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.go_to)
        assertEquals("$name: exactly one Details item in $labels", 1, labels.count { it.startsWith("Details") })
        assertTrue("$name: Details is the first item in $labels", labels.firstOrNull()?.startsWith("Details") == true)
        assertFalse("$name: no Go To item in $labels", labels.any { it.startsWith(goTo) })
    }

    @Test
    fun homeCwMenuEpisode() = menuShot("audit2-home-cw-menu-episode", episode("Slow Horses", "Failure's Contagious", 3, 60.0), withDetails = true, focusLabel = "Details")

    @Test
    fun homeCwMenuFilm() = menuShot("audit2-home-cw-menu-film", movie("Heat", 35.0), withDetails = true, focusLabel = "Details")

    // ---- 4. The four event tiles ----

    private val now = System.currentTimeMillis() / 1000

    private fun ch(
        id: String,
        name: String,
        quality: IptvQuality? = null,
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
        quality = quality,
        alternateChannels = emptyList(),
    )

    @Composable
    private fun Sized(
        tag: String,
        caption: String,
        tile: @Composable (Modifier) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tile(Modifier.testTag(tag))
            Text(caption, color = Color(0xFF00FFFF), fontSize = 11.sp)
        }
    }

    @Test
    fun eventTiles() {
        val alternates = listOf(ch("a1", "Fox League 2"), ch("a2", "Kayo Sports 4"))
        val onNow =
            IptvOnNow(
                channel = ch("c1", longChannel, quality = IptvQuality("fhd50", 1080, 50.0, "atmos")),
                title = longTitle,
                start = now - 1800,
                stop = now + 3600,
                sport = true,
                kind = "live",
                alternates = alternates,
                league = "NRL",
                sportName = "rugby league",
            )
        val upcoming =
            IptvUpcoming(
                key = "u1",
                title = longTitle,
                league = "Super Rugby Pacific",
                favourite = true,
                start = now + 7200,
                stop = now + 14400,
                kind = "live",
                channel = ch("c2", longChannel, quality = IptvQuality("hd", 720, 25.0, null)),
                alternateChannels = alternates,
                recordable = true,
                recordingId = "r1",
                phase = "pre",
                startsAt = now + 7200,
            )
        val recording =
            IptvRecording(
                id = "r1",
                channelId = "c3",
                channel = longChannel,
                title = longTitle,
                desc = "A long description of the recorded programme that will not fit on a single line of the tile",
                start = now - 7200,
                stop = now - 600,
                state = "done",
                bytes = 1_000_000L,
                error = null,
                watched = false,
                playable = true,
                positionSecs = null,
                durationSecs = null,
                logo = "https://h/logo/c3",
            )
        shot(
            "audit2-event-tiles",
            inset = false,
            measure = {
                for (t in listOf("t-guide", "t-home", "t-upcoming", "t-recording")) logBox("audit2-event-tiles", t, hasTestTag(t))
                fun size(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot().let { (it.right - it.left).value to (it.bottom - it.top).value }
                for ((tag, w, h) in listOf(Triple("t-guide", 230f, 56f), Triple("t-home", 300f, 84f), Triple("t-upcoming", 300f, 84f), Triple("t-recording", 300f, 84f))) {
                    val (mw, mh) = size(tag)
                    assertEquals("audit2-event-tiles: $tag width (dp)", w, mw, 0.5f)
                    assertEquals("audit2-event-tiles: $tag height (dp)", h, mh, 0.5f)
                }
            },
        ) {
            Column(Modifier.padding(start = 96.dp, top = 60.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    // Guide "On now" strip (JellytipIptvOnNow.kt:187): 230 x 56; Home "Live sport on now": 300 x 84 (same as Upcoming / Recordings)
                    Sized("t-guide", "OnNowCard guide 230 x 56 dp") { m -> OnNowCard(onNow, now, 230.dp, 56.dp, onClick = {}, modifier = m) }
                    Sized("t-home", "OnNowCard Home 300 x 84 dp") { m -> OnNowCard(onNow, now, 300.dp, 84.dp, onClick = {}, modifier = m) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    // Upcoming sport tile and Recordings (Home) tile: both fixed at 300 x 84 inside the composable
                    Sized("t-upcoming", "UpcomingTile 300 x 84 dp") { m -> UpcomingTile(upcoming, now, onClick = {}, modifier = m) }
                    Sized("t-recording", "RecordingCard 300 x 84 dp") { m -> RecordingCard(recording, now, onClick = {}, modifier = m) }
                }
            }
        }
    }

    // ---- 5. Search first screen (RECENT / TRY) ----

    @Test
    fun search() {
        shot(
            "audit2-search",
            // second Recent chip focused: the first stays unscaled, so its left edge is the layout's
            steps = { focusNode("audit2-search", hasText("heat")) },
            measure = {
                logBox("audit2-search", "RECENT title", hasText("RECENT"))
                logBox("audit2-search", "TRY title", hasText("TRY"))
                logBox("audit2-search", "first Recent chip", hasText("severance"))
                logBox("audit2-search", "first Try chip", hasText("slow horses"))
                // The chips are the clickable (merged) nodes; the labels are the Text nodes (ItemRowTitle's 8 dp is outside its bounds)
                val chipLeft = { text: String -> compose.onAllNodes(hasText(text)).onFirst().getUnclippedBoundsInRoot().left.value }
                assertEquals("audit2-search: first Recent chip vs RECENT label (dp)", leftOf(hasText("RECENT")), chipLeft("severance"), 0.5f)
                assertEquals("audit2-search: first Try chip vs TRY label (dp)", leftOf(hasText("TRY")), chipLeft("slow horses"), 0.5f)
            },
        ) {
            // Stand-in for the top row (VoiceSearchButton + SearchEditTextBox take a Hilt-provided voice manager); the column and
            // padding are SearchContent's: start 24 (inside the 72 dp page inset), top PageTop, end PageEnd
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = JellytipLayout.PageTop, end = JellytipLayout.PageEnd),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(Color.White.copy(alpha = 0.12f), CircleShape))
                    Box(Modifier.width(480.dp).height(48.dp).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.CenterStart) {
                        Text("stand-in: search box", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(start = 12.dp))
                    }
                    Chip("Not sure? Ask") {}
                }
                SuggestionsContent(
                    recent = listOf("severance", "heat", "nrl", "the bear", "ghibli"),
                    suggested = listOf("slow horses", "heist films", "studio ghibli", "warriors", "documentaries"),
                    onPick = {},
                    onClearRecent = {},
                )
            }
        }
    }

    // ---- 6. Nav drawer ----

    private val drawerUser = JellyfinUser(id = uuid("user"), name = "Ricki Hendersonville-Longname", serverId = uuid("server"), accessToken = null)

    private fun inboxService(): JellytipInboxService? =
        runCatching {
            val svc = mockk<JellytipInboxService>(relaxed = true)
            every { svc.store } returns MutableStateFlow<Inbox.Store?>(null)
            svc
        }.onFailure { println("shot audit2-sidebar: inbox service not mocked, Inbox item skipped (${it.message?.lineSequence()?.firstOrNull()})") }.getOrNull()

    /** The items NavDrawer's drawerContent draws, with plain values (profile, Search, Home, Inbox, libraries, Settings + storage line). */
    @Composable
    private fun NavigationDrawerScope.DrawerItems(
        isOpen: Boolean,
        inbox: JellytipInboxService?,
    ) {
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
                ProfileIcon(
                    user = drawerUser,
                    imageUrl = null,
                    serverName = "my-very-long-server-hostname.example.com",
                    drawerOpen = isOpen,
                    onClick = {},
                )
                LazyColumn(
                    contentPadding = PaddingValues(0.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedByWithFooter(4.dp),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    item { IconNavItem(text = stringResource(R.string.search), icon = Icons.Default.Search, selected = false, drawerOpen = isOpen, onClick = {}) }
                    item { IconNavItem(text = stringResource(R.string.home), icon = Icons.Default.Home, selected = true, drawerOpen = isOpen, onClick = {}) }
                    if (inbox != null) {
                        item { JellytipInboxNavItem(service = inbox, userId = "u1", selected = false, drawerOpen = isOpen, onClick = {}) }
                    }
                    itemsIndexed(libraries) { _, lib -> NavItem(library = lib, selected = false, moreExpanded = false, drawerOpen = isOpen, onClick = {}) }
                    item { NavItem(library = NavDrawerItem.Collections, selected = false, moreExpanded = false, drawerOpen = isOpen, onClick = {}) }
                    item {
                        IconNavItem(
                            subtext = "325 GB free of 3.7 TB",
                            text = stringResource(R.string.settings),
                            icon = Icons.Default.Settings,
                            selected = false,
                            drawerOpen = isOpen,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    private fun drawerShot(
        name: String,
        open: Boolean,
    ) {
        val inbox = inboxService()
        shot(
            name,
            inset = false,
            steps = { if (open) focusNode(name, hasText("Home")) },
            measure = {
                // x of each label (left edge of its text), for the closed / open comparison
                for (label in listOf("Search", "Home", "Inbox", "Movies", "Collections", "Settings", "325 GB free of 3.7 TB", "Ricki Hendersonville-Longname")) {
                    logBox(name, "label '$label'", hasText(label))
                }
                if (open) {
                    // Item labels (headlines) share ONE x; every label and sub-line ends 16 dp before the drawer's right edge
                    val headlines = listOfNotNull("Search", "Home", "Inbox".takeIf { inbox != null }, "Movies", "TV Shows", "Collections", "Settings", "Ricki Hendersonville-Longname")
                    val lefts = headlines.associateWith { leftOf(hasText(it)) }
                    println("shot $name: label lefts=$lefts")
                    val first = lefts.values.first()
                    for ((label, left) in lefts) assertEquals("$name: label '$label' left (dp) vs '${headlines.first()}'", first, left, 0.5f)
                    val limit = ExpandedDrawerItemWidth.value - 16f
                    val all = headlines + listOf("325 GB free of 3.7 TB", "my-very-long-server-hostname.example.com")
                    for (label in all) {
                        val right = compose.onAllNodes(hasText(label), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot().right.value
                        assertTrue("$name: '$label' right edge $right dp must be <= $limit", right <= limit + 0.5f)
                    }
                }
            },
        ) {
            val state = rememberDrawerState(if (open) DrawerValue.Open else DrawerValue.Closed)
            ModalNavigationDrawer(
                modifier = Modifier.fillMaxSize(),
                drawerState = state,
                drawerContent = { v -> DrawerItems(v == DrawerValue.Open, inbox) },
            ) {
                // Stand-in page: DestinationContent's 72 dp start inset (64 + 8), pushed 160 dp right while the drawer is open
                Box(Modifier.fillMaxSize().offset(x = if (open) 160.dp else 0.dp).padding(start = 72.dp, end = 16.dp)) {
                    Text("PAGE CONTENT (stand-in)", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(start = 24.dp, top = 40.dp))
                }
            }
        }
    }

    @Test
    fun sidebarOpen() = drawerShot("audit2-sidebar-open", open = true)

    @Test
    fun sidebarClosed() = drawerShot("audit2-sidebar-closed", open = false)

    // ---- 7. Settings side panel ----

    @Test
    fun settingsPanel() {
        shot(
            "audit2-settings-panel",
            steps = { focusNode("audit2-settings-panel", hasText("Auto-play next episode")) },
            measure = {
                logBox("audit2-settings-panel", "panel", hasTestTag("settings-panel"))
                logBox("audit2-settings-panel", "title", hasText("Settings"))
                logBox("audit2-settings-panel", "group title", hasText("Playback"))
                logBox("audit2-settings-panel", "switch row", hasText("Auto-play next episode"))
                // Stand-in geometry (see above): rows end by x = 912 and the title starts at or below y = 28
                val title = compose.onAllNodes(hasText("Settings"), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
                assertTrue("audit2-settings-panel: title top ${title.top.value} dp must be >= 27.5", title.top.value >= 27.5f)
                for (row in listOf("Auto-play next episode", "Skip intros", "Preferred audio language", "Customise home", "Show clock")) {
                    val b = compose.onAllNodes(hasText(row)).onFirst().getUnclippedBoundsInRoot()
                    println("shot audit2-settings-panel: row '$row' left=${b.left.value} right=${b.right.value} w=${b.right.value - b.left.value}")
                    assertTrue("audit2-settings-panel: row '$row' right edge ${b.right.value} dp must be <= 912.5", b.right.value <= 912.5f)
                }
            },
        ) {
            // STAND-IN container with PreferencesPage / PreferencesContent's geometry (PreferencesContent.kt: fillMaxWidth(.4f),
            // fillMaxHeight, TopEnd inside the page inset; Column padding top PageTop + end 16 inside the background; title
            // headlineSmall centred, end 16, vertical padding 8; LazyColumn contentPadding 16; group title titleMedium, padding
            // top 8 bottom 4); the rows are the real SwitchPreference / ClickPreference. The asserts below measure THIS stand-in,
            // not the real PreferencesContent (three Hilt view models): keep it in step with PreferencesContent.kt.
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .testTag("settings-panel")
                            .fillMaxWidth(.4f)
                            .fillMaxHeight()
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                            .padding(top = JellytipLayout.PageTop, end = 16.dp),
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp).padding(vertical = 8.dp),
                    )
                    LazyColumn(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item {
                            Text(
                                text = "Playback",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        item { SwitchPreference(title = "Auto-play next episode", value = true, onClick = {}, summaryOn = "Starts the next episode after the credits", summaryOff = null) }
                        item { SwitchPreference(title = "Skip intros", value = false, onClick = {}, summaryOn = null, summaryOff = "Off") }
                        item { ClickPreference(title = "Preferred audio language", onClick = {}, summary = "English") }
                        item {
                            Text(
                                text = "Interface",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        item { ClickPreference(title = "Customise home", onClick = {}, summary = "Rows, order and size") }
                        item { SwitchPreference(title = "Show clock", value = true, onClick = {}, summaryOn = "On", summaryOff = null) }
                    }
                }
            }
        }
    }
}
