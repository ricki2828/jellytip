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
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in labels.indices) listState.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(sizes.px(14f)),
        modifier = modifier.focusRestorer(selectedRequester),
    ) {
        itemsIndexed(labels) { index, label ->
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val selected = index == selectedIndex
            Column(
                modifier =
                    Modifier
                        .then(if (selected) Modifier.focusRequester(selectedRequester) else Modifier)
                        .onFocusChanged { if (it.hasFocus) currentOnSelect(index) }
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

/**
 * Admin-only free-space line for the nav drawer ("58 GB free of 3.7 TB" + thin bar). Refreshes whenever the drawer
 * opens; text and bar turn jelly red below [JellytipStorage.LOW_GB]. Collapsed rail shows only a red dot when low.
 */
@Composable
fun JellytipStorageMeter(
    api: JellytipApi,
    drawerOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val admin by rememberIsJellytipAdmin()
    if (!admin) return
    var storage by remember { mutableStateOf<JellytipStorage?>(null) }
    LaunchedEffect(drawerOpen) { if (drawerOpen || storage == null) api.getStorage()?.let { storage = it } }
    val s = storage ?: return
    val sizes = rememberJellytipSizes()
    val color = if (s.low) JellytipPalette.Jelly else Cream
    if (!drawerOpen) {
        if (s.low) {
            Box(
                modifier
                    .padding(vertical = sizes.px(8f))
                    .size(sizes.px(10f))
                    .background(JellytipPalette.Jelly, CircleShape),
            )
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = sizes.px(24f), vertical = sizes.px(10f))) {
        Text(
            text = s.label(),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = sizes.sp(JellytipHomeTokens.BADGE_PX)),
            color = color.copy(alpha = if (s.low) 1f else 0.6f),
            maxLines = 1,
        )
        Box(
            Modifier
                .padding(top = sizes.px(6f))
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF2A2A2A)),
        ) {
            Box(Modifier.fillMaxWidth(s.fractionUsed).height(2.dp).background(color))
        }
    }
}
