package com.github.damontecres.wholphin.jellytip

import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class JellytipScore(
    val source: String,
    val label: String,
    val value: Double,
    val max: Double?,
    val votes: Long?,
    val url: String?,
)

data class JellytipLetterboxd(
    val myRating: Double?,
    val liked: Boolean,
    val watchedDates: List<String>,
    val rewatch: Boolean,
    val url: String?,
)

data class JellytipStill(
    val url: String,
    val width: Int?,
    val height: Int?,
    val lang: String?,
)

data class JellytipExtras(
    val scores: List<JellytipScore> = emptyList(),
    val letterboxd: JellytipLetterboxd? = null,
    val stills: List<JellytipStill> = emptyList(),
) {
    companion object {
        fun parse(json: String): JellytipExtras {
            val root = JSONObject(json)
            val scores =
                root.optJSONArray("scores")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        if (o.isNull("value")) return@mapNotNull null
                        val source = o.optString("source")
                        JellytipScore(
                            source = source,
                            label = o.optString("label", source),
                            value = o.optDouble("value"),
                            max = if (o.isNull("max")) null else o.optDouble("max"),
                            votes = if (o.isNull("votes")) null else o.optLong("votes"),
                            url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                        )
                    }
                }.orEmpty()
            val lb =
                root.optJSONObject("letterboxd")?.let { o ->
                    JellytipLetterboxd(
                        myRating = if (o.isNull("myRating")) null else o.optDouble("myRating"),
                        liked = o.optBoolean("liked", false),
                        watchedDates =
                            o.optJSONArray("watchedDates")?.let { a ->
                                (0 until a.length()).map { a.optString(it) }
                            }.orEmpty(),
                        rewatch = o.optBoolean("rewatch", false),
                        url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            val stills =
                root.optJSONArray("stills")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val url = o.optString("url").takeIf { it.isNotBlank() && it != "null" }
                        url?.let {
                            JellytipStill(
                                url = it,
                                width = if (o.isNull("width")) null else o.optInt("width"),
                                height = if (o.isNull("height")) null else o.optInt("height"),
                                lang = o.optString("lang").takeIf { l -> l.isNotBlank() && l != "null" },
                            )
                        }
                    }
                }.orEmpty()
            return JellytipExtras(scores, lb, stills)
        }
    }
}

/**
 * Client for the Jellytip companion service. Everything here fails soft: any problem returns null.
 */
