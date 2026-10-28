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

        private val backdropCache = ConcurrentHashMap<UUID, Int>() // NO_BACKDROP_PICK = no usable answer
        private val backdropFailedAt = ConcurrentHashMap<UUID, Long>()

        /** Cached photo-ranked backdrop index for [itemId], or null if unknown / animation / no ranking. */
        fun peekBestBackdropIndex(itemId: UUID): Int? = backdropCache[itemId]?.takeIf { it >= 0 }

        /**
         * Index of the best real-photography backdrop (companion `/backdrops`), cached per item.
         * Null when unconfigured, failing, `animation` is true or `backdrops` is null; callers keep index 0.
         */
        suspend fun bestBackdropIndex(itemId: UUID): Int? {
            backdropCache[itemId]?.let { return it.takeIf { i -> i >= 0 } }
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
                        val root = JSONObject(response.body.string())
                        val arr = root.optJSONArray("backdrops")
                        val index =
                            if (root.optBoolean("animation", false) || arr == null || arr.length() == 0) {
                                -1
                            } else {
                                arr.optJSONObject(0)?.optInt("index", -1) ?: -1
                            }
                        backdropCache[itemId] = index
                        index.takeIf { it >= 0 }
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
        suspend fun getJson(path: String): String? =
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
                    client.newCall(request).execute().use { response ->
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

        /**
         * POST a JSON body to a companion API path. Always carries the Jellyfin session token (the server names the
         * requester from it) in addition to the manual token if one is set. Returns (http code, body) or null if unreachable.
         */
        suspend fun postJson(
            path: String,
            body: String,
        ): Pair<Int, String>? =
            withContext(WholphinDispatchers.IO) {
                try {
                    val prefs = preferences.data.first()
                    val base = prefs.jellytipApiUrl.ifBlank { JellytipPreferences.DEFAULT_API_URL }.trimEnd('/')
                    val request =
                        Request
                            .Builder()
                            .url("$base$path".toHttpUrlOrNull() ?: return@withContext null)
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .apply {
                                if (prefs.jellytipApiToken.isNotBlank()) header("X-Jellytip-Token", prefs.jellytipApiToken)
                                api.accessToken?.takeIf { it.isNotBlank() }?.let {
                                    header("Authorization", "MediaBrowser Token=\"$it\"")
                                }
                            }.build()
                    client.newCall(request).execute().use { it.code to it.body.string() }
                } catch (e: Exception) {
                    Timber.w(e, "Jellytip API %s unavailable", path)
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
        }
    }
