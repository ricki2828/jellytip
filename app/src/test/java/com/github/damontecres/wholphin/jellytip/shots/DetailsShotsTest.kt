package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.jellytip.JellytipAudience
import com.github.damontecres.wholphin.jellytip.JellytipDetailsSource
import com.github.damontecres.wholphin.jellytip.JellytipExtras
import com.github.damontecres.wholphin.jellytip.JellytipSettings
import com.github.damontecres.wholphin.jellytip.LocalJellytipDetailsSource
import com.github.damontecres.wholphin.jellytip.cache.ImagePick
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.iptv.IptvCastMember
import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvProgress
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodDetail
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.JellytipIptvDetailsContent
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.detail.episode.EpisodeDetailsContent
import com.github.damontecres.wholphin.ui.detail.movie.MovieDetailsContent
import com.github.damontecres.wholphin.ui.detail.movie.MovieState
import com.github.damontecres.wholphin.ui.detail.series.EpisodeList
import com.github.damontecres.wholphin.ui.detail.series.SeriesDetailsContent
import com.github.damontecres.wholphin.ui.detail.series.SeriesOverviewContent
import com.github.damontecres.wholphin.ui.detail.series.SeriesOverviewPosition
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/*
 * Details-page shots of the CURRENT pages (commit 0 of the details unification; for eyes, not compared). Same harness and
 * @Config as AuditShotsTest (sdk 34, w960dp-h540dp-land-xhdpi = a 1920x1080 TV at 2x), full 960x540 dp frame with the
 * magenta safe-area guides. The composables are the real page contents (MovieDetailsContent, SeriesDetailsContent,
 * SeriesOverviewContent, EpisodeDetailsContent, JellytipIptvDetailsContent); the Jellytip data seam is a fake provided
 * through LocalJellytipDetailsSource, images are the synthetic photo. The page content is inset 72 dp at the start and
 * 16 dp at the end, as in the app (collapsed nav drawer; see fillFullBleed).
 *
 * The clock is driven by hand (autoAdvance = false): some pages hold infinite animations (the Down hint) that would
 * keep waitForIdle spinning.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class DetailsShotsTest {
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

    /** Image urls are built from a mocked api client, as ImageUrlServiceTest does. */
    private val imageUrlService by lazy {
        val api = mockk<ApiClient>()
        every { api.baseUrl } returns "http://localhost"
        every { api.createUrl(any(), any(), any(), any()) } answers { callOriginal() }
        every { api.imageApi } returns ImageApi(api)
        ImageUrlService(api)
    }

    private class FakeDetailsSource : JellytipDetailsSource {
        override val settings: Flow<JellytipSettings> = flowOf(JellytipSettings())
        override val httpClient: OkHttpClient = OkHttpClient()

        override suspend fun getExtras(item: BaseItem): JellytipExtras? = null

        override suspend fun getAudience(item: BaseItem): JellytipAudience? = null

        override suspend fun detailsPick(item: BaseItem): ImagePick? = null

        override fun cachedDetailsPick(item: BaseItem): ImagePick? = null

        override fun stillChoice(id: UUID): Flow<Int> = flowOf(0)

        override fun stillUrls(
            item: BaseItem,
            extras: JellytipExtras?,
        ): List<String> = listOf("https://h/still/${item.id}")

        override fun trailerUrl(trailer: LocalTrailer): String? = null
    }

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        val handler = AsyncImagePreviewHandler { _ -> photo }
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides handler,
            LocalImageUrlService provides imageUrlService,
            LocalJellytipDetailsSource provides FakeDetailsSource(),
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) { content() }
        }
    }

    @Composable
    private fun Frame(content: @Composable BoxScope.() -> Unit) {
        val guide = Color(0xFFFF00FF)
        Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
            Box(Modifier.fillMaxSize().padding(start = 72.dp, end = 16.dp)) { content() }
            Box(Modifier.offset(x = 48.dp).fillMaxHeight().width(1.dp).background(guide))
            Box(Modifier.offset(x = 912.dp).fillMaxHeight().width(1.dp).background(guide))
            Box(Modifier.offset(y = 27.dp).fillMaxWidth().height(1.dp).background(guide))
            Box(Modifier.offset(y = 513.dp).fillMaxWidth().height(1.dp).background(guide))
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** Focus the clickable node showing [text] (a button / chip); only logs when it cannot. */
    private fun focusText(
        name: String,
        text: String,
    ) {
        runCatching {
            val m: SemanticsMatcher = hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text)))
            compose.onAllNodes(m).onFirst().requestFocus()
        }.onFailure { println("shot $name: focus on '$text' not set (${it.message?.lineSequence()?.firstOrNull()})") }
    }

    private fun pressDown(name: String) {
        runCatching {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
        }.onFailure { println("shot $name: Down key not sent (${it.message?.lineSequence()?.firstOrNull()})") }
    }

    /**
     * Render [content] in the frame, let the page settle (the details still waits 500 ms for its ranking), run [steps]
     * (focus / keys), settle again, capture.
     */
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
        compose.onRoot().captureRoboImage(out(name))
    }

    // ---- synthetic data ----

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray())

    private fun minutes(m: Long) = m * 600_000_000L

    private fun userData(
        itemId: UUID,
        positionMin: Long = 0,
        played: Boolean = false,
        percent: Double? = null,
        favourite: Boolean = false,
    ) = UserItemDataDto(
        playedPercentage = percent,
        playbackPositionTicks = minutes(positionMin),
        playCount = if (played) 1 else 0,
        isFavorite = favourite,
        lastPlayedDate = null,
        played = played,
        key = "",
        itemId = itemId,
    )

    private val heatOverview =
        "A group of high-end professional thieves start to feel the heat from the LAPD when they unknowingly leave a clue " +
            "at their latest heist. A obsessive detective who has already sacrificed two marriages to the job makes it his mission to bring them down."

    private fun film(
        title: String = "Heat",
        resumeMin: Long = 0,
        year: Int = 1995,
    ): BaseItem {
        val id = uuid("film:$title")
        return BaseItem(
            BaseItemDto(
                id = id,
                type = BaseItemKind.MOVIE,
                name = title,
                productionYear = year,
                officialRating = "R",
                genres = listOf("Crime", "Drama", "Thriller"),
                overview = heatOverview,
                runTimeTicks = minutes(170),
                imageTags = mapOf(ImageType.PRIMARY to "poster"),
                backdropImageTags = listOf("backdrop"),
                productionLocations = listOf("United States of America"),
                people = listOf(BaseItemPerson(name = "Michael Mann", id = uuid("p:mann"), type = PersonKind.DIRECTOR)),
                userData = userData(id, positionMin = resumeMin),
            ),
        )
    }

    private fun people() =
        listOf(
            Person(uuid("p:1"), "Al Pacino", "Vincent Hanna", PersonKind.ACTOR, "https://h/p/1", false),
            Person(uuid("p:2"), "Robert De Niro", "Neil McCauley", PersonKind.ACTOR, "https://h/p/2", false),
            Person(uuid("p:3"), "Val Kilmer", "Chris Shiherlis", PersonKind.ACTOR, "https://h/p/3", false),
            Person(uuid("p:4"), "Jon Voight", "Nate", PersonKind.ACTOR, "https://h/p/4", false),
            Person(uuid("p:5"), "Tom Sizemore", "Michael Cheritto", PersonKind.ACTOR, "https://h/p/5", false),
            Person(uuid("p:6"), "Michael Mann", "Director", PersonKind.DIRECTOR, "https://h/p/6", false),
        )

    private fun similarFilms() =
        listOf("Collateral", "Ronin", "The Town", "Blackhat", "Thief", "Public Enemies").map { film(it, year = 2004) }

    private val seriesId = uuid("series:slow-horses")

    private val seriesOverview =
        "Failed spies are sent to Slough House, a dumping ground for those who have made mistakes. Led by the caustic, " +
            "brilliant Jackson Lamb, they find themselves at the centre of a plot far bigger than they were ever meant to see."

    private fun series() =
        BaseItem(
            BaseItemDto(
                id = seriesId,
                type = BaseItemKind.SERIES,
                name = "Slow Horses",
                productionYear = 2022,
                officialRating = "TV-MA",
                genres = listOf("Drama", "Thriller", "Comedy"),
                overview = seriesOverview,
                imageTags = mapOf(ImageType.PRIMARY to "poster"),
                backdropImageTags = listOf("backdrop"),
                userData = userData(seriesId),
            ),
        )

    private fun seasons() =
        (1..3).map { n ->
            val id = uuid("season:$n")
            BaseItem(
                BaseItemDto(
                    id = id,
                    type = BaseItemKind.SEASON,
                    name = "Season $n",
                    indexNumber = n,
                    seriesId = seriesId,
                    seriesName = "Slow Horses",
                    imageTags = mapOf(ImageType.PRIMARY to "poster"),
                    userData = userData(id),
                ),
            )
        }

    private val episodeTitles =
        listOf(
            "Failure's Contagious",
            "Work Ethic",
            "Hey, Joe",
            "Spook Street",
            "The Ten Thousand Hour Rule",
            "A Very Long Episode Title That Will Not Fit On One Line",
        )

    private val episodeOverview =
        "River Cartwright is given a last chance to prove himself, and a loose thread from an old operation threatens to pull the whole house down. " +
            "Lamb knows more than he is saying, and the slow horses are about to find out how much."

    /** Six episodes of season 1: E1 watched, E2 part-watched, E4 is next up (set by the caller via nextUpId). */
    private fun episodes(): List<BaseItem> =
        episodeTitles.mapIndexed { i, title ->
            val n = i + 1
            val id = uuid("episode:1:$n")
            BaseItem(
                BaseItemDto(
                    id = id,
                    type = BaseItemKind.EPISODE,
                    name = title,
                    indexNumber = n,
                    parentIndexNumber = 1,
                    seriesId = seriesId,
                    seriesName = "Slow Horses",
                    seasonId = uuid("season:1"),
                    seasonName = "Season 1",
                    overview = episodeOverview,
                    runTimeTicks = minutes(46L + n),
                    primaryImageAspectRatio = 16.0 / 9.0,
                    imageTags = mapOf(ImageType.PRIMARY to "still"),
                    userData =
                        when (n) {
                            1 -> userData(id, played = true)
                            2 -> userData(id, positionMin = 21, percent = 44.0)
                            else -> userData(id)
                        },
                ),
            )
        }

    private val nextUpId get() = uuid("episode:1:4")

    /** A Success list over a mocked pager (the real one needs a server); null (and a log line) when mocking fails. */
    private fun episodeList(
        name: String,
        eps: List<BaseItem>,
    ): EpisodeList? =
        try {
            val pager = mockk<ApiRequestPager<GetEpisodesRequest>>(relaxed = true)
            every { pager.size } returns eps.size
            every { pager[any()] } answers { eps[firstArg<Int>()] }
            EpisodeList.Success(uuid("season:1"), pager, 3)
        } catch (t: Throwable) {
            println("shot $name: skipped, episode pager could not be mocked: $t")
            null
        }

    private val prefs = UserPreferences(AppPreferences.getDefaultInstance(), null)

    private val noActions =
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
        )

    // ---- library pages ----

    @Composable
    private fun FilmPage(
        film: BaseItem,
        withRows: Boolean,
    ) {
        MovieDetailsContent(
            preferences = prefs,
            movie = film,
            state = if (withRows) MovieState(people = people(), similar = similarFilms()) else MovieState(),
            playOnClick = {},
            trailerOnClick = {},
            overviewOnClick = {},
            watchOnClick = {},
            favoriteOnClick = {},
            moreOnClick = {},
            onClickItem = { _, _ -> },
            onClickPerson = {},
            onLongClickPerson = { _, _ -> },
            onLongClickSimilar = { _, _ -> },
            onClickExtra = { _, _ -> },
            onClickDiscover = { _, _ -> },
            canDelete = false,
            onConfirmDelete = {},
            onChooseVersion = {},
        )
    }

    @Test
    fun filmHero() {
        val name = "details-film-hero"
        shot(name, steps = { focusText(name, "Play") }) { FilmPage(film(), withRows = true) }
    }

    @Test
    fun filmResume() {
        val name = "details-film-resume"
        shot(name, steps = { focusText(name, "Resume") }) { FilmPage(film(resumeMin = 74), withRows = true) }
    }

    @Test
    fun filmRows() {
        val name = "details-film-rows"
        shot(name, steps = {
            focusText(name, "Play")
            pressDown(name)
            advance(1500)
        }) { FilmPage(film(), withRows = true) }
    }

    @Test
    fun longTitle() {
        val name = "details-long-title"
        val title = "The Extraordinarily Long And Winding Title Of A Film That Will Not Fit"
        check(title.length == 70) { "title is ${title.length} chars" }
        shot(name, steps = { focusText(name, "Play") }) { FilmPage(film(title = title), withRows = true) }
    }

    @Composable
    private fun SeriesPage(episodes: EpisodeList) {
        SeriesDetailsContent(
            preferences = prefs,
            series = series(),
            seasons = seasons(),
            similar = similarFilms(),
            trailers = emptyList(),
            extras = emptyList(),
            people = people(),
            discovered = emptyList(),
            played = false,
            favorite = false,
            canDelete = false,
            onClickItem = { _, _ -> },
            episodes = episodes,
            nextUpId = nextUpId,
            seasonIndex = 0,
            initialEpisodeIndex = 3,
            onChangeSeason = {},
            onClickEpisode = {},
            consumeReturnToEpisodes = { false },
            onClickPerson = {},
            onLongClickItem = { _, _ -> },
            overviewOnClick = {},
            playOnClick = {},
            watchOnClick = {},
            favoriteOnClick = {},
            trailerOnClick = {},
            onClickExtra = { _, _ -> },
            onShowContextMenu = {},
            actions = noActions,
            onClickDiscover = { _, _ -> },
            discoverSeries = null,
            onClickDiscoverSeries = {},
        )
    }

    @Test
    fun seriesHero() {
        val name = "details-series-hero"
        shot(name, steps = { focusText(name, "Play") }) { SeriesPage(EpisodeList.Loading) }
    }

    @Test
    fun seriesEpisodes() {
        val name = "details-series-episodes"
        val list = episodeList(name, episodes()) ?: return
        shot(name, steps = {
            focusText(name, "Play")
            pressDown(name)
            advance(1500)
        }) { SeriesPage(list) }
    }

    @Test
    fun overview() {
        val name = "details-overview"
        val list = episodeList(name, episodes()) ?: return
        val first = FocusRequester()
        shot(name, steps = {
            // S1 E3 is the focused episode card; take focus through its own requester
            runCatching { compose.runOnIdle { first.requestFocus() } }
                .onFailure { println("shot $name: focus not set (${it.message?.lineSequence()?.firstOrNull()})") }
        }) {
            SeriesOverviewContent(
                preferences = prefs,
                series = series(),
                seasons = seasons(),
                episodes = list,
                seasonExtras = emptyList(),
                chosenStreams = null,
                peopleInEpisode = emptyList(),
                position = SeriesOverviewPosition(seasonTabIndex = 0, episodeRowIndex = 2),
                firstItemFocusRequester = first,
                episodeRowFocusRequester = remember { FocusRequester() },
                castCrewRowFocusRequester = remember { FocusRequester() },
                guestStarRowFocusRequester = remember { FocusRequester() },
                extrasRowFocusRequester = remember { FocusRequester() },
                onChangeSeason = {},
                nextUpId = nextUpId,
                onOpenSeries = {},
                onFocusEpisode = {},
                onClick = {},
                onLongClick = {},
                playOnClick = {},
                watchOnClick = {},
                favoriteOnClick = {},
                moreOnClick = {},
                overviewOnClick = {},
                personOnClick = {},
                canDelete = { false },
                onConfirmDelete = {},
                onClickExtra = { _, _ -> },
                onChooseVersion = { _, _ -> },
            )
        }
    }

    @Test
    fun episode() {
        val name = "details-episode"
        shot(name, steps = { focusText(name, "Play") }) {
            EpisodeDetailsContent(
                preferences = prefs,
                ep = episodes()[2],
                chosenStreams = null,
                playOnClick = {},
                overviewOnClick = {},
                watchOnClick = {},
                favoriteOnClick = {},
                moreOnClick = {},
                prevId = uuid("episode:1:2"),
                nextId = uuid("episode:1:4"),
                onGoToEpisode = {},
                onOpenSeries = {},
                onOpenSeason = {},
                canDelete = false,
                onConfirmDelete = {},
                onChooseVersion = {},
            )
        }
    }

    // ---- IPTV pages ----

    private val iptvOverview =
        "A group of high-end professional thieves start to feel the heat from the LAPD when they unknowingly leave a clue at their latest heist."

    private fun iptvItem(
        kind: String,
        title: String,
        progress: IptvProgress? = null,
    ) = IptvVodItem(
        id = if (kind == "series") "p1:s:42" else "p1:m:42",
        kind = kind,
        tmdbId = null,
        title = title,
        year = if (kind == "series") 2022 else 1995,
        quality = listOf("4k", "hdr"),
        poster = "https://h/p/42",
        backdrop = "https://h/b/42",
        overview = if (kind == "series") seriesOverview else iptvOverview,
        rating = 7.9,
        providerName = "Provider One",
        progress = progress,
        audio = "atmos",
    )

    private val iptvPeople =
        listOf(
            IptvCastMember("Al Pacino", "Vincent Hanna", "https://h/c/1"),
            IptvCastMember("Robert De Niro", "Neil McCauley", "https://h/c/2"),
            IptvCastMember("Val Kilmer", "Chris Shiherlis", "https://h/c/3"),
            IptvCastMember("Jon Voight", "Nate", null),
        )

    private fun iptvFilmDetail() =
        IptvVodDetail(
            item = iptvItem("movie", "Heat"),
            runtimeMin = 170,
            genre = "Crime",
            director = "Michael Mann",
            cast = "Al Pacino, Robert De Niro",
            seasons = emptyList(),
            people = iptvPeople,
            genres = listOf("Crime", "Drama", "Thriller"),
        )

    private fun iptvSeriesDetail() =
        IptvVodDetail(
            item = iptvItem("series", "Slow Horses"),
            runtimeMin = 46,
            genre = "Drama",
            director = null,
            cast = null,
            seasons =
                (1..3).map { s ->
                    IptvSeason(
                        s,
                        episodeTitles.mapIndexed { i, t ->
                            IptvEpisode(
                                id = "p1:s:42:$s:${i + 1}",
                                episode = i + 1,
                                title = t,
                                runtimeMin = 46 + i,
                                overview = episodeOverview,
                                still = "https://h/s/$s/${i + 1}",
                                progress = if (s == 1 && i == 1) IptvProgress(1260.0, 2800.0, false) else null,
                            )
                        },
                    )
                },
            people = iptvPeople,
            genres = listOf("Drama", "Thriller", "Comedy"),
        )

    @Composable
    private fun IptvPage(detail: IptvVodDetail) {
        JellytipIptvDetailsContent(
            id = detail.item.id,
            title = detail.item.title,
            detail = detail,
            failed = false,
            trailerSettings = JellytipSettings(trailerAutoplay = false),
            httpClient = OkHttpClient(),
            onShowCopy = {},
            onNavigate = { _, _ -> },
            setFavourite = { _, on -> on },
        )
    }

    @Test
    fun iptvFilm() {
        val name = "details-iptv-film"
        shot(name, steps = { focusText(name, "Play") }) { IptvPage(iptvFilmDetail()) }
    }

    @Test
    fun iptvSeries() {
        val name = "details-iptv-series"
        // The page puts focus on the first season chip itself
        shot(name) { IptvPage(iptvSeriesDetail()) }
    }

    @Test
    fun iptvSeriesScrolled() {
        val name = "details-iptv-series-scrolled"
        shot(name, steps = {
            // Down from the first season chip lands on the first episode card
            pressDown(name)
            advance(1500)
        }) { IptvPage(iptvSeriesDetail()) }
    }
}
