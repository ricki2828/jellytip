package com.github.damontecres.wholphin.jellytip.cache

import android.content.Context
import androidx.compose.ui.layout.ContentScale
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
import com.github.damontecres.wholphin.data.model.BaseItem
import androidx.compose.ui.unit.Dp
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.cards.selectJellytipCardArt
import com.github.damontecres.wholphin.jellytip.home.jellytipRowOptions
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecial
import com.github.damontecres.wholphin.jellytip.specials.JellytipSpecialsPage
import com.github.damontecres.wholphin.jellytip.specials.SpecialTokens
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.playback.scale
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Warms Coil's disk + memory cache with the images the home page is about to show.
 *
 * Requests use the exact URL and pixel size the cards compute (see HomePageCardContent / BannerCard),
 * so the card's own request is a memory hit, or at worst a disk hit with no network round trip.
 * Everything is bounded: [PARALLELISM] decodes at once, a fixed number of cards per row, and
 * row-ahead work is cancelled when focus moves on.
 */
@Singleton
class HomeImagePrefetcher
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val imageUrlService: ImageUrlService,
        private val usage: RowUsageStore,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val permits = Semaphore(PARALLELISM)
        private var aheadJob: Job? = null
        private var neighbourJob: Job? = null
        private var aheadRow = -1

        private val density get() = context.resources.displayMetrics.density

        /** Card image requests for the first [count] items of [row], as the Jellytip home lays them out. */
        fun thumbsFor(
            row: HomeRowLoadingState.Success,
            count: Int = CARDS_PER_ROW,
        ): List<ThumbSpec> {
            val options = jellytipRowOptions(row.viewOptions)
            val heightPx = (options.heightDp * density).roundToInt()
            return row.items.take(count).mapNotNull { item ->
                if (item == null || item.type == BaseItemKind.GENRE || item.type == BaseItemKind.STUDIO) return@mapNotNull null
                val episode = item.type == BaseItemKind.EPISODE
                val imageType =
                    when {
                        episode -> options.episodeImageType.imageType
                        options.imageType == ViewOptionImageType.THUMB -> ImageType.BACKDROP
                        else -> options.imageType.imageType
                    }
                val ratio = if (episode) options.episodeAspectRatio.ratio else options.aspectRatio.ratio
                val scale = if (episode) options.episodeContentScale.scale else options.contentScale.scale
                val url =
                    item.imageUrlOverride
                        ?: imageUrlService.getItemImageUrl(
                            item,
                            imageType,
                            fillWidth = null,
                            fillHeight = heightPx,
                            useSeriesForPrimary = options.useSeries,
                        ) ?: return@mapNotNull null
                ThumbSpec(
                    url = url,
                    width = (heightPx * ratio).roundToInt(),
                    height = heightPx,
                    fill = scale != ContentScale.Fit && scale != ContentScale.Inside,
                )
            }
        }

        fun heroUrl(item: BaseItem): String? = jellytipBackdropUrl(imageUrlService, item)

        /** Home data is fresh: warm the most-used (else first) rows, plus the hero for the first card. */
        fun onHomeLoaded(
            keys: List<String?>,
            rows: List<HomeRowLoadingState>,
        ) {
            val order = usage.priorityRows(keys)
            val firstItem =
                rows.firstNotNullOfOrNull { (it as? HomeRowLoadingState.Success)?.items?.firstOrNull() }
            scope.launch {
                warm(
                    order.mapNotNull { (rows.getOrNull(it) as? HomeRowLoadingState.Success)?.let(::thumbsFor) },
                    firstItem?.let(::heroUrl),
                )
            }
        }

        /** Cold start: the saved snapshot already holds the specs, so no server data is needed. */
        fun warmFromSnapshot(snapshot: HomeSnapshot) {
            val order = usage.priorityRows(snapshot.rows.map { it.key })
            scope.launch { warm(order.mapNotNull { snapshot.rows.getOrNull(it)?.thumbs }, snapshot.heroUrl) }
        }

        private suspend fun warm(
            rows: List<List<ThumbSpec>>,
            heroUrl: String?,
        ) {
            try {
                JellytipImageGate.await()
                coroutineScope {
                    heroUrl?.let { launch { loadHero(it, toMemory = true) } }
                    rows.forEach { specs -> specs.forEach { launch { loadThumb(it) } } }
                }
            } catch (ex: kotlin.coroutines.cancellation.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Home image warm-up failed")
            }
        }

        /**
         * Focus moved to ([row], [column]). Entering a new row prefetches the next two rows;
         * the focused card's neighbours get their hero backdrops (disk only, to spare memory).
         * Both are debounced so holding a D-pad key does not queue work for rows it flies past.
         */
        fun onFocus(
            rows: List<HomeRowLoadingState>,
            row: Int,
            column: Int,
        ) {
            if (row != aheadRow) {
                aheadRow = row
                aheadJob?.cancel()
                aheadJob =
                    scope.launch {
                        delay(FOCUS_DEBOUNCE_MS)
                        JellytipImageGate.await()
                        coroutineScope {
                            for (r in row + 1..row + 2) {
                                val next = rows.getOrNull(r) as? HomeRowLoadingState.Success ?: continue
                                thumbsFor(next).forEach { launch { loadThumb(it) } }
                            }
                        }
                    }
            }
            neighbourJob?.cancel()
            neighbourJob =
                scope.launch {
                    delay(FOCUS_DEBOUNCE_MS)
                    JellytipImageGate.await()
                    val items = (rows.getOrNull(row) as? HomeRowLoadingState.Success)?.items ?: return@launch
                    coroutineScope {
                        listOf(column + 1, column - 1, column + 2)
                            .mapNotNull { items.getOrNull(it) }
                            .mapNotNull(::heroUrl)
                            .forEach { launch { loadHero(it, toMemory = false) } }
                    }
                }
        }

        /** Warm card images computed elsewhere (server rows, their cold-start snapshot). */
        fun warmThumbs(specs: List<ThumbSpec>) {
            if (specs.isEmpty()) return
            scope.launch {
                try {
                    JellytipImageGate.await()
                    coroutineScope { specs.forEach { launch { loadThumb(it) } } }
                } catch (ex: kotlin.coroutines.cancellation.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Row image warm-up failed")
                }
            }
        }

        /**
         * A fresh specials answer: warm the first tiles of each special at the sizes the tiles request, and the backdrop
         * stills of Trailer Break / Tonight's Pick (disk only, like the neighbours' heroes).
         */
        fun warmSpecials(page: JellytipSpecialsPage) {
            scope.launch {
                try {
                    JellytipImageGate.await()
                    val (thumbs, stills) = specialsSpecs(page)
                    coroutineScope {
                        stills.forEach { launch { loadHero(it, toMemory = false) } }
                        thumbs.forEach { launch { loadThumb(it) } }
                    }
                } catch (ex: kotlin.coroutines.cancellation.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Specials image warm-up failed")
                }
            }
        }

        private fun specialsSpecs(page: JellytipSpecialsPage): Pair<List<ThumbSpec>, List<String>> {
            val d = density
            fun px(dp: Dp) = (dp.value * d).roundToInt()
            val thumbs = mutableListOf<ThumbSpec>()
            val stills = mutableListOf<String>()

            fun poster(item: BaseItem?) {
                item ?: return
                val w = px(SpecialTokens.POSTER_W)
                val h = px(SpecialTokens.POSTER_H)
                imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillWidth = w, fillHeight = h)?.let { thumbs += ThumbSpec(it, w, h, true) }
            }

            fun titled(
                item: BaseItem?,
                width: Dp,
                height: Dp,
            ) {
                item ?: return
                val w = px(width)
                val h = px(height)
                val ranked = item.data.seriesId?.takeIf { item.type == BaseItemKind.EPISODE } ?: item.id
                val art = selectJellytipCardArt(imageUrlService, item, w, h, JellytipApi.shared?.peekBestBackdropIndex(ranked))
                art.baseUrl?.let { thumbs += ThumbSpec(it, w, h, true) }
            }

            fun still(
                item: BaseItem?,
                heroIndex: Int?,
            ) {
                item ?: return
                JellytipImagePick.forHome(imageUrlService, item, heroIndex).url?.let { stills += it }
            }
            val items = page.items
            val n = CARDS_PER_ROW
            page.specials.forEach { special ->
                when (special) {
                    is JellytipSpecial.TrailerBreak -> still(items[special.itemId], special.heroIndex)
                    is JellytipSpecial.TonightsPick -> still(special.itemId?.let { items[it] }, special.heroIndex)
                    is JellytipSpecial.PersonSpotlight -> {
                        val w = px(SpecialTokens.PORTRAIT_W)
                        val h = px(SpecialTokens.POSTER_H)
                        imageUrlService
                            .getItemImageUrl(itemId = special.personId, imageType = ImageType.PRIMARY, fillWidth = w, fillHeight = h, tag = special.imageTag)
                            ?.let { thumbs += ThumbSpec(it, w, h, true) }
                        special.films.take(n).forEach { poster(items[it]) }
                    }
                    is JellytipSpecial.Top10 -> special.ranked.take(n).forEach { poster(items[it]) }
                    is JellytipSpecial.DoubleFeature ->
                        special.pairs.take(n / 2).forEach {
                            titled(it.a?.let { a -> items[a] }, SpecialTokens.PAIR_W, SpecialTokens.PAIR_H)
                            titled(it.b?.let { b -> items[b] }, SpecialTokens.PAIR_W, SpecialTokens.PAIR_H)
                        }
                    is JellytipSpecial.BecauseLoved -> {
                        titled(items[special.anchorItemId], SpecialTokens.QUOTE_W, SpecialTokens.WIDE_H)
                        special.similar.take(n).forEach { titled(items[it], SpecialTokens.WIDE_W, SpecialTokens.WIDE_H) }
                    }
                    is JellytipSpecial.AroundHouse -> special.entries.take(n).forEach { titled(items[it.itemId], SpecialTokens.WIDE_W, SpecialTokens.WIDE_H) }
                    is JellytipSpecial.ClosetPicks -> special.picks.take(n).forEach { pick -> pick.itemId?.let { poster(items[it]) } }
                }
            }
            return thumbs to stills
        }

        private suspend fun loadThumb(spec: ThumbSpec) {
            if (spec.width <= 0 || spec.height <= 0) return
            execute(
                ImageRequest
                    .Builder(context)
                    .data(spec.url)
                    .size(spec.width, spec.height)
                    .scale(if (spec.fill) Scale.FILL else Scale.FIT)
                    .build(),
            )
        }

        private suspend fun loadHero(
            url: String,
            toMemory: Boolean,
        ) {
            val metrics = context.resources.displayMetrics
            execute(
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(minOf(metrics.widthPixels, HERO_BACKDROP_MAX_WIDTH), minOf(metrics.heightPixels, 1080))
                    .scale(Scale.FILL)
                    // 1080p backdrops are ~8 MB decoded: only the first one earns a memory slot
                    .memoryCachePolicy(if (toMemory) CachePolicy.ENABLED else CachePolicy.READ_ONLY)
                    .build(),
            )
        }

        private suspend fun execute(request: ImageRequest) {
            permits.withPermit {
                SingletonImageLoader.get(context).execute(request)
            }
        }

        companion object {
            const val CARDS_PER_ROW = 6
            private const val PARALLELISM = 3
            private const val FOCUS_DEBOUNCE_MS = 200L
        }
    }
