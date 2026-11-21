package com.github.damontecres.wholphin.jellytip.tonight

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.ask.AskChip
import com.github.damontecres.wholphin.jellytip.ask.AskItem
import com.github.damontecres.wholphin.jellytip.ask.AskParse
import com.github.damontecres.wholphin.jellytip.ask.ChipData
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.jellytip.iptv.IptvContinueRules
import com.github.damontecres.wholphin.jellytip.iptv.IptvResume
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodTile
import com.github.damontecres.wholphin.jellytip.iptv.JellytipIptvResumeStore
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.main.HomePageCardContent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import javax.inject.Inject

/*
 * Pick for tonight (web 7056c179 / 466f81da, lib/tonight.ts): three things to watch, each with one factual reason, from
 * what is already there: films / episodes in progress with 10+ min left, next episodes, unwatched films from the For-you
 * rows, unwatched highly rated films, IPTV films only when the rest is thin. "Show me 3 more" never repeats. Constraint
 * chips come later (with the Ask parser).
 */
object Tonight {
    const val PICKS = 3
    const val MIN_LEFT_MIN = 10
    const val HIGH_RATING = 7.5
    const val THIN = PICKS * 2

    /** resume 0, nextup 1, recs 2, rated 3, iptv 4 */
    data class Cand(
        val key: String,
        val tier: Int,
        val title: String,
        val sub: String?,
        val reason: String,
        val playLabel: String,
        val minutes: Int?,
        val rank: Int,
        val item: BaseItem? = null,
        val iptv: IptvResume? = null,
        val iptvTile: com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem? = null,
        /** For the constraint chips: genres and the sitting's length (time left for a resume). */
        val probe: AskItem = AskItem(null, null, null, null),
        val rating: String? = null,
        /** Already begun (a resume, the next episode of a show in progress). */
        val started: Boolean = false,
    )

    // ---- constraint chips (web 7056c179 466f81da): the Ask parser's chips for fixed phrases ----

    data class Constraint(
        val id: String,
        val label: String,
        val ask: String,
    )

    val CONSTRAINTS =
        listOf(
            Constraint("short", "Under 2 hours", "under 2 hours"),
            Constraint("light", "Something light", "feel-good"),
            Constraint("new", "Something new to me", "I haven't seen"),
            Constraint("kids", "Kids around", "for kids"),
        )

    fun chipsFor(ids: Set<String>): List<AskChip> = CONSTRAINTS.filter { it.id in ids }.flatMap { AskParse.parse(it.ask).chips }

    /** Picks that fit every chosen constraint. Unknown length / genre / rating never passes one that needs it. */
    fun applyConstraints(
        cands: List<Cand>,
        ids: Set<String>,
    ): List<Cand> {
        if (ids.isEmpty()) return cands
        val chips = chipsFor(ids)
        val kids = chips.firstNotNullOfOrNull { it.data as? ChipData.Kids }
        val wantNew = chips.any { it.data is ChipData.Unplayed }
        val fits = AskParse.filterItems(cands, chips.filter { it.data is ChipData.Genre || it.data is ChipData.MaxRuntime }) { it.probe }.map { it.key }.toSet()
        return cands.filter { c ->
            if (wantNew && c.started) return@filter false
            if (c.key !in fits) return@filter false
            if (kids != null) {
                val r = c.rating ?: return@filter false
                if (r !in kids.ratings) return@filter false
                // Always a Family / Animation / Kids genre as well: a PG comedy alone is not kids-safe
                val have = c.probe.genres.orEmpty().map { it.lowercase() }.toSet()
                if (kids.genres.none { it.lowercase() in have }) return@filter false
            }
            true
        }
    }

    /** What was shown, per set of constraints (a different set starts fresh). */
    fun shownFor(
        store: MutableMap<String, MutableSet<String>>,
        ids: Set<String>,
    ): MutableSet<String> = store.getOrPut(ids.sorted().joinToString("+")) { mutableSetOf() }

