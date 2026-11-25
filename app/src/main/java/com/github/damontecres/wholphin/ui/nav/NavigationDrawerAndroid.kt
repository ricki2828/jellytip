/*
 * This file contains code copied and modified from:
 * https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-androidx-tv-material-release/tv/tv-material/src/main/java/androidx/tv/material3/
 *
 * Their license & required attribution is below.
 *
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.damontecres.wholphin.ui.nav

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.DrawerState
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.NavigationDrawerItemColors
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.rememberDrawerState

/**
 * This is a re-implementation of [androidx.tv.material3.ModalNavigationDrawer].
 *
 * It changes padding, sizing, and animations that couldn't be changed in the Androidx implementation.
 */
@Composable
fun ModalNavigationDrawer(
    drawerContent: @Composable NavigationDrawerScope.(DrawerValue) -> Unit,
    modifier: Modifier = Modifier,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    // Jellytip (25-Nov): Back with the drawer open closes it and returns to the page (false while the caller's own Back
    // use comes first, e.g. NavDrawer's expanded "More")
    backCloses: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Jellytip (t5n): whether the page holds focus, so an arrow move from the page can be told from initial-focus
    // search (nothing focused), which must be let in
    var contentHasFocus by remember { mutableStateOf(false) }
    // The page box: leaving the drawer falls back to focusing into it when the geometric Right search finds nothing
    val pageRequester = remember { FocusRequester() }
    // Jellytip: the open drawer is drawn OVER the page (no push) with a light scrim on the page. Only what is drawn changes:
    // the scrim fades with the spring the page offset used; while open or fading the drawer is raised above page and scrim
    // (zIndex changes draw order only, not composition or focus order); closed, draw order is as before.
    val scrimAlpha by animateFloatAsState(
        targetValue = if (drawerState.currentValue == DrawerValue.Open) DRAWER_SCRIM_ALPHA else 0f,
        animationSpec = spring(stiffness = DrawerAnimationStiffness),
        label = "drawerScrim",
    )
    val raised = drawerState.currentValue == DrawerValue.Open || scrimAlpha > 0f
    Box(modifier = modifier) {
        DrawerSheet(
            modifier =
                Modifier
                    .zIndex(if (raised) 1f else 0f)
                    .align(Alignment.CenterStart),
            drawerState = drawerState,
            contentHasFocus = { contentHasFocus },
            backCloses = backCloses,
            pageRequester = pageRequester,
            content = drawerContent,
        )

        Box(Modifier.focusRequester(pageRequester).onFocusChanged { contentHasFocus = it.hasFocus }) {
            content()
        }
        if (scrimAlpha > 0f) {
            // Draw-only: no pointer, key or focus handling, so it cannot intercept anything
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(0.5f)
                    .drawBehind { drawRect(color = Color.Black, alpha = scrimAlpha) },
            )
        }
    }
}

/** Black over the page while the drawer is open. */
private const val DRAWER_SCRIM_ALPHA = 0.35f

