package com.github.damontecres.wholphin.jellytip.search

import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.discover.DiscoverTitle
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
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

data class SearchIptv(
    val id: String,
    val name: String,
    val year: Int?,
    val kind: String,
    val tech: List<String>,
)

/** A result group in the server's order; only the list matching [type] is filled. */
data class SearchGroup(
    val type: String,
    val total: Int,
    val ids: List<UUID> = emptyList(),
    val people: List<SearchPerson> = emptyList(),
    val collections: List<SearchCollection> = emptyList(),
    val iptv: List<SearchIptv> = emptyList(),
    val discover: List<DiscoverTitle> = emptyList(),
)

data class SearchAnswer(
    val q: String,
    val text: String,
    val chips: List<SearchChip>,
    val groups: List<SearchGroup>,
    /** Groups still coming (admins' discover): ask again with `types=<them>` ~300 ms later. */
    val pending: List<String>,
) {
    val isEmpty get() = groups.all { it.ids.isEmpty() && it.people.isEmpty() && it.collections.isEmpty() && it.iptv.isEmpty() && it.discover.isEmpty() }
}

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

    fun answer(json: String): SearchAnswer {
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
                    else -> null // lists etc.: no TV page for them yet
                }
            }
        return SearchAnswer(
            q = root.str("q").orEmpty(),
            text = parsed?.str("text").orEmpty(),
            chips = chips(parsed?.optJSONObject("filters")),
            groups = groups,
            pending = strings(root.optJSONArray("pending")),
        )
    }

    fun suggest(json: String): SearchSuggest {
        val root = JSONObject(json)
        return SearchSuggest(strings(root.optJSONArray("recent")), strings(root.optJSONArray("suggested")))
    }
}

/** Removing a chip: drop the words that produced it from the query (pure; unit tested). */
object SearchRules {
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
            return runCatching { SearchParse.answer(body) }.getOrNull()
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
