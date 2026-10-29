package com.github.damontecres.wholphin.jellytip.rows

import android.content.Context
import com.github.damontecres.wholphin.data.ServerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.cache.HomeImagePrefetcher
import com.github.damontecres.wholphin.jellytip.cache.HomeSnapshot
import com.github.damontecres.wholphin.jellytip.cache.RowSnapshot
import com.github.damontecres.wholphin.jellytip.cards.JellytipThumbTitles
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One server-built row from `GET /v1/home/carousels?rows=1&page=…` (contract: ~/ai/plans/jellytip-10x.md,
 * "Manifest additions"). Items are loaded by [itemIds] when the row is first drawn; [layout] is standard, poster or
 * franchise (anything else is drawn as standard).
 */
data class JellytipServerRow(
    val id: String,
    val source: String,
    val family: String,
    val title: String,
    val layout: String,
    val mediaType: String,
    val itemIds: List<UUID>,
    val collection: JellytipCollection?,
    /** People rows ("Starring X", "Directed by X"): the row opens with this person's portrait. */
    val person: Person? = null,
    /** IPTV rows (`source:"iptv"`): the titles come with the row, there are no library ids. */
    val iptvItems: List<com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem> = emptyList(),
) {
    data class Person(
        val id: UUID,
        val name: String,
        val role: String,
        val imageTag: String?,
    )

    val isPoster get() = layout == LAYOUT_POSTER
    val isFranchise get() = layout == LAYOUT_FRANCHISE && collection != null

    companion object {
        const val LAYOUT_POSTER = "poster"
        const val LAYOUT_FRANCHISE = "franchise"
    }
}

/** A franchise: TMDB collection with its parts in series order (row `collection` object and `/v1/collections/{id}`). */
data class JellytipCollection(
    val tmdbId: Int,
    val name: String,
    val overview: String,
    val backdrop: String?,
    val poster: String?,
    val parts: List<Part>,
    val watchedCount: Int,
    val total: Int,
    val inLibrary: Int,
    /** In-library items, series order. */
    val itemIds: List<UUID>,
    /** Only on the collection page: parts not in the library. */
    val missing: List<Missing> = emptyList(),
    /** Only on the collection page: the caller is an admin, so missing parts can be requested. */
    val canRequest: Boolean = false,
) {
    data class Part(
        val tmdbId: Int?,
        val title: String,
        val year: Int?,
        val itemId: UUID?,
    )

    data class Missing(
        val tmdbId: Int,
        val title: String,
        val year: Int?,
        val posterUrl: String?,
        val released: Boolean,
        /** The person's IPTV has this part (`source:"iptv"`): it fills the gap, playable "via IPTV". */
        val iptv: com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem? = null,
    )

    /** "3 of 8 watched" */
    val progressText: String get() = "$watchedCount of $total watched"
}

/** The rows answer: the ordered rows, and the BoxSet ids of the old manifest (those rows now arrive as server rows). */
data class JellytipRowsAnswer(
    val rows: List<JellytipServerRow>,
    val carouselIds: Set<UUID>,
)

