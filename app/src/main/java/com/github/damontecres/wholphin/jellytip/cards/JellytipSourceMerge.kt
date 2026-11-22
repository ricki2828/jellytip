package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvResume
import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import org.jellyfin.sdk.model.api.BaseItemKind

/*
 * Source-neutral lists: a title that is in the library AND on IPTV is ONE tile. Pure Kotlin (no Compose), unit tested.
 *
 * The library tile wins. An IPTV entry is dropped when
 *  (a) its `libraryId` is a library entry of the same list, or
 *  (b) its `sources` has a library source whose id is a library entry of the same list, or
 *  (c) when neither field is present: same kind + same (non-null) TMDB id as a library entry of the same list.
 * Two IPTV entries with the same kind + TMDB id: the first stays. Unknown ids never drop anything, and a list in which no
 * rule fires comes back unchanged (same entries, same order).
 *
 * What was dropped is not lost: [Merged.otherSources] answers "what else can this tile be played from" (the IPTV copies
 * riding on a library tile, from the server's `sourcesById` and from the dropped twins). Nothing draws it yet.
 */
object JellytipSourceMerge {
    /** Library ids with or without dashes compare the same way. */
    fun norm(id: String): String = id.replace("-", "").lowercase()

    /** Key of a library entry in [Merged.others]. */
    fun libKey(id: String): String = "l:${norm(id)}"

    /** Key of an IPTV entry in [Merged.others]. */
    fun iptvKey(id: String): String = "v:$id"

    /** A library entry as the merge sees it. [kind] movie | series | episode (null = unknown); only movie / series match by TMDB. */
    data class LibRef(
        val id: String,
        val kind: String?,
        val tmdbId: Int? = null,
        /** Episodes only: the TMDB id of the episode's series (Continue Watching). Library episode items do not carry it, so callers pass null. */
        val seriesTmdbId: Int? = null,
    )

    /** An IPTV entry as the merge sees it. */
    data class IptvRef(
        val id: String,
        /** movie | series */
        val kind: String?,
        val tmdbId: Int?,
        val libraryId: String? = null,
        val sources: List<IptvSource> = emptyList(),
        val quality: List<String> = emptyList(),
        val tech: List<String> = emptyList(),
    )

    /** One entry of a list: exactly one of [lib] / [iptv] is set; [payload] is whatever the caller draws. */
    data class Entry<T>(
        val payload: T,
        val lib: LibRef? = null,
        val iptv: IptvRef? = null,
    )

    data class Merged<T>(
        /** The surviving payloads, in display order. */
        val entries: List<T>,
        /** Entry key ([libKey] / [iptvKey]) -> the other sources of that entry (IPTV copies of a library tile; the other sources of an IPTV tile). */
        val others: Map<String, List<IptvSource>>,
    ) {
        fun otherSources(key: String): List<IptvSource> = others[key].orEmpty()
    }

    private fun iptvSourcesOf(v: IptvRef): List<IptvSource> {
        val list = v.sources.filter { it.type == "iptv" }
        return if (list.any { it.id == v.id }) list else listOf(IptvSource("iptv", v.id, v.quality, v.tech)) + list
    }

