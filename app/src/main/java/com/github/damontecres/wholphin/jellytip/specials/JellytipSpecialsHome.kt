package com.github.damontecres.wholphin.jellytip.specials

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.ui.main.isContinueWatchingNextUp
import com.github.damontecres.wholphin.util.HomeRowLoadingState

/** One entry of the home list: a normal row (index into the page's rows) or a special inserted between them. */
sealed interface JellytipHomeEntry {
    val key: String

    data class Row(
        val index: Int,
    ) : JellytipHomeEntry {
        override val key get() = "row-$index"
    }

    data class Special(
        val special: JellytipSpecial,
    ) : JellytipHomeEntry {
        override val key get() = "special-${special.id}"
    }
}

/** What a focused special tile tells the page: the film for the hero (null = keep the last one) and whether its text shows. */
data class JellytipSpecialFocus(
    val id: String,
    val item: BaseItem?,
    val showHeader: Boolean,
)

/**
 * Interleave [specials] with the page's [rows]. A special's `position` N = before the Nth normal row, where normal rows
 * are the visible rows after the leading Continue Watching / Next Up block ([leadingRows] when the page knows it, else
 * detected from the row types). Past the last row = at the end. No specials = exactly the rows, in order.
 */
fun jellytipHomeEntries(
    rows: List<HomeRowLoadingState>,
    specials: List<JellytipSpecial>,
    leadingRows: Int?,
): List<JellytipHomeEntry> {
    if (specials.isEmpty()) return rows.indices.map { JellytipHomeEntry.Row(it) }
    val lead = leadingRows ?: rows.indexOfFirst { !it.isContinueWatchingNextUp }.let { if (it < 0) rows.size else it }
    val normal = rows.indices.filter { it >= lead && !(rows[it] is HomeRowLoadingState.Success && (rows[it] as HomeRowLoadingState.Success).items.isEmpty()) }
    val before = mutableMapOf<Int, MutableList<JellytipSpecial>>()
    val tail = mutableListOf<JellytipSpecial>()
    specials.distinctBy { it.id }.sortedBy { it.position }.forEach { special ->
        val target = normal.getOrNull(special.position)
        if (target == null) tail += special else before.getOrPut(target) { mutableListOf() } += special
    }
    return buildList {
        rows.indices.forEach { index ->
            before[index]?.forEach { add(JellytipHomeEntry.Special(it)) }
            add(JellytipHomeEntry.Row(index))
        }
        tail.forEach { add(JellytipHomeEntry.Special(it)) }
    }
}

/** Today's specials for [page] (`home`, `movies`, `shows`): the cached answer at once, then the fresh one. */
@Composable
fun rememberJellytipSpecials(
    page: String,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
): JellytipSpecialsPage? {
    val loaded by produceState(viewModel.specials.peek(page), page) {
        viewModel.specials.load(page)?.let { value = it }
    }
    return loaded
}
