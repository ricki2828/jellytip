package com.github.damontecres.wholphin.jellytip.home

import android.content.Context
import com.github.damontecres.wholphin.data.model.HomeGetItemsFilter
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A Kometa-style curated collection that the companion API publishes as a home carousel. */
data class JellytipCarousel(
    val id: UUID,
    val title: String,
    val order: String,
)

/** One of Ricki's Letterboxd lists, already reduced to films that are in the Jellyfin library. */
data class JellytipLetterboxdRow(
    val slug: String,
    val name: String,
    val itemIds: List<UUID>,
)

data class JellytipCarouselManifest(
    val carousels: List<JellytipCarousel>,
    val letterboxd: List<JellytipLetterboxdRow>,
) {
    companion object {
        fun parse(json: String): JellytipCarouselManifest {
            val root = JSONObject(json)
            val carousels =
                root
                    .optJSONArray("carousels")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val title = o.optString("title").ifBlank { o.optString("name") }
                            JellytipCarousel(id.toUUID(), title, o.optString("order", "custom"))
                        }
                    }.orEmpty()
            val letterboxd =
                root
                    .optJSONArray("letterboxd")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val ids =
                                o
                                    .optJSONArray("itemIds")
                                    ?.let { a ->
                                        (0 until a.length()).map { a.optString(it).toUUID() }
                                    }.orEmpty()
                            JellytipLetterboxdRow(o.optString("slug"), o.optString("name"), ids)
                        }
                    }.orEmpty()
            return JellytipCarouselManifest(carousels, letterboxd)
        }
    }
}

/**
 * Row definitions for the Kometa carousels and the "From your Letterboxd" group. Pure functions, no I/O,
 * so the home cache/prefetch can ask for the same definitions without going through the service.
 */
object JellytipCarouselRows {
    const val LETTERBOXD_PREFIX = "Letterboxd · "
    const val MAX_CAROUSEL_ROWS = 10
    const val MAX_LETTERBOXD_ROWS = 4

    fun carouselRow(carousel: JellytipCarousel): HomeRowConfig.GetItems {
        val (sortBy, sortOrder) =
            when (carousel.order) {
                "random" -> {
                    listOf(ItemSortBy.RANDOM) to listOf(SortOrder.ASCENDING)
                }

                "release" -> {
                    listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME) to listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
                }

                "release.desc" -> {
                    listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME) to
                        listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
                }

