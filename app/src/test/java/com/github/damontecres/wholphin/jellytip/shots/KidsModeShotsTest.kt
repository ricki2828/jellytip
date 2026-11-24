package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.jellytip.kids.KidsCanvas
import com.github.damontecres.wholphin.jellytip.kids.KidsChip
import com.github.damontecres.wholphin.jellytip.kids.KidsDetails
import com.github.damontecres.wholphin.jellytip.kids.KidsDetailsContent
import com.github.damontecres.wholphin.jellytip.kids.KidsEpisode
import com.github.damontecres.wholphin.jellytip.kids.KidsGlyphs
import com.github.damontecres.wholphin.jellytip.kids.KidsGridContent
import com.github.damontecres.wholphin.jellytip.kids.KidsGridPage
import com.github.damontecres.wholphin.jellytip.kids.KidsTokens
import com.github.damontecres.wholphin.jellytip.kids.KidsHero
import com.github.damontecres.wholphin.jellytip.kids.KidsHomeContent
import com.github.damontecres.wholphin.jellytip.kids.KidsHomeData
import com.github.damontecres.wholphin.jellytip.kids.KidsHomeUi
import com.github.damontecres.wholphin.jellytip.kids.KidsKind
import com.github.damontecres.wholphin.jellytip.kids.KidsNavBar
import com.github.damontecres.wholphin.jellytip.kids.KidsPlayTarget
import com.github.damontecres.wholphin.jellytip.kids.KidsRow
import com.github.damontecres.wholphin.jellytip.kids.KidsSeason
import com.github.damontecres.wholphin.jellytip.kids.KidsSection
import com.github.damontecres.wholphin.jellytip.kids.KidsTags
import com.github.damontecres.wholphin.jellytip.kids.KidsTile
import com.github.damontecres.wholphin.jellytip.kids.rememberKidsFocusMemory
import com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Kids mode shots (look B "Playground"): the pure content composables on synthetic data, full 960x540 dp (a 1920x1080 TV at 2x),
 * the app's theme wrapper, keyboard input mode (a TV is never in touch mode, and a foundation clickable cannot take focus in
 * touch mode). Focus goes through the test framework: requested inside the content it does not take under Robolectric.
 * For eyes, not compared. The nav bar is drawn over the pages the way KidsShell draws it.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class KidsModeShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    // ---- fixture art: a pastel gradient with a round character (invented, not from any real title) ----

    private val palettes =
        listOf(
            0xFF3A2F6B.toInt() to 0xFFB79CF2.toInt(),
            0xFF0F4A5A.toInt() to 0xFF57C2D6.toInt(),
            0xFF5A4A12.toInt() to 0xFFE8C85A.toInt(),
            0xFF1D3F7A.toInt() to 0xFF6FA3F2.toInt(),
            0xFF7A2D3F.toInt() to 0xFFF29CB0.toInt(),
            0xFF1F5A4A.toInt() to 0xFF7FE0B8.toInt(),
        )
    private val bodies = listOf(0xFFFFB74D.toInt(), 0xFF9FE3C4.toInt(), 0xFFFF9FB5.toInt(), 0xFFFFD24D.toInt(), 0xFFC8F29F.toInt(), 0xFFFF8F6B.toInt())

    private fun art(seed: String): coil3.Image {
        val n = (seed.hashCode() and 0x7fffffff)
        val (a, b) = palettes[n % palettes.size]
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 640f, 360f, a, b, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 640f, 360f, p)
        p.shader = null
        p.color = 0x33000000
        c.drawOval(-120f, 270f, 420f, 460f, p)
        c.drawOval(300f, 290f, 760f, 480f, p)
        p.color = bodies[(n / 7) % bodies.size]
        c.drawOval(380f, 120f, 600f, 320f, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(455f, 205f, 24f, p)
        c.drawCircle(525f, 205f, 24f, p)
        p.color = 0xFF1B1730.toInt()
        c.drawCircle(460f, 208f, 12f, p)
        c.drawCircle(530f, 208f, 12f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        c.drawArc(450f, 215f, 535f, 280f, 20f, 140f, false, p)
        return bmp.asImage()
    }

    private val keyboardMode =
        object : InputModeManager {
            override val inputMode = InputMode.Keyboard

            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        val handler = AsyncImagePreviewHandler { request -> art(request.data.toString()) }
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides handler,
            LocalInputModeManager provides keyboardMode,
        ) {
            MaterialTheme(colorScheme = PurpleThemeColors.darkScheme) { Surface(Modifier.fillMaxSize()) { content() } }
        }
    }

    /** What KidsShell draws: the canvas, the nav bar, then the page. */
    @Composable
    private fun Page(
        selected: KidsSection?,
        content: @Composable () -> Unit,
    ) {
        KidsCanvas {
            Column(Modifier.fillMaxSize()) {
                KidsNavBar(userName = "Piper", selected = selected, onSection = {}, onSearch = {}, onSwitch = {})
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** Focus the node tagged [tag] and check it took (the failing line names the cause). Null = leave focus where it is. */
    private fun shot(
        name: String,
        focusTag: String?,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { Tv { content() } }
        advance(800)
        if (focusTag != null) {
            compose.onNodeWithTag(focusTag).requestFocus()
            advance(900)
            compose.onNodeWithTag(focusTag).assertIsFocused()
        }
        compose.onRoot().captureRoboImage(out(name))
    }

    // ---- fixtures ----

    private fun tile(
        id: String,
        name: String,
        kind: KidsKind = KidsKind.SERIES,
        progress: Float = 0f,
        playCount: Int = 0,
        seriesName: String? = null,
    ) = KidsTile(id = id, source = "library", kind = kind, name = name, seriesName = seriesName, progress = progress, playCount = playCount, imageUrl = "fixture://$id")

    private val hero =
        KidsHero(
            resume = true,
            tile = tile("rocket-ep4", "Splashdown!", KidsKind.EPISODE, progress = 0.62f, seriesName = "Rocket Otters"),
            imageUrl = "fixture://rocket-hero",
        )

    private val watchAgain =
        KidsRow(
            "watch_again",
            "Watch again",
            false,
            listOf(
                tile("w1", "Rocket Otters", playCount = 5),
                tile("w2", "Moon Cheese", KidsKind.MOVIE),
                tile("w3", "Pip and the Pond", playCount = 3),
                tile("w4", "Biscuit Bakes", progress = 0.4f),
                tile("w5", "Sock Puppet Club"),
                tile("w6", "Zig the Zebra", KidsKind.MOVIE),
            ),
        )

    private val friends =
        KidsRow(
            "friends",
            "Your friends",
            true,
            listOf("Otto", "Pip", "Biscuit", "Mabel", "Sock", "Zig", "Dot", "Goldie").map { tile("f-$it", it) },
        )

    private val movieNight =
        KidsRow(
            "movie_night",
            "Movies for movie night",
            false,
            listOf("Moon Cheese", "Night Lights", "The Wobbly Rocket", "Big Pond Day", "Cloud Race", "Sleepy Sun").map { tile("m-$it", it, KidsKind.MOVIE) },
        )

    private val fullHome = KidsHomeUi.Ready(KidsHomeData(hero, listOf(watchAgain, friends, movieNight)))

    @Composable
    private fun Home(ui: KidsHomeUi) {
        Page(KidsSection.HOME) {
            KidsHomeContent(ui, rememberKidsFocusMemory(), onOpen = {}, onPlayHero = {})
        }
    }

    // ---- shots ----

    @Test
    fun homeHeroFocused() = shot("kids-home-hero-focused", KidsTags.HERO_PLAY) { Home(fullHome) }

    @Test
    fun homeTileFocused() = shot("kids-home-tile-focused", KidsTags.tile("watch_again", 1)) { Home(fullHome) }

    @Test
    fun homeBubbleFocused() = shot("kids-home-bubble-focused", KidsTags.bubble(2)) { Home(fullHome) }

    /** Thin state: nothing but the hero (every row was too thin for the server to send). */
    @Test
    fun homeThin() = shot("kids-home-thin", KidsTags.HERO_PLAY) { Home(KidsHomeUi.Ready(KidsHomeData(hero, emptyList()))) }

    /** A favourite pick that is a whole series: no progress, no dots; its Play starts the next (or first) episode. */
    @Test
    fun homeSeriesHero() =
        shot("kids-home-hero-series", KidsTags.HERO_PLAY) {
            Home(
                KidsHomeUi.Ready(
                    KidsHomeData(
                        KidsHero(resume = false, tile = tile("pip-pond", "Pip and the Pond"), imageUrl = "fixture://pip-hero"),
                        listOf(watchAgain, friends),
                    ),
                ),
            )
        }

    /**
     * Rows but no hero: the first visit puts focus on the first tile. Focus asked from inside the content may not take under
     * Robolectric, so this shot does not assert it (it prints what it found); the page must still draw rows first.
     */
    @Test
    fun homeRowsNoHeroInitialFocus() {
        compose.setContent { Tv { Home(KidsHomeUi.Ready(KidsHomeData(null, listOf(watchAgain, friends, movieNight)))) } }
        advance(1500)
        val landed = runCatching { compose.onNodeWithTag(KidsTags.tile("watch_again", 0)).assertIsFocused() }.isSuccess
        println("shot kids-home-rows-no-hero: initial focus on the first tile landed=$landed")
        compose.onRoot().captureRoboImage(out("kids-home-rows-no-hero"))
    }

    /** The endpoint said no (403 / error): no rows, no hero, and not the adult Home. */
    @Test
    fun homeUnavailable() = shot("kids-home-unavailable", null) { Home(KidsHomeUi.Unavailable) }

    /** 401: the session ended; the page says so and focus goes to the nav (nothing else to focus). */
    @Test
    fun homeSignedOut() = shot("kids-home-signed-out", null) { Home(KidsHomeUi.SignedOut) }

    @Composable
    private fun Grid(
        section: KidsSection,
        tiles: List<KidsTile>,
    ) {
        Page(section) {
            KidsGridContent(section, tiles, rememberKidsFocusMemory(), onOpen = {}, gridState = rememberLazyGridState())
        }
    }

    private val shows =
        listOf(
            "Rocket Otters", "Pip and the Pond", "Biscuit Bakes", "Sock Puppet Club", "Zig the Zebra",
            "Mabel's Garden", "Dot and Dash", "Goldie Goes", "Cloud Race", "Sleepy Sun",
            "Big Pond Day", "Night Lights", "Tiny Trains", "Otto Explores", "Puddle Park",
        ).map { tile("s-$it", it, progress = if (it.startsWith("Pip")) 0.5f else 0f) }

    private val movies =
        listOf(
            "Moon Cheese", "The Wobbly Rocket", "Night Lights", "Big Pond Day", "Cloud Race",
            "Sleepy Sun", "Zig and the Moon", "Biscuit Bakes Again", "Pip's Big Swim", "Dot's Birthday",
        ).map { tile("mv-$it", it, KidsKind.MOVIE) }

    @Test
    fun showsPage() = shot("kids-shows", KidsTags.grid(1)) { Grid(KidsSection.SHOWS, shows) }

    @Test
    fun moviesPage() = shot("kids-movies", KidsTags.grid(1)) { Grid(KidsSection.MOVIES, movies) }

    @Test
    fun myListPage() = shot("kids-my-list", KidsTags.grid(0)) { Grid(KidsSection.MY_LIST, (shows.take(3) + movies.take(4))) }

    @Test
    fun myListEmpty() = shot("kids-my-list-empty", null) { Grid(KidsSection.MY_LIST, emptyList()) }

    /** A friend's collection (a bubble opens it): the same art grid, titled with the collection's name, in the Home colour. */
    @Test
    fun collectionGrid() =
        shot("kids-collection", KidsTags.grid(1)) {
            KidsCanvas {
                KidsGridPage(
                    title = "Pip and Pals",
                    glyph = KidsGlyphs.STAR,
                    color = KidsTokens.Playground.homeColor,
                    emptyText = "Nothing here yet",
                    tiles = (shows.take(5) + movies.take(4)),
                    memory = rememberKidsFocusMemory(),
                    onOpen = {},
                    modifier = Modifier.padding(top = 18.dp),
                    gridState = rememberLazyGridState(),
                )
            }
        }

    private fun episode(
        n: Int,
        title: String,
        played: Boolean = false,
        progress: Float = 0f,
    ) = KidsEpisode(id = "ep$n", number = n, title = title, imageUrl = "fixture://ep$n", progress = progress, played = played)

    private val details =
        KidsDetails(
            id = "rocket",
            isSeries = true,
            title = "Rocket Otters",
            chips = listOf(KidsChip("G", rating = true), KidsChip("2 seasons"), KidsChip("11 min")),
            overview = "Otto and his crew of otters build a rocket from river junk and go looking for the moon.",
            imageUrl = "fixture://rocket-hero",
            playLabel = "Keep going!",
            playSub = "Season 2 · Episode 4",
            favourite = false,
            seasons =
                listOf(
                    KidsSeason(1, "Season 1", listOf(episode(1, "Pond Day", true), episode(2, "Big Splash", true))),
                    KidsSeason(
                        2,
                        "Season 2",
                        listOf(
                            episode(1, "The Wobbly Rocket", true),
                            episode(2, "Otto Gets Lost", true),
                            episode(3, "Moon Cheese", true),
                            episode(4, "Splashdown!", progress = 0.62f),
                            episode(5, "Night Lights"),
                            episode(6, "Pip's Visit"),
                        ),
                    ),
                ),
            initialSeason = 1,
            playTarget = KidsPlayTarget("ep4", 62_000L),
        )

    @Composable
    private fun Details(
        d: KidsDetails,
        favourite: Boolean = false,
    ) {
        KidsCanvas {
            KidsDetailsContent(
                details = d,
                seasonIndex = d.initialSeason,
                favourite = favourite,
                onSeason = {},
                onPlay = {},
                onToggleList = {},
                onEpisode = {},
                memory = rememberKidsFocusMemory(),
            )
        }
    }

    @Test
    fun detailsPlayFocused() = shot("kids-details-play", KidsTags.DETAILS_PLAY) { Details(details) }

    @Test
    fun detailsEpisodeFocused() = shot("kids-details-episode", KidsTags.episode(3)) { Details(details) }

    /** A film: no episodes, the heart is on. */
    @Test
    fun detailsMovie() =
        shot("kids-details-movie", KidsTags.DETAILS_LIST) {
            Details(
                KidsDetails(
                    id = "moon-cheese",
                    isSeries = false,
                    title = "Moon Cheese",
                    chips = listOf(KidsChip("G", rating = true), KidsChip("1h 32m")),
                    overview = "A very hungry otter is sure the moon is made of cheese. Time to build a rocket and find out.",
                    imageUrl = "fixture://moon-cheese",
                    playLabel = "Play",
                    playSub = null,
                    favourite = true,
                    seasons = emptyList(),
                    initialSeason = 0,
                    playTarget = KidsPlayTarget("moon-cheese", 0L),
                ),
                favourite = true,
            )
        }

    @Test
    fun navSwitchProfileFocused() = shot("kids-nav-switch-focused", KidsTags.SWITCH) { Home(fullHome) }

    @Test
    fun navSectionFocused() = shot("kids-nav-shows-focused", KidsTags.nav(KidsSection.SHOWS)) { Home(fullHome) }
}
