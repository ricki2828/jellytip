package com.github.damontecres.wholphin.jellytip.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.JellytipStorage
import com.github.damontecres.wholphin.jellytip.rememberIsJellytipAdmin
import com.github.damontecres.wholphin.jellytip.rememberJellytipSizes

private val Cream = Color(0xFFF2F2F0)

/** One level of the series > season > episode trail. */
data class JellytipCrumb(
    val label: String,
    val onClick: () -> Unit,
    val focusRequester: FocusRequester? = null,
)

/**
 * Quiet trail ("Slow Horses  >  Season 6  >  E2 . Dead Weight"): the last crumb is the current level in off-white,
 * parents are secondary grey. Every crumb is focusable and opens its level.
 */
@Composable
fun JellytipBreadcrumb(
    crumbs: List<JellytipCrumb>,
    modifier: Modifier = Modifier,
) {
    val sizes = rememberJellytipSizes()
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = sizes.sp(JellytipHomeTokens.BADGE_PX))
    Row(
        horizontalArrangement = Arrangement.spacedBy(sizes.px(6f)),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.focusGroup(),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text("›", style = textStyle, color = Cream.copy(alpha = 0.35f))
            }
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val last = index == crumbs.lastIndex
            Text(
                text = crumb.label,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color =
                    when {
                        focused -> Color.White
                        last -> Cream
                        else -> Cream.copy(alpha = 0.5f)
                    },
                modifier =
                    Modifier
                        .then(if (crumb.focusRequester != null) Modifier.focusRequester(crumb.focusRequester) else Modifier)
                        .clip(RoundedCornerShape(sizes.px(4f)))
                        .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable(interactionSource = source, indication = null, onClick = crumb.onClick)
                        .padding(horizontal = sizes.px(8f), vertical = sizes.px(4f)),
            )
        }
    }
}

private const val SEASON_DEBOUNCE_MS = 250L

/**
 * Horizontal season chips ("S1 S2 ... Specials"). Focusing a chip selects it, so Left/Right switches season in place;
 * the selected season is off-white with a jelly-red underline.
 */
@Composable
fun JellytipSeasonChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    selectedRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit = {},
) {
    val sizes = rememberJellytipSizes()
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = sizes.sp(JellytipHomeTokens.TEXT_PX))
    val listState = rememberLazyListState()
    val currentOnSelect by rememberUpdatedState(onSelect)
    var rowFocused by remember { mutableStateOf(false) }
    // Keep the selected chip composed: when the row is not focused, jump (instantly) so focusRestorer can always find it
    LaunchedEffect(selectedIndex, rowFocused) {
        if (selectedIndex !in labels.indices) return@LaunchedEffect
        if (rowFocused) listState.animateScrollToItem(selectedIndex) else listState.scrollToItem(selectedIndex)
    }
    // Debounce: skimming across chips must not load every season, only the one focus settles on
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(focusedIndex) {
        val i = focusedIndex ?: return@LaunchedEffect
        if (i == selectedIndex) return@LaunchedEffect
        delay(SEASON_DEBOUNCE_MS)
        currentOnSelect(i)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(sizes.px(14f)),
        modifier = modifier.onFocusChanged { rowFocused = it.hasFocus }.focusRestorer(selectedRequester),
    ) {
        itemsIndexed(labels) { index, label ->
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val selected = index == selectedIndex
            Column(
                modifier =
                    Modifier
                        .then(if (selected) Modifier.focusRequester(selectedRequester) else Modifier)
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index }
                        .clip(RoundedCornerShape(sizes.px(4f)))
                        .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable(interactionSource = source, indication = null, onClick = onConfirm)
                        .padding(horizontal = sizes.px(10f), vertical = sizes.px(4f)),
            ) {
                Text(
                    text = label,
                    style = textStyle,
                    maxLines = 1,
                    color =
                        when {
                            focused -> Color.White
                            selected -> Cream
                            else -> Cream.copy(alpha = 0.5f)
                        },
                )
                Box(
                    Modifier
                        .padding(top = sizes.px(3f))
                        .height(2.dp)
                        .fillMaxWidth()
                        .background(if (selected) JellytipPalette.Jelly else Color.Transparent),
                )
            }
        }
    }
}

/** Admin-only server free space for the drawer; null for non-admins. Refreshes whenever the drawer opens. */
@Composable
fun rememberJellytipStorage(
    api: JellytipApi,
    drawerOpen: Boolean,
): JellytipStorage? {
    val admin by rememberIsJellytipAdmin()
    var storage by remember { mutableStateOf<JellytipStorage?>(null) }
    LaunchedEffect(drawerOpen, admin) {
        if (admin && (drawerOpen || storage == null)) api.getStorage()?.let { storage = it }
    }
    return if (admin && drawerOpen) storage else null
}
