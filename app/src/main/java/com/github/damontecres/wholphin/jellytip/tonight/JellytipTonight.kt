package com.github.damontecres.wholphin.jellytip.tonight

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
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
    )

    /** "You're 23 min into this" (under two minutes in: "Started recently"). */
    fun into(posSec: Double): String = if (posSec < 120) "Started recently" else "You're ${maxOf(1, Math.round(posSec / 60).toInt())} min into this"

    /** Tier, then rank inside it, then input order. */
    fun ranked(cands: List<Cand>): List<Cand> = cands.withIndex().sortedWith(compareBy({ it.value.tier }, { it.value.rank }, { it.index })).map { it.value }

    /** The next three not shown yet ([shown] is updated). */
    fun nextThree(
        cands: List<Cand>,
        shown: MutableSet<String>,
    ): List<Cand> {
        val out = ranked(cands).filterNot { it.key in shown }.take(PICKS)
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
                    .getResumeItems(GetResumeItemsRequest(userId = userId, fields = SlimItemFields, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE), enableUserData = true, limit = 12))
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
                        )
                }
            }
            // 1b. In progress (IPTV), not hidden in Continue Watching
            val user = com.github.damontecres.wholphin.jellytip.JellytipApi.shared?.currentUserKey().orEmpty()
            JellytipIptvResumeStore.continueItems.value.forEachIndexed { i, r ->
                if (r.kind == "recording" || IptvContinueRules.stillHidden(r, IptvContinueRules.hiddenAt(context, user, r.key))) return@forEachIndexed
                val left = if (r.dur > 0) (r.dur - r.pos) / 60 else -1.0
                if (left >= Tonight.MIN_LEFT_MIN) {
                    out += Tonight.Cand("iptv:${r.key}", 0, r.seriesTitle ?: r.title, if (r.kind == "episode") r.label else null, Tonight.into(r.pos), "Resume", left.toInt(), 100 + i, iptv = r, iptvTile = r.asVodItem())
                }
            }
            // 2. Next episodes
            safe {
                api.tvShowsApi
                    .getNextUp(GetNextUpRequest(userId = userId, fields = SlimItemFields, enableUserData = true, limit = 8))
                    .toBaseItems()
            }.forEachIndexed { i, it ->
                val m = (it.data.runTimeTicks ?: 0L) / 600_000_000L
                out +=
                    Tonight.Cand(
                        key(it), 1, it.data.seriesName ?: it.name.orEmpty(),
                        listOfNotNull(it.data.parentIndexNumber?.let { s -> "S$s E${it.data.indexNumber ?: "?"}" }, it.name).joinToString(" · "),
                        "Next episode of ${it.data.seriesName ?: it.name}", "Play", m.toInt().takeIf { v -> v > 0 }, i, item = it,
                    )
            }
            // 3. Unwatched films from the For-you rows on Home
            val forYou = rows.peek("home")?.rows.orEmpty().filter { it.family == "perso" || it.family == "foryou" || it.title.startsWith("For you", true) }.take(2)
            forYou.forEach { row ->
                safe { rows.items(row).orEmpty() }.forEachIndexed { i, it ->
                    if (it.type == BaseItemKind.MOVIE && it.data.userData?.played != true && it.resumeMs <= 0) {
                        val m = (it.data.runTimeTicks ?: 0L) / 600_000_000L
                        out += Tonight.Cand(key(it), 2, it.name.orEmpty(), it.data.productionYear?.toString(), "In your \"${row.title}\" row", "Play", m.toInt().takeIf { v -> v > 0 }, i, item = it)
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
                            fields = SlimItemFields,
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
    val shown = remember { mutableSetOf<String>() }
    var picks by remember { mutableStateOf<List<Tonight.Cand>?>(null) }
    LaunchedEffect(all) {
        val a = all ?: return@LaunchedEffect
        if (picks == null) picks = Tonight.nextThree(a, shown)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(picks) {
        if (!picks.isNullOrEmpty()) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 32.dp)) {
        Text("Pick for tonight", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        val p = picks
        when {
            all == null -> Text("Finding three for you…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            p.isNullOrEmpty() ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (all.isNullOrEmpty()) "Nothing to pick from yet." else "That's everything for now.",
                        color = JellytipPalette.Vanilla.copy(alpha = 0.7f),
                        fontFamily = JellytipFontFamily,
                    )
                    if (!all.isNullOrEmpty()) {
                        Button(
                            onClick = {
                                shown.clear()
                                picks = Tonight.nextThree(all.orEmpty(), shown)
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
                Button(onClick = { picks = Tonight.nextThree(all.orEmpty(), shown) }) { Text("Show me 3 more") }
            }
        }
    }
}
