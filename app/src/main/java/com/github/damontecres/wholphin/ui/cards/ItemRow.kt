package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.JellytipRowFocusScope
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus

@Composable
fun <T> ItemRow(
    title: String,
    items: List<T?>,
    onClickItem: (Int, T) -> Unit,
    onLongClickItem: (Int, T) -> Unit,
    cardContent: @Composable (
        index: Int,
        item: T?,
        modifier: Modifier,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    showViewMore: Boolean = false,
    viewMoreCardContent: @Composable (Modifier) -> Unit = {},
    /** Jellytip: a line under the title ("Because you like …" on For you). */
    subtitle: String? = null,
    /** Stable identity of a tile (its item id); null = index identity as before. See [ItemRowKeys]. */
    itemKey: ((T) -> String)? = null,
) {
    val state = rememberLazyListState()
    val keys = remember(items, itemKey) { itemKey?.let { k -> ItemRowKeys.build(items.map { it?.let(k) }) } }
    // A tile inserted at the front of a row that is not focused (Continue Watching after a watch) must not leave the
    // row scrolled one tile in: keys keep the old first tile anchored, so scroll back to the start when it was at the start
    var rowFocused by remember { mutableStateOf(false) }
    val firstKey = keys?.firstOrNull()
    var lastFirstKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(firstKey) {
        val previous = lastFirstKey
        if (firstKey != null) lastFirstKey = firstKey
        // Only a real change of the first tile (not first composition, not a row scrolling back into view)
        if (firstKey != null && previous != null && previous != firstKey && !rowFocused && state.firstVisibleItemIndex <= 1) {
            state.requestScrollToItem(0)
        }
    }
    // getOrNull: a list mutated in place after the keys were built must not crash the row
    val rowKey: ((Int, T?) -> Any)? =
        keys?.let { k -> { index: Int, _: T? -> k.getOrNull(index) ?: "i$index" } }
    val firstFocus = remember { FocusRequester() }
    val focusRequester = remember { FocusRequester() }
    var position by rememberInt()

    val currentOnClickItem by rememberUpdatedState(onClickItem)
    val currentOnLongClickItem by rememberUpdatedState(onLongClickItem)

    Column(
        verticalArrangement = Arrangement.spacedBy(itemRowTitleGap(subtitle)),
        modifier =
            modifier.focusProperties {
                onEnter = {
                    focusRequester.tryRequestFocus()
                }
            },
    ) {
        ItemRowTitle(title, subtitle = subtitle)

        JellytipRowFocusScope { rowFocusModifier ->
            LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(horizontalPadding),
            contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(horizontalPadding, 8.dp),
            modifier =
                rowFocusModifier
                    .jellytipRowBleed()
                    .fillMaxWidth()
                    .focusGroup()
                    .onFocusChanged { rowFocused = it.hasFocus }
                    .focusRestorer(firstFocus)
                    .focusRequester(focusRequester),
        ) {
            itemsIndexed(items, key = rowKey) { index, item ->
                val cardModifier =
                    remember(index, position) {
                        if (index == position) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        }
                    }

                val onClick =
                    remember(index, item) {
                        {
                            position = index
                            if (item != null) currentOnClickItem(index, item)
                        }
                    }

                val onLongClick =
                    remember(index, item) {
                        {
                            position = index
                            if (item != null) currentOnLongClickItem(index, item)
                        }
                    }

                cardContent.invoke(
                    index,
                    item,
                    cardModifier,
                    onClick,
                    onLongClick,
                )
            }
            if (showViewMore) {
                item {
                    val cardModifier =
                        remember(items.size, position) {
                            if (position == items.size) {
                                Modifier.focusRequester(firstFocus)
                            } else {
                                Modifier
                            }
                        }
                    viewMoreCardContent.invoke(cardModifier)
                }
            }
        }
        }
    }
}

@Composable
@NonRestartableComposable
fun ItemRowTitle(
    title: String,
    modifier: Modifier = Modifier,
) = Text(
    text = title.uppercase(),
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.08.em,
    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
    modifier = modifier.padding(start = 8.dp),
)

/**
 * Jellytip: gap between a row's title block and its cards. With a subtitle the line takes the gap's place, so the block
 * grows ~8 dp, not ~23: Home keeps a fixed 50 dp above the focused card for the title (HomePage spaceAbovePx), and the
 * reason line must fit in it without being clipped under the hero.
 */
fun itemRowTitleGap(subtitle: String?) = if (subtitle.isNullOrBlank()) 8.dp else 0.dp

/** Jellytip: the row title with an optional quieter line under it (For you's "Because you like …"). */
@Composable
fun ItemRowTitle(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    if (subtitle.isNullOrBlank()) {
        ItemRowTitle(title, modifier)
    } else {
        Column(modifier) {
            ItemRowTitle(title)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
