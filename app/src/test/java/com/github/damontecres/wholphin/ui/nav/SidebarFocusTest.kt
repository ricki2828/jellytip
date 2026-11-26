package com.github.damontecres.wholphin.ui.nav

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerState
import androidx.tv.material3.DrawerValue
import com.github.damontecres.wholphin.ui.tryRequestFocus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Jellytip (25-Nov, Ricki on the TV): in the open sidebar Right did nothing (no way back to the page) and Back quit the app.
 * The REAL ModalNavigationDrawer / DrawerSheet with NavDrawer's rail (LazyColumn focus group whose onEnter redirects to the
 * selected item) over a page laid out as NavDrawer's DestinationContent (72 dp start padding, so the open drawer overlays
 * the page's first tiles).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class SidebarFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var drawerState: DrawerState
    private var blockTiles by androidx.compose.runtime.mutableStateOf(false)
    private lateinit var backDispatcher: OnBackPressedDispatcher

    /** [staleEnter]: the page's onEnter redirects to a requester that is not attached (HomePage's row requester for a row that is not composed). */
    private fun setPage(staleEnter: Boolean) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val state = androidx.tv.material3.rememberDrawerState(DrawerValue.Closed)
            drawerState = state
            val stale = remember { FocusRequester() }
            ModalNavigationDrawer(
                modifier = Modifier.fillMaxSize(),
                drawerState = state,
                drawerContent = { v -> Rail(v == DrawerValue.Open) },
            ) {
                Box(Modifier.fillMaxSize().padding(start = CollapsedDrawerItemWidth + 8.dp, end = 16.dp)) {
                    Column(
                        Modifier
                            .then(
                                if (staleEnter) {
                                    Modifier.focusProperties { onEnter = { stale.tryRequestFocus() } }
                                } else {
                                    Modifier
                                },
                            ).fillMaxSize(),
                    ) {
                        Row(Modifier.padding(top = 200.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            repeat(4) { i ->
                                Box(
                                    Modifier
                                        .size(160.dp, 90.dp)
                                        .background(Color.DarkGray)
                                        .testTag("tile$i")
                                        .focusProperties { canFocus = !blockTiles }
                                        .focusable(),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun androidx.tv.material3.NavigationDrawerScope.Rail(isOpen: Boolean) {
        val selected = remember { FocusRequester() }
        Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxHeight()) {
            LazyColumn(
                contentPadding = PaddingValues(0.dp),
                modifier =
                    Modifier
                        .focusGroup()
                        .focusProperties { onEnter = { selected.tryRequestFocus() } }
                        .fillMaxHeight(),
            ) {
                item { IconNavItem(text = "Search", icon = Icons.Default.Search, selected = false, drawerOpen = isOpen, onClick = {}, modifier = Modifier.testTag("rail-search")) }
                item {
                    IconNavItem(
                        text = "Home",
                        icon = Icons.Default.Home,
                        selected = true,
                        drawerOpen = isOpen,
                        onClick = {},
                        modifier = Modifier.focusRequester(selected).testTag("rail-home"),
                    )
                }
                item { IconNavItem(text = "Settings", icon = Icons.Default.Settings, selected = false, drawerOpen = isOpen, onClick = {}, modifier = Modifier.testTag("rail-settings")) }
            }
        }
    }

    private fun openWithLeft() {
        compose.onNodeWithTag("tile0").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithTag("tile0").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals("Left on the first tile opens the drawer", DrawerValue.Open, drawerState.currentValue)
        compose.onNodeWithTag("rail-home").assertIsFocused()
    }

    private fun pressRightInDrawer() {
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun assertBackOnPage(what: String) {
        assertEquals("$what: drawer closed", DrawerValue.Closed, drawerState.currentValue)
        val onPage = (0..3).any { i -> runCatching { compose.onNodeWithTag("tile$i").assertIsFocused() }.isSuccess }
        assertEquals("$what: a page tile holds focus", true, onPage)
    }

    @Test
    fun rightFromOpenDrawerReturnsToPage() {
        setPage(staleEnter = false)
        openWithLeft()
        pressRightInDrawer()
        assertBackOnPage("Right")
    }

    @Test
    fun rightFromOpenDrawerReturnsToPage_pageOnEnterMisses() {
        setPage(staleEnter = true)
        openWithLeft()
        pressRightInDrawer()
        assertBackOnPage("Right (page onEnter misses)")
    }

    @Test
    fun backFromOpenDrawerClosesItNeverExits() {
        setPage(staleEnter = false)
        openWithLeft()
        assertEquals("an enabled Back callback exists while the drawer is open (else Back exits the app)", true, backDispatcher.hasEnabledCallbacks())
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertBackOnPage("Back")
    }

    @Test
    fun backFromOpenDrawerClosesIt_pageOnEnterMisses() {
        setPage(staleEnter = true)
        openWithLeft()
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertBackOnPage("Back (page onEnter misses)")
    }

    /** 26-Nov: the page took ~1.1 s to get focus back (page-box fallback only on the 5th try). It must land within ~2 frames of the key. */
    @Test
    fun rightFromOpenDrawerReachesPageQuickly() {
        setPage(staleEnter = true)
        openWithLeft()
        compose.mainClock.autoAdvance = false
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        assertBackOnPage("Right within ~150 ms")
    }

    @Test
    fun backFromOpenDrawerReachesPageQuickly() {
        setPage(staleEnter = true)
        openWithLeft()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { backDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        assertBackOnPage("Back within ~150 ms")
    }

    /** Page with nothing focusable: leaving must not strand focus on the closed rail; the drawer reopens and keeps it. */
    @Test
    fun rightWithNothingFocusableOnPageReopensDrawer() {
        setPage(staleEnter = false)
        openWithLeft()
        // Make the page's tiles unfocusable
        compose.runOnUiThread { blockTiles = true }
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        assertEquals("drawer reopened", DrawerValue.Open, drawerState.currentValue)
        compose.onNodeWithTag("rail-home").assertIsFocused()
    }
}
