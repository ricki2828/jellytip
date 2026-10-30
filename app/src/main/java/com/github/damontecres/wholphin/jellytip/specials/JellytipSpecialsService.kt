package com.github.damontecres.wholphin.jellytip.specials

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.cache.HomeImagePrefetcher
import com.github.damontecres.wholphin.jellytip.cards.JellytipThumbTitles
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The specials for one page, with every Jellyfin item they draw already loaded (one batch request, no per-tile calls). */
data class JellytipSpecialsPage(
    val specials: List<JellytipSpecial>,
    val items: Map<UUID, BaseItem>,
)

/**
 * Loads `GET /v1/home/specials?page=home|movies|shows` for the signed-in user and the items it references. The answer is
 * the same all day, so it is kept in memory per user/page/day; Home shows the cached copy instantly when you come back.
 * Any failure (404, unreachable, malformed) means no specials: the page looks exactly as it does without them.
 */
@Singleton
class JellytipSpecialsService
    @Inject
    constructor(
        @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
        private val api: ApiClient,
        private val jellytipApi: JellytipApi,
        private val serverRepository: ServerRepository,
        private val prefetcher: HomeImagePrefetcher,
    ) {
        private data class Entry(
            val page: JellytipSpecialsPage,
            val at: Long,
        )

        private val cache = ConcurrentHashMap<String, Entry>()

        private fun key(page: String): String? {
            val user = serverRepository.currentUser?.id ?: return null
            return "$user:$page:${LocalDate.now()}"
        }

        /**
         * Cached answer for [page] (today, this user), or null. Falls back to today's copy on disk (answer + its items),
         * so Home draws the specials in their places on the first frame instead of inserting them under the user.
         */
        fun peek(page: String): JellytipSpecialsPage? {
            val key = key(page) ?: return null
            cache[key]?.let { return it.page }
            return try {
                val file = diskFile(page)?.takeIf { it.exists() && it.lastModified() >= startOfToday() } ?: return null
                val saved = org.json.JSONObject(file.readText())
                val answer = JellytipSpecialsParser.parseAnswer(saved.getString("answer"))
                val items =
                    diskJson
                        .decodeFromString(kotlinx.serialization.builtins.ListSerializer(BaseItem.serializer()), saved.getString("items"))
                        .associateBy { it.id }
                JellytipThumbTitles.markUntitled(answer.untitled)
                answer.heroIndexes.forEach { (id, index) -> jellytipApi.hintHeroIndex(id, index) }
                // Stale on purpose (at = 0): shown now, refreshed by the next load()
                JellytipSpecialsPage(answer.specials.mapNotNull { it.keepVisible(items) }, items).also { cache[key] = Entry(it, 0L) }
            } catch (e: Exception) {
                Timber.w(e, "Specials disk cache unreadable")
                null
            }
        }

        private fun diskFile(page: String): java.io.File? =
            serverRepository.currentUser?.id?.let {
                java.io.File(java.io.File(context.filesDir, "jellytip").apply { mkdirs() }, "specials_${it}_$page.json")
            }

        private fun startOfToday(): Long = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        private fun saveToDisk(
            page: String,
            json: String,
            items: Map<UUID, BaseItem>,
        ) {
            try {
                val slim = items.values.map { it.copy(data = it.data.copy(people = null, chapters = null, trickplay = null)) }
                val out =
                    org.json.JSONObject()
                        .put("answer", json)
                        .put("items", diskJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(BaseItem.serializer()), slim))
                diskFile(page)?.writeText(out.toString())
            } catch (e: Exception) {
                Timber.w(e, "Specials disk cache not written")
            }
        }

        suspend fun load(page: String): JellytipSpecialsPage? {
            val key = key(page) ?: return null
            cache[key]?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }?.let { return it.page }
            return try {
                val json =
                    jellytipApi.getJson("/v1/home/specials?page=$page", timeoutMs = TIMEOUT_MS, asUser = true)
                        ?: return cache[key]?.page
                val answer = JellytipSpecialsParser.parseAnswer(json)
                JellytipThumbTitles.markUntitled(answer.untitled)
                answer.heroIndexes.forEach { (id, index) -> jellytipApi.hintHeroIndex(id, index) }
                val items = loadItems(answer.specials.flatMap { it.itemIds }.distinct())
                val resolved = JellytipSpecialsPage(answer.specials.mapNotNull { it.keepVisible(items) }, items)
                cache[key] = Entry(resolved, System.currentTimeMillis())
                saveToDisk(page, json, items)
                prefetcher.warmSpecials(resolved)
                resolved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Jellytip specials unavailable for %s", page)
                cache[key]?.page
            }
        }

        private suspend fun loadItems(ids: List<UUID>): Map<UUID, BaseItem> {
            if (ids.isEmpty()) return emptyMap()
            val userId = serverRepository.currentUser?.id
            return ids
                .chunked(BATCH)
                .flatMap { chunk ->
                    val request =
                        GetItemsRequest(
                            userId = userId,
                            ids = chunk,
                            fields = HomeItemFields + ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                            enableUserData = true,
                        )
                    GetItemsRequestHandler
                        .execute(api, request)
                        .content.items
                        .map { BaseItem(it, false) }
                }.associateBy { it.id }
        }

        /** Stream URL for a local trailer item (Trailer Break). */
        fun trailerStreamUrl(trailerItemId: UUID): String = api.videosApi.getVideoStreamUrl(itemId = trailerItemId, static = true)

        /** Absolute URL for a companion-relative path (closet videos are `v1/closet/<file>`). */
        suspend fun companionUrl(path: String): String? = jellytipApi.resolveUrl(path)

        companion object {
            private val diskJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            private const val TIMEOUT_MS = 6_000L
            private const val FRESH_MS = 30 * 60_000L
            private const val BATCH = 100
        }
    }

/** Drop references the caller cannot see; a special with too little left is skipped (it degrades, never breaks). */
private fun JellytipSpecial.keepVisible(items: Map<UUID, BaseItem>): JellytipSpecial? =
    when (this) {
        is JellytipSpecial.TrailerBreak -> takeIf { itemId in items }
        is JellytipSpecial.TonightsPick -> takeIf { (itemId != null && itemId in items) || iptvItem != null }
        is JellytipSpecial.PersonSpotlight -> copy(films = films.filter { it in items }).takeIf { it.films.isNotEmpty() || it.iptvItems.isNotEmpty() }
        is JellytipSpecial.Top10 -> copy(ranked = ranked.filter { it in items }).takeIf { it.ranked.size >= 3 }
        is JellytipSpecial.DoubleFeature ->
            copy(pairs = pairs.filter { (it.a?.let { a -> a in items } ?: (it.aIptv != null)) && (it.b?.let { b -> b in items } ?: (it.bIptv != null)) })
                .takeIf { it.pairs.isNotEmpty() }
        is JellytipSpecial.BecauseLoved -> copy(similar = similar.filter { it in items }).takeIf { anchorItemId in items && it.similar.isNotEmpty() }
        is JellytipSpecial.AroundHouse -> copy(entries = entries.filter { it.itemId in items }).takeIf { it.entries.isNotEmpty() }
        // A pick whose item went missing is shown like any other not-in-library pick
        is JellytipSpecial.ClosetPicks -> copy(picks = picks.map { if (it.itemId != null && it.itemId !in items) it.copy(itemId = null) else it })
    }