    /**
     * [entries] in display order -> duplicates removed (see the file header). The surviving library tile sits at the earlier
     * of its own position and its dropped twin's. [sourcesById] = the row's `sourcesById` (library id -> sources).
     */
    fun <T> merge(
        entries: List<Entry<T>>,
        sourcesById: Map<String, List<IptvSource>> = emptyMap(),
    ): Merged<T> {
        val byId = sourcesById.mapKeys { norm(it.key) }
        val libIdx = HashMap<String, Int>()
        val libTmdb = HashMap<Pair<String, Int>, Int>()
        entries.forEachIndexed { i, e ->
            val l = e.lib ?: return@forEachIndexed
            libIdx.putIfAbsent(norm(l.id), i)
            val lKind: String? = l.kind
            val lTmdb: Int? = l.tmdbId
            if (lKind != null && lTmdb != null && (lKind == "movie" || lKind == "series")) libTmdb.putIfAbsent(Pair(lKind, lTmdb), i)
        }
        val others = LinkedHashMap<String, MutableList<IptvSource>>()

        fun addOthers(
            key: String,
            list: List<IptvSource>,
        ) {
            if (list.isEmpty()) return
            val dst = others.getOrPut(key) { ArrayList() }
            list.forEach { s -> if (dst.none { it.id == s.id && it.type == s.type }) dst += s }
        }
        entries.forEach { e -> e.lib?.let { l -> byId[norm(l.id)]?.let { addOthers(libKey(l.id), it.filter { s -> s.type == "iptv" }) } } }

        val dropped = HashSet<Int>()
        val moveTo = HashMap<Int, Int>()
        val firstOfTitle = HashMap<Pair<String, Int>, Int>()
        val foldedInto = HashMap<Int, Int>()
        entries.forEachIndexed { i, e ->
            val v = e.iptv ?: return@forEachIndexed
            val libSources = v.sources.filter { it.type == "library" }
            var twin: Int? = v.libraryId?.let { libIdx[norm(it)] }
            if (twin == null) {
                for (s in libSources) {
                    val j = libIdx[norm(s.id)]
                    if (j != null) {
                        twin = j
                        break
                    }
                }
            }
            val vKind: String? = v.kind
            val vTmdb: Int? = v.tmdbId
            val titleKey: Pair<String, Int>? = if (vKind != null && vTmdb != null) Pair(vKind, vTmdb) else null
            if (twin == null && v.libraryId == null && libSources.isEmpty() && titleKey != null) {
                twin = libTmdb[titleKey]
            }
            val earlier = titleKey?.let { firstOfTitle[it] }
            if (titleKey != null && earlier == null) firstOfTitle[titleKey] = i
            if (twin != null) {
                dropped += i
                foldedInto[i] = twin
                moveTo[twin] = minOf(moveTo[twin] ?: twin, i)
                entries[twin].lib?.let { addOthers(libKey(it.id), iptvSourcesOf(v)) }
            } else if (earlier != null) {
                dropped += i
                val lj = foldedInto[earlier]
                if (lj != null) {
                    foldedInto[i] = lj
                    moveTo[lj] = minOf(moveTo[lj] ?: lj, i)
                    entries[lj].lib?.let { addOthers(libKey(it.id), iptvSourcesOf(v)) }
                } else {
                    entries[earlier].iptv?.let { addOthers(iptvKey(it.id), iptvSourcesOf(v)) }
                }
            } else {
                addOthers(iptvKey(v.id), v.sources.filter { it.id != v.id })
            }
        }
        val kept = entries.indices.filter { it !in dropped }.sortedBy { moveTo[it] ?: it }
        return Merged(kept.map { entries[it].payload }, others)
    }

    /**
     * Continue Watching / Next Up: the IPTV entries to keep. Library wins: a film entry goes when the row has a library film
     * with the same TMDB id, an episode entry when the row has a library episode whose SERIES has the same TMDB id. Unknown
     * ids keep both. Recordings are never dropped.
     */
    fun filterResume(
        library: List<LibRef>,
        iptv: List<IptvResume>,
    ): List<IptvResume> {
        val films = library.filter { it.kind == "movie" }.mapNotNull { it.tmdbId }.toSet()
        val shows = library.filter { it.kind == "episode" }.mapNotNull { it.seriesTmdbId }.toSet()
        if (films.isEmpty() && shows.isEmpty()) return iptv
        return iptv.filterNot { r ->
            when (r.kind) {
                "movie" -> r.tmdbId != null && r.tmdbId in films
                "episode" -> r.seriesTmdbId != null && r.seriesTmdbId in shows
                else -> false
            }
        }
    }

    /** IPTV items with no library twin in [library] (Watchlist: these join the "Films" / "Shows" row), first of each title only. */
    fun iptvOnly(
        library: List<LibRef>,
        iptv: List<IptvVodItem>,
    ): List<IptvVodItem> =
        merge(
            library.map { Entry<IptvVodItem?>(null, lib = it) } + iptv.map { Entry<IptvVodItem?>(it, iptv = it.iptvRef()) },
        ).entries.filterNotNull()

    /** [ordered] (the row's mixed order) with duplicates removed; `lib` / `vod` build the caller's tile. */
    fun <T> orderedMerged(
        order: List<String>,
        library: List<BaseItem>,
        iptv: List<IptvVodItem>,
        sourcesById: Map<String, List<IptvSource>>,
        lib: (BaseItem) -> T,
        vod: (IptvVodItem) -> T,
    ): Merged<T> =
        merge(
            com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse.ordered<Entry<T>>(
                order,
                library,
                iptv,
                { Entry(lib(it), lib = it.libRef()) },
                { Entry(vod(it), iptv = it.iptvRef()) },
            ),
            sourcesById,
        )
}

/** A library item as the merge sees it: films and series carry their TMDB id (providerIds "Tmdb"); an episode has none to match by. */
fun BaseItem.libRef(): JellytipSourceMerge.LibRef {
    val kind =
        when (type) {
            BaseItemKind.MOVIE -> "movie"
            BaseItemKind.SERIES -> "series"
            BaseItemKind.EPISODE -> "episode"
            else -> null
        }
    val tmdb =
        if (kind == "movie" || kind == "series") {
            data.providerIds
                ?.entries
                ?.firstOrNull { it.key.equals("Tmdb", true) }
                ?.value
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        } else {
            null
        }
    return JellytipSourceMerge.LibRef(id.toString(), kind, tmdb)
}

fun IptvVodItem.iptvRef(): JellytipSourceMerge.IptvRef =
    JellytipSourceMerge.IptvRef(
        id = id,
        kind = kind,
        tmdbId = tmdbId,
        libraryId = libraryId,
        sources = sources,
        quality = quality,
        tech = tech,
    )
