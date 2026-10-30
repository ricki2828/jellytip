package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.jellytipGlobalWrite
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsAnswer
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecial
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecialsPage
import com.github.damontecres.wholphin.ui.main.isContinueWatchingNextUp
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.util.UUID

/** One hero slide while focus sits on Continue Watching / Next Up: a featured film and where it came from ([kicker]). */
data class JellytipHeroSlide(
    val item: BaseItem,
    val kicker: String,
)

/**
 * The home hero's featured mix (Ricki 30-Oct: "i don't need trailers of continue watching"). While focus is on Continue
 * Watching / Next Up the hero rotates through new releases, just-added titles and the day's specials instead of the
 * focused tile. Built only from rows the page already loaded: no extra server call.
 */
object JellytipHeroMix {
    /** The slide the hero shows now, or null when the hero follows focus. Read by the header (kicker) and hero (mute). */
    var current by mutableStateOf<JellytipHeroSlide?>(null)
        private set

    fun show(slide: JellytipHeroSlide?) {
        if (current?.item?.id == slide?.item?.id && current?.kicker == slide?.kicker) return
        JellytipTrace.player("hero", "mix ${slide?.let { "${it.kicker}: ${it.item.name}" } ?: "off"}")
        jellytipGlobalWrite { current = slide }
    }

    /** Kicker for [item] when it is the featured slide on screen. */
    fun kickerFor(item: BaseItem?): String? = current?.takeIf { item != null && it.item.id == item.id }?.kicker

    /**
     * Interleave the sources one slide each (New release, Just added, Tonight's pick, ...), never anything in Continue
     * Watching / Next Up, each film once, watched films left out.
     */
    fun build(
        homeRows: List<HomeRowLoadingState>,
        specials: JellytipSpecialsPage?,
        serverRows: JellytipRowsAnswer?,
        serverItems: (com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow) -> List<BaseItem>?,
    ): List<JellytipHeroSlide> {
        val leading = homeRows.filter { it.isContinueWatchingNextUp }.flatMap { (it as HomeRowLoadingState.Success).items }.filterNotNull()
        val excluded = (leading.map { it.id } + leading.mapNotNull { it.data.seriesId }).toSet()
        val buckets = LinkedHashMap<String, MutableList<BaseItem>>()

        fun addSource(
            kicker: String,
            items: List<BaseItem?>?,
        ) {
            items?.filterNotNull()?.let { buckets.getOrPut(kicker) { mutableListOf() }.addAll(it.take(PER_SOURCE * 2)) }
        }
        homeRows.forEach { row ->
            val s = row as? HomeRowLoadingState.Success ?: return@forEach
            when (s.rowType) {
                is HomeRowConfig.RecentlyReleased -> addSource(KICKER_NEW, s.items)
                is HomeRowConfig.RecentlyAdded -> addSource(KICKER_ADDED, s.items)
                else -> {}
            }
        }
        specials?.let { page ->
            page.specials.forEach { special ->
                when (special) {
                    is JellytipSpecial.TonightsPick -> addSource("Tonight's pick", listOf(page.items[special.itemId]))
                    is JellytipSpecial.PersonSpotlight -> addSource("${special.name} spotlight", special.films.map { page.items[it] })
                    is JellytipSpecial.BecauseLoved -> addSource("Because you loved it", listOf(page.items[special.anchorItemId]))
                    is JellytipSpecial.Top10 -> addSource("Top 10 today", special.ranked.map { page.items[it] })
                    else -> {}
                }
            }
        }
        serverRows?.rows?.forEach { row ->
            val t = row.title.lowercase()
            val kicker =
                when {
                    "new release" in t -> KICKER_NEW
                    "just added" in t || row.family == "fresh" -> KICKER_ADDED
                    t.startsWith("trending") -> "Trending"
                    row.family == "ratings" || row.family == "awards" -> "Highly rated"
                    row.family == "director" -> row.title
                    else -> null
                } ?: return@forEach
            if (row.iptvItems.isNotEmpty()) return@forEach
            addSource(kicker, serverItems(row))
        }

        val queues =
            buckets
                .mapValues { (_, items) ->
                    items
                        .filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES }
                        .filter { it.id !in excluded && it.data.userData?.played != true }
                        .distinctBy { it.id }
                        .take(PER_SOURCE)
                        .toMutableList()
                }.filterValues { it.isNotEmpty() }
                .toList()
        if (queues.isEmpty()) return emptyList()
        // A different source leads each day
        val start = LocalDate.now().dayOfYear % queues.size
        val order = queues.drop(start) + queues.take(start)
        val seen = HashSet<UUID>()
        return buildList {
            while (size < MAX_SLIDES && order.any { it.second.isNotEmpty() }) {
                for ((kicker, queue) in order) {
                    while (queue.isNotEmpty()) {
                        val item = queue.removeAt(0)
                        if (seen.add(item.id)) {
                            add(JellytipHeroSlide(item, kicker))
                            break
                        }
                    }
                }
            }
        }.take(MAX_SLIDES)
    }

    const val KICKER_NEW = "New release"
    const val KICKER_ADDED = "Just added"
    private const val PER_SOURCE = 4
    private const val MAX_SLIDES = 14

    /** A slide stays at least this long; a trailer that started for it plays to the end first. */
    const val SLIDE_MS = 9_000L
}

/** The featured slides for this page, recomputed only when its rows/specials change (not on focus moves). */
@Composable
fun rememberJellytipHeroMix(
    homeRows: List<HomeRowLoadingState>,
    specials: JellytipSpecialsPage?,
    serverRows: JellytipRowsAnswer?,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
): List<JellytipHeroSlide> =
    remember(homeRows, specials, serverRows) {
        JellytipHeroMix.build(homeRows, specials, serverRows) { viewModel.rows.peekItems(it) }
    }

/**
 * The slide to show while [active] (focus on Continue Watching / Next Up), else null. Advances every [JellytipHeroMix.SLIDE_MS];
 * when the slide's muted trailer is playing it waits for the trailer to end. The position survives leaving Home.
 */
@Composable
fun rememberJellytipHeroSlide(
    slides: List<JellytipHeroSlide>,
    active: Boolean,
): JellytipHeroSlide? {
    var index by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(active, slides) {
        if (!active || slides.isEmpty()) return@LaunchedEffect
        while (true) {
            val slide = slides[index % slides.size]
            delay(JellytipHeroMix.SLIDE_MS)
            snapshotFlow { JellytipTrailerControl.isPlayingFor(slide.item) }.first { !it }
            index = (index + 1) % slides.size
        }
    }
    return if (active && slides.isNotEmpty()) slides[index % slides.size] else null
}
