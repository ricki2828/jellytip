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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.jellytip.JellytipDetailsPullQuote
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.JellytipQuote
import com.github.damontecres.wholphin.jellytip.JellytipReview
import com.github.damontecres.wholphin.jellytip.JellytipReviewCard
import com.github.damontecres.wholphin.jellytip.JellytipReviews
import com.github.damontecres.wholphin.jellytip.JellytipRowFocusScope
import com.github.damontecres.wholphin.jellytip.JellytipSkeleton
import com.github.damontecres.wholphin.jellytip.discover.Chip
import com.github.damontecres.wholphin.jellytip.discover.DiscoverBrowseTile
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle
import com.github.damontecres.wholphin.jellytip.discover.JellytipDiscoverEntry
import com.github.damontecres.wholphin.jellytip.discover.RequestState
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.iptv.IdentifiedMark
import com.github.damontecres.wholphin.jellytip.iptv.IptvChannel
import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisodeCard
import com.github.damontecres.wholphin.jellytip.iptv.IptvLastFail
import com.github.damontecres.wholphin.jellytip.iptv.IptvOnNow
import com.github.damontecres.wholphin.jellytip.iptv.IptvProgress
import com.github.damontecres.wholphin.jellytip.iptv.IptvQuality
import com.github.damontecres.wholphin.jellytip.iptv.IptvRecording
import com.github.damontecres.wholphin.jellytip.iptv.IptvUpcoming
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile
import com.github.damontecres.wholphin.jellytip.iptv.KindPill
import com.github.damontecres.wholphin.jellytip.iptv.OnNowCard
import com.github.damontecres.wholphin.jellytip.iptv.Pill
import com.github.damontecres.wholphin.jellytip.iptv.PreGamePill
import com.github.damontecres.wholphin.jellytip.iptv.QualityChips
import com.github.damontecres.wholphin.jellytip.iptv.RecordingCard
import com.github.damontecres.wholphin.jellytip.iptv.SoonPill
import com.github.damontecres.wholphin.jellytip.iptv.SportMark
import com.github.damontecres.wholphin.jellytip.iptv.TimePill
import com.github.damontecres.wholphin.jellytip.iptv.UpcomingTile
import com.github.damontecres.wholphin.jellytip.journeys.JourneyCard
import com.github.damontecres.wholphin.jellytip.journeys.JourneyKind
import com.github.damontecres.wholphin.jellytip.journeys.JourneySummary
import com.github.damontecres.wholphin.jellytip.nav.JellytipBreadcrumb
import com.github.damontecres.wholphin.jellytip.nav.JellytipCrumb
import com.github.damontecres.wholphin.jellytip.nav.JellytipSeasonChips
import com.github.damontecres.wholphin.jellytip.jellytipFocusRoom
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes
import com.github.damontecres.wholphin.jellytip.rows.JellytipCollection
import com.github.damontecres.wholphin.jellytip.rows.MissingPoster
import com.github.damontecres.wholphin.jellytip.search.PosterCard
import com.github.damontecres.wholphin.jellytip.specials.Caption
import com.github.damontecres.wholphin.jellytip.specials.Kicker
import com.github.damontecres.wholphin.jellytip.specials.SpecialCard
import com.github.damontecres.wholphin.jellytip.specials.SpecialTokens
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.TimeDisplay
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.util.Clock
import com.github.damontecres.wholphin.ui.util.LocalClock
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Design-audit shots (for eyes, not compared). Same harness and @Config as LaneBShotsTest / FixtureArtShotsTest (sdk 34,
 * w960dp-h540dp-land-xhdpi = a 1920x1080 TV at 2x). Unlike those, each shot fills the whole 960x540 dp frame with no
 * padding, and draws magenta 1 dp guides at the TV safe area (x = 48 / 912 dp, y = 27 / 513 dp) so margins can be
 * measured from the PNG. Every composable is the real one; the focused element is tagged "focus" (or, where the
 * composable takes no modifier, the first clickable node is focused through the test framework).
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class AuditShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val now = System.currentTimeMillis() / 1000

    private val focus = Modifier.testTag("focus")

    private val longTitle = "The Extraordinarily Long Title Of A Film That Will Not Fit On One Line"

    /** A colourful synthetic picture for every AsyncImage (posters, stills, logos). */
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

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        val handler = AsyncImagePreviewHandler { _ -> photo }
        CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides handler) {
            MaterialTheme(colorScheme = darkColorScheme()) { content() }
        }
    }

    /** The full 960x540 dp dark frame with the safe-area guides drawn on top of [content]. */
    @Composable
    private fun Frame(content: @Composable BoxScope.() -> Unit) {
        val guide = Color(0xFFFF00FF)
        Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
            content()
            Box(Modifier.offset(x = 48.dp).fillMaxHeight().width(1.dp).background(guide))
            Box(Modifier.offset(x = 912.dp).fillMaxHeight().width(1.dp).background(guide))
            Box(Modifier.offset(y = 27.dp).fillMaxWidth().height(1.dp).background(guide))
            Box(Modifier.offset(y = 513.dp).fillMaxWidth().height(1.dp).background(guide))
        }
    }

    /** Render [content] in a [Frame], focus the "focus"-tagged node (else the first clickable one) if [focused], capture. */
    private fun shot(
        name: String,
        focused: Boolean = false,
        content: @Composable BoxScope.() -> Unit,
    ) {
        compose.setContent { Tv { Frame(content) } }
        compose.waitForIdle()
        if (focused) {
            // A shot without its focus is still worth having: say so in the log rather than failing the build
            runCatching {
                if (compose.onAllNodesWithTag("focus").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("focus").requestFocus()
                } else {
                    compose.onAllNodes(hasClickAction()).onFirst().requestFocus()
                }
            }.onFailure { println("shot $name: focus not set (${it.message?.lineSequence()?.firstOrNull()})") }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(out(name))
    }

    // ---- synthetic data ----

    private fun vod(
        id: String,
        title: String,
        poster: String? = "https://h/p/$id",
        backdrop: String? = "https://h/b/$id",
        quality: List<String> = listOf("4k", "hdr"),
        audio: String? = "atmos",
        progress: IptvProgress? = null,
        played: Boolean = false,
        favourite: Boolean = false,
    ) = IptvVodItem(
        id = id,
        kind = "movie",
        tmdbId = null,
        title = title,
        year = 2021,
        quality = quality,
        poster = poster,
        backdrop = backdrop,
        overview = "",
        rating = 7.4,
        providerName = null,
        progress = progress,
        audio = audio,
        played = played,
        favourite = favourite,
    )

    private fun vods() =
        listOf(
            vod("1", longTitle, progress = IptvProgress(1800.0, 7200.0, false), favourite = true),
            vod("2", "Heat", played = true),
            vod("3", "Collateral", poster = null, backdrop = null, quality = emptyList(), audio = null),
            vod("4", "Alien", quality = listOf("1080p")),
        )

    private fun ch(
        id: String,
        name: String,
        logo: String? = "https://h/logo/$id",
        quality: IptvQuality? = null,
        alternates: List<IptvChannel> = emptyList(),
    ) = IptvChannel(
        id = id,
        providerId = "p1",
        num = 101,
        name = name,
        logo = logo,
        group = "Sport",
        epgId = null,
        catchupDays = 3,
        favourite = false,
        quality = quality,
        alternateChannels = alternates,
    )

    private val longChannel = "Sky Sport Premier League Extra HD Channel With A Very Long Name"

    // ---- shots ----

    @Test
    fun clock() =
        shot("audit-clock") {
            CompositionLocalProvider(LocalClock provides Clock(timeString = mutableStateOf("21:47"))) {
                TimeDisplay()
            }
        }

    @Test
    fun badges() =
        shot("audit-badges") {
            Column(Modifier.padding(start = 96.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindPill("live")
                    KindPill("replay")
                    PreGamePill()
                    SoonPill()
                    TimePill("Sat 19:30")
                    IdentifiedMark()
                    Pill("A LONG PILL LABEL", Color.White, Color(0xFF5A5F6B))
                    SportMark(Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    QualityChips(IptvQuality("fhd50", 1080, 50.0, "atmos"))
                    QualityChips(IptvQuality("slow", 720, 25.0, "5.1", hint = "may buffer"))
                    QualityChips(IptvQuality("uhd", 2160, 50.0, null))
                }
            }
        }

    @Test
    fun reviewsRowFocused() {
        val reviews =
            listOf(
                JellytipReview("1", "Ricki", true, 4.5, 12, "A tight, mean little thriller that earns every one of its 140 minutes. The last act is the best.", "", false, "letterboxd"),
                JellytipReview("2", "A Reviewer With An Extraordinarily Long Display Name", false, 3.0, 12_400, "Beautifully shot but the long middle section drags. The performances carry it, particularly the leads, and the score is excellent throughout.", "", false, "letterboxd"),
                JellytipReview("3", "spoiler_person", false, 5.0, 880, "", "", true, "letterboxd"),
                JellytipReview("4", "no_rating", false, null, null, "Short.", "", false, "letterboxd"),
            )
        shot("audit-reviews-row-focused", focused = true) {
            Column(Modifier.padding(start = 96.dp, top = 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemRowTitle("Reviews · Letterboxd")
                // As JellytipReviewsRow builds it (the row itself keeps its cards private, so no tag could reach the first)
                JellytipRowFocusScope { rowFocusModifier ->
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(8.dp),
                        modifier = rowFocusModifier.fillMaxWidth(),
                    ) {
                        itemsIndexed(reviews, key = { _, r -> r.id }) { index, review ->
                            JellytipReviewCard(review = review, onClick = {}, modifier = if (index == 0) focus else Modifier)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun iptvEpisodeRowFocused() {
        val episodes =
            listOf(
                IptvEpisode("e1", 1, longTitle, 58, null, "https://h/s/1", IptvProgress(1500.0, 3500.0, false)),
                IptvEpisode("e2", 2, "Dead Weight", 47, null, "https://h/s/2", IptvProgress(3000.0, 3000.0, true)),
                IptvEpisode("e3", 3, "No Still", null, null, null, null, lastFail = IptvLastFail(System.currentTimeMillis() / 1000 - 7200, "stall", 1)),
                IptvEpisode("e4", 4, "Strange Games", 52, null, "https://h/s/4", null),
            )
        shot("audit-iptv-episode-row-focused", focused = true) {
            // JellytipIptvVodUi.kt:635: LazyRow(spacedBy(12.dp)), no contentPadding; the row starts at x = 96 dp
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = JellytipLayout.FocusRoom),
                modifier = Modifier.padding(start = 96.dp, top = 200.dp).fillMaxWidth().jellytipFocusRoom(),
            ) {
                itemsIndexed(episodes, key = { _, e -> e.id }) { _, e -> IptvEpisodeCard(e) {} }
            }
        }
    }

    @Test
    fun iptvVodTilesFocused() =
        shot("audit-iptv-vod-tiles-focused", focused = true) {
            val items = vods()
            Column(Modifier.padding(start = 96.dp, top = 100.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(items, key = { _, i -> "w" + i.id }) { index, i ->
                        IptvVodTile(item = i, width = (106 * 16 / 9).dp, height = 106.dp, poster = false, onClick = {}, modifier = if (index == 0) focus else Modifier)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(items, key = { _, i -> "p" + i.id }) { _, i ->
                        IptvVodTile(item = i, width = 100.dp, height = 150.dp, poster = true, onClick = {})
                    }
                }
            }
        }

    @Test
    fun journeysRowFocused() {
        val cards =
            listOf(
                JourneySummary("d:1", JourneyKind.DIRECTOR, "Denis Villeneuve", "Director", 8),
                JourneySummary("f:1", JourneyKind.FRANCHISE, "Alien in order · 6 films", null, 6),
                JourneySummary("l:1", JourneyKind.LIST, "$longTitle in a list", "Letterboxd list", 120),
            )
        shot("audit-journeys-row-focused", focused = true) {
            // JellytipJourneysPage.kt ~244: LazyRow(spacedBy(12.dp), contentPadding = PaddingValues(end = 24.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = JellytipLayout.FocusRoom, end = 24.dp),
                modifier = Modifier.padding(start = 96.dp, top = 200.dp).fillMaxWidth().jellytipFocusRoom(),
            ) {
                itemsIndexed(cards, key = { _, s -> s.id }) { index, s -> JourneyCard(s, modifier = if (index == 0) focus else Modifier) {} }
            }
        }
    }

    @Test
    fun breadcrumb() =
        shot("audit-breadcrumb") {
            Column(Modifier.padding(start = 96.dp, top = 120.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                JellytipBreadcrumb(
                    listOf(
                        JellytipCrumb("Slow Horses", {}),
                        JellytipCrumb("Season 6", {}),
                        JellytipCrumb("E2 · Dead Weight", {}),
                    ),
                )
                JellytipBreadcrumb(
                    listOf(
                        JellytipCrumb("The Extraordinarily Long Series Title", {}),
                        JellytipCrumb("Season 12", {}),
                        JellytipCrumb("E10 · An Episode Title That Is Also Quite Long", {}),
                    ),
                )
                JellytipSeasonChips(
                    labels = listOf("S1", "S2", "S3", "S4", "S5", "S6", "Specials"),
                    selectedIndex = 5,
                    onSelect = {},
                    selectedRequester = remember { FocusRequester() },
                )
            }
        }

    @Test
    fun specialsText() =
        shot("audit-specials-text") {
            Column(Modifier.padding(start = 96.dp, top = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Kicker("Trailer break")
                Kicker("A very long kicker label that keeps going past the edge")
                Caption("Because you watched Heat and loved the heist pacing", alpha = 0.9f, px = JellytipLayout.MIN_TEXT_PX)
                Caption("2021 · 2h 14m · 7.4", alpha = 0.65f)
                Caption("More picks for tonight ›", alpha = 0.95f, italic = true, px = JellytipLayout.MIN_TEXT_PX, maxLines = 3)
                Caption("$longTitle (a long caption, one line)", alpha = 0.75f)
            }
        }

    @Test
    fun discoverPosters() {
        fun entry(
            id: Int,
            title: String,
            poster: String?,
            inLibrary: Boolean,
            state: RequestState?,
        ) = JellytipDiscoverEntry(DiscoverTitle(id, "movie", title, 2021, posterUrl = poster), inLibrary, null, state)
        val entries =
            listOf(
                entry(1, longTitle, "https://h/p/1", true, null),
                entry(2, "Heat", "https://h/p/2", false, RequestState("requested")),
                entry(3, "Collateral", null, false, RequestState("downloading", 42)),
                entry(4, longTitle, null, false, null),
                entry(5, "Alien", "https://h/p/5", false, null),
            )
        shot("audit-discover-posters", focused = true) {
            Column(Modifier.padding(start = 96.dp, top = 100.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(entries, key = { _, e -> e.title.tmdbId }) { index, e ->
                        DiscoverBrowseTile(entry = e, onClick = {}, modifier = if (index == 0) focus else Modifier)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Movies", true) {}
                    Chip("TV", false) {}
                    Chip("A longer chip label", false) {}
                }
            }
        }
    }

    @Test
    fun searchPostersFocused() =
        shot("audit-search-posters-focused", focused = true) {
            val posters =
                listOf(
                    Triple("1", "https://h/p/1", "$longTitle · 2021"),
                    Triple("2", "https://h/p/2", "Heat · 1995"),
                    Triple("3", null, "No Poster · 2020"),
                    Triple("4", "https://h/p/4", "Collateral · 2004"),
                    Triple("5", null, "$longTitle · 1999"),
                )
            // As JellytipSearchPage builds it: spacedBy(12), contentPadding 8/8
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.padding(start = 88.dp, top = 160.dp).fillMaxWidth(),
            ) {
                itemsIndexed(posters, key = { _, p -> p.first }) { _, p -> PosterCard(p.second, p.third) {} }
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
        shot("audit-event-tiles") {
            Column(Modifier.padding(start = 96.dp, top = 120.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    // Guide "On now" strip: 230 x 56; Home "Live sport on now": 280 x 72
                    OnNowCard(onNow, now, 230.dp, 56.dp, onClick = {})
                    OnNowCard(onNow, now, 280.dp, 72.dp, onClick = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    // Upcoming sport tile and Recordings (Home) tile: both 300 x 84
                    UpcomingTile(upcoming, now, onClick = {})
                    RecordingCard(recording, now, onClick = {})
                }
            }
        }
    }

    @Test
    fun cardSubtitle() {
        val missing = JellytipCollection.Missing(1, longTitle, 2021, "https://h/p/m", true)
        shot("audit-card-subtitle") {
            val sizes = rememberJellytipSizes()
            Row(Modifier.padding(start = 96.dp, top = 160.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                // Home wide card: title + subtitle, built from the same SpecialCard / Caption the rows use
                SpecialCard(SpecialTokens.WIDE_W, SpecialTokens.WIDE_H, onClick = {}, onLongClick = {}, modifier = Modifier) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.55f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(sizes.px(12))) {
                        Text(
                            text = longTitle,
                            color = Color.White,
                            fontFamily = JellytipFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = sizes.sp(JellytipLayout.MIN_TEXT_PX),
                            maxLines = 2,
                        )
                        Caption("Because you watched Heat", alpha = 0.75f, px = JellytipLayout.MIN_TEXT_PX)
                    }
                }
                // Person card (PersonRow in JellytipRowsUi.kt): name + "N films" Caption at px = 16, as that card draws them
                SpecialCard(SpecialTokens.POSTER_W, SpecialTokens.POSTER_H, onClick = {}, onLongClick = {}, modifier = Modifier) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.55f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(sizes.px(12))) {
                        Text(
                            text = "Denis Villeneuve",
                            color = Color.White,
                            fontFamily = JellytipFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = sizes.sp(JellytipLayout.MIN_TEXT_PX),
                            maxLines = 2,
                        )
                        Caption("8 films", alpha = 0.75f, px = JellytipLayout.MIN_TEXT_PX)
                    }
                }
                // The real "missing part" poster (collection / franchise rows): title text + "Get it"
                Box(Modifier.size(SpecialTokens.POSTER_W, SpecialTokens.POSTER_H).background(Color(0xFF141414))) {
                    MissingPoster(missing, showGetIt = true)
                }
            }
        }
    }

    @Test
    fun detailsQuote() {
        val reviews =
            JellytipReviews(
                source = "letterboxd",
                quote = JellytipQuote("A tight, mean little thriller that earns every one of its 140 minutes, and the last act is the best thing the director has done in a decade of trying.", "A Reviewer", "letterboxd"),
                reviews = listOf(JellytipReview("1", "A Reviewer", false, 4.5, 1200, "x", "", false, "letterboxd")),
            )
        shot("audit-details-quote") {
            JellytipDetailsPullQuote(reviews = reviews, alpha = { 1f })
        }
    }

    @Test
    fun skeletons() =
        shot("audit-skeletons") {
            // rows/JellytipListPage.kt:73: page title, then a one-row skeleton of 5 tiles (140 x 210, no title block)
            Column(Modifier.padding(start = 24.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("A Letterboxd list name", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
                JellytipSkeleton(tileW = 140.dp, tileH = 210.dp, rows = 1, perRow = 5, title = false)
            }
        }
}
