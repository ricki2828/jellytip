package com.github.damontecres.wholphin.jellytip.search

import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle
import com.github.damontecres.wholphin.jellytip.iptv.IptvChannel
import com.github.damontecres.wholphin.jellytip.iptv.IptvOnNow
import com.github.damontecres.wholphin.jellytip.iptv.IptvParse
import com.github.damontecres.wholphin.jellytip.iptv.MatchParse
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/*
 * 10x search (companion `GET /v1/search`, contract "Search API" in ~/ai/plans/jellytip-10x.md): fuzzy, plain-English
 * filters echoed back as removable chips, grouped results. A 404 means the server has no search: the app's own page.
 */

/** One parsed filter the user can remove ("1990s", "France", "Crime", "Under 2h", "With Al Pacino", "4K"). */
data class SearchChip(
    val kind: String,
    val label: String,
    /** The raw value, used to find the words that produced it. */
    val value: String,
)

data class SearchPerson(
    val personId: UUID,
    val name: String,
    val role: String,
    val imageTag: String?,
    val filmCount: Int,
)

data class SearchCollection(
    val tmdbId: Int,
    val name: String,
    val inLibrary: Int,
    val total: Int,
)

data class SearchList(
    val slug: String,
    val name: String,
    val count: Int,
)

data class SearchIptv(
    val id: String,
    val name: String,
    val year: Int?,
    val kind: String,
    val tech: List<String>,
    /** Poster link (resolved to absolute by the service); null = title card. */
    val poster: String? = null,
    val backdrop: String? = null,
    val quality: List<String> = emptyList(),
    val tmdbId: Int? = null,
    val rating: Double? = null,
    val audio: String? = null,
)

/**
 * One entry of the ranked `results` list (plan jellytip-search-10x "A2/A3"): [kind] film | series | person |
 * collection, [source] library | iptv | discover. Only the fields of its kind/source are set.
 */
data class SearchResult(
    val kind: String,
    val source: String,
    val title: String,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val rating: Double? = null,
    /** Library film/series. */
    val id: UUID? = null,
    val seasons: Int? = null,
    val episodes: Int? = null,
    /** IPTV film/series. */
    val iptvId: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val quality: List<String> = emptyList(),
    val tech: List<String> = emptyList(),
    /** IPTV audio: atmos | 5.1 | stereo (optional). */
    val audio: String? = null,
    /** Discover (admins). */
    val posterUrl: String? = null,
    /** Person. */
    val personId: UUID? = null,
    val role: String? = null,
    val imageTag: String? = null,
    val filmCount: Int = 0,
    /** Collection. */
    val collectionId: Int? = null,
    val inLibrary: Int = 0,
    val total: Int = 0,
) {
    val isTitle get() = kind == "film" || kind == "series"

    /** Same entry (the top hit is also first in results). */
    val key get() = "$kind:$source:${id ?: iptvId ?: personId ?: collectionId ?: tmdbId ?: title}"
}

/** A result group in the server's order; only the list matching [type] is filled. */
data class SearchGroup(
    val type: String,
    val total: Int,
    val ids: List<UUID> = emptyList(),
    val people: List<SearchPerson> = emptyList(),
    val collections: List<SearchCollection> = emptyList(),
    val iptv: List<SearchIptv> = emptyList(),
    val discover: List<DiscoverTitle> = emptyList(),
    val lists: List<SearchList> = emptyList(),
    /** `results` group: library ids (dashless) and IPTV ids in rank order. */
    val order: List<String> = emptyList(),
)

