package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.jellytip.iptv.FxItem
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsAnswer
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecial
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecialsPage
import com.github.damontecres.wholphin.ui.main.isContinueWatchingNextUp
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind

/*
 * The top of Home is a rotating hero carousel again (Ricki 22-Nov: "it should just rotate and be part of the group ... use
 * arrows to rotate through the hero carousel"; replaces the 1-Nov focus-only hero for the top of Home). While focus is on
 * the top row or the hero's buttons the hero shows the current slide: his top live game first, one at most (his starred
 * teams / followed leagues only, LiveHero.homePicks), then a featured mix of what Home already loaded (new releases, just added, the day's
 * specials; never Continue Watching / Next Up). A slide lasts [JellytipHeroCarousel.SLIDE_MS]; Left on the first hero
 * button / Right on the last steps. Lower rows: the hero follows the focused tile, as since 1-Nov.
 */

/** One hero slide: a film / show from the mix ([kicker] = where it came from) or a live game. */
sealed interface HeroSlide {
    /** Stable across rebuilds: the slide on screen stays put when a game starts / ends or the rows refresh. */
    val key: String

    data class Title(
        val item: BaseItem,
        val kicker: String,
    ) : HeroSlide {
        override val key get() = "t:${item.id}"
    }

    data class Match(
        val f: FxItem,
    ) : HeroSlide {
        override val key get() = "m:${f.fid}"
    }

    /** Journeys v1: the active journey's tonight step (one slide at most). */
    data class Journey(
        val s: com.github.damontecres.wholphin.jellytip.journeys.JvSummary,
    ) : HeroSlide {
        override val key get() = "j:${s.id}"
    }
}

object JellytipHeroCarousel {
    /** A match slide covers the hero: the film hero skips its still / Ken Burns underneath (set by Home). */
    var matchCovers by mutableStateOf(false)

    /** The carousel turns by itself: no hero autoplay (each preview would be cut at the next slide). Set by Home. */
    var holdAutoplay by mutableStateOf(false)

    /** Same pace as the web hero (heroMix SLIDE_MS). */
    const val SLIDE_MS = 8_000L

    /** Buttons focused this long = he is reading / choosing: the carousel holds still until focus leaves them. */
    const val BUTTON_HOLD_MS = 1_500L

    const val KICKER_NEW = "New release"
    const val KICKER_ADDED = "Just added"
    const val PER_SOURCE = 4
    const val MAX_SLIDES = 14

    /**
     * At most ONE live game (his top pick: already ranked and filtered to his teams / leagues) as slide 1, then the mix
     * (Ricki 22-Nov: a mix of all sorts, not a games carousel).
     */
    fun slides(
        matches: List<FxItem>,
        mix: List<HeroSlide.Title>,
        /** Journeys v1: the active journey, right after the game (before the mix). */
        journey: com.github.damontecres.wholphin.jellytip.journeys.JvSummary? = null,
    ): List<HeroSlide> = listOfNotNull(matches.firstOrNull()?.let { HeroSlide.Match(it) }, journey?.let { HeroSlide.Journey(it) }) + mix

    /** The next / previous slide, wrapping. */
    fun step(
        index: Int,
        size: Int,
        dir: Int,
    ): Int = if (size <= 0) 0 else ((index + dir) % size + size) % size

    /**
     * Left on the first hero button: the previous slide, except on the first slide, where Left keeps doing what it did
     * before (not consumed). Right on the last button always steps (wraps to the first slide).
     */
    fun arrow(
        index: Int,
        size: Int,
        right: Boolean,
    ): Int? =
        when {
            size < 2 -> null
            right -> step(index, size, 1)
            index > 0 -> index - 1
            else -> null
        }

    /** After a rebuild: the slide that was on screen (by key) keeps its place; gone = the same position, clamped. */
    fun keep(
        slides: List<HeroSlide>,
        key: String?,
        index: Int,
    ): Int {
        if (slides.isEmpty()) return 0
        val at = key?.let { k -> slides.indexOfFirst { it.key == k } } ?: -1
        return if (at >= 0) at else index.coerceIn(0, slides.size - 1)
    }

    /** Whether the timer waits: buttons held, a trailer playing with sound, Home hidden, or focus off the page. */
    fun paused(
        buttonsHeld: Boolean,
        trailerWithSound: Boolean,
        resumed: Boolean,
        pageFocused: Boolean,
    ): Boolean = buttonsHeld || trailerWithSound || !resumed || !pageFocused