    /** "You're 23 min into this" (under two minutes in: "Started recently"). */
    fun into(posSec: Double): String = if (posSec < 120) "Started recently" else "You're ${maxOf(1, Math.round(posSec / 60).toInt())} min into this"

    /** Tier, then rank inside it, then input order. */
    fun ranked(cands: List<Cand>): List<Cand> = cands.withIndex().sortedWith(compareBy({ it.value.tier }, { it.value.rank }, { it.index })).map { it.value }

    /** The next three not shown yet ([shown] is updated). */
    fun nextThree(
        cands: List<Cand>,
        shown: MutableSet<String>,
        ids: Set<String> = emptySet(),
    ): List<Cand> {
        val out = ranked(applyConstraints(cands, ids)).filterNot { it.key in shown }.take(PICKS)
        out.forEach { shown += it.key }
        return out
    }

    /** Each title once, in the order given (sources already in priority order). */
    fun dedupe(cands: List<Cand>): List<Cand> {
        val seen = HashSet<String>()
        return cands.filter { seen.add(it.key) }
    }
}

@HiltViewModel
class JellytipTonightViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val rows: JellytipRowsService,
        val navigationManager: NavigationManager,
        @param:ApplicationContext private val context: android.content.Context,
    ) : ViewModel() {
        private val _cands = MutableStateFlow<List<Tonight.Cand>?>(null)
        val cands: StateFlow<List<Tonight.Cand>?> = _cands

        init {
            viewModelScope.launch { _cands.value = load() }
        }

        private fun key(item: BaseItem) = item.id.toString().replace("-", "")

        /** Genres and length (minutes, e.g. time left) for the chips. */
        private fun probe(
            item: BaseItem,
            minutes: Double?,
        ) = AskItem(
            if (item.type == BaseItemKind.SERIES) "Series" else "Movie",
            minutes?.takeIf { it > 0 }?.let { (it * 600_000_000L).toLong() } ?: item.data.runTimeTicks,
            null,
            item.data.genres,
        )

        private val fields = SlimItemFields + ItemFields.GENRES

        private suspend fun load(): List<Tonight.Cand> {
            val userId = serverRepository.currentUser?.id ?: return emptyList()
            val out = ArrayList<Tonight.Cand>()
            suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Tonight source failed")
                    emptyList()
                }
            // 1. In progress (library), 10+ min left
            safe {
                api.itemsApi
                    .getResumeItems(GetResumeItemsRequest(userId = userId, fields = fields, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE), enableUserData = true, limit = 12))
                    .toBaseItems()
            }.forEachIndexed { i, it ->
                val total = (it.data.runTimeTicks ?: 0L) / 10_000_000.0
                val pos = it.resumeMs / 1000.0
                val left = if (total > 0) (total - pos) / 60 else -1.0
                if (left >= Tonight.MIN_LEFT_MIN) {
                    val ep = it.type == BaseItemKind.EPISODE
                    out +=
                        Tonight.Cand(
                            key(it), 0, if (ep) it.data.seriesName ?: it.name.orEmpty() else it.name.orEmpty(),
                            if (ep) listOfNotNull(it.data.parentIndexNumber?.let { s -> "S$s E${it.data.indexNumber ?: "?"}" }, it.name).joinToString(" · ") else null,
                            Tonight.into(pos), "Resume", left.toInt(), i, item = it,
                            probe = probe(it, left), rating = it.data.officialRating, started = true,
                        )
                }
            }
            // 1b. In progress (IPTV), not hidden in Continue Watching
            val user = com.github.damontecres.wholphin.jellytip.JellytipApi.shared?.currentUserKey().orEmpty()
            JellytipIptvResumeStore.continueItems.value.forEachIndexed { i, r ->
                if (r.kind == "recording" || IptvContinueRules.stillHidden(r, IptvContinueRules.hiddenAt(context, user, r.key))) return@forEachIndexed
                val left = if (r.dur > 0) (r.dur - r.pos) / 60 else -1.0
                if (left >= Tonight.MIN_LEFT_MIN) {
                    out +=
                        Tonight.Cand(
                            "iptv:${r.key}", 0, r.seriesTitle ?: r.title, if (r.kind == "episode") r.label else null, Tonight.into(r.pos), "Resume", left.toInt(), 100 + i,
                            iptv = r, iptvTile = r.asVodItem(), probe = AskItem("Movie", (left * 600_000_000L).toLong(), null, null), started = true,
                        )
                }
            }
            // 2. Next episodes
            safe {
                api.tvShowsApi
                    .getNextUp(GetNextUpRequest(userId = userId, fields = fields, enableUserData = true, limit = 8))
                    .toBaseItems()
            }.forEachIndexed { i, it ->
                val m = (it.data.runTimeTicks ?: 0L) / 600_000_000L
                out +=
                    Tonight.Cand(
                        key(it), 1, it.data.seriesName ?: it.name.orEmpty(),
                        listOfNotNull(it.data.parentIndexNumber?.let { s -> "S$s E${it.data.indexNumber ?: "?"}" }, it.name).joinToString(" · "),
                        "Next episode of ${it.data.seriesName ?: it.name}", "Play", m.toInt().takeIf { v -> v > 0 }, i, item = it,
                        probe = probe(it, null), rating = it.data.officialRating, started = true,
                    )
            }
            // 3. Unwatched films from the For-you rows on Home
            val forYou = rows.peek("home")?.rows.orEmpty().filter { it.family == "perso" || it.family == "foryou" || it.title.startsWith("For you", true) }.take(2)
            forYou.forEach { row ->
                safe { rows.items(row).orEmpty() }.forEachIndexed { i, it ->
                    if (it.type == BaseItemKind.MOVIE && it.data.userData?.played != true && it.resumeMs <= 0) {
                        val m = (it.data.runTimeTicks ?: 0L) / 600_000_000L
                        out +=
                            Tonight.Cand(
                                key(it), 2, it.name.orEmpty(), it.data.productionYear?.toString(), "In your \"${row.title}\" row", "Play", m.toInt().takeIf { v -> v > 0 }, i,
                                item = it, probe = probe(it, null), rating = it.data.officialRating,
                            )
                    }
                }
            }
            // 4. Unwatched highly rated films
            safe {
                api.itemsApi
                    .getItems(
                        GetItemsRequest(
                            userId = userId,
                            includeItemTypes = listOf(BaseItemKind.MOVIE),
                            recursive = true,
                            isPlayed = false,
                            minCommunityRating = Tonight.HIGH_RATING,
                            sortBy = listOf(ItemSortBy.RANDOM),
                            limit = 30,
                            fields = fields,
                            enableUserData = true,
                        ),
                    ).toBaseItems()
            }.sortedByDescending { it.data.communityRating ?: 0f }.forEachIndexed { i, it ->
                if (it.resumeMs > 0) return@forEachIndexed
                val m = (it.data.runTimeTicks ?: 0L) / 600_000_000L
                out +=
                    Tonight.Cand(
                        key(it), 3, it.name.orEmpty(), it.data.productionYear?.toString(),
                        "Rated ${String.format(java.util.Locale.US, "%.1f", it.data.communityRating ?: 0f)}, unwatched", "Play", m.toInt().takeIf { v -> v > 0 }, i, item = it,
                        probe = probe(it, null), rating = it.data.officialRating,
                    )
            }
            return Tonight.dedupe(out)
        }

        fun play(c: Tonight.Cand) {
            JellytipTrailerControl.stop()
            JellytipTrace.log("tonight", "play ${c.key} tier=${c.tier}")
            val item = c.item
            val r = c.iptv
            when {
                item != null -> navigationManager.navigateTo(Destination.Playback(item), "tonight pick")
                r != null -> navigationManager.navigateTo(r.destination(), "tonight pick IPTV")
            }
        }
    }

