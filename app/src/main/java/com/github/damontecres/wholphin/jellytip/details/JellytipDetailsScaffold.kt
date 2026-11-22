package com.github.damontecres.wholphin.jellytip.details

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.jellytip.BoxScopeDownHint
import com.github.damontecres.wholphin.jellytip.RestoreFocusOnResume
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * One lower block of a details page (a row, or a custom block). It is composed only after Down from the hero buttons,
 * so moving along the buttons can never scroll to, reveal or flash it.
 *
 * @param key unique and stable within a page; also the lazy item key and the value [JellytipDetailsScaffold]'s `openAt` matches
 * @param present false = left out entirely (nothing to show, or not loaded yet)
 * @param content draws the section; it must put `Modifier.focusRequester(focus)` on its first focusable node, or the
 *  scaffold cannot focus it
 */
class JellytipDetailsSection(
    val key: String,
    val present: Boolean,
    val content: @Composable (focus: FocusRequester) -> Unit,
)

/** What [JellytipDetailsScaffold] hands the hero slot: modifiers to put on the hero block, and the focus callback to wire. */
class JellytipHeroSlot(
    /** Fills the first screen and fades with the page: apply to the block's outer container (`JellytipDetailsBlock`'s `modifier`). */
    val modifier: Modifier,
    /** Focus requester, Down handling and focus tracking for the buttons row (`buttonRowModifier`). Append to it freely. */
    val buttonRowModifier: Modifier,
    /** Pass to the block's `onFocusWithin`: tells the scaffold whether focus is anywhere in the hero. */
    val onFocusWithin: (Boolean) -> Unit,
)

/**
 * Page state the caller may read: e.g. library film only takes its lower-row data while [sectionsActive] is false so
 * nothing moves under focus. Hoisted so the caller sees changes in the same composition as the scaffold.
 */
@Stable
class JellytipDetailsScaffoldState(
    openAtSection: Boolean = false,
) {
    /** True from Down on the hero buttons until focus returns to the hero. */
    var sectionsActive by mutableStateOf(openAtSection)

    /** True while focus is anywhere in the hero block. */
    var topFocused by mutableStateOf(!openAtSection)

    /** True while focus is on the hero's buttons row. */
    var buttonRowFocused by mutableStateOf(false)
}

@Composable
fun rememberJellytipDetailsScaffoldState(openAt: String? = null): JellytipDetailsScaffoldState = remember { JellytipDetailsScaffoldState(openAt != null) }

/**
 * The details page skeleton shared by every source: backdrop, a full-screen hero block, and [sections] that appear
 * only after Down from the hero buttons.
 *
 *  - Opens hero-first: list at the top, hero buttons focused (or, with [openAt], that section opened and focused).
 *  - Down on the hero buttons (when at least one section is [JellytipDetailsSection.present]) composes the sections,
 *    scrolls to them and focuses the first present one (the [openAt] one first, once); if nothing takes focus it
 *    falls back to the hero. Down is swallowed only for that opening press; afterwards the focus system moves it.
 *  - While focus is below, the hero fades out and the backdrop dims; when focus returns to the hero the sections are
 *    disposed (the LazyColumn is back to the hero alone).
 *  - A Down hint shows while the hero has focus and there is something below.
 *  - Focus is restored to the hero on resume when nothing on the page has it (trailer, external activity).
 *
 * @param backdrop the full-bleed background; told whether the buttons row has focus and whether to dim
 * @param hero draws the hero block (library: `JellytipDetailsBlock`), using the [JellytipHeroSlot]'s modifiers
 * @param overlay drawn over the backdrop and under the list (pull-quote, breadcrumb); [headerAlpha] is the hero's current fade
 */
@Composable
fun JellytipDetailsScaffold(
    backdrop: @Composable (buttonRowFocused: Boolean, dimmed: Boolean) -> Unit,
    hero: @Composable (JellytipHeroSlot) -> Unit,
    sections: List<JellytipDetailsSection>,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(headerAlpha: () -> Float) -> Unit = {},
    openAt: String? = null,
    state: JellytipDetailsScaffoldState = rememberJellytipDetailsScaffoldState(openAt),
) {
    val heroFocus = remember { FocusRequester() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val currentSections by rememberUpdatedState(sections)
    val hasBelow = sections.any { it.present }
    var pendingOpenAt by remember { mutableStateOf(openAt) }

    val listState = rememberLazyListState()
    // Always open hero-first: hero focused, list scrolled to top (never restore a saved row). With openAt the sections
    // effect below takes focus instead.
    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
        if (openAt == null) heroFocus.tryRequestFocus()
    }
    LaunchedEffect(state.topFocused) { if (state.topFocused) state.sectionsActive = false }
    LaunchedEffect(state.sectionsActive) {
        if (state.sectionsActive) {
            // Rows only exist once sectionsActive composes them: wait for that, scroll to them, then focus
            withFrameNanos { }
            withFrameNanos { }
            if (listState.layoutInfo.totalItemsCount > 1) listState.animateScrollToItem(1)
            withFrameNanos { }
            val present = currentSections.filter { it.present }
            val first = pendingOpenAt?.let { k -> present.firstOrNull { it.key == k } }
            pendingOpenAt = null
            val order = if (first != null) listOf(first) + present.filter { it !== first } else present
            var focused = false
            for (s in order) {
                if (requesters.getOrPut(s.key) { FocusRequester() }.tryRequestFocus()) {
                    focused = true
                    break
                }
            }
            // Nothing took focus: fall back to the hero so Down is not swallowed forever
            if (!focused) {
                state.sectionsActive = false
                listState.scrollToItem(0)
                heroFocus.tryRequestFocus()
            }
        }
    }
    val headerAlpha by animateFloatAsState(if (state.topFocused) 1f else 0f, tween(250), label = "jellytip-header")

    var pageHasFocus by remember { mutableStateOf(false) }
    RestoreFocusOnResume(heroFocus, pageHasFocus)
    Box(modifier = modifier.onFocusChanged { pageHasFocus = it.hasFocus }) {
        backdrop(state.buttonRowFocused, !state.topFocused)
        overlay(this) { headerAlpha }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                hero(
                    JellytipHeroSlot(
                        modifier = Modifier.fillParentMaxSize().graphicsLayer { alpha = headerAlpha },
                        buttonRowModifier =
                            Modifier
                                .focusRequester(heroFocus)
                                .onPreviewKeyEvent {
                                    if (hasBelow && it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                        // Already active: let the focus system move Down normally
                                        if (state.sectionsActive) {
                                            false
                                        } else {
                                            state.sectionsActive = true
                                            true
                                        }
                                    } else {
                                        false
                                    }
                                }.onFocusChanged { state.buttonRowFocused = it.hasFocus },
                        onFocusWithin = { state.topFocused = it },
                    ),
                )
            }
            if (state.sectionsActive) {
                sections.forEach { section ->
                    if (section.present) {
                        item(key = section.key) {
                            section.content(requesters.getOrPut(section.key) { FocusRequester() })
                        }
                    }
                }
            }
        }
        BoxScopeDownHint(
            visible = state.topFocused && hasBelow && !state.sectionsActive,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
