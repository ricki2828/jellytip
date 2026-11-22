package com.github.damontecres.wholphin.jellytip.journeys

import com.github.damontecres.wholphin.jellytip.rows.HubSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/*
 * Guided film journeys: port of web/src/lib/journeys.ts (origin/jellytip-web 7cc13de9, 1a1f4dca, f34fc72e). A journey is a
 * short ordered viewing path built from data the app already has: franchises (companion collection), directors (the
 * library's own credits) and Letterboxd lists. No invented copy: titles are plain facts ("Hitchcock in order · 6 films").
 * Pure (fetchers injected via [JourneyDeps]); tested in JourneysTest.
 */

enum class JourneyKind(
    val prefix: String,
) {
    FRANCHISE("f"),
    DIRECTOR("d"),
    LIST("l"),
}

/** One film on a path; itemId null = not in the person's library: on IPTV ([vod]) it still plays, else greyed and skipped. */
data class JourneyPart(
    val itemId: String?,
    val title: String,
    val year: Int?,
    /** The IPTV copy of a film the library lacks (source-neutral: the path plays it from there). */
    val vod: com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem? = null,
)

data class JourneyDef(
    val id: String,
    val kind: JourneyKind,
    /** The subject: a franchise name, a director's name, a list's name. */
    val name: String,
    val parts: List<JourneyPart>,
    /** Films that qualified before the cap cut the path (parts.size when nothing was cut). */
    val total: Int? = null,
)

data class JourneySummary(
    val id: String,
    val kind: JourneyKind,
    val title: String,
    /** Small label above the title ("Director"). */
    val kicker: String? = null,
    val films: Int,
)

data class JourneyGroups(
    val franchise: List<JourneySummary>,
    val director: List<JourneySummary>,
    val list: List<JourneySummary>,
) {
    val isEmpty: Boolean get() = franchise.isEmpty() && director.isEmpty() && list.isEmpty()
}

/** The slice of a Jellyfin item the path needs (fresh each visit: watched state moves). */
data class JourneyItem(
    val id: String,
    val name: String? = null,
    val productionYear: Int? = null,
    val locationType: String? = null,
    val played: Boolean = false,
    val positionTicks: Long = 0,
    /** Jellyfin item type ("Movie", ...), so a list entry opens the right page. */
    val type: String? = null,
)

data class JourneyEntry(
    val n: Int,
    val itemId: String?,
    val title: String,
    val year: Int?,
    val playable: Boolean,
    val watched: Boolean,
    val inProgress: Boolean,
    val positionTicks: Long = 0,
    val type: String? = null,
    /** Played from IPTV (no library copy): OK opens its details. */
    val vod: com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem? = null,
)

/** A Letterboxd list from the carousels manifest (ids as the library gives them). */
data class JourneyList(
    val slug: String,
    val name: String,
    val itemIds: List<String>,
    /** S8: library ids and IPTV ids interleaved in the list's own order (empty = an older server: [itemIds]). */
    val order: List<String> = emptyList(),
    /** S8: the IPTV titles of [order], by id. */
    val iptv: Map<String, com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem> = emptyMap(),
) {
    /** The list's entries: [order] when the server sends it, else the library ids. */
    val entryIds: List<String> get() = order.ifEmpty { itemIds }
}

/** The franchise page fields a path needs (`/v1/collections/{tmdbId}` partsOrder). */
data class FranchisePage(
    val name: String,
    val partsOrder: List<JourneyPart>,
)

sealed interface JourneyResult {
    data class Ok(
        val def: JourneyDef,
    ) : JourneyResult

    /** The server has no such journey (or fewer than 2 films in the library). */
    data object None : JourneyResult

    /** A director with fewer than [Journeys.DIRECTOR_MIN] playable films in the library. */
    data class Short(
        val personId: String,
        val films: Int,
    ) : JourneyResult

    /** A fetch failed: not the same as "none", and never cached. */
    data object Error : JourneyResult
}

interface JourneyDeps {
    /** null only for a real not-found (404); a load failure throws. */
    suspend fun collectionPage(tmdbId: Int): FranchisePage?

