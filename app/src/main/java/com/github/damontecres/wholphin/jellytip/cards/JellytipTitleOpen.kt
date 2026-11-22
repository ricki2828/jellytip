package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.concurrent.ConcurrentHashMap

/*
 * Source-neutral opening of a title. A title that is in the library AND on IPTV has ONE page: the library's (films: Play and
 * "Ways to watch"; series: the split merge shows the episodes the library lacks as IPTV tiles). Every place that opens IPTV
 * details from a tile goes through [detailsFor]; anything else that navigates to an IPTV page is caught by [resolve]
 * (NavigationManager), from the cross-links the app has already been sent ([remember]).
 *
 * An explicit pick of one copy (the library page's "Ways to watch" tile) is NOT an open site: it navigates with
 * `JellytipIptvDetails(pick = true)`, otherwise it would bounce straight back.
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

    /** The library item kind of an IPTV kind (film / series); null = not a title kind. */
    fun libraryKind(kind: String?): BaseItemKind? =
        when (kind) {
            "movie", "film" -> BaseItemKind.MOVIE
            "series", "tv", "show" -> BaseItemKind.SERIES
            else -> null
        }

    /** Where OK on an IPTV title's tile goes: the library page when it has a library copy (films and series), else the IPTV page. */
    fun detailsFor(
        id: String,
        title: String,
        kind: String?,
        libraryId: String?,
        sources: List<IptvSource> = emptyList(),
    ): Destination {
        val k = libraryKind(kind) ?: kindOfId(id)
        val lib = libraryIdOf(libraryId, sources)
        if (k != null && lib != null) {
            remember(id, lib, k)
            return Destination.MediaItem(itemId = lib, type = k)
        }
        return Destination.JellytipIptvDetails(id, title)
    }

    fun detailsFor(item: IptvVodItem): Destination = detailsFor(item.id, item.title, item.kind, item.libraryId, item.sources)

    /** `pid:m:sid` = film, `pid:s:sid` = series (the IPTV id carries its kind). */
    fun kindOfId(id: String): BaseItemKind? =
        when (id.split(':').getOrNull(1)) {
            "m" -> BaseItemKind.MOVIE
            "s" -> BaseItemKind.SERIES
            else -> null
        }

    private const val MAX = 4000
    private val libraryByIptv = ConcurrentHashMap<String, Pair<java.util.UUID, BaseItemKind>>()

    /** The signed-in person's key (profile switches must not share links); replaceable by a test. */
    internal var userKey: () -> String = { com.github.damontecres.wholphin.jellytip.JellytipApi.shared?.currentUserKey().orEmpty() }

    /** An IPTV title the server said is also in this person's library (rows, search, My list, Continue watching). */
    fun remember(
        iptvId: String,
        libraryId: java.util.UUID,
        kind: BaseItemKind,
    ) {
        if (kind != BaseItemKind.MOVIE && kind != BaseItemKind.SERIES) return
        if (libraryByIptv.size >= MAX) libraryByIptv.clear()
        libraryByIptv["${userKey()}|$iptvId"] = libraryId to kind
    }

    /** [remember] from a parsed item's own fields (no-op without a usable library id). */
    fun rememberFrom(
        iptvId: String,
        kind: String?,
        libraryId: String?,
        sources: List<IptvSource>,
    ) {
        val k = libraryKind(kind) ?: kindOfId(iptvId) ?: return
        val lib = libraryIdOf(libraryId, sources)
        when {
            lib != null -> remember(iptvId, lib, k)
            // The server listed this title's sources and there is no library one now (copy removed): forget the old link
            sources.isNotEmpty() -> libraryByIptv.remove("${userKey()}|$iptvId")
        }
    }

    /** The library page of an IPTV title the app knows has one; null = none known. */
    fun libraryPageFor(iptvId: String): Destination.MediaItem? =
        libraryByIptv["${userKey()}|$iptvId"]?.let { (lib, k) -> Destination.MediaItem(itemId = lib, type = k) }

    /**
     * The safety net under every IPTV page open: an IPTV title with a known library copy opens the library page instead,
     * unless the person picked that IPTV copy on purpose ([Destination.JellytipIptvDetails.pick]).
     */
    fun resolve(d: Destination): Destination =
        if (d is Destination.JellytipIptvDetails && !d.pick) libraryPageFor(d.id) ?: d else d

    fun clear() = libraryByIptv.clear()
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
