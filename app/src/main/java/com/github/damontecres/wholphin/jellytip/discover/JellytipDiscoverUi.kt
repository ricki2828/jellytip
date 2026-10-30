package com.github.damontecres.wholphin.jellytip.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.jellytipRowBleed
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.ConfirmDialog
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private const val TILE_HEIGHT_DP = 132
private const val POLL_MS = 60_000L

@HiltViewModel
class JellytipDiscoverViewModel
    @Inject
    constructor(
        val service: JellytipDiscoverService,
    ) : ViewModel()

/** Appends the Discover rows for [library] ("TV Shows" / "Movies") to a LazyColumn. Nothing is added when there are none. */
fun LazyListScope.jellytipDiscoverRows(
    rows: List<DiscoverRow>,
    statuses: Map<String, RequestState>,
    onOpen: (DiscoverTitle) -> Unit,
) {
    items(rows, key = { "discover:${it.id}" }) { row ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            ItemRowTitle(row.title)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = com.github.damontecres.wholphin.jellytip.jellytipRowPadding(12.dp, 8.dp),
                modifier = Modifier.jellytipRowBleed().fillMaxWidth().focusGroup(),
            ) {
                items(row.items, key = { it.key }) { t -> DiscoverTile(t, statuses[t.key], onClick = { onOpen(t) }) }
            }
        }
    }
}

/**
 * Loads the Discover rows for [library] and keeps their request badges fresh (one poll a minute) while this composable is on screen.
 * Returns the rows plus the badge map; empty when the companion has no Discover rows.
 */
@Composable
fun rememberJellytipDiscover(
    library: String,
    viewModel: JellytipDiscoverViewModel = hiltViewModel(),
): Pair<List<DiscoverRow>, Map<String, RequestState>> {
    val service = viewModel.service
    val all by service.rows.collectAsStateWithLifecycle()
    val statuses by service.statuses.collectAsStateWithLifecycle()
    val rows = remember(all, library) { all.filter { it.library.equals(library, ignoreCase = true) } }
    LaunchedEffect(Unit) { service.loadRows() }
    LaunchedEffect(rows) {
        if (rows.isEmpty()) return@LaunchedEffect
        val titles = rows.flatMap { it.items }
        while (true) {
            service.refreshStatuses(titles)
            delay(POLL_MS)
        }
    }
    return rows to statuses
}

@Composable
private fun DiscoverTile(
    title: DiscoverTitle,
    state: RequestState?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(TILE_HEIGHT_DP.dp).width((TILE_HEIGHT_DP * 16 / 9).dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF141414),
                focusedContainerColor = Color(0xFF141414),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, JellytipPalette.Vanilla), shape = RoundedCornerShape(6.dp)),
            ),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = title.backdropUrl ?: title.posterUrl,
                contentDescription = title.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
            )
            Text(
                text = title.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = JellytipPalette.Vanilla,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Tag("DISCOVER", Modifier.align(Alignment.TopStart).padding(6.dp))
            state?.label?.let { Tag(it, Modifier.align(Alignment.TopEnd).padding(6.dp), highlight = true) }
        }
    }
}

@Composable
private fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = JellytipPalette.Vanilla,
        modifier =
            modifier
                .background(
                    if (highlight) JellytipPalette.Jelly else Color.Black.copy(alpha = 0.6f),
                    RoundedCornerShape(3.dp),
                ).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun gb(v: Double?): String = v?.let { String.format(Locale.US, "%.0f GB", it) } ?: "unknown"