data class SearchAnswer(
    val q: String,
    val text: String,
    val chips: List<SearchChip>,
    val groups: List<SearchGroup>,
    /** Groups still coming (admins' discover): ask again with `types=<them>` ~300 ms later. */
    val pending: List<String>,
    /** The ranked list (newer servers); null = only [groups]. */
    val results: List<SearchResult>? = null,
    val top: SearchResult? = null,
    /** Programmes airing now or in the next 48 h (plan "E"): live first, then by start; replays carry kind "replay". */
    val live: List<IptvOnNow> = emptyList(),
    /** A pure sport / league / team query with live results: "Live & upcoming" goes first. */
    val liveFirst: Boolean = false,
    val channels: List<SearchChannel> = emptyList(),
) {
    val isEmpty
        get() =
            live.isEmpty() && channels.isEmpty() &&
                (results == null || results.isEmpty()) &&
                groups.all { it.ids.isEmpty() && it.people.isEmpty() && it.collections.isEmpty() && it.iptv.isEmpty() && it.discover.isEmpty() && it.lists.isEmpty() }

    /**
     * What the page shows after the top hit when [results] exists: "In your library" and "On IPTV" rows (films and
     * shows, rank order, top hit left out), then people, collections, "not available" (discover), then the old
     * Letterboxd lists group if any.
     */
    val rankedGroups: List<SearchGroup>
        get() {
            val r = results ?: return emptyList()
            val media = r.filter { it.isTitle && it.source != "discover" && it.key != top?.key }
            val people = r.filter { it.kind == "person" && it.personId != null }
            val collections = r.filter { it.kind == "collection" && it.collectionId != null }
            val discover = r.filter { it.isTitle && it.source == "discover" && (it.tmdbId ?: 0) > 0 && it.key != top?.key }
            // Library and IPTV shown separately (plan "D"), each in rank order; an empty one is left out
            val lib = media.filter { it.source == "library" && it.id != null }
            val iptv = media.filter { it.source == "iptv" && it.iptvId != null }
            return listOfNotNull(
                lib.takeIf { it.isNotEmpty() }?.let { m ->
                    SearchGroup(type = "results-library", total = m.size, ids = m.mapNotNull { it.id }, order = m.mapNotNull { it.id?.toString()?.replace("-", "") })
                },
                iptv.takeIf { it.isNotEmpty() }?.let { m ->
                    SearchGroup(type = "results-iptv", total = m.size, iptv = m.map { it.toIptv() }, order = m.mapNotNull { it.iptvId })
                },
                people.takeIf { it.isNotEmpty() }?.let { p ->
                    SearchGroup("people", p.size, people = p.map { SearchPerson(it.personId!!, it.title, it.role ?: "actor", it.imageTag, it.filmCount) })
                },
                collections.takeIf { it.isNotEmpty() }?.let { c ->
                    SearchGroup("collections", c.size, collections = c.map { SearchCollection(it.collectionId!!, it.title, it.inLibrary, it.total) })
                },
                discover.takeIf { it.isNotEmpty() }?.let { d ->
                    SearchGroup(
                        "discover",
                        d.size,
                        discover = d.map { DiscoverTitle(tmdbId = it.tmdbId!!, mediaType = if (it.kind == "series") "tv" else "movie", title = it.title, year = it.year, posterUrl = it.posterUrl) },
                    )
                },
            ) + groups.filter { it.type == "lists" && it.lists.isNotEmpty() }
        }
}

fun SearchResult.toIptv() =
    SearchIptv(
        id = iptvId.orEmpty(),
        name = title,
        year = year,
        kind = if (kind == "series") "series" else "movie",
        tech = tech,
        poster = poster,
        backdrop = backdrop,
        quality = quality,
        tmdbId = tmdbId,
        rating = rating,
        audio = audio,
    )

/** A channel whose name matched (`channels`, plan jellytip-search-10x "E"): OK tunes it. */
data class SearchChannel(
    val channel: IptvChannel,
    /** What is on now, when the guide knows. */
    val nowTitle: String?,
    val nowPhase: String?,
)

data class SearchSuggest(
    val recent: List<String>,
    val suggested: List<String>,
)

object SearchParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    private fun uuid(s: String?): UUID? = s?.let { runCatching { it.toUUID() }.getOrNull() }

    fun chips(filters: JSONObject?): List<SearchChip> {
        filters ?: return emptyList()
        return buildList {
            filters.str("decade")?.toIntOrNull()?.let { add(SearchChip("decade", "${it}s", it.toString())) }
            filters.str("country")?.let { add(SearchChip("country", countryName(it), it)) }
            filters.str("language")?.let { add(SearchChip("language", languageName(it), it)) }
            filters.str("genre")?.let { add(SearchChip("genre", it, it)) }
            filters.str("runtimeMax")?.toIntOrNull()?.let { add(SearchChip("runtime", "Under ${runtimeText(it)}", it.toString())) }
            if (filters.optBoolean("unwatched", false)) add(SearchChip("unwatched", "Unwatched", "true"))
            val person = filters.optJSONObject("person")?.str("name") ?: filters.str("person")
            person?.let { add(SearchChip("person", "With $it", it)) }
            val tech = filters.optJSONArray("tech")?.let { strings(it) } ?: listOfNotNull(filters.str("tech"))
            tech.forEach { add(SearchChip("tech", it.uppercase(Locale.ROOT).replace("DOLBY VISION", "Dolby Vision"), it)) }
        }
    }

    private fun runtimeText(min: Int) = if (min % 60 == 0) "${min / 60}h" else "$min min"

    private fun countryName(code: String): String =
        if (code.length == 2) Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH).ifBlank { code } else code

    private fun languageName(code: String): String =
        if (code.length == 2) Locale(code.lowercase(Locale.ROOT)).getDisplayLanguage(Locale.ENGLISH).ifBlank { code } else code

    /** [resolve] turns relative companion paths (channel logos) into absolute links. */
    fun answer(
        json: String,
        resolve: (String) -> String? = { it },
    ): SearchAnswer {
        val root = JSONObject(json)
        val parsed = root.optJSONObject("parsed")
        com.github.damontecres.wholphin.jellytip.cards.JellytipTech.setAll(root.optJSONObject("techs"))
        com.github.damontecres.wholphin.jellytip.JellytipLogoTint.setStyles(root.optJSONObject("logoStyles"))
        com.github.damontecres.wholphin.jellytip.cards.JellytipThumbTitles.markUntitled(strings(root.optJSONArray("untitled")).mapNotNull(::uuid))
        val groups =
            root.objects("groups").mapNotNull { g ->
                val type = g.str("type") ?: return@mapNotNull null
                val items = g.objects("items")
                val total = g.optInt("total", items.size)
                when (type) {
                    "films", "shows" -> SearchGroup(type, total, ids = items.mapNotNull { uuid(it.str("id")) })
                    "people" ->
                        SearchGroup(
                            type,
                            total,
                            people =
                                items.mapNotNull {
                                    SearchPerson(
                                        uuid(it.str("personId")) ?: return@mapNotNull null,
                                        it.str("name").orEmpty(),
                                        it.str("role") ?: "actor",
                                        it.str("imageTag"),
                                        it.optInt("filmCount", 0),
                                    )
                                },
                        )
                    "collections" ->
                        SearchGroup(
                            type,
                            total,
                            collections =
                                items.mapNotNull {
                                    val id = it.optInt("tmdbCollectionId", 0).takeIf { n -> n > 0 } ?: return@mapNotNull null
                                    SearchCollection(id, it.str("name").orEmpty(), it.optInt("inLibrary", 0), it.optInt("total", 0))
                                },
                        )
                    "iptv" ->
                        SearchGroup(
                            type,
                            total,
                            iptv =
                                items.mapNotNull {
                                    SearchIptv(
                                        it.str("iptvId") ?: it.str("id") ?: return@mapNotNull null,
                                        it.str("name") ?: it.str("title").orEmpty(),
                                        it.optInt("year", 0).takeIf { y -> y > 0 },
                                        it.str("kind") ?: "movie",
                                        strings(it.optJSONArray("tech")),
                                        it.str("poster") ?: it.str("posterUrl"),
                                    )
                                },
                        )
                    "discover" ->
                        SearchGroup(
                            type,
                            total,
                            discover =
                                items.mapNotNull {
                                    DiscoverTitle(
                                        tmdbId = it.optInt("tmdbId", 0).takeIf { n -> n > 0 } ?: return@mapNotNull null,
                                        mediaType = it.str("mediaType") ?: "movie",
                                        title = it.str("title").orEmpty(),
                                        year = it.optInt("year", 0).takeIf { y -> y > 0 },
                                        posterUrl = it.str("posterUrl"),
                                    )
                                },
                        )
                    "lists" ->
                        SearchGroup(
                            type,
                            total,
                            lists = items.mapNotNull { SearchList(it.str("slug") ?: return@mapNotNull null, it.str("name").orEmpty(), it.optInt("count", 0)) },
                        )
                    else -> null
                }
            }
        val results = root.optJSONArray("results")?.let { a -> (0 until a.length()).mapNotNull { result(a.optJSONObject(it)) } }
        return SearchAnswer(
            q = root.str("q").orEmpty(),
            text = parsed?.str("text").orEmpty(),
            chips = chips(parsed?.optJSONObject("filters")),
            groups = groups,
            pending = strings(root.optJSONArray("pending")),
            results = results,
            top = result(root.optJSONObject("top")) ?: results?.firstOrNull(),
            live = live(groupItems(root, "live") ?: root.optJSONArray("live"), resolve),
            liveFirst =
                if (root.has("liveFirst")) {
                    root.optBoolean("liveFirst", false)
                } else {
                    parsed?.optJSONObject("filters")?.let { it.str("sport") != null || it.str("league") != null } == true
                },
            channels = channels(groupItems(root, "channels") ?: root.optJSONArray("channels"), resolve),
        )
    }

    /** `live` / `channels` are top-level arrays; an older build sent them as groups. */
    private fun groupItems(
        root: JSONObject,
        type: String,
    ): JSONArray? = root.objects("groups").firstOrNull { it.str("type") == type }?.optJSONArray("items")

    /** `live[]`: `{title, start, stop, phase?, startsAt?, matchInfo?, sportName?, league?, replay?, channel, alternateChannels?}`. */
    fun live(
        a: JSONArray?,
        resolve: (String) -> String?,
    ): List<IptvOnNow> {
        a ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val seen = HashSet<String>()
        return (0 until a.length())
            .mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val ch = o.optJSONObject("channel")?.let { IptvParse.channel(it, resolve) } ?: return@mapNotNull null
                val title = o.str("title") ?: return@mapNotNull null
                val start = o.optLong("start", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                val stop = o.optLong("stop", 0L).takeIf { it > start } ?: return@mapNotNull null
                if (!seen.add("${ch.id}|$start")) return@mapNotNull null
                val alts = o.objects("alternateChannels").mapNotNull { IptvParse.channel(it, resolve) }.filter { it.id != ch.id }
                val match = MatchParse.match(o.optJSONObject("matchInfo"), resolve)
                val phase = o.str("phase")
                IptvOnNow(
                    channel = ch,
                    title = title,
                    start = start,
                    stop = stop,
                    sport = match != null || o.str("sportName") != null || o.str("league") != null,
                    kind =
                        when {
                            o.optBoolean("replay", false) -> "replay"
                            now in start until stop -> "live"
                            else -> null
                        },
                    alternates = alts,
                    league = o.str("league") ?: match?.league,
                    sportName = o.str("sportName"),
                    // "live" | "upcoming" from search; "pre" (holding screen before the real start) keeps the pre-game pill
                    phase = phase?.takeIf { it == "pre" },
                    startsAt = IptvParse.isoSeconds(o.str("startsAt")),
                    match = match,
                )
            }.take(30)
    }

    /** `channels[]`: `{id, name, num, logo, group, quality?, now: {title, phase?}}`. */
    fun channels(
        a: JSONArray?,
        resolve: (String) -> String?,
    ): List<SearchChannel> {
        a ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until a.length())
            .mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val ch = IptvParse.channel(o, resolve) ?: return@mapNotNull null
                if (!seen.add(ch.id)) return@mapNotNull null
                val now = o.optJSONObject("now")
                SearchChannel(ch, now?.str("title"), now?.str("phase"))
            }.take(20)
    }

    fun result(o: JSONObject?): SearchResult? {
        o ?: return null
        val kind = o.str("kind") ?: return null
        val title = o.str("title") ?: o.str("name") ?: return null
        val r =
            SearchResult(
                kind = kind,
                source = o.str("source") ?: "library",
                title = title,
                year = o.optInt("year", 0).takeIf { it > 0 },
                tmdbId = o.optInt("tmdbId", 0).takeIf { it > 0 },
                rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
                id = uuid(o.str("id")),
                seasons = o.optInt("seasons", 0).takeIf { it > 0 },
                episodes = o.optInt("episodes", 0).takeIf { it > 0 },
                iptvId = o.str("iptvId"),
                poster = o.str("poster"),
                backdrop = o.str("backdrop"),
                quality = strings(o.optJSONArray("quality")),
                tech = strings(o.optJSONArray("tech")),
                audio = o.str("audio"),
                posterUrl = o.str("posterUrl"),
                personId = uuid(o.str("personId")),
                role = o.str("role"),
                imageTag = o.str("imageTag"),
                filmCount = o.optInt("filmCount", 0),
                collectionId = o.optInt("tmdbCollectionId", 0).takeIf { it > 0 },
                inLibrary = o.optInt("inLibrary", 0),
                total = o.optInt("total", 0),
            )
        // An entry the client can't open is dropped
        val openable =
            when {
                kind == "person" -> r.personId != null
                kind == "collection" -> r.collectionId != null
                r.source == "library" -> r.id != null
                r.source == "iptv" -> r.iptvId != null
                r.source == "discover" -> r.tmdbId != null
                else -> false
            }
        return r.takeIf { openable }
    }

    fun suggest(json: String): SearchSuggest {
        val root = JSONObject(json)
        return SearchSuggest(strings(root.optJSONArray("recent")), strings(root.optJSONArray("suggested")))
    }
}

