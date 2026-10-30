package com.github.damontecres.wholphin.jellytip.mylist

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import com.github.damontecres.wholphin.jellytip.iptv.JellytipIptvVodService
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One My list entry (`GET /v1/me/list`, plan "My list"): a library title or an IPTV title, newest-favourited first. */
sealed interface JellytipMyListEntry {
    data class Library(
        val id: UUID,
    ) : JellytipMyListEntry

    data class Iptv(
        val item: IptvVodItem,
    ) : JellytipMyListEntry
}

object JellytipMyListParse {
    /** `{items: [...]}` or a bare array; `source: "iptv"` entries are IPTV vod items, everything else a library id. */
    fun parse(
        json: String,
        base: String,
    ): List<JellytipMyListEntry> {
        val t = json.trimStart()
        val a = if (t.startsWith("[")) JSONArray(json) else JSONObject(json).optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("source") == "iptv") {
                IptvVodParse.item(o, base)?.let { JellytipMyListEntry.Iptv(it) }
            } else {
                runCatching { o.optString("id").toUUID() }.getOrNull()?.let { JellytipMyListEntry.Library(it) }
            }
        }
    }

    /** A mixed row's tiles in [order] (library uuids with or without dashes, IPTV vod ids); leftovers keep their place after. */
    fun <T> ordered(
        order: List<String>,
        library: List<BaseItem>,
        iptv: List<IptvVodItem>,
        lib: (BaseItem) -> T,
        vod: (IptvVodItem) -> T,
    ): List<T> {
        if (order.isEmpty()) return library.map(lib) + iptv.map(vod)
        val libById = library.associateBy { it.id.toString().replace("-", "") }
        val vodById = iptv.associateBy { it.id }
        val used = HashSet<String>()
        val out = ArrayList<T>()
        for (key in order) {
            val k = key.replace("-", "").lowercase()
            val l = libById[k]
            if (l != null && used.add("l:$k")) {
                out += lib(l)
                continue
            }
            vodById[key]?.let { if (used.add("v:$key")) out += vod(it) }
        }
        library.filter { "l:${it.id.toString().replace("-", "")}" !in used }.forEach { out += lib(it) }
        iptv.filter { "v:${it.id}" !in used }.forEach { out += vod(it) }
        return out
    }
}

@Singleton
class JellytipMyListService
    @Inject
    constructor(
        private val api: JellytipApi,
        private val vod: JellytipIptvVodService,
    ) {
        /** My list for [type] (`movie` | `series`); null = route missing (older server) or unreachable. */
        suspend fun list(
            type: String,
            limit: Int = 40,
        ): List<JellytipMyListEntry>? {
            val base = vod.base()
            val (code, body) = api.userCall("v1/me/list?type=$type&limit=$limit") ?: return null
            if (code != 200) return null
            return runCatching { JellytipMyListParse.parse(body, base) }.getOrNull()
        }
    }