    /** A director's films (see [Journeys.DIRECTOR_QUERY_LIMIT]) and the server's total. */
    suspend fun directorFilms(personId: String): Pair<List<JourneyItem>, Int?>

    suspend fun personName(personId: String): String

    suspend fun list(slug: String): JourneyList?
}

object Journeys {
    const val FRANCHISE_MIN = 2 // films in the library for a franchise to be a path
    const val DIRECTOR_MIN = 4 // films in the library for a director to be a path
    const val DIRECTOR_CAP = 6 // films on a director path
    const val LIST_CAP = 8 // films on a list path
    const val FRANCHISES_SHOWN = 12
    const val DIRECTORS_SHOWN = 12
    const val LISTS_SHOWN = 10
    const val DIRECTOR_QUERY_LIMIT = 60

    // ---------- ids (route-safe) ----------

    private val KEY_RE =
        mapOf(
            JourneyKind.FRANCHISE to Regex("^\\d{1,9}$"),
            JourneyKind.DIRECTOR to Regex("^([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$", RegexOption.IGNORE_CASE),
            JourneyKind.LIST to Regex("^[a-z0-9][a-z0-9_-]{0,120}$", RegexOption.IGNORE_CASE),
        )
    private val ID_RE = Regex("^([fdl]):(.+)$", RegexOption.IGNORE_CASE)

    fun journeyId(
        kind: JourneyKind,
        key: String,
    ) = "${kind.prefix}:$key"

    fun parseJourneyId(id: String): Pair<JourneyKind, String>? {
        val m = ID_RE.find(id) ?: return null
        val kind = JourneyKind.entries.first { it.prefix == m.groupValues[1].lowercase() }
        val key = m.groupValues[2]
        if (!KEY_RE.getValue(kind).matches(key)) return null
        return kind to if (kind == JourneyKind.DIRECTOR) key.lowercase().replace("-", "") else key
    }

    /** Ids compare without dashes, any case. */
    fun norm(id: String) = id.replace("-", "").lowercase()

    // ---------- ordering ----------

    /** By release year. Films without a year stay where they came in; the dated ones fill the other slots in order (ties keep their order). */
    fun <T> orderByRelease(
        parts: List<T>,
        year: (T) -> Int?,
    ): List<T> {
        val dated = parts.filter { year(it) != null }.sortedBy { year(it)!! } // sortedBy is stable
        var k = 0
        return parts.map { if (year(it) == null) it else dated[k++] }
    }

    private fun plural(n: Int) = "$n film${if (n == 1) "" else "s"}"

    private fun titleFor(
        name: String,
        films: Int,
        kind: JourneyKind,
    ) = if (kind == JourneyKind.LIST) "$name · ${plural(films)}" else "$name in order · ${plural(films)}"

    /** Headline; says so when the path is only the start of what qualifies ("Hitchcock: earliest 6 of 14 films"). */
    fun journeyTitle(d: JourneyDef): String {
        val total = d.total ?: d.parts.size
        if (total > d.parts.size) return "${d.name}: ${if (d.kind == JourneyKind.DIRECTOR) "earliest" else "first"} ${d.parts.size} of $total films"
        return titleFor(d.name, d.parts.size, d.kind)
    }

    // ---------- definitions ----------

    /** Every film of a franchise by release date; parts the library lacks stay in place, flagged missing. */
    fun franchiseDef(
        tmdbId: String,
        page: FranchisePage,
    ): JourneyDef? {
        val parts = page.partsOrder.map { it.copy(itemId = it.itemId?.takeIf { id -> id.isNotEmpty() }) }
        if (parts.count { it.itemId != null } < FRANCHISE_MIN) return null
        return JourneyDef(journeyId(JourneyKind.FRANCHISE, tmdbId), JourneyKind.FRANCHISE, page.name, orderByRelease(parts) { it.year }, parts.size)
    }