object JellytipRowsParser {
    /** Null when the answer has no `rows` (old server): the caller keeps today's behaviour. */
    fun parse(json: String): JellytipRowsAnswer? {
        val root = JSONObject(json)
        com.github.damontecres.wholphin.jellytip.JellytipLogoTint.setStyles(root.optJSONObject("logoStyles"))
        com.github.damontecres.wholphin.jellytip.cards.JellytipTech.setAll(root.optJSONObject("techs"))
        val arr = root.optJSONArray("rows") ?: return null
        val rows =
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                try {
                    row(o)
                } catch (e: Exception) {
                    Timber.w(e, "Row %d skipped", i)
                    null
                }
            }
        val carousels =
            root.optJSONArray("carousels")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.str("id")?.toUuidOrNull() }
            }.orEmpty()
        return JellytipRowsAnswer(rows, carousels.toSet())
    }

    private fun row(o: JSONObject): JellytipServerRow? {
        val id = o.str("id") ?: return null
        val itemIds = o.uuids("itemIds")
        val iptv = com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse.items(o.optJSONArray("iptvItems"), com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse.imageBase)
        if (itemIds.isEmpty() && iptv.isEmpty()) return null
        JellytipThumbTitles.markUntitled(o.uuids("untitled"))
        com.github.damontecres.wholphin.jellytip.JellytipLogoTint.setStyles(o.optJSONObject("logoStyles"))
        com.github.damontecres.wholphin.jellytip.cards.JellytipTech.setAll(o.optJSONObject("techs"))
        return JellytipServerRow(
            id = id,
            source = o.optString("source"),
            family = o.optString("family"),
            title = o.str("title").orEmpty(),
            layout = o.optString("layout", "standard"),
            mediaType = o.optString("mediaType", "movie"),
            itemIds = itemIds,
            iptvItems = iptv,
            collection = o.optJSONObject("collection")?.let { collection(it) },
            person =
                o.optJSONObject("person")?.let { p ->
                    val pid = p.str("id")?.toUuidOrNull() ?: return@let null
                    JellytipServerRow.Person(pid, p.str("name").orEmpty(), p.optString("role", "actor"), p.str("imageTag"))
                },
        )
    }

    fun collection(o: JSONObject): JellytipCollection? {
        val tmdbId = o.optInt("tmdbId", 0).takeIf { it > 0 } ?: return null
        val parts =
            o.objects("partsOrder").map { p ->
                JellytipCollection.Part(
                    tmdbId = p.optInt("tmdbId", 0).takeIf { it > 0 },
                    title = p.str("title").orEmpty(),
                    year = p.optInt("year", 0).takeIf { it > 0 },
                    itemId = p.str("itemId")?.toUuidOrNull(),
                )
            }
        val missing =
            o.objects("missing").mapNotNull { m ->
                JellytipCollection.Missing(
                    tmdbId = m.optInt("tmdbId", 0).takeIf { it > 0 } ?: return@mapNotNull null,
                    title = m.str("title").orEmpty(),
                    year = m.optInt("year", 0).takeIf { it > 0 },
                    posterUrl = m.str("posterUrl"),
                    released = m.optBoolean("released", true),
                    iptv = iptvPart(m),
                )
            }
        return JellytipCollection(
            tmdbId = tmdbId,
            name = o.str("name").orEmpty(),
            overview = o.str("overview").orEmpty(),
            backdrop = o.str("backdrop"),
            poster = o.str("poster"),
            parts = parts,
            watchedCount = o.optInt("watchedCount", 0),
            total = o.optInt("total", parts.size),
            inLibrary = o.optInt("inLibrary", 0),
            itemIds = o.uuids("itemIds"),
            missing = missing,
            canRequest = o.optBoolean("canRequest", false),
        )
    }

    /** A part the library lacks but the person's IPTV has: `source:"iptv", iptv:{id, title, year, quality, poster, …}`. */
    private fun iptvPart(m: JSONObject): com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem? {
        if (m.optString("source") != "iptv") return null
        val i = m.optJSONObject("iptv") ?: return null
        if (!i.has("kind")) i.put("kind", "movie")
        return com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse.item(i, com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse.imageBase)
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { toUUID() }.getOrNull()

    private fun JSONObject.str(key: String): String? = optString(key).takeIf { !isNull(key) && it.isNotBlank() && it != "null" }

    private fun JSONObject.uuids(key: String): List<UUID> {
        val a: JSONArray = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() }?.toUuidOrNull() }
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val a: JSONArray = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
}

/**
 * Server rows for Home / Movies / Shows and the franchise pages. The page's row list is fixed per user and day, so it is
 * kept in memory; each row's items are loaded with one batch request the first time the row is drawn. Any failure =
 * null/empty, and the page falls back to today's rows.
 */