/** Lightweight details screen for a title that is not in the library: backdrop, facts, overview and "Get it". */
@Composable
fun JellytipDiscoverDetails(
    title: DiscoverTitle,
    modifier: Modifier = Modifier,
    viewModel: JellytipDiscoverViewModel = hiltViewModel(),
) {
    val service = viewModel.service
    val statuses by service.statuses.collectAsStateWithLifecycle()
    val state = statuses[title.key]
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<RequestOutcome?>(null) }
    // Series: which seasons the confirmed request is for (first | latest | all)
    var confirmSeasons by remember { mutableStateOf<String?>(null) }
    // Series: the season choice that has focus; its estimate / refusal shows under the buttons
    var focusedChoice by remember { mutableStateOf(SEASON_CHOICES.first().first) }
    // Bumped after a request so the estimates are asked again ("have it" for what was just requested)
    var requests by remember { mutableStateOf(0) }
    // Series: the server's dry-run answer for each choice, asked when the page opens (dry runs add nothing)
    val estimates by produceState<Map<String, RequestOutcome>>(emptyMap(), title.key, requests) {
        if (title.isTv) {
            // Start from the last answers so a re-ask never blanks a choice while it runs
            val out = value.toMutableMap()
            for (choice in SEASON_CHOICES.map { it.first }) {
                out[choice] = service.preview(title, choice)
                value = out.toMap()
            }
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(title.key) {
        focus.tryRequestFocus()
        while (true) {
            service.refreshStatuses(listOf(title))
            delay(POLL_MS)
        }
    }

    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        AsyncImage(
            model = title.backdropUrl ?: title.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.7f to Color.Transparent)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 48.dp).width(520.dp),
        ) {
            Tag("DISCOVER")
            Text(
                text = title.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Medium,
                color = JellytipPalette.Vanilla,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta =
                listOfNotNull(
                    title.year?.toString(),
                    if (title.isTv) "TV series" else "Film",
                    title.rating?.let { String.format(Locale.US, "TMDB %.1f", it) },
                ).joinToString("  ·  ")
            Text(text = meta, fontSize = 14.sp, color = JellytipPalette.Vanilla.copy(alpha = 0.8f))
            if (title.overview.isNotBlank()) {
                Text(
                    text = title.overview,
                    fontSize = 14.sp,
                    color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val label = state?.label
            if (title.isTv) {
                // Ricki: "In get, we need options - first season or latest season or all seasons". The buttons stay
                // after a request (asking for more seasons extends it), so focus never loses its button.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SEASON_CHOICES.forEachIndexed { i, (choice, name) ->
                        val e = estimates[choice]
                        Button(
                            onClick = {
                                if (busy) return@Button
                                busy = true
                                message = null
                                scope.launch {
                                    val p = e ?: service.preview(title, choice)
                                    busy = false
                                    if (p.status == "added") {
                                        confirmSeasons = choice
                                        confirm = p
                                    } else {
                                        message = blockedText(p)
                                    }
                                }
                            },
                            modifier =
                                (if (i == 0) Modifier.focusRequester(focus) else Modifier)
                                    .onFocusChanged { if (it.isFocused) focusedChoice = choice },
                        ) {
                            Text(text = if (e?.status == "exists") "$name ✓" else name)
                        }
                    }
                }
                Text(
                    text = seasonLine(estimates[focusedChoice]),
                    fontSize = 14.sp,
                    color = JellytipPalette.Vanilla.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                (message ?: label)?.let { Text(text = it, fontSize = 14.sp, color = JellytipPalette.Vanilla) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (busy || label != null) return@Button
                        busy = true
                        message = null
                        scope.launch {
                            val p = service.preview(title)
                            busy = false
                            if (p.status == "added") confirm = p else message = blockedText(p)
                        }
                    },
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text(text = label ?: if (busy) "Checking…" else "Get it")
                }
                message?.let { Text(text = it, fontSize = 14.sp, color = JellytipPalette.Vanilla) }
            }
        }
    }

    confirm?.let { p ->
        val what = p.label ?: title.title
        ConfirmDialog(
            title = "Download $what?",
            body = "About ${gb(p.estimateGb)}; ${gb(p.freeGbNow)} free now" + (p.freeGbAfter?.let { ", ${gb(it)} after." } ?: "."),
            onCancel = { confirm = null },
            onConfirm = {
                confirm = null
                busy = true
                val seasons = confirmSeasons
                scope.launch {
                    val r = service.request(title, seasons)
                    busy = false
                    requests++
                    message =
                        when (r.status) {
                            "added" -> "Requested. It will appear here once downloaded."
                            "exists" -> "Already requested."
                            else -> blockedText(r)
                        }
                }
            },
        )
    }
}

private val SEASON_CHOICES = listOf("first" to "First season", "latest" to "Latest season", "all" to "All seasons")

/** The server's dry run for one season choice: what it covers and its size, or why it would be refused. */
private fun seasonLine(e: RequestOutcome?): String =
    when {
        e == null -> "Checking size…"
        e.status == "exists" -> (e.label?.let { "$it: " } ?: "") + "already requested or in the library."
        e.status == "added" ->
            listOfNotNull(
                e.label,
                e.episodes?.let { if (it == 1) "1 episode" else "$it episodes" },
                "≈${gb(e.estimateGb)}",
                e.freeGbAfter?.let { "${gb(it)} free after" },
            ).joinToString("  ·  ")
        else -> blockedText(e)
    }

private fun blockedText(r: RequestOutcome): String =
    when (r.status) {
        "exists" -> "Already requested or in the library."
        "blocked" -> "Not requested: ${r.reason}."
        else -> "Request failed: ${r.reason}."
    }
