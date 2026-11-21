package com.github.damontecres.wholphin.jellytip.inbox

import android.content.Context
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import com.github.damontecres.wholphin.jellytip.iptv.LibraryIptvCopies
import com.github.damontecres.wholphin.ui.toBaseItems
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** The inbox on this TV: looks again at most every 15 minutes (Home open) or when the Inbox opens; no polling. */
@Singleton
class JellytipInboxService
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        @param:ApplicationContext private val context: Context,
    ) {
        init {
            shared = this
        }

        private val _store = MutableStateFlow<Inbox.Store?>(null)
        val store: StateFlow<Inbox.Store?> = _store
        private val mutex = Mutex()

        private fun user() = serverRepository.currentUser?.id?.toString()

        private fun prefs() = context.getSharedPreferences("jellytip_inbox", Context.MODE_PRIVATE)

        private fun load(u: String): Inbox.Store = _store.value?.takeIf { it.userId == u } ?: Inbox.fromJson(prefs().getString("v2.$u", null), u).also { _store.value = it }

        private fun save(s: Inbox.Store) {
            prefs().edit().putString("v2.${s.userId}", Inbox.toJson(s)).apply()
            _store.value = s
        }

        fun current(): Inbox.Store? = user()?.let { load(it) }

        /** "Get it" on a title: its arrival becomes news. */
        fun recordRequest(
            tmdbId: Int,
            media: String,
            title: String,
        ) {
            val u = user() ?: return
            if (tmdbId <= 0 || title.isBlank()) return
            val m = if (media == "tv" || media == "series") "tv" else "movie"
            save(Inbox.addRequest(load(u), Inbox.Request(Inbox.reqKey(m, tmdbId), tmdbId, m, title, System.currentTimeMillis())))
        }

        fun markRead() {
            val u = user() ?: return
            save(Inbox.markAllRead(load(u)))
        }

        suspend fun refresh(force: Boolean = false) =
            mutex.withLock {
                val u = user() ?: return@withLock
                val s0 = load(u)
                val now = System.currentTimeMillis()
                if (!force && s0.seeded && !Inbox.dueForRefresh(s0, now)) return@withLock
                val t = runCatching { observeTitles(s0, now) }.getOrNull()
                val e = runCatching { observeEpisodes(s0) }.getOrNull()
                if (user() != u) return@withLock // switched person meanwhile: not theirs
                if (t == null && e == null) return@withLock
                save(Inbox.apply(load(u), Inbox.Observed(t?.first, e?.first, t?.second.orEmpty() + e?.second.orEmpty()), System.currentTimeMillis()))
            }

        private fun oldestFirst(
            keys: Collection<String>,
            checked: Map<String, Long>,
        ) = keys.sortedBy { checked[it] ?: 0L }

        private suspend fun observeEpisodes(s: Inbox.Store): Pair<Map<String, List<Inbox.ObservedEpisode>>, List<String>>? {
            val userId = serverRepository.currentUser?.id ?: return null
            val names = LinkedHashMap<String, String>()
            val fav =
                runCatching {
                    api.itemsApi
                        .getItems(GetItemsRequest(userId = userId, includeItemTypes = listOf(BaseItemKind.SERIES), isFavorite = true, recursive = true, limit = 200))
                        .toBaseItems()
                        .map { it.id.toString() to it.name.orEmpty() }
                }.getOrNull()
            val up =
                runCatching {
                    api.tvShowsApi
                        .getNextUp(GetNextUpRequest(userId = userId, limit = 50))
                        .toBaseItems()
                        .mapNotNull { e -> e.data.seriesId?.let { it.toString() to (e.data.seriesName ?: "") } }
                }.getOrNull()
            if (fav == null && up == null) return null
            fav.orEmpty().forEach { (k, v) -> names[k] = v }
            up.orEmpty().forEach { (k, v) -> names.putIfAbsent(k, v) }
            val todo = oldestFirst(names.keys, s.checked).take(MAX_SERIES_PER_LOOK)
            val got =
                coroutineScope {
                    todo.chunked(PARALLEL).flatMap { chunk ->
                        chunk.map { sid -> async { sid to runCatching { episodesOf(userId, sid) }.getOrNull() } }.awaitAll()
                    }
                }
            val eps = LinkedHashMap<String, List<Inbox.ObservedEpisode>>()
            val done = ArrayList<String>()
            got.forEach { (sid, list) ->
                list ?: return@forEach
                done += sid
                eps[sid] = list.map { it.copy(show = it.show.ifBlank { names[sid].orEmpty() }) }
            }
            return eps to done
        }

        private suspend fun episodesOf(
            userId: java.util.UUID,
            sid: String,
        ): List<Inbox.ObservedEpisode> =
            api.itemsApi
                .getItems(
                    GetItemsRequest(
                        userId = userId,
                        parentId = java.util.UUID.fromString(sid),
                        includeItemTypes = listOf(BaseItemKind.EPISODE),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        fields = listOf(ItemFields.DATE_CREATED),
                        enableUserData = false,
                        limit = 100,
                    ),
                ).toBaseItems()
                .filter { it.data.locationType != LocationType.VIRTUAL } // unaired / missing placeholders are not arrivals
                .map {
                    Inbox.ObservedEpisode(
                        it.id.toString(),
                        it.data.seriesName.orEmpty(),
                        it.data.parentIndexNumber,
                        it.data.indexNumber,
                        it.data.dateCreated?.toEpochSecond(ZoneOffset.UTC)?.times(1000) ?: 0L,
                    )
                }

        private suspend fun observeTitles(
            s: Inbox.Store,
            now: Long,
        ): Pair<Map<String, Inbox.ObservedTitle>, List<String>>? {
            val reqs = s.requests
            if (reqs.isEmpty()) return emptyMap<String, Inbox.ObservedTitle>() to emptyList()
            val userId = serverRepository.currentUser?.id ?: return null
            val inLib = HashMap<String, String>()
            // At most 10 library searches a look (least recently checked first); ones already in the library are done
            val libTodo =
                reqs
                    .filter { (s.titles[it.key] ?: 0) < 2 }
                    .sortedBy { s.checked["lib:${it.key}"] ?: 0L }
                    .take(MAX_LIBRARY_PER_LOOK)
            try {
                // The SDK has no provider-id filter: search each request still short of the library by title, match TMDB.
                // Requests already in the library stay there (one search each until then).
                for (r in libTodo) {
                    api.itemsApi
                        .getItems(
                            GetItemsRequest(
                                userId = userId,
                                searchTerm = r.title,
                                includeItemTypes = listOf(if (r.media == "tv") BaseItemKind.SERIES else BaseItemKind.MOVIE),
                                recursive = true,
                                fields = listOf(ItemFields.PROVIDER_IDS),
                                enableUserData = false,
                                limit = 20,
                            ),
                        ).toBaseItems()
                        .forEach { item ->
                            val t = item.data.providerIds?.entries?.firstOrNull { e -> e.key.equals("Tmdb", true) }?.value?.toIntOrNull()
                            if (t == r.tmdbId) inLib[r.key] = item.id.toString()
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Inbox: library unreachable")
                return null // leave the titles snapshot as it was
            }
            // IPTV for open requests under 60 days old, at most 10 a look (least recently checked first)
            val open = reqs.filter { it.key !in inLib && (s.titles[it.key] ?: 0) < 2 && now - it.at <= REQUEST_IPTV_AGE_MS }
            val pick = oldestFirst(open.map { it.key }, s.checked).take(MAX_IPTV_PER_LOOK).toSet()
            val companion = JellytipApi.shared
            val allowed =
                if (pick.isEmpty() || companion == null) {
                    null
                } else {
                    companion.userCall("v1/iptv/providers")?.takeIf { it.first == 200 }?.let { (_, b) ->
                        val a = JSONObject(b).optJSONArray("providers")
                        (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it)?.optString("id")?.takeIf { x -> x.isNotBlank() } }
                    }
                }
            val hits = HashMap<String, String?>()
            if (companion != null && !allowed.isNullOrEmpty()) {
                val base = companion.companionBase()
                for (r in reqs.filter { it.key in pick }) {
                    val kind = if (r.media == "tv") "series" else "movie"
                    val res = companion.userCall("v1/iptv/vod?kind=$kind&q=${URLEncoder.encode(r.title, "UTF-8")}&limit=50") ?: continue
                    if (res.first != 200) continue
                    val items = runCatching { IptvVodParse.items(JSONObject(res.second).optJSONArray("items"), base) }.getOrNull() ?: continue
                    hits[r.key] = LibraryIptvCopies.pick(items, kind, r.tmdbId, allowed).firstOrNull()?.id
                }
            }
            val titles = LinkedHashMap<String, Inbox.ObservedTitle>()
            for (r in reqs) {
                val libId = inLib[r.key]
                titles[r.key] =
                    when {
                        libId != null -> Inbox.ObservedTitle(2, r.title, requested = true, libId = libId)
                        r.key in hits -> hits[r.key]?.let { Inbox.ObservedTitle(1, r.title, requested = true, iptvId = it) } ?: Inbox.ObservedTitle(0, r.title, requested = true)
                        else -> Inbox.ObservedTitle(0, r.title, requested = true, partial = true) // keeps a known rank
                    }
            }
            return titles to (hits.keys + libTodo.map { "lib:${it.key}" }).toList()
        }

        companion object {
            /** Set when Hilt builds the singleton (composables and the request flow reach it without plumbing). */
            @Volatile
            var shared: JellytipInboxService? = null
                private set

            const val MAX_SERIES_PER_LOOK = 40
            const val MAX_IPTV_PER_LOOK = 10
            const val MAX_LIBRARY_PER_LOOK = 10
            const val PARALLEL = 4
            const val REQUEST_IPTV_AGE_MS = 60L * 24 * 3600_000
        }
    }
