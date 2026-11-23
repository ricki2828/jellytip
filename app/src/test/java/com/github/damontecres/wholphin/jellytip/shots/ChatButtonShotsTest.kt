package com.github.damontecres.wholphin.jellytip.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.rememberDrawerState
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.JellytipLayout
import com.github.damontecres.wholphin.jellytip.askchat.AskChatActions
import com.github.damontecres.wholphin.jellytip.askchat.AskChatContent
import com.github.damontecres.wholphin.jellytip.askchat.AskChatUi
import com.github.damontecres.wholphin.jellytip.askchat.CHAT_HOLD_HINT
import com.github.damontecres.wholphin.jellytip.askchat.ChatVoiceUi
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.search.SEARCH_CHAT_HINT
import com.github.damontecres.wholphin.jellytip.search.SearchHeaderRow
import com.github.damontecres.wholphin.jellytip.search.jellytipPressHold
import com.github.damontecres.wholphin.ui.nav.CollapsedDrawerItemWidth
import com.github.damontecres.wholphin.ui.nav.IconNavItem
import com.github.damontecres.wholphin.ui.nav.ModalNavigationDrawer
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.NavItem
import com.github.damontecres.wholphin.ui.spacedByWithFooter
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * One button for Search and Chat (23-Nov, brief jellytip-tv-chat-button-brief-20261123.md): the sidebar's Search item with no Ask
 * anywhere, the Search page's top row with its "Hold Search for Chat" hint, and Chat as a hold opens it (listening, tap mode). Harness
 * as Audit3ShotsTest: 960x540 dp, page inset 72 / 16, magenta safe-area guides at x 48 / 912 and y 27 / 513, keyboard input mode.
 * The press / hold wiring is driven with real key input on the real IconNavItem + jellytipPressHold (as NavDrawer.kt builds the item).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class ChatButtonShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun record() {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun out(name: String) = "build/outputs/roborazzi/$name.png"

    private val keyboardMode =
        object : InputModeManager {
            override val inputMode = InputMode.Keyboard

            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }

    private val imageUrlService by lazy {
        val api = mockk<ApiClient>()
        every { api.baseUrl } returns "http://localhost"
        every { api.createUrl(any(), any(), any(), any()) } answers { callOriginal() }
        every { api.imageApi } returns ImageApi(api)
        ImageUrlService(api)
    }

    @Composable
    private fun Tv(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalImageUrlService provides imageUrlService,
            LocalInputModeManager provides keyboardMode,
        ) {
            MaterialTheme(colorScheme = com.github.damontecres.wholphin.ui.theme.colors.PurpleThemeColors.darkScheme) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** The full 960x540 dp frame; [inset] = the page inset (72 dp start, 16 dp end) of DestinationContent. Safe-area guides on top. */
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

    private fun bounds(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    /** Inside the safe area: left >= 48, right <= 912, top >= 27, bottom <= 513 (dp). */
    private fun inSafe(
        name: String,
        what: String,
        matcher: SemanticsMatcher,
    ) {
        val b = bounds(matcher)
        println("shot $name: $what left=${b.left.value} right=${b.right.value} top=${b.top.value} bottom=${b.bottom.value}")
        assertTrue("$name: $what left ${b.left.value} < 48", b.left.value >= 48f)
        assertTrue("$name: $what right ${b.right.value} > 912", b.right.value <= 912f)
        assertTrue("$name: $what top ${b.top.value} < 27", b.top.value >= 27f)
        assertTrue("$name: $what bottom ${b.bottom.value} > 513", b.bottom.value <= 513f)
    }

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
        measure()
        compose.onRoot().captureRoboImage(out(name))
    }

    /** NavDrawer's items (as Audit3ShotsTest's), the Search item wired as NavDrawer.kt wires it. */
    @Composable
    private fun NavigationDrawerScope.Rail(
        isOpen: Boolean,
        onShort: () -> Unit = {},
        onHold: () -> Unit = {},
        onClick: () -> Unit = {},
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(fontSize = com.github.damontecres.wholphin.jellytip.rememberJellytipSizes().sp(24))) {
            Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxHeight()) {
                LazyColumn(
                    contentPadding = PaddingValues(0.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedByWithFooter(4.dp),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    item {
                        IconNavItem(
                            text = stringResource(R.string.search),
                            icon = Icons.Default.Search,
                            selected = false,
                            drawerOpen = isOpen,
                            onClick = onClick,
                            modifier = Modifier.jellytipPressHold(onShort = onShort, onHold = onHold).testTag("rail-search"),
                        )
                    }
                    item { IconNavItem(text = stringResource(R.string.home), icon = Icons.Default.Home, selected = true, drawerOpen = isOpen, onClick = {}) }
                    item { NavItem(library = NavDrawerItem.Collections, selected = false, moreExpanded = false, drawerOpen = isOpen, onClick = {}) }
                    item { NavItem(library = NavDrawerItem.Favorites, selected = false, moreExpanded = false, drawerOpen = isOpen, onClick = {}) }
                    item { IconNavItem(text = stringResource(R.string.settings), icon = Icons.Default.Settings, selected = false, drawerOpen = isOpen, onClick = {}) }
                }
            }
        }
    }

    /** Search's column as SearchContent pads it (24 dp in page, PageTop, PageEnd) with the real top row. */
    @Composable
    private fun SearchTop(searching: Boolean = false) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = JellytipLayout.PageTop, end = JellytipLayout.PageEnd),
        ) {
            SearchHeaderRow(
                query = "",
                searching = searching,
                voice = null,
                onSpeech = {},
                onQueryChange = {},
                onSubmit = {},
                readOnly = true,
                boxModifier = Modifier,
                onChat = {},
            )
        }
    }

    // ---- shots ----

    @Test
    fun sidebarWithoutAsk() {
        val name = "chatbtn-1-sidebar"
        shot(
            name,
            inset = false,
            steps = { focusNode(name, hasTestTag("rail-search")) },
            measure = {
                assertEquals("$name: no Ask anywhere", 0, count(hasText("Ask")) + count(hasText("Not sure? Ask")))
            },
        ) {
            val state = rememberDrawerState(DrawerValue.Open)
            ModalNavigationDrawer(modifier = Modifier.fillMaxSize(), drawerState = state, drawerContent = { v -> Rail(v == DrawerValue.Open) }) {
                Box(Modifier.fillMaxSize().padding(start = CollapsedDrawerItemWidth + 8.dp, end = 16.dp)) { SearchTop() }
            }
        }
    }

    @Test
    fun searchPageHint() {
        val name = "chatbtn-2-search-hint"
        shot(
            name,
            steps = { focusNode(name, hasSetTextAction()) },
            measure = {
                assertEquals("$name: the Ask chip is gone", 0, count(hasText("Not sure? Ask")))
                inSafe(name, "Chat chip", hasTestTag("search-chat"))
                inSafe(name, "hint", hasText(SEARCH_CHAT_HINT))
                // voice = null here, so the mic (MinButtonSize 40 dp + the row's 12 dp gap) is not drawn: the device row is 52 dp wider
                val hintRight = bounds(hasText(SEARCH_CHAT_HINT)).right.value
                assertTrue("$name: hint right $hintRight + 52 dp of mic must stay <= 912", hintRight + 52f <= 912f)
            },
        ) { SearchTop() }
    }

    @Test
    fun searchPageSearchingHidesHint() {
        val name = "chatbtn-2b-search-searching"
        shot(
            name,
            measure = {
                assertEquals("$name: the hint makes room for Searching…", 0, count(hasText(SEARCH_CHAT_HINT)))
                inSafe(name, "Searching…", hasText("Searching…"))
            },
        ) { SearchTop(searching = true) }
    }

    @Test
    fun chatFromHoldListening() {
        val name = "chatbtn-3-chat-hold-listening"
        shot(
            name,
            steps = { focusNode(name, hasSetTextAction()) },
            measure = {
                assertEquals("$name: title says Chat", 1, count(hasText("Chat")))
                assertEquals("$name: no Ask title", 0, count(hasText("Ask")))
                inSafe(name, "hold hint", hasText(CHAT_HOLD_HINT))
                inSafe(name, "listening hint", hasTestTag("chat-voice-hint"))
                assertEquals("$name: tap-mode listening line", 1, count(hasText("Listening… just talk", substring = true)))
            },
        ) {
            AskChatContent(
                ui = AskChatUi(notice = false, voice = ChatVoiceUi(listening = true, level = 0.5f, tap = true)),
                actions = AskChatActions({}, {}, {}, {}, {}, {}, {}, null, { null }, micAvailable = true),
            )
        }
    }

    // ---- press / hold on the real rail item ----

    private class Hits {
        var short = 0
        var hold = 0
        var click = 0
    }

    private fun railFor(hits: Hits) {
        compose.setContent {
            Tv {
                val state = rememberDrawerState(DrawerValue.Open)
                ModalNavigationDrawer(
                    modifier = Modifier.fillMaxSize(),
                    drawerState = state,
                    drawerContent = { v -> Rail(v == DrawerValue.Open, onShort = { hits.short++ }, onHold = { hits.hold++ }, onClick = { hits.click++ }) },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag("rail-search")).onFirst().requestFocus()
        compose.waitForIdle()
    }

    @Test
    fun railQuickOkIsSearch() {
        val hits = Hits()
        railFor(hits)
        compose.onAllNodes(hasTestTag("rail-search")).onFirst().performKeyInput { pressKey(Key.DirectionCenter, 80) }
        compose.waitForIdle()
        assertEquals("short", 1, hits.short)
        assertEquals("hold", 0, hits.hold)
        assertEquals("the item's own onClick never also fires from OK", 0, hits.click)
    }

    @Test
    fun railHeldOkIsChatOnly() {
        val hits = Hits()
        railFor(hits)
        compose.onAllNodes(hasTestTag("rail-search")).onFirst().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(700)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertEquals("hold", 1, hits.hold)
        assertEquals("a hold never also opens Search", 0, hits.short)
        assertEquals("click", 0, hits.click)
    }

    @Test
    fun railTwoQuickTapsAreTwoSearches() {
        val hits = Hits()
        railFor(hits)
        compose.onAllNodes(hasTestTag("rail-search")).onFirst().performKeyInput {
            pressKey(Key.DirectionCenter, 60)
            advanceEventTime(100)
            pressKey(Key.DirectionCenter, 60)
        }
        compose.waitForIdle()
        assertEquals("short", 2, hits.short)
        assertEquals("hold", 0, hits.hold)
    }
}
