package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.concurrent.ConcurrentHashMap

/*
 * Source-neutral opening of a title. A title that is in the library AND on IPTV has ONE page: the library's (Play, and
 * "Ways to watch" listing the IPTV copies). Every place that opens IPTV details from a tile goes through [detailsFor].
 *
 * Films only: the library series page has no "Ways to watch" row yet, so an IPTV series keeps opening its own page.
 * An explicit pick of one copy (the library page's "Ways to watch" tile, a copy picker) is NOT an open site: it navigates
 * to `Destination.JellytipIptvDetails` directly, otherwise it would bounce straight back.
 */
object JellytipTitleOpen {
    /** A library item id as the server sends it (with or without dashes), or null when blank / malformed. */
    fun libraryUuid(raw: String?): java.util.UUID? {
        val s = raw?.trim()?.replace("-", "")?.lowercase().orEmpty()
        if (s.length != 32 || s.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return runCatching {
            java.util.UUID.fromString("${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}")
        }.getOrNull()
    }

    /** The library copy of an IPTV title: `libraryId`, else the library entry of `sources`; null = none (or unusable). */
    fun libraryIdOf(
        libraryId: String?,
        sources: List<IptvSource>,
    ): java.util.UUID? = libraryUuid(libraryId) ?: sources.firstOrNull { it.type == "library" }?.let { libraryUuid(it.id) }

    /** Where OK on an IPTV title's tile goes: the library page when it has a library copy (films), else the IPTV page. */
    fun detailsFor(
        id: String,
        title: String,
        kind: String?,
        libraryId: String?,
        sources: List<IptvSource> = emptyList(),
    ): Destination {
        if (kind == "movie" || kind == "film") {
            libraryIdOf(libraryId, sources)?.let { return Destination.MediaItem(itemId = it, type = BaseItemKind.MOVIE) }
        }
        return Destination.JellytipIptvDetails(id, title)
    }

    fun detailsFor(item: IptvVodItem): Destination = detailsFor(item.id, item.title, item.kind, item.libraryId, item.sources)
}

/**
 * The IPTV copies of library titles the app has been told about (a row's `sourcesById`, the IPTV twins a merged tile folded
 * in). The library hold-OK menu reads it to offer "Play from IPTV"; nothing is fetched. Library id (dashes / case ignored)
 * -> the title's sources. Empty = unknown = no extra item.
 */
object JellytipTitleSources {
    private const val MAX = 4000
    private val byLibraryId = ConcurrentHashMap<String, List<IptvSource>>()

    /** The signed-in person's key (profile switches must not share copies); replaceable by a test. */
    internal var userKey: () -> String = { com.github.damontecres.wholphin.jellytip.JellytipApi.shared?.currentUserKey().orEmpty() }

    private fun key(id: String) = "${userKey()}|${id.replace("-", "").lowercase()}"

    fun put(
        libraryId: String,
        sources: List<IptvSource>,
    ) {
        val iptv = sources.filter { it.type == "iptv" }
        if (iptv.isEmpty()) return
        if (byLibraryId.size >= MAX) byLibraryId.clear()
        byLibraryId[key(libraryId)] = iptv
    }

    fun putAll(map: Map<String, List<IptvSource>>) = map.forEach { (id, s) -> put(id, s) }

    /** The IPTV copies of this library title; empty when none is known. */
    fun iptvFor(libraryId: String): List<IptvSource> = byLibraryId[key(libraryId)].orEmpty()

    fun clear() = byLibraryId.clear()
}