@Composable
fun JellytipTonightPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipTonightViewModel = hiltViewModel(),
) {
    val all by viewModel.cands.collectAsState()
    val store = remember { mutableMapOf<String, MutableSet<String>>() }
    var ids by remember { mutableStateOf(emptySet<String>()) }
    var picks by remember { mutableStateOf<List<Tonight.Cand>?>(null) }
    LaunchedEffect(all) {
        val a = all ?: return@LaunchedEffect
        if (picks == null) picks = Tonight.nextThree(a, Tonight.shownFor(store, ids), ids)
    }
    val first = remember { FocusRequester() }
    // A chip toggle keeps focus on the chips; new picks from the buttons (or the first load) take it
    var chipsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(picks) {
        if (!chipsFocused) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 32.dp)) {
        Text("Pick for tonight", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        // Constraint chips: OK toggles one; the picks start fresh for the new set (Up from the picks reaches them)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.onFocusChanged { chipsFocused = it.hasFocus }.focusGroup()) {
            Tonight.CONSTRAINTS.forEach { c ->
                val on = c.id in ids
                Surface(
                    onClick = {
                        ids = if (on) ids - c.id else ids + c.id
                        all?.let { a -> picks = Tonight.nextThree(a, Tonight.shownFor(store, ids), ids) }
                    },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = if (on) JellytipPalette.Jelly.copy(alpha = 0.35f) else JellytipPalette.Vanilla.copy(alpha = 0.12f),
                            focusedContainerColor = Color.White,
                            contentColor = JellytipPalette.Vanilla,
                            focusedContentColor = Color.Black,
                        ),
                ) {
                    Text((if (on) "✓ " else "") + c.label, fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                }
            }
        }
        val p = picks
        when {
            all == null -> Text("Finding three for you…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            p.isNullOrEmpty() ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            all.isNullOrEmpty() -> "Nothing to pick from yet."
                            ids.isNotEmpty() && Tonight.applyConstraints(all.orEmpty(), ids).isEmpty() -> "Nothing fits all of those. Try fewer."
                            else -> "That's everything for now."
                        },
                        color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                        fontFamily = JellytipFontFamily,
                    )
                    if (!all.isNullOrEmpty() && Tonight.applyConstraints(all.orEmpty(), ids).isNotEmpty()) {
                        Button(
                            onClick = {
                                Tonight.shownFor(store, ids).clear()
                                picks = Tonight.nextThree(all.orEmpty(), Tonight.shownFor(store, ids), ids)
                            },
                            modifier = Modifier.focusRequester(first),
                        ) { Text("Start over") }
                    }
                }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.focusGroup()) {
                    p.forEachIndexed { i, c ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(360.dp)) {
                            val mod = if (i == 0) Modifier.focusRequester(first) else Modifier
                            val item = c.item
                            val tile = c.iptvTile
                            if (item != null) {
                                HomePageCardContent(index = i, item = item, onClick = { viewModel.play(c) }, onLongClick = { viewModel.play(c) }, viewOptions = HomeRowViewOptions(), modifier = mod)
                            } else if (tile != null) {
                                IptvVodTile(item = tile, width = 360.dp, height = 202.dp, poster = false, onClick = { viewModel.play(c) }, modifier = mod, onLongClick = { viewModel.play(c) })
                            }
                            Text(c.title, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            listOfNotNull(c.sub, c.minutes?.let { m -> if (c.tier == 0) "$m min left" else "$m min" }).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                                Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(c.reason, color = JellytipPalette.Vanilla.copy(alpha = 0.85f), fontFamily = JellytipFontFamily, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Button(onClick = { picks = Tonight.nextThree(all.orEmpty(), Tonight.shownFor(store, ids), ids) }) { Text("Show me 3 more") }
            }
        }
    }
}