    /** Movies a director path can use: placeholders and duplicates out. */
    fun playableFilms(films: List<JourneyItem>): List<JourneyItem> {
        val seen = HashSet<String>()
        return films.filter { it.locationType != "Virtual" && seen.add(it.id) }
    }

    /** A director's films from the library's own credits: eligible at DIRECTOR_MIN, chronological, first DIRECTOR_CAP. */
    fun directorDef(
        personId: String,
        name: String,
        films: List<JourneyItem>,
        total: Int? = null,
    ): JourneyDef? {
        val movies = playableFilms(films)
        if (name.isEmpty() || movies.size < DIRECTOR_MIN) return null
        val parts = orderByRelease(movies.map { JourneyPart(it.id, it.name.orEmpty(), it.productionYear) }) { it.year }.take(DIRECTOR_CAP)
        return JourneyDef(journeyId(JourneyKind.DIRECTOR, personId), JourneyKind.DIRECTOR, name, parts, maxOf(total ?: 0, movies.size))
    }

    /** A Letterboxd list in the list's own order (library and IPTV films interleaved when the server sends `order`), first LIST_CAP. */
    fun listDef(list: JourneyList): JourneyDef? {
        val all = list.entryIds.distinct()
        val ids = all.take(LIST_CAP)
        if (ids.size < 2) return null
        val parts =
            ids.map { id ->
                list.iptv[id]?.let { v -> JourneyPart(null, v.title, v.year, vod = v) } ?: JourneyPart(id, "", null)
            }
        return JourneyDef(journeyId(JourneyKind.LIST, list.slug), JourneyKind.LIST, list.name, parts, all.size)
    }

    // ---------- the journeys page (no per-journey calls: counts from the hub and manifest) ----------

    private val TMDB_ID = Regex("^tmdb:\\d{1,9}$")

    fun summaries(
        hub: List<HubSection>?,
        lists: List<JourneyList>?,
    ): JourneyGroups {
        fun items(key: String) = hub?.firstOrNull { it.key == key }?.items.orEmpty()
        val franchise =
            items("franchises")
                .filter { TMDB_ID.matches(it.id) && it.mediaType != "tv" && it.libCount >= FRANCHISE_MIN }
                .sortedByDescending { it.libCount }
                .take(FRANCHISES_SHOWN)
                .map { JourneySummary(journeyId(JourneyKind.FRANCHISE, it.id.removePrefix("tmdb:")), JourneyKind.FRANCHISE, titleFor(it.title, it.libCount, JourneyKind.FRANCHISE), films = it.libCount) }
        val director =
            items("directors")
                .filter { it.personId != null && it.mediaType != "tv" && it.libCount >= DIRECTOR_MIN }
                .sortedByDescending { it.libCount }
                .take(DIRECTORS_SHOWN)
                // No film count on the card: the hub's count and the library's director credits can differ; the path states its own
                .map { JourneySummary(journeyId(JourneyKind.DIRECTOR, norm(it.personId.toString())), JourneyKind.DIRECTOR, it.title, kicker = "Director", films = 0) }
        val list =
            lists
                .orEmpty()
                .filter { parseJourneyId(journeyId(JourneyKind.LIST, it.slug)) != null && it.entryIds.toSet().size >= 2 }
                .take(LISTS_SHOWN)
                .map {
                    val films = minOf(it.entryIds.toSet().size, LIST_CAP)
                    JourneySummary(journeyId(JourneyKind.LIST, it.slug), JourneyKind.LIST, titleFor(it.name, films, JourneyKind.LIST), films = films)
                }
        return JourneyGroups(franchise, director, list)
    }

    // ---------- progress ----------