@Singleton
class JellytipApi
    @Inject
    constructor(
        @param:StandardOkHttpClient private val baseClient: OkHttpClient,
        private val preferences: DataStore<AppPreferences>,
        private val api: ApiClient,
    ) {
        private data class Entry(
            val value: JellytipExtras?,
            val at: Long,
        )

        private val cache = HashMap<UUID, Entry>()
        private val mutex = Mutex()

        private val client by lazy {
            baseClient
                .newBuilder()
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }

        /**
         * Fetch extras for the item, cached in memory. Returns null if unconfigured, unreachable or slow.
         */
        suspend fun getExtras(item: BaseItem): JellytipExtras? {
            val type =
                when (item.type) {
                    BaseItemKind.MOVIE -> "movie"
                    BaseItemKind.SERIES -> "series"
                    else -> return null
                }
            return try {
                mutex.withLock {
                    val now = System.currentTimeMillis()
                    val cached = cache[item.id]
                    // Failures are remembered briefly so a dead API doesn't cost 3s per visit
                    if (cached != null && (cached.value != null || now - cached.at < FAILURE_TTL_MS)) {
                        return cached.value
                    }
                    val result = withTimeoutOrNull(TIMEOUT_MS) { fetch(item, type) }
                    cache[item.id] = Entry(result, now)
                    result
                }
            } catch (e: Exception) {
                Timber.w(e, "Jellytip extras failed")
                null
            }
        }

        private suspend fun fetch(
            item: BaseItem,
            type: String,
        ): JellytipExtras? =
            withContext(WholphinDispatchers.IO) {
                try {
                    val prefs = preferences.data.first()
                    val base = prefs.jellytipApiUrl.ifBlank { JellytipPreferences.DEFAULT_API_URL }.trimEnd('/')
                    if (base.isBlank()) return@withContext null
                    val providerIds = item.data.providerIds.orEmpty()
                    val url =
                        "$base/v1/items/${item.id}/extras".toHttpUrlOrNull()?.newBuilder()?.apply {
                            addQueryParameter("tmdb", providerIds.entries.firstOrNull { it.key.equals("Tmdb", true) }?.value.orEmpty())
                            addQueryParameter("imdb", providerIds.entries.firstOrNull { it.key.equals("Imdb", true) }?.value.orEmpty())
                            addQueryParameter("type", type)
                        }?.build() ?: return@withContext null
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .apply {
                                if (prefs.jellytipApiToken.isNotBlank()) {
                                    header("X-Jellytip-Token", prefs.jellytipApiToken)
                                } else {
                                    // No manual token: authenticate with the current Jellyfin session
                                    api.accessToken?.takeIf { it.isNotBlank() }?.let {
                                        header("Authorization", "MediaBrowser Token=\"$it\"")
                                    }
                                }
                            }.build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("Jellytip API returned %s", response.code)
                            return@withContext null
                        }
                        response.body.string().let { JellytipExtras.parse(it) }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Jellytip API unavailable")
                    null
                }
            }

        init {
            shared = this
        }

        private val infoCache = ConcurrentHashMap<UUID, JellytipBackdropInfo>()
        private val backdropFailedAt = ConcurrentHashMap<UUID, Long>()
        private val episodeUsable = ConcurrentHashMap<UUID, Boolean>()
        private val episodeParentsLoaded = ConcurrentHashMap.newKeySet<UUID>()

        /** Cached companion answer for [itemId], or null if not fetched (yet) / unreachable. */
        fun peekInfo(itemId: UUID): JellytipBackdropInfo? = infoCache[itemId]

        /** Cached companion `heroIndex` for [itemId], or null if unknown. */
        fun peekHeroIndex(itemId: UUID): Int? = infoCache[itemId]?.heroIndex

        /** Cached companion `detailsIndex` for [itemId], or null if unknown. */
        fun peekDetailsIndex(itemId: UUID): Int? = infoCache[itemId]?.detailsIndex

        /** Companion `heroIndex` (a backdrop that does not look like the item's card art); one request shared with [bestBackdropIndex]. */
        suspend fun heroBackdropIndex(itemId: UUID): Int? = backdropInfo(itemId)?.heroIndex

        /** Cached photo-ranked backdrop index for [itemId], or null if unknown / animation / no ranking. */
        fun peekBestBackdropIndex(itemId: UUID): Int? = infoCache[itemId]?.best?.takeIf { it >= 0 }

        /** True unless the companion scored the item's Thumb (titled art) as poor; unknown counts as good. */
        fun isThumbGood(itemId: UUID): Boolean = infoCache[itemId]?.thumbGood ?: true

        /** True unless the companion marked the episode's Primary still unusable (black, blurry, not a photo); unknown counts as usable. */
        fun isEpisodeStillUsable(episodeId: UUID): Boolean = episodeUsable[episodeId] ?: true

        /** Fetch (once per series/season) the usable flags of its episode stills so [isEpisodeStillUsable] can answer. */
        suspend fun loadEpisodeArt(parentId: UUID) {
            if (!episodeParentsLoaded.add(parentId)) return
            val body = getJson("/v1/items/$parentId/episodes-art")
            if (body == null) {
                episodeParentsLoaded.remove(parentId)
                return
            }
            try {
                val arr = org.json.JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = parseUuid(o.getString("episodeId")) ?: continue
                    episodeUsable[id] = o.optBoolean("usable", true)
                }
            } catch (e: Exception) {
                Timber.w(e, "episodes-art parse failed")
            }
        }

        private fun parseUuid(raw: String): UUID? =
            try {
                UUID.fromString(raw.replaceFirst(Regex("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})"), "$1-$2-$3-$4-$5"))
            } catch (e: Exception) {
                null
            }

        /**
         * Index of the best real-photography backdrop (companion `/backdrops`), cached per item.
         * Null when unconfigured, failing, `animation` is true or `backdrops` is null; callers keep index 0.
         */
        suspend fun bestBackdropIndex(itemId: UUID): Int? = backdropInfo(itemId)?.best?.takeIf { it >= 0 }

        /** Companion `/backdrops` answer for [itemId] (cached; failures are retried after a minute). */
        suspend fun backdropInfo(itemId: UUID): JellytipBackdropInfo? {
            infoCache[itemId]?.let { return it }
            val failed = backdropFailedAt[itemId]
            if (failed != null && System.currentTimeMillis() - failed < FAILURE_TTL_MS) return null
            return withContext(WholphinDispatchers.IO) {
                try {
                    val prefs = preferences.data.first()
                    val base = prefs.jellytipApiUrl.ifBlank { JellytipPreferences.DEFAULT_API_URL }.trimEnd('/')
                    if (base.isBlank()) return@withContext null
                    val request =
                        Request
                            .Builder()
                            .url("$base/v1/items/$itemId/backdrops")
                            .apply {
                                if (prefs.jellytipApiToken.isNotBlank()) {
                                    header("X-Jellytip-Token", prefs.jellytipApiToken)
                                } else {
                                    api.accessToken?.takeIf { it.isNotBlank() }?.let {
                                        header("Authorization", "MediaBrowser Token=\"$it\"")
                                    }
                                }
                            }.build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            backdropFailedAt[itemId] = System.currentTimeMillis()
                            return@withContext null
                        }
                        val info = JellytipBackdropInfo.parse(response.body.string())
                        infoCache[itemId] = info
                        info
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Jellytip backdrops unavailable")
                    backdropFailedAt[itemId] = System.currentTimeMillis()
                    null
                }
            }
        }

        /**
         * GET a companion API path (e.g. `/v1/home/carousels`) and return the body, or null on any failure.
         * Same auth as [getExtras]: the manual token if set, else the current Jellyfin session.
         */
        suspend fun getJson(
            path: String,
            timeoutMs: Long = TIMEOUT_MS,
        ): String? =
            withContext(WholphinDispatchers.IO) {
                try {
                    val prefs = preferences.data.first()
                    val base = prefs.jellytipApiUrl.ifBlank { JellytipPreferences.DEFAULT_API_URL }.trimEnd('/')
                    val request =
                        Request
                            .Builder()
                            .url("$base$path".toHttpUrlOrNull() ?: return@withContext null)
                            .apply {
                                if (prefs.jellytipApiToken.isNotBlank()) {
                                    header("X-Jellytip-Token", prefs.jellytipApiToken)
                                } else {
                                    api.accessToken?.takeIf { it.isNotBlank() }?.let {
                                        header("Authorization", "MediaBrowser Token=\"$it\"")
                                    }
                                }
                            }.build()
                    client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("Jellytip API %s returned %s", path, response.code)
                            return@withContext null
                        }
                        response.body.string()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Jellytip API %s unavailable", path)
                    null
                }
            }

        // The server builds its Plex history index on the first call, which can exceed the 3 s used elsewhere
        private val audienceClient by lazy { baseClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }
        private val audienceCache = ConcurrentHashMap<UUID, Pair<Long, JellytipAudience>>()

        /**
         * Admin-only: who requested / watched [item]. Always authenticates with the Jellyfin session (the shared
         * app token is refused by the server). Null for non-admins, other item kinds, or any failure. Cached 10 min.
         */
        suspend fun getAudience(item: BaseItem): JellytipAudience? {
            if (!JellytipAdmin.isAdmin) return null
            if (item.type !in AUDIENCE_KINDS) return null
            audienceCache[item.id]?.let { (at, value) -> if (System.currentTimeMillis() - at < AUDIENCE_TTL_MS) return value }
            val token = api.accessToken?.takeIf { it.isNotBlank() } ?: return null
            return withContext(WholphinDispatchers.IO) {
                try {
                    val prefs = preferences.data.first()
                    val base = prefs.jellytipApiUrl.ifBlank { JellytipPreferences.DEFAULT_API_URL }.trimEnd('/')
                    val request =
                        Request
                            .Builder()
                            .url("$base/v1/items/${item.id}/audience".toHttpUrlOrNull() ?: return@withContext null)
                            .header("Authorization", "MediaBrowser Token=\"$token\"")
                            .build()
                    audienceClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("Jellytip audience returned %s", response.code)
                            return@withContext null
                        }
                        JellytipAudience.parse(response.body.string()).also {
                            audienceCache[item.id] = System.currentTimeMillis() to it
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Jellytip audience unavailable")
                    null
                }
            }
        }

        /**
         * POST a JSON body to a companion API path and return the response body, or null on any failure.
         * Same auth as [getJson].
         */
        suspend fun postJson(
            path: String,
            body: JSONObject,
        ): String? =
            withContext(WholphinDispatchers.IO) {
                try {
                    val prefs = preferences.data.first()
                    val base = prefs.jellytipApiUrl.ifBlank { JellytipPreferences.DEFAULT_API_URL }.trimEnd('/')
                    val request =
                        Request
                            .Builder()
                            .url("$base$path".toHttpUrlOrNull() ?: return@withContext null)
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .apply {
                                if (prefs.jellytipApiToken.isNotBlank()) {
                                    header("X-Jellytip-Token", prefs.jellytipApiToken)
                                } else {
                                    api.accessToken?.takeIf { it.isNotBlank() }?.let {
                                        header("Authorization", "MediaBrowser Token=\"$it\"")
                                    }
                                }
                            }.build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("Jellytip API POST %s returned %s", path, response.code)
                            return@withContext null
                        }
                        response.body.string()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Jellytip API POST %s unavailable", path)
                    null
                }
            }

        companion object {
            /** Set when Hilt builds the singleton, so composables can reach it without plumbing. */
            @Volatile
            var shared: JellytipApi? = null
                private set

            private const val TIMEOUT_MS = 3_000L
            private const val FAILURE_TTL_MS = 60_000L
            private const val AUDIENCE_TTL_MS = 10 * 60_000L
            private val AUDIENCE_KINDS = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)
        }
    }