@Composable
private fun DrawerSheet(
    modifier: Modifier = Modifier,
    drawerState: DrawerState = remember { DrawerState() },
    contentHasFocus: () -> Boolean = { true },
    backCloses: Boolean = true,
    pageRequester: FocusRequester? = null,
    content: @Composable NavigationDrawerScope.(DrawerValue) -> Unit,
) {
    // indicates that the drawer has been set to its initial state and has grabbed focus if
    // necessary. Controls whether focus is used to decide the state of the drawer going forward.
    var initializationComplete: Boolean by remember { mutableStateOf(false) }
    var focusState by remember { mutableStateOf<FocusState?>(null) }
    val focusRequester = remember { FocusRequester() }
    // Jellytip (t5n, 21-Nov): the drawer opens only when the person asks for it. Ricki saw it slide in and out on
    // ordinary clicks: when a page's focused item is disposed during navigation (or its focus restore misses),
    // Compose gives focus to the first focusable in the tree, the drawer, which opened it until the page took focus
    // back a few frames later. Now: Left at the left edge (or a programmatic Open, e.g. Back on Home) opens it;
    // Up/Down/Right into the rail are refused; any other arrival (fallback focus) keeps it closed and hands focus
    // back to the page, opening only if the page never takes it (so focus is never left invisible).
    var enterDirection by remember { mutableStateOf<FocusDirection?>(null) }
    var enterAt by remember { mutableStateOf(0L) }
    var uninvited by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // Jellytip (25-Nov, Ricki on the TV): in the open drawer Right did nothing and Back quit the app. Leaving now closes
    // the drawer first, then moves focus right once the closed rail is laid out (retried while the page settles); if
    // the page never takes focus the drawer opens again, so focus is never left on the invisible rail.
    var leaving by remember { mutableStateOf(false) }
    val leaveToPage: (String) -> Unit = { why ->
        if (!leaving) {
            leaving = true
            uninvited = false
            JellytipTrace.focus("drawer: $why -> page")
            drawerState.setValue(DrawerValue.Closed)
            scope.launch {
                try {
                    repeat(LEAVE_TRIES) { n ->
                        if (n > 0) delay(HANDBACK_RETRY_MS) else withFrameNanos { }
                        if (focusState?.hasFocus != true) return@launch
                        // Opened again meanwhile (a second Back on Home opens it): stop, he is in the drawer now
                        if (drawerState.currentValue != DrawerValue.Closed) return@launch
                        if (focusManager.moveFocus(FocusDirection.Right)) {
                            JellytipTrace.focus("drawer: $why moved right (try ${n + 1})")
                            return@launch
                        }
                        if (pageRequester != null && pageRequester.tryRequestFocus() && focusState?.hasFocus != true) {
                            JellytipTrace.focus("drawer: $why right search failed; focused the page box (try ${n + 1})")
                            return@launch
                        }
                    }
                    if (focusState?.hasFocus == true && drawerState.currentValue == DrawerValue.Closed) {
                        JellytipTrace.focus("drawer: page did not take focus after $why; reopened")
                        drawerState.setValue(DrawerValue.Open)
                    }
                } finally {
                    leaving = false
                }
            }
        }
    }
    BackHandler(enabled = backCloses && drawerState.currentValue == DrawerValue.Open) {
        if (focusState?.hasFocus == true) leaveToPage("Back") else drawerState.setValue(DrawerValue.Closed)
    }
    LaunchedEffect(key1 = drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open && focusState?.hasFocus == false) {
            // used to grab focus if the drawer state is set to Open on start.
            focusRequester.requestFocus()
        }
        initializationComplete = true
    }

    val internalModifier =
        Modifier
            .focusRequester(focusRequester)
            .animateContentSize(
                animationSpec =
                    spring(
                        stiffness = DrawerAnimationStiffness,
                        visibilityThreshold = IntSize.VisibilityThreshold,
                    ),
            ).fillMaxHeight()
            // adding passed-in modifier here to ensure animateContentSize is called before other
            // size based modifiers.
            .then(modifier)
            .onFocusChanged {
                val had = focusState?.hasFocus == true
                focusState = it
                if (!initializationComplete) return@onFocusChanged
                if (!it.hasFocus) {
                    uninvited = false
                    enterDirection = null
                    drawerState.setValue(DrawerValue.Closed)
                    return@onFocusChanged
                }
                if (had) return@onFocusChanged // moving inside the drawer
                // A Left entry counts only if it just happened (the rail's own onEnter may redirect focus inside the
                // same move, re-entering with Enter: that must not cancel the Left)
                val dir = enterDirection.takeIf { android.os.SystemClock.uptimeMillis() - enterAt < 500 }
                enterDirection = null
                if (dir == FocusDirection.Left || drawerState.currentValue == DrawerValue.Open) {
                    uninvited = false
                    drawerState.setValue(DrawerValue.Open)
                } else {
                    uninvited = true
                    JellytipTrace.focus("drawer: focus arrived without Left (dir=${dir ?: "none"}): kept closed")
                    scope.launch {
                        // Slow pages (cold Home, loading screens) may have nothing focusable yet: keep trying for ~2 s
                        // before opening, or the page grabbing focus later slides the drawer out again
                        delay(UNINVITED_HANDBACK_MS)
                        repeat(HANDBACK_TRIES) { n ->
                            if (!uninvited || focusState?.hasFocus != true) return@launch
                            if (focusManager.moveFocus(FocusDirection.Right)) {
                                JellytipTrace.focus("drawer: page did not take focus; handed it right (try ${n + 1})")
                                return@launch
                            }
                            delay(HANDBACK_RETRY_MS)
                        }
                        if (uninvited && focusState?.hasFocus == true) {
                            JellytipTrace.focus("drawer: nothing to hand focus to after retries; opening")
                            uninvited = false
                            drawerState.setValue(DrawerValue.Open)
                        }
                    }
                }
            }.onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionRight && drawerState.currentValue == DrawerValue.Open) {
                    // Right anywhere in the open drawer = back to the page (both key events, acting on the down)
                    if (e.type == KeyEventType.KeyDown) leaveToPage("Right")
                    true
                } else if (uninvited && drawerState.currentValue != DrawerValue.Open &&
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft
                ) {
                    uninvited = false
                    drawerState.setValue(DrawerValue.Open)
                    true
                } else {
                    false
                }
            }.focusProperties {
                onEnter = {
                    when (requestedFocusDirection) {
                        // Arrow keys other than Left never pull focus into the rail
                        FocusDirection.Up, FocusDirection.Down, FocusDirection.Right -> {
                            // Only refuse a move that comes from the page: with nothing focused this is initial-focus
                            // search (it starts top-left), which must be let in (then handed back) or the key goes dead
                            if (drawerState.currentValue != DrawerValue.Open && contentHasFocus()) {
                                cancelFocusChange()
                            } else {
                                enterDirection = requestedFocusDirection
                                enterAt = android.os.SystemClock.uptimeMillis()
                            }
                        }
                        else -> {
                            val recentLeft = enterDirection == FocusDirection.Left && android.os.SystemClock.uptimeMillis() - enterAt < 500
                            if (!recentLeft) {
                                enterDirection = requestedFocusDirection
                                enterAt = android.os.SystemClock.uptimeMillis()
                            }
                        }
                    }
                }
            }.focusGroup()

    // Collapsed: soft left gradient so rail icons stay legible over imagery; open: translucent panel
    val panelAlpha by animateFloatAsState(if (drawerState.currentValue == DrawerValue.Open) 1f else 0f, label = "panelAlpha")
    Box(
        modifier =
            internalModifier.drawBehind {
                if (panelAlpha < 1f) {
                    drawRect(
                        brush =
                            Brush.horizontalGradient(
                                listOf(JellytipDrawerNight.copy(alpha = 0.7f), Color.Transparent),
                                startX = 0f,
                                endX = size.width,
                            ),
                        alpha = 1f - panelAlpha,
                    )
                }
                if (panelAlpha > 0f) {
                    drawRect(color = JellytipDrawerNight.copy(alpha = 0.92f), alpha = panelAlpha)
                }
            },
    ) {
        NavigationDrawerScopeImpl(drawerState.currentValue == DrawerValue.Open).apply {
            content(drawerState.currentValue)
        }
    }
}

