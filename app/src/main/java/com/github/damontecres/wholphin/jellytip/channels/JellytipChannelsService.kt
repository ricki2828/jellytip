package com.github.damontecres.wholphin.jellytip.channels

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.jellytip.JellytipApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/*
 * My Channels data (port of web api/channels.ts): one library query per channel (series then all episodes for episode
 * channels; the all-episodes list is fetched once and shared), plus the person's IPTV catalogue from
 * /v1/iptv/channel-pool, weighted by the taste profile (/v1/me/taste). Cached per person for 6 h (15 min while the
 * server says the IPTV pool is still filling, or the profile could not be read). Schedule maths: Channels.kt.
 *
 * Library ids are kept dashless lower-case hex, exactly as the web sees them: the shuffle hashes ids, so a dashed id
 * would give the TV a different schedule from the web.
 */
@Singleton
class JellytipChannelsService
    @Inject
    constructor(
        private val api: ApiClient,
        private val jellytipApi: JellytipApi,
        private val serverRepository: ServerRepository,
    ) {
        private data class Entry(
            val at: Long,
            val items: List<ChanItem>,
            val ttl: Long,
        )

        private val cache = ConcurrentHashMap<String, Entry>()
        private val locks = ConcurrentHashMap<String, Mutex>()
        /** Person -> when IPTV answered "not for you" (404/501/403); retried after [NO_IPTV_TTL_MS]. */
        private val noIptv = ConcurrentHashMap<String, Long>()

        @Volatile
        private var episodes: Pair<String, Pair<Long, List<BaseItemDto>>>? = null
        private val episodesLock = Mutex()

        @Volatile
        private var taste: Triple<String, Long, TasteProfile?>? = null

        @Volatile
        var personalDefs: List<ChannelDef> = emptyList()
            private set

        private fun user() = serverRepository.currentUser?.id?.toString() ?: ""

        /** The taste profile (30 min), waiting at most 3 s; null = none (or not reachable: then not remembered). */
        suspend fun profile(): Pair<TasteProfile?, Boolean> {
            val u = user()
            taste?.takeIf { it.first == u && System.currentTimeMillis() - it.second < TASTE_TTL_MS }?.let { return it.third to true }
            val r = withTimeoutOrNull(3_000) { jellytipApi.userCall("v1/me/taste", timeoutMs = 3_000) }
            if (r == null) return null to false
            val p = if (r.first == 200) Taste.parse(r.second) else null
            taste = Triple(u, System.currentTimeMillis(), p)
            return p to true
        }

        /** Every built-in channel's items plus the personal channels the profile supports. */
        suspend fun all(fresh: Boolean = false): Pair<List<ChannelDef>, Map<String, List<ChanItem>>> =
            coroutineScope {
                val (prof, settled) = profile()
                val base =
                    CHANNELS
                        .map { def -> async { def.id to runCatching { items(def, prof, settled, fresh) }.onFailure { if (it is CancellationException) throw it }.getOrNull() } }
                        .awaitAll()
                        .mapNotNull { (id, l) -> l?.let { id to it } }
                        .toMap()
                val personal = PersonalChannels.personalDefs(prof, base)
                personalDefs = personal
                val withPersonal = base + personal.associate { it.id to PersonalChannels.personalItems(it, base, it.genres?.firstOrNull()) }
                (personal + CHANNELS) to withPersonal
            }

        private suspend fun items(
            def: ChannelDef,
            prof: TasteProfile?,
            settled: Boolean,
            fresh: Boolean,
        ): List<ChanItem> {
            val u = user()
            val key = "$u.${def.id}.${if (prof != null) "p" else "n"}"
            val lock = locks.getOrPut(key) { Mutex() }
            return lock.withLock {
                if (!fresh) cache[key]?.takeIf { System.currentTimeMillis() - it.at < it.ttl }?.let { return@withLock it.items }
                val (items, pending) = fetch(def, u, prof)
                if (user() != u) throw CancellationException("account changed")
                cache[key] = Entry(System.currentTimeMillis(), items, if (pending > 0 || !settled) PENDING_TTL_MS else TTL_MS)
                items
            }
        }

        private fun hex(id: java.util.UUID?) = id?.toString()?.replace("-", "")?.lowercase()

        private suspend fun query(req: GetItemsRequest): List<BaseItemDto> = api.itemsApi.getItems(req).content.items

        private fun base(
            types: List<BaseItemKind>,
            fields: List<ItemFields> = emptyList(),
        ) = GetItemsRequest(
            userId = serverRepository.currentUser?.id,
            recursive = true,
            includeItemTypes = types,
            enableUserData = false,
            enableImages = false,
            isMissing = false,
            fields = fields,
        )

        /**
         * Library items of one genre set. Jellyfin wants several genres pipe-delimited; one query per genre (union by id)
         * gives the same OR without depending on how the SDK joins a list.
         */
        private suspend fun byGenres(
            def: ChannelDef,
            type: BaseItemKind,
        ): List<BaseItemDto> {
            val genres = def.genres.orEmpty().ifEmpty { listOf(null) }
            val years = Channels.yearsParam(def)
            val seen = LinkedHashMap<java.util.UUID, BaseItemDto>()
            for (g in genres) {
                val req = base(listOf(type), listOf(ItemFields.PROVIDER_IDS, ItemFields.GENRES)).copy(genres = g?.let { listOf(it) }, years = if (type == BaseItemKind.MOVIE) years else null)
                for (it in query(req)) seen.putIfAbsent(it.id, it)
            }
            return seen.values.toList()
        }

        private suspend fun allEpisodes(u: String): List<BaseItemDto> =
            episodesLock.withLock {
                episodes?.takeIf { it.first == u && System.currentTimeMillis() - it.second.first < TTL_MS }?.let { return@withLock it.second.second }
                val l = query(base(listOf(BaseItemKind.EPISODE)))
                episodes = u to (System.currentTimeMillis() to l)
                l
            }

        private suspend fun fetch(
            def: ChannelDef,
            u: String,
            prof: TasteProfile?,
        ): Pair<List<ChanItem>, Int> =
            coroutineScope {
                val iptv = async { iptvPool(def, u, prof) }
                if (def.type == ChanType.MOVIE) {
                    val lib =
                        Channels.sample(
                            Channels.playable(
                                byGenres(def, BaseItemKind.MOVIE).mapNotNull { i ->
                                    val id = hex(i.id) ?: return@mapNotNull null
                                    ChanItem(
                                        id = id,
                                        name = i.name ?: "",
                                        type = "Movie",
                                        year = i.productionYear,
                                        runTimeTicks = i.runTimeTicks,
                                        tmdb = i.providerIds?.get("Tmdb"),
                                        genres = i.genres?.take(4),
                                        aff = prof?.let { Taste.affinity(it, i.genres, i.productionYear, true) },
                                    )
                                },
                                def,
                            ),
                            CAP,
                            def.id,
                        )
                    val v = iptv.await()
                    Channels.mixItems(lib, v.first) to v.second
                } else {
                    val shows = byGenres(def, BaseItemKind.SERIES).filter { def.yearTo == null || (it.productionYear != null && it.productionYear!! <= def.yearTo) }
                    val bySeries = shows.associateBy { hex(it.id)!! }
                    var lib: List<ChanItem> = emptyList()
                    if (bySeries.isNotEmpty()) {
                        val mine =
                            Channels
                                .playable(
                                    allEpisodes(u).mapNotNull { e ->
                                        val id = hex(e.id) ?: return@mapNotNull null
                                        ChanItem(
                                            id = id,
                                            name = e.name ?: "",
                                            type = "Episode",
                                            seriesName = e.seriesName,
                                            season = e.parentIndexNumber,
                                            episode = e.indexNumber,
                                            seriesId = hex(e.seriesId),
                                            year = e.productionYear,
                                            runTimeTicks = e.runTimeTicks,
                                        )
                                    },
                                    def,
                                ).filter { it.seriesId != null && it.seriesId in bySeries }
                        lib =
                            Channels.sampleByGroup(mine, { it.seriesId }, PER_SERIES, EPISODE_CAP, def.id).map { e ->
                                val s = bySeries.getValue(e.seriesId!!)
                                e.copy(
                                    seriesTmdb = s.providerIds?.get("Tmdb"),
                                    genres = s.genres?.take(4),
                                    aff = prof?.let { Taste.affinity(it, s.genres, s.productionYear, false) },
                                )
                            }
                    }
                    val v = iptv.await()
                    Channels.mixEpisodes(lib, v.first) to v.second
                }
            }

        private suspend fun iptvPool(
            def: ChannelDef,
            u: String,
            prof: TasteProfile?,
        ): Pair<List<ChanItem>, Int> {
            val tg = def.tmdbGenres
            if (tg.isNullOrEmpty() || (noIptv[u]?.let { System.currentTimeMillis() - it < NO_IPTV_TTL_MS } == true)) return emptyList<ChanItem>() to 0
            val episodes = def.type == ChanType.EPISODE
            val q = StringBuilder("v1/iptv/channel-pool?genres=${tg.joinToString("%2C")}&limit=200")
            if (episodes) {
                q.append("&kind=episode")
                def.minMinutes?.let { q.append("&runtimeMin=$it") }
                def.maxMinutes?.let { q.append("&runtimeMax=${minOf(100, it)}") }
            } else {
                def.yearFrom?.let { q.append("&yearFrom=$it") }
                def.yearTo?.let { q.append("&yearTo=$it") }
            }
            return try {
                val r = jellytipApi.userCall(q.toString(), timeoutMs = 20_000) ?: return emptyList<ChanItem>() to 0
                if (r.first in setOf(403, 404, 501)) noIptv[u] = System.currentTimeMillis()
                if (r.first != 200) return emptyList<ChanItem>() to 0
                val (items, pending) =
                    if (episodes) {
                        Channels.iptvEpisodeItems(r.second).let { (l, p) -> Channels.withinYearTo(l, def) to p }
                    } else {
                        Channels.iptvPoolItems(r.second)
                    }
                // The channel's genres stand in for the title's; the server's own affinity wins when it sends one
                items.map { i ->
                    i.copy(genres = def.genres, aff = i.aff ?: prof?.let { Taste.affinity(it, def.genres, i.year, !episodes) })
                } to pending
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "channel pool %s", def.id)
                emptyList<ChanItem>() to 0
            }
        }

        // ---------- chaining budget (sessionStorage on the web: process memory here) ----------
        private var chainStamps: List<Long> = emptyList()

        @Synchronized
        fun takeChainHop(): Boolean {
            val (ok, stamps) = Channels.chainBudget(chainStamps, System.currentTimeMillis())
            chainStamps = stamps
            return ok
        }

        companion object {
            private const val TTL_MS = 6 * 3600 * 1000L
            private const val PENDING_TTL_MS = 15 * 60 * 1000L
            private const val TASTE_TTL_MS = 30 * 60 * 1000L
            private const val NO_IPTV_TTL_MS = 15 * 60 * 1000L
            private const val CAP = 150
            private const val PER_SERIES = 15
            private const val EPISODE_CAP = 300
        }
    }
