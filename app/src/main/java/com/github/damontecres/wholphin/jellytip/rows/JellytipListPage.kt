package com.github.damontecres.wholphin.jellytip.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.home.JellytipCarouselService
import com.github.damontecres.wholphin.jellytip.home.JellytipLetterboxdRow
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class JellytipListViewModel
    @Inject
    constructor(
        val carousels: JellytipCarouselService,
    ) : ViewModel()

private const val PER_ROW = 8

private sealed interface ListState {
    data object Loading : ListState

    data object Missing : ListState

    data class Loaded(
        val list: JellytipLetterboxdRow,
    ) : ListState
}

/**
 * A Letterboxd list (#list): the films of one list the companion tracks, in the list's own order, from the carousels
 * manifest (the same data as the Home rows). Rows of [PER_ROW] titled cards, drawn like server rows.
 */
@Composable
fun JellytipListPage(
    slug: String,
    name: String,
    modifier: Modifier = Modifier,
    viewModel: JellytipListViewModel = hiltViewModel(),
) {
    val state by produceState<ListState>(ListState.Loading, slug) {
        val l = viewModel.carousels.manifest()?.letterboxd?.firstOrNull { it.slug == slug }
        value = if (l == null) ListState.Missing else ListState.Loaded(l)
    }
    Column(modifier.fillMaxSize().background(JellytipPalette.Night).padding(start = 24.dp, top = 20.dp)) {
        val s = state
        Text(
            (s as? ListState.Loaded)?.list?.name ?: name,
            color = JellytipPalette.Vanilla,
            fontFamily = JellytipFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
        )
        when (s) {
            ListState.Loading -> Text("Loading…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            ListState.Missing -> Text("This list isn't available.", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            is ListState.Loaded -> {
                Text(
                    "${s.list.itemIds.size} films in the library · Letterboxd",
                    color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                    fontFamily = JellytipFontFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                val chunks = s.list.itemIds.chunked(PER_ROW)
                if (chunks.isEmpty()) {
                    Text("None of this list's films are in the library.", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                    itemsIndexed(chunks, key = { i, _ -> "list:$slug:$i" }) { i, ids ->
                        JellytipServerRowView(
                            row =
                                JellytipServerRow(
                                    id = "list:$slug:$i",
                                    source = "letterboxd",
                                    family = "letterboxd",
                                    title = if (i == 0) "In list order" else "",
                                    layout = "standard",
                                    mediaType = "movie",
                                    itemIds = ids,
                                    collection = null,
                                ),
                            onFocusItem = { _, _ -> },
                            onLongClickItem = null,
                            focusModifier = Modifier.focusGroup(),
                        )
                    }
                }
            }
        }
    }
}