/** Removing a chip: drop the words that produced it from the query (pure; unit tested). */
object SearchRules {
    /**
     * Display order (Ricki 30-Oct: "iptv content should be in search below standard stuff"): library groups in the
     * server's order, then "On your IPTV", then "Not in the library". Stable, so server order holds within a band.
     */
    fun displayOrder(groups: List<SearchGroup>): List<SearchGroup> =
        groups.sortedBy {
            when (it.type) {
                "iptv" -> 1
                "discover" -> 2
                else -> 0
            }
        }

    private val decadeWords = Regex("""\b(?:19|20)?\d0'?s\b""", RegexOption.IGNORE_CASE)
    private val techWords = Regex("""\b(?:4k|uhd|dolby atmos|atmos|dolby vision|dv|hdr10\+?|hdr|imax)\b""", RegexOption.IGNORE_CASE)
    private val runtimeWords = Regex("""\b(?:under|less than|shorter than)\s+\d+\s*(?:h|hrs?|hours?|m|mins?|minutes?)\b|\bshort\b""", RegexOption.IGNORE_CASE)
    private val unwatchedWords = Regex("""\b(?:unwatched|new to me|not seen)\b""", RegexOption.IGNORE_CASE)

    fun removeChip(
        q: String,
        text: String,
        chip: SearchChip,
        all: List<SearchChip>,
    ): String {
        val out =
            when (chip.kind) {
                "decade" -> q.replace(decadeWords, " ")
                "tech" -> q.replace(Regex("""(?<![\w])""" + Regex.escape(chip.value) + """(?![\w])""", RegexOption.IGNORE_CASE), " ")
                "runtime" -> q.replace(runtimeWords, " ")
                "unwatched" -> q.replace(unwatchedWords, " ")
                "person" -> q.replace(Regex("""\b(?:with|starring|directed by|by)?\s*""" + Regex.escape(chip.value), RegexOption.IGNORE_CASE), " ")
                "genre" -> q.replace(Regex("""\b""" + Regex.escape(chip.value.lowercase(Locale.ROOT).take(5)) + """\w*""", RegexOption.IGNORE_CASE), " ")
                else -> leftover(q, text, all.filter { it != chip && it.kind !in setOf("country", "language") })
            }
        val tidy = tidy(out)
        // Nothing matched: rebuild from the plain text and the chips that stay
        return if (tidy.equals(tidy(q), ignoreCase = true)) {
            tidy((listOf(text) + all.filter { it != chip }.map { it.label.lowercase(Locale.ROOT) }).joinToString(" "))
        } else {
            tidy
        }
    }