    /** Merge fresh item state into a path. Items the server no longer returns (or that are virtual placeholders) are not playable. */
    fun buildEntries(
        def: JourneyDef,
        items: List<JourneyItem>,
    ): List<JourneyEntry> {
        val by = items.associateBy { norm(it.id) }
        return def.parts.mapIndexed { i, p ->
            val hit = p.itemId?.let { id -> by[norm(id)] }
            val playable = hit != null && hit.locationType != "Virtual"
            // Not in the library but on IPTV: plays from there (source-neutral), its own watched / progress state
            val vod = p.vod?.takeIf { !playable }
            if (vod != null) {
                return@mapIndexed JourneyEntry(
                    n = i + 1,
                    itemId = null,
                    title = vod.title.ifEmpty { p.title },
                    year = vod.year ?: p.year,
                    playable = true,
                    // The IPTV tiles' rule: its own flag or its progress says played
                    watched = vod.played || vod.progress?.played == true,
                    inProgress = !(vod.played || vod.progress?.played == true) && vod.progress?.resumable == true,
                    type = "Movie",
                    vod = vod,
                )
            }
            val watched = playable && hit!!.played
            JourneyEntry(
                n = i + 1,
                itemId = if (playable) hit!!.id else null,
                title = hit?.name?.takeIf { n -> n.isNotEmpty() } ?: p.title,
                year = hit?.productionYear ?: p.year,
                playable = playable,
                watched = watched,
                inProgress = playable && !watched && hit!!.positionTicks > 0,
                positionTicks = if (playable) hit!!.positionTicks else 0,
                type = hit?.type,
            )
        }
    }

    /** The first unwatched film you can play; missing ones are skipped. Null when everything playable is watched. */
    fun nextUp(entries: List<JourneyEntry>): Pair<JourneyEntry, Boolean>? = entries.firstOrNull { it.playable && !it.watched }?.let { it to it.inProgress }

    data class Progress(
        val watched: Int,
        val playable: Int,
        val total: Int,
    )

    fun progress(entries: List<JourneyEntry>) = Progress(entries.count { it.watched }, entries.count { it.playable }, entries.size)

    // ---------- building (fetchers injected: tested without a server) ----------

    suspend fun build(
        id: String,
        deps: JourneyDeps,
    ): JourneyResult {
        val (kind, key) = parseJourneyId(id) ?: return JourneyResult.None
        return try {
            when (kind) {
                JourneyKind.FRANCHISE -> {
                    val page = deps.collectionPage(key.toInt())
                    page?.let { franchiseDef(key, it) }?.let { JourneyResult.Ok(it) } ?: JourneyResult.None
                }
                JourneyKind.DIRECTOR -> {
                    val (r, name) =
                        coroutineScope {
                            val films = async { deps.directorFilms(key) }
                            val name = async { deps.personName(key) }
                            films.await() to name.await()
                        }
                    val films = playableFilms(r.first)
                    val total = if (r.first.size < DIRECTOR_QUERY_LIMIT) films.size else maxOf(films.size, r.second ?: 0)
                    directorDef(key, name, r.first, total)?.let { JourneyResult.Ok(it) } ?: JourneyResult.Short(key, films.size)
                }
                JourneyKind.LIST -> deps.list(key)?.let { listDef(it) }?.let { JourneyResult.Ok(it) } ?: JourneyResult.None
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JourneyResult.Error
        }
    }

    /** Only an explicit 404 reads as not-found; no answer or any other status is a load failure (Retry). */
    fun isNotFoundStatus(status: Int?): Boolean = status == 404
}

/** Results cached per person (10 min); errors are never kept, so a retry asks again. Watched state is not cached here. */
class JourneyCache(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val m = HashMap<String, Pair<Long, JourneyResult>>()

    suspend fun get(
        user: String,
        key: String,
        load: suspend () -> JourneyResult,
    ): JourneyResult {
        val k = "$user|$key"
        synchronized(m) { m[k] }?.takeIf { now() - it.first < TTL_MS }?.let { return it.second }
        val r =
            try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JourneyResult.Error
            }
        if (r !is JourneyResult.Error) synchronized(m) { m[k] = now() to r }
        return r
    }

    fun clear() = synchronized(m) { m.clear() }

    /** Forget one journey (its IPTV parts carry watched / progress state, which the cache would keep for [TTL_MS]). */
    fun drop(
        user: String,
        key: String,
    ) = synchronized(m) { m.remove("$user|$key") }

    companion object {
        const val TTL_MS = 10 * 60_000L
    }
}
