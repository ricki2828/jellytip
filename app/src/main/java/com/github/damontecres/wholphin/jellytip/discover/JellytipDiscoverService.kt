package com.github.damontecres.wholphin.jellytip.discover

import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A title that is not in the library yet (TMDB metadata from the companion's Discover rows). */
@Serializable
data class DiscoverTitle(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    val rating: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
) {
    val key: String get() = "$mediaType:$tmdbId"
    val isTv: Boolean get() = mediaType == "tv"
}

/** A Discover browse tile: [state] = request progress, [jellyfinId] when it is already in the library. */
data class JellytipDiscoverEntry(
    val title: DiscoverTitle,
    val inLibrary: Boolean,
    val jellyfinId: java.util.UUID?,
    val state: RequestState?,
) {
    /** Badge text: In library / Requested / Downloading n%. */
    val badge: String? get() = if (inLibrary) "In library" else state?.label
}

data class DiscoverRow(
    val id: String,
    val title: String,
    val library: String,
    val items: List<DiscoverTitle>,
)

/** none | requested | downloading | in_library. [progress] is a percentage while downloading. */
data class RequestState(
    val state: String,
    val progress: Int? = null,
) {
    val label: String?
        get() =
            when (state) {
                "requested" -> "Requested"
                "downloading" -> if (progress != null) "Downloading $progress%" else "Downloading"
                "in_library" -> "In library"
                else -> null
            }
}

/** Answer of POST /v1/request (also its dry run): status is added | exists | blocked | error. */
data class RequestOutcome(
    val status: String,
    val reason: String,
    val label: String?,
    val estimateGb: Double?,
    val freeGbNow: Double?,
    val freeGbAfter: Double?,
    /** Series: episodes the request covers (only the missing ones when it extends a series Sonarr has). */
    val episodes: Int? = null,
)

object JellytipDiscoverParser {
    fun rows(json: String): List<DiscoverRow> {
        val arr = JSONObject(json).optJSONArray("carousels") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optBoolean("discover", false)) return@mapNotNull null
            val items =
                o.optJSONArray("items")?.let { a ->
                    (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let(::title) }
                }.orEmpty()
            if (items.isEmpty()) return@mapNotNull null
            DiscoverRow(
                id = o.optString("id"),
                title = o.optString("title").ifBlank { o.optString("name") },
                library = o.optString("library"),
                items = items,
            )
        }
    }

    /** A `/v1/discover…` item: the title plus whether it is in the library / requested. */
    fun entry(o: JSONObject): JellytipDiscoverEntry? {
        val t = title(o) ?: return null
        val req = o.optJSONObject("requested")
        return JellytipDiscoverEntry(
            title = t,
            inLibrary = o.optBoolean("inLibrary", false),
            jellyfinId = o.optString("jellyfinId").takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { it.toUUID() }.getOrNull() },
            state = req?.let { RequestState(it.optString("state"), if (it.isNull("progress")) null else it.optInt("progress")) },
        )
    }

    fun entries(json: String): List<JellytipDiscoverEntry> {
        val a = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::entry) }
    }

    fun genres(json: String): List<Pair<Int, String>> {
        val a = JSONObject(json).optJSONArray("genres") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val g = a.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            id to g.optString("name")
        }
    }

    private fun title(o: JSONObject): DiscoverTitle? {
        val id = o.optInt("tmdbId", 0)
        val name = o.optString("title")
        if (id <= 0 || name.isBlank()) return null
        val type = if (o.optString("mediaType") == "movie") "movie" else "tv"
        return DiscoverTitle(
            tmdbId = id,
            mediaType = type,
            title = name,
            year = if (o.isNull("year")) null else o.optInt("year").takeIf { it > 0 },
            overview = o.optString("overview"),
            rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { it > 0 },
            posterUrl = o.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" },
            backdropUrl = o.optString("backdropUrl").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    fun statuses(json: String): Map<String, RequestState> {
        val o = JSONObject(json).optJSONObject("status") ?: return emptyMap()
        return o.keys().asSequence().associateWith { k ->
            val s = o.optJSONObject(k)
            RequestState(s?.optString("state").orEmpty(), if (s == null || s.isNull("progress")) null else s.optInt("progress"))
        }
    }

    fun outcome(
        code: Int,
        json: String,
    ): RequestOutcome {
        val o = runCatching { JSONObject(json) }.getOrNull()
        fun num(k: String) = if (o == null || o.isNull(k)) null else o.optDouble(k)
        val status = o?.optString("status").orEmpty().ifBlank { "error" }
        return RequestOutcome(
            status = status,
            reason = o?.optString("reason")?.takeIf { it.isNotBlank() } ?: o?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $code",
            label = o?.optString("label")?.takeIf { it.isNotBlank() },
            estimateGb = num("estimateGb"),
            freeGbNow = num("freeGbNow"),
            freeGbAfter = num("freeGbAfter"),
            episodes = o?.takeUnless { it.isNull("episodes") }?.optInt("episodes", 0)?.takeIf { it > 0 },
        )
    }
}

/**
 * Discover rows and request state from the companion API. In-memory only; everything fails soft (no rows, no badges).
 */
@Singleton
class JellytipDiscoverService
    @Inject
    constructor(
        private val api: JellytipApi,
    ) {
        private val _rows = MutableStateFlow<List<DiscoverRow>>(emptyList())
        val rows: StateFlow<List<DiscoverRow>> = _rows.asStateFlow()
        private val _statuses = MutableStateFlow<Map<String, RequestState>>(emptyMap())
        val statuses: StateFlow<Map<String, RequestState>> = _statuses.asStateFlow()
        private var rowsAt = 0L

        suspend fun loadRows() {
            if (_rows.value.isNotEmpty() && System.currentTimeMillis() - rowsAt < ROWS_TTL_MS) return
            val json = api.getJson("/v1/home/carousels?includeDiscover=1") ?: return
            withContext(WholphinDispatchers.IO) {
                try {
                    _rows.value = JellytipDiscoverParser.rows(json)
                    rowsAt = System.currentTimeMillis()
                } catch (e: Exception) {
                    Timber.w(e, "Bad discover manifest")
                }
            }
        }

        /** Refresh badges for [titles]. Callers poll this at most once a minute while the tiles are on screen. */
        suspend fun refreshStatuses(titles: Collection<DiscoverTitle>) {
            if (titles.isEmpty()) return
            val ids = titles.map { it.key }.distinct().take(MAX_STATUS_IDS).joinToString(",")
            val json = api.getJson("/v1/request/status?tmdbIds=$ids") ?: return
            try {
                val fresh = JellytipDiscoverParser.statuses(json)
                val stale = ids.split(",").toSet()
                _statuses.update { it.filterKeys { k -> k !in stale } + fresh }
            } catch (e: Exception) {
                Timber.w(e, "Bad request status")
            }
        }

        /** Dry run: the server checks space and returns the estimate without adding anything. */
        suspend fun preview(
            title: DiscoverTitle,
            seasons: String? = null,
        ): RequestOutcome = send(title, dryRun = true, seasons = seasons)

        suspend fun request(
            title: DiscoverTitle,
            seasons: String? = null,
        ): RequestOutcome {
            val outcome = send(title, dryRun = false, seasons = seasons)
            if (outcome.status == "added" || outcome.status == "exists") refreshStatuses(listOf(title))
            return outcome
        }

        /** `GET /v1/discover?section=…` (admin only). Null = unavailable (not admin, offline). */
        suspend fun section(
            section: String,
            media: String,
            genre: Int? = null,
        ): List<JellytipDiscoverEntry>? {
            val path = "/v1/discover?section=$section&media=$media" + (genre?.let { "&genre=$it" } ?: "")
            val json = api.getJson(path, timeoutMs = DISCOVER_TIMEOUT_MS, asUser = true) ?: return null
            return runCatching { JellytipDiscoverParser.entries(json) }.getOrNull()
        }

        suspend fun genres(media: String): List<Pair<Int, String>> {
            val json = api.getJson("/v1/discover/genres?media=$media", timeoutMs = DISCOVER_TIMEOUT_MS, asUser = true) ?: return emptyList()
            return runCatching { JellytipDiscoverParser.genres(json) }.getOrDefault(emptyList())
        }

        suspend fun search(q: String): List<JellytipDiscoverEntry>? {
            if (q.trim().length < 2) return emptyList()
            val enc = java.net.URLEncoder.encode(q.trim(), "UTF-8")
            val json = api.getJson("/v1/discover/search?q=$enc", timeoutMs = DISCOVER_TIMEOUT_MS, asUser = true) ?: return null
            return runCatching { JellytipDiscoverParser.entries(json) }.getOrNull()
        }

        private suspend fun send(
            title: DiscoverTitle,
            dryRun: Boolean,
            seasons: String? = null,
        ): RequestOutcome {
            val body =
                JSONObject()
                    .put("tmdbId", title.tmdbId)
                    .put("type", title.mediaType)
                    .apply { if (title.isTv && seasons != null) put("seasons", seasons) }
                    .toString()
            val res =
                api.postJson("/v1/request" + if (dryRun) "?dryRun=1" else "", body)
                    ?: return RequestOutcome("error", "Could not reach the server", null, null, null, null)
            return JellytipDiscoverParser.outcome(res.first, res.second)
        }

        companion object {
            private const val ROWS_TTL_MS = 10 * 60 * 1000L
            private const val MAX_STATUS_IDS = 200
            private const val DISCOVER_TIMEOUT_MS = 8_000L
        }
    }