@Singleton
class JellytipRowsService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val jellytipApi: JellytipApi,
        private val serverRepository: ServerRepository,
        private val prefetcher: HomeImagePrefetcher,
    ) {
        private data class Timed<T>(
            val value: T,
            val at: Long,
        )

        private val answers = ConcurrentHashMap<String, Timed<JellytipRowsAnswer>>()
        private val rowItems = ConcurrentHashMap<String, Timed<List<BaseItem>>>()

        private fun user() = serverRepository.currentUser?.id

        private fun pageKey(page: String): String? = user()?.let { "$it:$page:${LocalDate.now()}" }

        private fun rowKey(row: JellytipServerRow): String? = user()?.let { "$it:${row.id}:${row.itemIds.hashCode()}" }

        /**
         * Today's cached rows for [page], or null. Falls back to today's copy on disk (small file) so a cold start draws
         * the server rows at once instead of swapping the old carousel rows out under the user's focus.
         */
        fun peek(page: String): JellytipRowsAnswer? {
            val key = pageKey(page) ?: return null
            answers[key]?.let { return it.value }
            val file = diskFile(page) ?: return null
            return try {
                if (!file.exists() || file.lastModified() < startOfToday()) return null
                JellytipRowsParser.parse(file.readText())?.takeIf { it.rows.isNotEmpty() }?.also {
                    // Stale on purpose (at = 0): shown now, refreshed by the next load()
                    answers[key] = Timed(it, 0L)
                }
            } catch (e: Exception) {
                Timber.w(e, "Rows disk cache unreadable")
                null
            }
        }

        private fun diskFile(page: String): File? =
            user()?.let { File(File(context.filesDir, "jellytip").apply { mkdirs() }, "rows_${it}_$page.json") }

        private fun startOfToday(): Long =
            LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        suspend fun load(page: String): JellytipRowsAnswer? {
            val key = pageKey(page) ?: return null
            answers[key]?.takeIf { System.currentTimeMillis() - it.at < PAGE_FRESH_MS }?.let { return it.value }
            return try {
                val json = jellytipApi.getJson("/v1/home/carousels?rows=1&page=$page", timeoutMs = TIMEOUT_MS, asUser = true)
                val answer = json?.let { JellytipRowsParser.parse(it) }?.takeIf { it.rows.isNotEmpty() }
                if (answer == null) return answers[key]?.value
                answers[key] = Timed(answer, System.currentTimeMillis())
                runCatching { json?.let { diskFile(page)?.writeText(it) } }
                answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Jellytip rows unavailable for %s", page)
                answers[key]?.value
            }
        }

        /**
         * Already loaded items of [row] (server order), or null. Before the first load of this process the last saved
         * copy (cold-start snapshot) is used, so the row draws at once and refreshes in place.
         */
        fun peekItems(row: JellytipServerRow): List<BaseItem>? {
            val key = rowKey(row) ?: return null
            rowItems[key]?.let { return it.value }
            return snapshotRows()[row.id]?.items
        }

        /**
         * [row]'s items in server order (items the caller cannot see are left out). Null = the load failed and nothing
         * is cached; empty = the caller can see none of them. A cached copy is reused unless it is old or one of its
         * items changed since (played, watched toggled: [markChanged]).
         */
        suspend fun items(row: JellytipServerRow): List<BaseItem>? {
            if (row.itemIds.isEmpty()) return emptyList() // IPTV rows: titles came with the row
            val key = rowKey(row) ?: return null
            val cached = rowItems[key]
            if (cached != null && System.currentTimeMillis() - cached.at < ITEMS_FRESH_MS && !cached.changedSince()) return cached.value
            // One request per row even when the prefetch and the drawn row ask at the same time
            val job =
                inFlight.computeIfAbsent(key) {
                    scope.async {
                        try {
                            loadItems(row.itemIds).also { rowItems[key] = Timed(it, System.currentTimeMillis()) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Row %s items failed", row.id)
                            null
                        } finally {
                            inFlight.remove(key)
                        }
                    }
                }
            return job.await() ?: cached?.value
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlight = ConcurrentHashMap<String, Deferred<List<BaseItem>?>>()

        private fun Timed<List<BaseItem>>.changedSince(): Boolean =
            value.any { item -> (changedAt[item.id] ?: 0L) > at || item.data.seriesId?.let { (changedAt[it] ?: 0L) > at } == true }

        /**
         * A fresh page: load the first rows' items in the background, warm their card images and save them as the
         * cold-start snapshot (rows below the fold load when drawn).
         */
        suspend fun prefetch(
            page: String,
            answer: JellytipRowsAnswer,
        ) {
            val userId = user() ?: return
            val first = answer.rows.take(PREFETCH_ROWS)
            val loaded = mutableListOf<RowSnapshot>()
            for (row in first) {
                val items = items(row)?.takeIf { it.isNotEmpty() } ?: continue
                val thumbs = prefetcher.thumbsFor(HomeRowLoadingState.Success(StringStringProvider(row.title), items, row.rawViewOptions()))
                prefetcher.warmThumbs(thumbs)
                loaded += RowSnapshot("srv:${row.id}", items.take(SNAPSHOT_ITEMS).map(::slim), false, thumbs)
            }
            if (loaded.isEmpty()) return
            try {
                val merged = (loaded.associateBy { it.key.removePrefix("srv:") } + snapshotRows().filterKeys { k -> loaded.none { it.key == "srv:$k" } })
                val snap = HomeSnapshot(userId.toString(), merged.values.take(MAX_SNAPSHOT_ROWS).toList(), null)
                synchronized(this) {
                    snapshotMemo = userId to merged
                    snapshotFile(userId)?.writeText(snapshotJson.encodeToString(HomeSnapshot.serializer(), snap))
                }
            } catch (e: Exception) {
                Timber.w(e, "Rows snapshot not saved (%s)", page)
            }
        }

        /** Cold start (during the intro): warm the images of the saved server rows. */
        fun warmFromSnapshot() {
            val thumbs = snapshotRows().values.flatMap { it.thumbs }
            if (thumbs.isNotEmpty()) prefetcher.warmThumbs(thumbs)
        }

        /** Saved rows of one user (user id, rows by row id). */
        @Volatile
        private var snapshotMemo: Pair<UUID, Map<String, RowSnapshot>>? = null

        private fun snapshotFile(userId: UUID): File? = File(File(context.filesDir, "jellytip").apply { mkdirs() }, "rows_snapshot_$userId.json")

        /** Saved server rows by row id (empty when none or another user). */
        private fun snapshotRows(): Map<String, RowSnapshot> {
            val userId = user() ?: return emptyMap()
            snapshotMemo?.takeIf { it.first == userId }?.let { return it.second }
            val rows =
                try {
                    snapshotFile(userId)
                        ?.takeIf { it.exists() }
                        ?.readText()
                        ?.let { snapshotJson.decodeFromString(HomeSnapshot.serializer(), it) }
                        ?.takeIf { it.userId == userId.toString() }
                        ?.rows
                        ?.associateBy { it.key.removePrefix("srv:") }
                        .orEmpty()
                } catch (e: Exception) {
                    Timber.w(e, "Rows snapshot unreadable")
                    emptyMap()
                }
            snapshotMemo = userId to rows
            return rows
        }

        private fun slim(item: BaseItem) = item.copy(data = item.data.copy(people = null, chapters = null, trickplay = null))

        /** `/v1/collections/{tmdbId}` plus its in-library items (series order); null when unavailable (404 etc.). */
        suspend fun collection(tmdbId: Int): Pair<JellytipCollection, List<BaseItem>>? =
            try {
                jellytipApi
                    .getJson("/v1/collections/$tmdbId", timeoutMs = TIMEOUT_MS, asUser = true)
                    ?.let { JellytipRowsParser.collection(JSONObject(it)) }
                    ?.let { c -> c to loadItems(c.itemIds.ifEmpty { c.parts.mapNotNull { it.itemId } }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Collection %s unavailable", tmdbId)
                null
            }

        private suspend fun loadItems(ids: List<UUID>): List<BaseItem> {
            if (ids.isEmpty()) return emptyList()
            val userId = user()
            val byId =
                ids
                    .distinct()
                    .chunked(BATCH)
                    .flatMap { chunk ->
                        GetItemsRequestHandler
                            .execute(
                                api,
                                GetItemsRequest(
                                    userId = userId,
                                    ids = chunk,
                                    fields = HomeItemFields + ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                                    enableUserData = true,
                                ),
                            ).content.items
                            .map { BaseItem(it, false) }
                    }.associateBy { it.id }
            return ids.distinct().mapNotNull { byId[it] }
        }

        companion object {
            /** When each item last changed (played, watched toggled); rows holding it reload on their next draw. */
            private val changedAt = ConcurrentHashMap<UUID, Long>()

            /** Called when [itemId] starts playing or its watched state is toggled. */
            fun markChanged(itemId: UUID?) {
                if (itemId != null) changedAt[itemId] = System.currentTimeMillis()
            }

            private val snapshotJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            private const val PREFETCH_ROWS = 6
            private const val SNAPSHOT_ITEMS = 12
            private const val MAX_SNAPSHOT_ROWS = 40
            private const val TIMEOUT_MS = 6_000L
            private const val PAGE_FRESH_MS = 30 * 60_000L
            private const val ITEMS_FRESH_MS = 10 * 60_000L
            private const val BATCH = 100
        }
    }