    /** Country/language chips: the words that are neither the plain text nor another filter's words. */
    private fun leftover(
        q: String,
        text: String,
        others: List<SearchChip>,
    ): String {
        val keep = text.lowercase(Locale.ROOT).split(Regex("""\s+""")).filter { it.isNotBlank() }.toSet()
        val otherWords =
            Regex("""\b(?:with|starring|directed|by|under|less|than|short|unwatched|new|to|me)\b""", RegexOption.IGNORE_CASE)
        return q
            .split(Regex("""\s+"""))
            .filter { w ->
                val lw = w.lowercase(Locale.ROOT)
                lw in keep || decadeWords.matches(w) || techWords.matches(w) || otherWords.matches(w) || w.any { it.isDigit() } ||
                    others.any { lw.isNotEmpty() && it.label.lowercase(Locale.ROOT).contains(lw) }
            }.joinToString(" ")
    }

    private fun tidy(s: String) = s.replace(Regex("""\s+"""), " ").trim()
}

@Singleton
class JellytipSearchService
    @Inject
    constructor(
        private val api: JellytipApi,
    ) {
        /** null = not asked yet; false = the server has no search (404): the app's own search page. */
        @Volatile
        var available: Boolean? = null
            private set

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Recent + suggested; also decides [available]. Null when unavailable. */
        suspend fun suggest(): SearchSuggest? {
            val res = api.userCall("v1/search/suggest", timeoutMs = 5_000)
            if (res == null) {
                if (available == null) available = false
                return null
            }
            if (res.first == 404) {
                available = false
                return null
            }
            if (res.first != 200) return null
            available = true
            return runCatching { SearchParse.suggest(res.second) }.getOrNull()
        }

        /** Null = failed; marks [available] false on 404. */
        suspend fun search(
            q: String,
            types: String? = null,
        ): SearchAnswer? {
            val path = "v1/search?q=${enc(q)}&limit=$LIMIT" + (types?.let { "&types=$it" } ?: "")
            val (code, body) = api.userCall(path, timeoutMs = 8_000) ?: return null
            if (code == 404) {
                available = false
                return null
            }
            if (code != 200) return null
            // Channel logos are relative companion paths (provider URLs are refused, as in the guide)
            val base = runCatching { api.companionBase() }.getOrNull()
            val logo: (String) -> String? = { rel ->
                if (base == null || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(rel) || rel.startsWith("//")) null else "$base/${rel.trimStart('/')}"
            }
            val a = runCatching { SearchParse.answer(body, logo) }.getOrNull() ?: return null
            suspend fun img(u: String?) = u?.let { api.resolveUrl(it) }
            suspend fun SearchResult.resolved() = if (source == "iptv" || kind == "collection") copy(poster = img(poster), backdrop = img(backdrop)) else this
            return a.copy(
                groups =
                    a.groups.map { g ->
                        if (g.iptv.none { it.poster != null }) g else g.copy(iptv = g.iptv.map { it.copy(poster = img(it.poster)) })
                    },
                results = a.results?.map { it.resolved() },
                top = a.top?.resolved(),
            )
        }

        private val clickScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

        /**
         * Click log (plan jellytip-search-10x, "Click log"): `POST v1/search/click {q, kind, id, pos, source?, tmdbId?}`,
         * [pos] 1-based in `results` (the top hit = 1). Fire-and-forget: never awaited, failures ignored.
         */
        fun logClick(
            q: String,
            r: SearchResult,
            pos: Int,
        ) {
            val id = r.id?.toString() ?: r.iptvId ?: r.personId?.toString() ?: r.collectionId?.toString() ?: r.tmdbId?.toString() ?: return
            val body =
                JSONObject()
                    .put("q", q)
                    .put("kind", r.kind)
                    .put("id", id)
                    .put("pos", pos)
                    .put("source", r.source)
                    .apply { r.tmdbId?.let { put("tmdbId", it) } }
                    .toString()
            clickScope.launch {
                val res = runCatching { api.userCall("v1/search/click", body, timeoutMs = 5_000) }.getOrNull()
                com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.log("search", "click ${r.kind} pos=$pos -> ${res?.first}")
            }
        }

        suspend fun recordRecent(q: String) {
            if (q.isBlank()) return
            api.userCall("v1/search/recent", JSONObject().put("q", q).toString())
        }

        suspend fun clearRecent() {
            api.userCall("v1/search/recent", JSONObject().put("clear", true).toString())
        }

        companion object {
            private const val LIMIT = 12
        }
    }