                "added.desc" -> {
                    listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME) to listOf(SortOrder.DESCENDING, SortOrder.ASCENDING)
                }

                else -> {
                    null to null
                }
            }
        return HomeRowConfig.GetItems(
            name = carousel.title,
            filter = HomeGetItemsFilter(parentId = carousel.id, sortBy = sortBy, sortOrder = sortOrder),
        )
    }

    fun letterboxdRow(list: JellytipLetterboxdRow): HomeRowConfig.GetItems =
        HomeRowConfig.GetItems(
            name = LETTERBOXD_PREFIX + list.name,
            filter = HomeGetItemsFilter(ids = list.itemIds, includeItemTypes = listOf(BaseItemKind.MOVIE)),
        )

    private fun HomeRowConfig.carouselParent(): UUID? = (this as? HomeRowConfig.GetItems)?.filter?.parentId

    private fun HomeRowConfig.isLetterboxd(): Boolean = this is HomeRowConfig.GetItems && name.startsWith(LETTERBOXD_PREFIX)

    private fun HomeRowConfig.isLeading(): Boolean =
        this is HomeRowConfig.ContinueWatching ||
            this is HomeRowConfig.NextUp ||
            this is HomeRowConfig.ContinueWatchingCombined

    /**
     * Result of [reconcile]: the new rows and the ids to remember as seen. [rows] equals the input when nothing changed.
     */
    data class Reconciled(
        val rows: List<HomeRowConfig>,
        val seen: Set<String>,
    )

    /**
     * Merge the manifest into the user's current rows without overriding their customisation:
     *  - rows the user removed stay removed (everything the manifest ever offered is remembered in [seen]);
     *  - a first run (no [seen] state) adds the top rows in manifest order, after Continue Watching / Next Up;
     *  - later runs add only collections that are new to the manifest, and only while below the row cap;
     *  - rows whose collection left the manifest are dropped; Letterboxd rows get fresh film ids.
     */
    fun reconcile(
        current: List<HomeRowConfig>,
        manifest: JellytipCarouselManifest,
        seen: Set<String>?,
    ): Reconciled {
        val firstRun = seen == null
        val known = seen.orEmpty()
        val liveIds = manifest.carousels.map { it.id }.toSet()
        val lbByName = manifest.letterboxd.associateBy { LETTERBOXD_PREFIX + it.name }
        val rows = ArrayList<HomeRowConfig>()
        for (row in current) {
            val parent = row.carouselParent()
            when {
                parent != null && parent.toString() in known && parent !in liveIds -> {
                    Unit
                }

                row.isLetterboxd() -> {
                    val fresh = lbByName[(row as HomeRowConfig.GetItems).name]
                    if (fresh != null) rows.add(row.copy(filter = row.filter.copy(ids = fresh.itemIds))) else Unit
                }

                else -> {
                    rows.add(row)
                }
            }
        }
        val presentCarousels = rows.mapNotNull { it.carouselParent() }.toSet()
        val presentLists = rows.filter { it.isLetterboxd() }.map { (it as HomeRowConfig.GetItems).name }.toSet()

        val newCarousels =
            manifest.carousels.filter { it.id.toString() !in known && it.id !in presentCarousels }
        val newLists =
            manifest.letterboxd.filter { "lb:${it.slug}" !in known && LETTERBOXD_PREFIX + it.name !in presentLists }
        val carouselRoom = (MAX_CAROUSEL_ROWS - presentCarousels.size).coerceAtLeast(0)
        val listRoom = (MAX_LETTERBOXD_ROWS - presentLists.size).coerceAtLeast(0)
        val addCarousels = newCarousels.take(carouselRoom).map { carouselRow(it) }
        val addLists = newLists.take(listRoom).map { letterboxdRow(it) }

        var at = rows.indexOfLast { it.isLeading() } + 1
        rows.addAll(at, addCarousels)
        at += addCarousels.size
        rows.addAll(at, addLists)

        val nowSeen =
            known + manifest.carousels.map { it.id.toString() } + manifest.letterboxd.map { "lb:${it.slug}" }
        if (!firstRun && rows == current && nowSeen == known) return Reconciled(current, known)
        return Reconciled(rows, nowSeen)
    }
}

/**
 * Fetches the carousel manifest from the companion API (cached on disk) and merges it into the saved home rows.
 * Fails soft everywhere: a dead companion or a bad manifest leaves the home page exactly as it was.
 */
@Singleton
class JellytipCarouselService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val jellytipApi: JellytipApi,
    ) {
        private val dir get() = File(context.filesDir, "jellytip").apply { mkdirs() }

        private fun cacheFile() = File(dir, "carousels_manifest.json")

        private fun seenFile(userId: UUID) = File(dir, "carousels_seen_$userId.txt")

        suspend fun manifest(): JellytipCarouselManifest? =
            withContext(WholphinDispatchers.IO) {
                val cache = cacheFile()
                val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS
                val text =
                    (if (fresh) null else withTimeoutOrNull(FETCH_TIMEOUT_MS) { jellytipApi.getJson("/v1/home/carousels") })
                        ?.also { runCatching { cache.writeText(it) } }
                        ?: cache.takeIf { it.exists() }?.readText()
                text?.let {
                    try {
                        JellytipCarouselManifest.parse(it)
                    } catch (e: Exception) {
                        Timber.w(e, "Bad carousel manifest")
                        null
                    }
                }
            }

        /**
         * Returns the updated settings to persist, or null when there is nothing to change.
         */
        suspend fun reconcile(
            userId: UUID,
            settings: HomePageSettings,
        ): HomePageSettings? {
            val manifest = manifest() ?: return null
            if (manifest.carousels.isEmpty() && manifest.letterboxd.isEmpty()) return null
            return withContext(WholphinDispatchers.IO) {
                val file = seenFile(userId)
                val seen = if (file.exists()) file.readLines().filter { it.isNotBlank() }.toSet() else null
                val result = JellytipCarouselRows.reconcile(settings.rows, manifest, seen)
                runCatching { file.writeText(result.seen.joinToString("\n")) }
                if (result.rows == settings.rows) null else settings.copy(rows = result.rows)
            }
        }

        companion object {
            private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
            private const val FETCH_TIMEOUT_MS = 3_000L
        }
    }