internal class NavigationDrawerScopeImpl(
    override val hasFocus: Boolean,
) : NavigationDrawerScope

/** How long fallback focus may sit on the closed rail before it is handed back to the page. */
private const val UNINVITED_HANDBACK_MS = 350L
private const val HANDBACK_RETRY_MS = 250L
private const val HANDBACK_TRIES = 7

/** Tries (first after one frame, then every [HANDBACK_RETRY_MS]) to move focus from the closing drawer to the page. */
private const val LEAVE_TRIES = 6

internal val CollapsedDrawerItemWidth = 64.dp
internal val ExpandedDrawerItemWidth = 224.dp
internal val DrawerIconSize = 24.dp
internal val JellytipDrawerNight = Color(0xFF0A0A0A)
internal val JellytipDrawerFocus = Color(0xFF1C1C1C)
internal val JellytipDrawerText = Color(0xFFF2F2F0)
internal val JellytipDrawerAccent = Color(0xFFE2264D)
internal val DrawerIconPadding = (ListItemDefaults.IconSize - DrawerIconSize) / 2
internal val DrawerAnimationStiffness = Spring.StiffnessMedium

/** Room kept free at the open drawer's right edge, so a long label or sub-line ends before it (and shows an ellipsis). */
internal val DrawerItemEndPadding = 16.dp

/** Open drawer: one-line labels end in an ellipsis. Closed rail: clipped as before (the label is hidden there, an ellipsis must not peek out). */
internal fun drawerTextOverflow(drawerOpen: Boolean): TextOverflow = if (drawerOpen) TextOverflow.Ellipsis else TextOverflow.Clip