/** Where the subject sits in an image (0..1); [face] = from a face detector, else saliency. */
data class JellytipFocal(
    val x: Float,
    val y: Float,
    val face: Boolean,
)

/** Companion `/v1/items/{id}/backdrops` answer. [best] = photo-ranked index (-1: none/animation). */
data class JellytipBackdropInfo(
    val best: Int,
    val heroIndex: Int?,
    val detailsIndex: Int?,
    val thumbGood: Boolean,
    val focal: Map<Int, JellytipFocal>,
) {
    companion object {
        fun parse(json: String): JellytipBackdropInfo {
            val root = JSONObject(json)
            val arr = root.optJSONArray("backdrops")
            val best =
                if (root.optBoolean("animation", false) || arr == null || arr.length() == 0) {
                    -1
                } else {
                    arr.optJSONObject(0)?.optInt("index", -1) ?: -1
                }
            val focal = mutableMapOf<Int, JellytipFocal>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.isNull("focalX") || o.isNull("focalY")) continue
                    focal[o.optInt("index", i)] =
                        JellytipFocal(o.optDouble("focalX").toFloat(), o.optDouble("focalY").toFloat(), o.optBoolean("face", false))
                }
            }
            fun idx(key: String) = if (root.isNull(key)) null else root.optInt(key, -1).takeIf { it >= 0 }
            val thumb = root.optJSONObject("thumb")
            return JellytipBackdropInfo(best, idx("heroIndex"), idx("detailsIndex"), thumb?.optBoolean("good", true) ?: true, focal)
        }
    }
}
