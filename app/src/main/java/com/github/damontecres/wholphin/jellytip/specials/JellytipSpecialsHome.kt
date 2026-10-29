package com.github.damontecres.wholphin.jellytip.specials

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeViewModel
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsAnswer
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
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

    /** A server-built row (rows=1 manifest). */
    data class Server(
        val row: JellytipServerRow,
    ) : JellytipHomeEntry {
        override val key get() = "srv-${row.id}"
    }
}

/** What a focused special (or server-row) tile tells the page: the film for the hero (null = keep the last one) and whether its text shows. */
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
    serverRows: List<JellytipServerRow> = emptyList(),
    hiddenRows: Set<Int> = emptySet(),
): List<JellytipHomeEntry> {
    if (serverRows.isNotEmpty()) return withServerRows(rows, specials, leadingRows, serverRows, hiddenRows)
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

/**
 * Server rows present: Continue Watching / Next Up first, then the server rows in order (the server's list replaces the
 * old carousel rows, [hiddenRows]), then the page's other rows. A special's `position` N = before the Nth server row.
 */
private fun withServerRows(
    rows: List<HomeRowLoadingState>,
    specials: List<JellytipSpecial>,
    leadingRows: Int?,
    serverRows: List<JellytipServerRow>,
    hiddenRows: Set<Int>,
): List<JellytipHomeEntry> {
    val lead = leadingRows ?: rows.indexOfFirst { !it.isContinueWatchingNextUp }.let { if (it < 0) rows.size else it }
    val byPosition = specials.distinctBy { it.id }.sortedBy { it.position }.groupBy { it.position.coerceAtMost(serverRows.size) }
    return buildList {
        (0 until lead.coerceAtMost(rows.size)).filter { it !in hiddenRows }.forEach { add(JellytipHomeEntry.Row(it)) }
        serverRows.distinctBy { it.id }.forEachIndexed { n, row ->
            byPosition[n]?.forEach { add(JellytipHomeEntry.Special(it)) }
            add(JellytipHomeEntry.Server(row))
        }
        byPosition[serverRows.size]?.forEach { add(JellytipHomeEntry.Special(it)) }
        (lead until rows.size).filter { it !in hiddenRows }.forEach { add(JellytipHomeEntry.Row(it)) }
    }
}

/** Today's server rows for [page]: the cached answer at once, then the fresh one; null = old manifest / unavailable. */
@Composable
fun rememberJellytipRows(
    page: String,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
): JellytipRowsAnswer? {
    val loaded by produceState(viewModel.rows.peek(page), page) {
        viewModel.rows.load(page)?.let { value = it }
    }
    return loaded
}
