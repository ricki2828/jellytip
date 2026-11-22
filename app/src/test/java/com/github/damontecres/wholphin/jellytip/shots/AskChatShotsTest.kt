package com.github.damontecres.wholphin.jellytip.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.ask.AskParse
import com.github.damontecres.wholphin.jellytip.askchat.AskChat
import com.github.damontecres.wholphin.jellytip.askchat.AskChatActions
import com.github.damontecres.wholphin.jellytip.askchat.AskChatContent
import com.github.damontecres.wholphin.jellytip.askchat.AskChatUi
import com.github.damontecres.wholphin.jellytip.askchat.ChatError
import com.github.damontecres.wholphin.jellytip.askchat.ChatResponse
import com.github.damontecres.wholphin.jellytip.askchat.ChatState
import com.github.damontecres.wholphin.jellytip.askchat.ChatTitle
import com.github.damontecres.wholphin.jellytip.askchat.Fetched
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/*
 * TV chat search shots (Roborazzi inside the CI Robolectric step; artifact jellytip-shots-<sha>). Real AskChatContent over the state machine
 * with the five live server responses (resources/jellytip/askchat-live-20261122.json): empty + notice, pending (dimmed local chips +
 * skeleton, controls disabled), the kids headline, Paul Rudd with no results (action buttons synthesised until the fixture has them),
 * fallback, a mixed library + TV result, the unreachable state, and focus on the input / a card / a chip. Harness as Audit2ShotsTest:
 * w960dp-h540dp-land-xhdpi (a 1920x1080 TV at 2x), the page inside the 72 dp start / 16 dp end inset of DestinationContent, keyboard
 * input mode, fake image handler. Focus is set from the test (a LaunchedEffect focus request does not take under Robolectric).
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class AskChatShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    // ---- harness ----

    private val photo by lazy {
        val b = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 300f, 450f, 0xFF3A6EA5.toInt(), 0xFFB5651D.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 300f, 450f, p)
        p.shader = null
        p.color = 0xFFEFE6D5.toInt()
        c.drawCircle(150f, 170f, 60f, p)
        p.color = 0xFF1B1B1B.toInt()
        c.drawRect(0f, 330f, 300f, 450f, p)
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
            LocalInputModeManager provides keyboardMode,
        ) {
            MaterialTheme(colorScheme = com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors.darkScheme) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

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
        val texts = nodes.map { n -> n.config.getOrNull(SemanticsProperties.TestTag) ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { t -> t.text } ?: "(none)" }
        println("shot $name: focused nodes=${nodes.size} ids=$texts")
    }

    /** Where the chat column and the right pane sit (dp from the screen's top-left), to the CI log: nothing may leave x 96..912 / y 28..512. */
    private fun logBounds(name: String) {
        runCatching {
            val nodes = compose.onAllNodes(hasTestTag("chat-card"), useUnmergedTree = true).fetchSemanticsNodes().size
            if (nodes > 0) {
                val first = compose.onAllNodes(hasTestTag("chat-card"), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
                println("shot $name: cards=$nodes first card left=${first.left.value} top=${first.top.value} w=${first.right.value - first.left.value} h=${first.bottom.value - first.top.value}")
            } else {
                println("shot $name: no card nodes")
            }
            val input = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()
            println("shot $name: input left=${input.left.value} right=${input.right.value} top=${input.top.value} bottom=${input.bottom.value}")
        }.onFailure { println("shot $name: not measured (${it.message?.lineSequence()?.firstOrNull()})") }
    }

    /** Every removable chip is laid out (a node each) and inside the page: right edge <= 912 dp (PageEnd). */
    private fun assertChips(
        name: String,
        expected: Int,
    ) {
        val prefix = SemanticsMatcher("test tag starts with chat-chip:") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chat-chip:") == true }
        val nodes = compose.onAllNodes(prefix, useUnmergedTree = true)
        val n = nodes.fetchSemanticsNodes().size
        assertTrue("$name: expected $expected chips, found $n", n == expected)
        for (i in 0 until n) {
            val b = nodes[i].getUnclippedBoundsInRoot()
            println("shot $name: chip $i left=${b.left.value} right=${b.right.value} top=${b.top.value}")
            assertTrue("$name: chip $i right edge ${b.right.value} dp must be <= 912", b.right.value <= 912f)
        }
    }

    private fun shot(
        name: String,
        ui: AskChatUi,
        steps: () -> Unit = {},
        lastOpened: String? = null,
        expectChips: Int? = null,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Tv {
                Box(Modifier.fillMaxSize().background(JellytipPalette.Night)) {
                    Box(Modifier.fillMaxSize().padding(start = 72.dp, end = 16.dp)) {
                        AskChatContent(
                            ui = ui,
                            actions = AskChatActions({}, {}, {}, {}, {}, {}, {}, null) { lastOpened },
                        )
                    }
                }
            }
        }
        advance(1200)
        steps()
        advance(1800)
        logFocus(name)
        logBounds(name)
        if (expectChips != null) assertChips(name, expectChips)
        compose.onRoot().captureRoboImage(out(name))
    }

    // ---- data from the live fixture ----

    private val live: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/askchat-live-20261122.json")!!.bufferedReader().readText())
    }

    private fun respOf(
        key: String,
        edit: (JSONObject) -> Unit = {},
    ): Pair<String, ChatResponse> {
        val f = live.getJSONObject(key)
        val j = JSONObject(f.getJSONObject("resp").toString())
        edit(j)
        val body = f.getJSONObject("body")
        return (body.optString("text", "")) to AskChat.parseResponse(j.toString())!!
    }

    /** The state after [key]'s turn was asked and answered (as the page would hold it). */
    private fun settled(
        key: String,
        edit: (JSONObject) -> Unit = {},
    ): ChatState {
        val (text, resp) = respOf(key, edit)
        val issued = AskChat.submitText(ChatState(rev = resp.rev - 1), text.ifBlank { "x" })!!
        return AskChat.applyResponse(issued.state, resp)
    }

    private fun minutes(m: Int) = m * 600_000_000L

    private fun fetchedFor(cards: List<ChatTitle>): Fetched {
        val items =
            cards.filterIsInstance<ChatTitle.Library>().map { c ->
                BaseItem(
                    BaseItemDto(
                        id = c.id.toUUID(),
                        type = if (c.kind == "film") BaseItemKind.MOVIE else BaseItemKind.SERIES,
                        name = c.title,
                        productionYear = c.year,
                        officialRating = c.certification,
                        genres = listOf("Family", "Comedy"),
                        runTimeTicks = minutes(c.runtime ?: 100),
                        imageTags = mapOf(ImageType.PRIMARY to "poster"),
                    ),
                )
            }
        return Fetched(AskChat.libraryIds(cards).joinToString(","), items, emptyList())
    }

    private fun uiOf(
        chat: ChatState,
        text: String = "",
        notice: Boolean = false,
    ) = AskChatUi(chat = chat, text = text, notice = notice, fetched = fetchedFor(chat.cards))

    private fun tv(
        n: Int,
        title: String,
        kind: String = "film",
        cert: String = "PG",
    ) = ChatTitle.Iptv(kind, "p1:${if (kind == "film") "m" else "s"}:$n", title, 2018, 1000 + n, 7.8, "https://image.tmdb.org/t/p/w342/p$n.jpg", null, listOf("1080p"), emptyList(), false, cert, 95, null)

    // ---- the shots ----

    @Test
    fun emptyWithNotice() {
        shot("askchat-1-empty-notice", AskChatUi(notice = true), steps = { focusNode("askchat-1-empty-notice", hasSetTextAction()) })
    }

    @Test
    fun pending() {
        // the kids headline is on screen; the next text is out: dimmed local chips, a skeleton, no cards, every control disabled
        val head = settled("headline")
        val p = AskChat.submitText(head, "something with Paul Rudd")!!.state
        shot("askchat-2-pending", AskChatUi(chat = p, notice = false, fetched = null), steps = { focusNode("askchat-2-pending", hasSetTextAction()) })
    }

    @Test
    fun kidsHeadline() {
        val chat = settled("headline")
        shot("askchat-3-headline", uiOf(chat), steps = { focusNode("askchat-3-headline", hasSetTextAction()) }, expectChips = 3)
    }

    @Test
    fun paulRuddNoResultsWithActions() {
        val acts =
            JSONArray(
                """[{"label":"Drop “with Paul Rudd”","op":{"remove":"person:3e909596adb405578ff2191e296d5826"}},{"label":"Drop the kids filter","op":{"remove":"kids"}}]""",
            )
        val chat =
            settled("paul_rudd") { j ->
                j.put("notes", JSONArray().put(JSONObject().put("code", "no_results").put("text", "Nothing fits all of that. Try dropping one:").put("actions", acts)))
            }
        shot("askchat-4-paul-rudd-no-results", uiOf(chat), steps = { focusNode("askchat-4-paul-rudd-no-results", hasSetTextAction()) }, expectChips = 4)
    }

    @Test
    fun fallback() {
        shot("askchat-5-fallback", uiOf(settled("fallback_no_llm")), steps = { focusNode("askchat-5-fallback", hasSetTextAction()) })
    }

    @Test
    fun focusOnACard() {
        shot("askchat-6-focus-card", uiOf(settled("headline")), steps = { focusNode("askchat-6-focus-card", hasTestTag("chat-card")) })
    }

    @Test
    fun focusOnAChip() {
        shot("askchat-7-focus-chip", uiOf(settled("headline")), steps = { focusNode("askchat-7-focus-chip", hasTestTag("chat-chip:kids")) })
    }

    @Test
    fun mixedLibraryAndTv() {
        val base = settled("headline")
        val lib = base.cards.take(12)
        val mixed = buildList<ChatTitle> {
            lib.forEachIndexed { i, c ->
                add(c)
                when (i) {
                    1 -> add(tv(1, "Toy Story", cert = "G"))
                    4 -> add(tv(2, "Paddington 2"))
                    6 -> add(tv(3, "Bluey", kind = "series", cert = "TV-Y"))
                    9 -> add(tv(4, "Moana 2"))
                }
            }
        }
        val chat = base.copy(cards = mixed, sources = listOf("library", "iptv"), matched = 81)
        shot("askchat-8-mixed-library-and-tv", uiOf(chat), steps = { focusNode("askchat-8-mixed-library-and-tv", hasSetTextAction()) })
    }

    @Test
    fun unreachable() {
        val head = settled("headline")
        val p = AskChat.submitText(head, "something funny")!!
        val chat = AskChat.applyError(p.state, p.body.rev, 503).state
        assertTrue(chat.error == ChatError.Unreachable && chat.cards.isEmpty())
        shot("askchat-9-unreachable", AskChatUi(chat = chat, notice = false, fetched = Fetched("", emptyList(), emptyList())), steps = { focusNode("askchat-9-unreachable", hasSetTextAction()) })
    }

    @Test
    fun theExamplesAreTheAskPagesOwn() {
        assertTrue(AskParse.EXAMPLES.size >= 6)
    }
}
