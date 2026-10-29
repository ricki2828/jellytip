package com.github.damontecres.wholphin.jellytip.rows

import android.content.Context
import com.github.damontecres.wholphin.data.ServerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.cards.JellytipThumbTitles
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import kotlinx.coroutines.CancellationException
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
) {
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
        if (itemIds.isEmpty()) return null
        JellytipThumbTitles.markUntitled(o.uuids("untitled"))
        return JellytipServerRow(
            id = id,
            source = o.optString("source"),
            family = o.optString("family"),
            title = o.str("title").orEmpty(),
            layout = o.optString("layout", "standard"),
            mediaType = o.optString("mediaType", "movie"),
            itemIds = itemIds,
            collection = o.optJSONObject("collection")?.let { collection(it) },
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

        /** Already loaded items of [row] (server order), or null. */
        fun peekItems(row: JellytipServerRow): List<BaseItem>? = rowKey(row)?.let { rowItems[it]?.value }

        /** [row]'s items in server order (items the caller cannot see are left out); empty on failure. */
        suspend fun items(row: JellytipServerRow): List<BaseItem> {
            val key = rowKey(row) ?: return emptyList()
            rowItems[key]?.takeIf { System.currentTimeMillis() - it.at < ITEMS_FRESH_MS }?.let { return it.value }
            return try {
                val loaded = loadItems(row.itemIds)
                rowItems[key] = Timed(loaded, System.currentTimeMillis())
                loaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Row %s items failed", row.id)
                rowItems[key]?.value ?: emptyList()
            }
        }

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
            private const val TIMEOUT_MS = 6_000L
            private const val PAGE_FRESH_MS = 30 * 60_000L
            private const val ITEMS_FRESH_MS = 10 * 60_000L
            private const val BATCH = 100
        }
    }