@Composable
internal fun NavigationDrawerScope.NavigationDrawerItem(
    selected: Boolean,
    onClick: () -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    tonalElevation: Dp = NavigationDrawerItemDefaults.NavigationDrawerItemElevation,
    colors: NavigationDrawerItemColors = jellytipDrawerColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val animatedWidth by
        animateDpAsState(
            targetValue =
                if (hasFocus) {
                    ExpandedDrawerItemWidth
                } else {
                    CollapsedDrawerItemWidth
                },
            label = "NavigationDrawerItem width open/closed state of the drawer item",
        )
    val navDrawerItemHeight =
        if (supportingContent == null) {
            NavigationDrawerItemDefaults.ContainerHeightOneLine
        } else {
            NavigationDrawerItemDefaults.ContainerHeightTwoLine
        }

    // The same 16 dp at the end of the headline and the sub-line while the drawer is open
    val endPad = if (hasFocus) DrawerItemEndPadding else 0.dp
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Box(Modifier.padding(end = endPad)) { content() } },
        leadingContent = {
            Box(
                Modifier
                    .padding(horizontal = DrawerIconPadding)
                    .size(DrawerIconSize),
            ) { leadingContent() }
        },
        trailingContent = trailingContent,
        supportingContent = supportingContent?.let { sc -> { Box(Modifier.padding(end = endPad)) { sc() } } },
        modifier =
            modifier
                .layout { measurable, constraints ->
                    val width = animatedWidth.roundToPx()
                    val height = navDrawerItemHeight.roundToPx()
                    val placeable =
                        measurable.measure(
                            constraints.copy(
                                minWidth = width,
                                maxWidth = width,
                                minHeight = height,
                                maxHeight = height,
                            ),
                        )
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        enabled = enabled,
        onLongClick = onLongClick,
        tonalElevation = tonalElevation,
        colors = colors.toToggleableListItemColors(hasFocus),
        scale = ListItemDefaults.scale(1f, 1f),
        shape = ListItemDefaults.shape(RoundedCornerShape(10.dp)),
        border = ListItemDefaults.border(border = Border.None, focusedBorder = Border.None),
        interactionSource = interactionSource,
    )
}

@Composable
private fun NavigationDrawerItemColors.toToggleableListItemColors(doesNavigationDrawerHaveFocus: Boolean) =
    ListItemDefaults.colors(
        containerColor = containerColor,
        contentColor = if (doesNavigationDrawerHaveFocus) contentColor else inactiveContentColor,
        focusedContainerColor = focusedContainerColor,
        focusedContentColor = focusedContentColor,
        pressedContainerColor = pressedContainerColor,
        pressedContentColor = pressedContentColor,
        selectedContainerColor = selectedContainerColor,
        selectedContentColor = selectedContentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor =
            if (doesNavigationDrawerHaveFocus) {
                disabledContentColor
            } else {
                disabledInactiveContentColor
            },
        focusedSelectedContainerColor = focusedSelectedContainerColor,
        focusedSelectedContentColor = focusedSelectedContentColor,
        pressedSelectedContainerColor = pressedSelectedContainerColor,
        pressedSelectedContentColor = pressedSelectedContentColor,
    )

/** Transparent at rest, white text on a #1C1C1C rounded rect when focused. */
@Composable
internal fun jellytipDrawerColors(): NavigationDrawerItemColors =
    NavigationDrawerItemDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = JellytipDrawerText.copy(alpha = 0.7f),
        inactiveContentColor = JellytipDrawerText.copy(alpha = 0.6f),
        focusedContainerColor = JellytipDrawerFocus,
        focusedContentColor = Color.White,
        pressedContainerColor = JellytipDrawerFocus,
        pressedContentColor = Color.White,
        selectedContainerColor = Color.Transparent,
        selectedContentColor = JellytipDrawerText,
        focusedSelectedContainerColor = JellytipDrawerFocus,
        focusedSelectedContentColor = Color.White,
        pressedSelectedContainerColor = JellytipDrawerFocus,
        pressedSelectedContentColor = Color.White,
    )

/** 3 px jelly-red bar on the item's left edge marks the selected destination. */
@Composable
internal fun Modifier.jellytipSelectedBar(selected: Boolean): Modifier {
    if (!selected) return this
    val barWidth = with(LocalDensity.current) { 3.dp.toPx().coerceAtLeast(3f) }
    return drawBehind {
        val inset = 6.dp.toPx()
        drawRect(
            color = JellytipDrawerAccent,
            topLeft = Offset(0f, inset),
            size = Size(barWidth, size.height - inset * 2),
        )
    }
}