    /**
     * One from each source in turn (a different source leads each day, [start]), at most [PER_SOURCE] per source and
     * [MAX_SLIDES] in all, each id once, only what [ok] keeps.
     */
    fun <T> interleave(
        sources: List<Pair<String, List<T>>>,
        id: (T) -> Any,
        ok: (T) -> Boolean,
        start: Int,
    ): List<Pair<String, T>> {
        val queues =
            sources
                .map { (kicker, items) -> kicker to items.filter(ok).distinctBy(id).take(PER_SOURCE).toMutableList() }
                .filter { it.second.isNotEmpty() }
        if (queues.isEmpty()) return emptyList()
        val s = ((start % queues.size) + queues.size) % queues.size
        val order = queues.drop(s) + queues.take(s)
        val seen = HashSet<Any>()
        val out = ArrayList<Pair<String, T>>()
        while (out.size < MAX_SLIDES && order.any { it.second.isNotEmpty() }) {
            for ((kicker, queue) in order) {
                if (out.size >= MAX_SLIDES) break
                while (queue.isNotEmpty()) {
                    val item = queue.removeAt(0)
                    if (seen.add(id(item))) {
                        out.add(kicker to item)
                        break
                    }
                }
            }
        }
        return out
    }

    /** Kicker for a server row that feeds the mix; null = not featured (web heroMix rowKicker). */
    fun rowKicker(
        family: String,
        title: String,
    ): String? {
        val t = title.lowercase()
        return when {
            "new release" in t -> KICKER_NEW
            "just added" in t || family == "fresh" -> KICKER_ADDED
            t.startsWith("trending") -> "Trending"
            family == "ratings" || family == "awards" -> "Highly rated"
            else -> null
        }
    }

    /**
     * The featured mix from what Home already loaded (no extra request): films and shows only, never anything in
     * Continue Watching / Next Up (or their series), nothing watched.
     */
    fun mix(
        homeRows: List<HomeRowLoadingState>,
        specials: JellytipSpecialsPage?,
        serverRows: JellytipRowsAnswer?,
        serverItems: (JellytipServerRow) -> List<BaseItem>?,
        dayOfYear: Int,
    ): List<HeroSlide.Title> {
        val leading = homeRows.filter { it.isContinueWatchingNextUp }.flatMap { (it as HomeRowLoadingState.Success).items }.filterNotNull()
        val excluded = (leading.map { it.id } + leading.mapNotNull { it.data.seriesId }).toSet()
        val sources = LinkedHashMap<String, MutableList<BaseItem>>()

        fun add(
            kicker: String,
            items: List<BaseItem?>?,
        ) {
            items?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { sources.getOrPut(kicker) { mutableListOf() }.addAll(it.take(PER_SOURCE * 2)) }
        }
        homeRows.forEach { row ->
            val s = row as? HomeRowLoadingState.Success ?: return@forEach
            when (s.rowType) {
                is HomeRowConfig.RecentlyReleased -> add(KICKER_NEW, s.items)
                is HomeRowConfig.RecentlyAdded -> add(KICKER_ADDED, s.items)
                else -> {}
            }
        }
        serverRows?.rows?.forEach { row ->
            if (row.iptvItems.isNotEmpty()) return@forEach
            val kicker = rowKicker(row.family, row.title) ?: return@forEach
            add(kicker, serverItems(row))
        }
        specials?.let { page ->
            page.specials.forEach { special ->
                when (special) {
                    is JellytipSpecial.TonightsPick -> add("Tonight's pick", listOf(special.itemId?.let { page.items[it] }))
                    is JellytipSpecial.PersonSpotlight -> add("${special.name} spotlight", special.films.map { page.items[it] })
                    is JellytipSpecial.BecauseLoved -> add("Because you loved it", listOf(page.items[special.anchorItemId]))
                    is JellytipSpecial.Top10 -> add("Top 10 today", special.ranked.map { page.items[it] })
                    else -> {}
                }
            }
        }
        return interleave(
            sources.map { (k, v) -> k to v.toList() },
            id = { it.id },
            ok = { (it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES) && it.id !in excluded && it.data.userData?.played != true },
            start = dayOfYear,
        ).map { (kicker, item) -> HeroSlide.Title(item, kicker) }
    }
}

/** The featured mix for Home, recomputed only when its rows / specials change (not on focus moves). */
@Composable
fun rememberJellytipHeroMix(
    homeRows: List<HomeRowLoadingState>,
    specials: JellytipSpecialsPage?,
    serverRows: JellytipRowsAnswer?,
    viewModel: JellytipHomeViewModel = hiltViewModel(),
): List<HeroSlide.Title> {
    // Server-row items load after the rows answer and aren't observable: build now, then again as they land
    val mix by produceState(emptyList<HeroSlide.Title>(), homeRows, specials, serverRows) {
        for (wait in listOf(0L, 3_000L, 15_000L)) {
            delay(wait)
            val next =
                withContext(Dispatchers.Default) {
                    JellytipHeroCarousel.mix(homeRows, specials, serverRows, { viewModel.rows.peekItems(it) }, java.time.LocalDate.now().dayOfYear)
                }
            // Whole items, not just keys: a title marked watched or resumed carries its new state (Play / Resume)
            if (next != value) value = next
        }
    }
    return mix
}
