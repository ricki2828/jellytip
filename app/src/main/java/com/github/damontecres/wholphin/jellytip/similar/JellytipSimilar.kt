package com.github.damontecres.wholphin.jellytip.similar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListEntry
import com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRowView
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.model.api.BaseItemKind
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * "More like this" (web 847f8810, contract ~/ai/state/jellytip-similar-contract.md): the companion's `GET /v1/similar`
 * mix of library and IPTV titles, ranked server-side, drawn as a server row. Fails soft: null = keep Jellyfin's list
 * (library details) or no row (IPTV details).
 */
object JellytipSimilar {
    /** Same as the web: short-lived so a title just finished or thumbed down drops out soon. */
    private const val TTL_MS = 2 * 60_000L
    private const val MAX_ITEMS = 20

    private val cache = ConcurrentHashMap<String, Pair<Long, JellytipServerRow?>>()

    /** `v1/similar?kind=…` for a seed, or null when there is nothing to ask with. */
    fun path(
        kind: String,
        tmdb: Int?,
        iptvId: String? = null,
        jfId: String? = null,
    ): String? {
        val parts = mutableListOf("kind=${if (kind == "series") "series" else "movie"}")
        tmdb?.takeIf { it > 0 }?.let { parts += "tmdb=$it" }
        iptvId?.takeIf { it.isNotBlank() }?.let { parts += "iptvId=${URLEncoder.encode(it, "UTF-8")}" }
        jfId?.takeIf { it.isNotBlank() }?.let { parts += "jfId=${it.replace("-", "")}" }
        return if (parts.size > 1) "v1/similar?${parts.joinToString("&")}" else null
    }

    /** First [MAX_ITEMS] distinct entries, server order kept: the keys used as the row's display order. */
    fun orderKeys(keys: List<String>): List<String> {
        val seen = HashSet<String>()
        return keys.filter { seen.add(it.replace("-", "").lowercase()) }.take(MAX_ITEMS)
    }

    /** Seed query for a library title: movies ask as movies; series, seasons and episodes ask for their series. */
    fun queryFor(item: BaseItem): String? {
        val kind = item.type
        val series = kind == BaseItemKind.SERIES || kind == BaseItemKind.SEASON || kind == BaseItemKind.EPISODE
        if (!series && kind != BaseItemKind.MOVIE) return null
        val own = kind == BaseItemKind.SERIES || kind == BaseItemKind.MOVIE
        val jfId = if (own) item.id.toString() else item.data.seriesId?.toString()
        val tmdb =
            if (own) {
                item.data.providerIds
                    ?.entries
                    ?.firstOrNull { it.key.equals("Tmdb", true) }
                    ?.value
                    ?.toIntOrNull()
            } else {
                null
            }
        if (jfId == null && tmdb == null) return null
        return path(if (series) "series" else "movie", tmdb, jfId = jfId)
    }

    /** The row for [path]; null on any failure or an empty answer. */
    suspend fun row(path: String): JellytipServerRow? {
        cache[path]?.let { (at, v) -> if (System.currentTimeMillis() - at < TTL_MS) return v }
        val api = JellytipApi.shared ?: return null
        val result =
            try {
                val (code, body) = api.userCall(path) ?: return null // unreachable: not cached, retried next open
                if (code != 200) {
                    null
                } else {
                    val base = api.companionBase().also { IptvVodParse.imageBase = it }
                    parse(body, base, path)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        cache[path] = System.currentTimeMillis() to result
        return result
    }

    /** Thumbs / plays change the answer: drop cached rows. */
    fun invalidate() = cache.clear()

    internal fun parse(
        body: String,
        base: String,
        path: String,
    ): JellytipServerRow? {
        // Same item shape as My list: `source:"library"` ids and IPTV VodItems
        val entries = JellytipMyListParse.parse(body, base)
        val keyed =
            entries.map {
                when (it) {
                    is JellytipMyListEntry.Library -> it.id.toString()
                    is JellytipMyListEntry.Iptv -> it.item.id
                }
            }
        val order = orderKeys(keyed)
        if (order.isEmpty()) return null
        val keep = order.map { it.replace("-", "").lowercase() }.toHashSet()
        val lib =
            entries
                .filterIsInstance<JellytipMyListEntry.Library>()
                .map { it.id }
                .distinct()
                .filter { it.toString().replace("-", "").lowercase() in keep }
        val iptv =
            entries
                .filterIsInstance<JellytipMyListEntry.Iptv>()
                .map { it.item }
                .distinctBy { it.id }
                .filter { it.id.replace("-", "").lowercase() in keep }
        val series = path.contains("kind=series")
        return JellytipServerRow(
            id = "similar:${path.hashCode()}",
            source = "jellytip",
            family = "similar",
            title = "More like this",
            layout = "standard",
            mediaType = if (series) "tv" else "movie",
            itemIds = lib,
            collection = null,
            iptvItems = iptv,
            order = order,
        )
    }
}

/** The "More like this" row on a details page, drawn by the Home server-row code (library + IPTV tiles). */
@Composable
fun JellytipSimilarRowView(
    row: JellytipServerRow,
    modifier: Modifier = Modifier,
) {
    JellytipServerRowView(
        row = row,
        onFocusItem = { _, _ -> },
        onLongClickItem = null,
        focusModifier = modifier,
    )
}
