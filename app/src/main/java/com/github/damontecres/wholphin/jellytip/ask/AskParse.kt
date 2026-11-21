package com.github.damontecres.wholphin.jellytip.ask

/*
 * Plain-language discovery ("Ask"): port of web/src/lib/askParse.ts (origin/jellytip-web 69fc43f6 + f126b5d5).
 * Deterministic, no LLM: phrases map to Jellyfin Items params, and what cannot be mapped is reported back as "ignored"
 * rather than guessed. Pure Kotlin (no Android, no SDK types) so other screens can reuse it, e.g. Pick for tonight chips:
 *
 *   val r = AskParse.parse("something funny under 90 minutes")      // chips, ignored words, notes
 *   val q = AskParse.buildQuery(AskParse.activeChips(r, removed))   // AskQuery -> AskRequest.toGetItemsRequest(userId)
 *   val p = AskParse.pickFrom(items, total, chips, shuffled) { AskItem(it.type, it.runTimeTicks, it.childCount, it.genres) }
 *
 * Parity with the web is checked against golden vectors made by running the web TS under node
 * (app/src/test/resources/jellytip/ask-vectors.json).
 */

sealed interface ChipData {
    data class Genre(
        val genres: List<String>,
    ) : ChipData

    /** "Movie" or "Series". */
    data class Kind(
        val kind: String,
    ) : ChipData

    data class MaxRuntime(
        val min: Int,
    ) : ChipData

    data class MinRuntime(
        val min: Int,
    ) : ChipData

    data class Years(
        val from: Int,
        val to: Int,
    ) : ChipData

    data object Unplayed : ChipData

    data object Played : ChipData

    data class Kids(
        val ratings: List<String>,
        val genres: List<String>,
    ) : ChipData

    data class MinRating(
        val value: Double,
    ) : ChipData

    data class MaxSeasons(
        val n: Int,
    ) : ChipData
}

data class AskChip(
    /** Stable for a given reading, so a removed chip stays removed while the text is unchanged. */
    val id: String,
    val label: String,
    val data: ChipData,
    /** Where in the text it was read, and the words (used to resolve contradictions). */
    val pos: Int = 0,
    val src: String = "",
)

data class AskReading(
    val chips: List<AskChip>,
    /** Words that matched nothing (filler words are dropped silently). */
    val ignored: List<String>,
    /** Things the person should know about how this was read (negatives, contradictions). */
    val notes: List<String>,
)

/** Jellyfin Items params for a set of chips (web buildQuery). Lists are sent as repeated params by the SDK. */
data class AskQuery(
    /** "Movie" and/or "Series". */
    val includeItemTypes: List<String>,
    /** "Random" or "CommunityRating","SortName". */
    val sortBy: List<String>,
    /** null when shuffled. */
    val sortOrder: List<String>?,
    val limit: Int,
    /** "PrimaryImageAspectRatio", plus "Genres" / "ChildCount" when they are checked client-side. */
    val fields: List<String>,
    val genres: List<String>,
    val years: List<Int>,
    /** "IsUnplayed" / "IsPlayed". */
    val filters: List<String>,
    val officialRatings: List<String>,
    val minCommunityRating: Double?,
) {
    /** The web's wire form (comma / pipe joined), for parity tests and logs. */
    fun params(): Map<String, String> =
        buildMap {
            put("IncludeItemTypes", includeItemTypes.joinToString(","))
            put("Recursive", "true")
            put("SortBy", sortBy.joinToString(","))
            sortOrder?.let { put("SortOrder", it.joinToString(",")) }
            put("Limit", limit.toString())
            put("StartIndex", "0")
            put("Fields", fields.joinToString(","))
            if (genres.isNotEmpty()) put("Genres", genres.joinToString("|"))
            if (years.isNotEmpty()) put("Years", years.joinToString(","))
            if (filters.isNotEmpty()) put("Filters", filters.joinToString(","))
            if (officialRatings.isNotEmpty()) put("OfficialRatings", officialRatings.joinToString("|"))
            minCommunityRating?.let { put("MinCommunityRating", AskParse.num(it)) }
        }
}

/** The slice of an item the client-side checks need. */
data class AskItem(
    /** "Movie", "Series", ... */
    val type: String?,
    val runTimeTicks: Long?,
    val childCount: Int?,
    val genres: List<String>?,
)

data class AskPicks<T>(
    val items: List<T>,
    /** What the screen says about coverage. Never implies more than was checked. */
    val note: String,
)

object AskParse {
    val KIDS_RATINGS = listOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "U")
    val KIDS_GENRES = listOf("Family", "Animation", "Kids")

    /** How many matches are requested in one go: runtime and season limits are checked client-side over this page. */
    const val FETCH_LIMIT = 300
    const val PICKS = 24
    val EXAMPLES =
        listOf(
            "something funny under 90 minutes",
            "a 70s thriller I haven't seen",
            "feel-good for the kids",
            "well rated sci-fi from the 90s",
            "short series, mind-bending",
            "scary movie I haven't seen",
        )

    private class Mood(
        val re: Regex,
        val genres: List<String>,
        val label: String,
    )

    private val MOODS =
        listOf(
            Mood(Regex("""\b(?:feel[- ]?good|heart-?warming|uplifting|wholesome)\b"""), listOf("Comedy", "Family", "Animation", "Kids"), "Feel-good"),
            Mood(Regex("""\b(?:mind[- ]?bending|twisty|trippy|head[- ]?scratcher)\b"""), listOf("Science Fiction", "Sci-Fi & Fantasy", "Mystery"), "Mind-bending"),
            Mood(Regex("""\b(?:funny|comedy|comedies|hilarious|laugh|comic)\b"""), listOf("Comedy"), "Comedy"),
            Mood(Regex("""\b(?:scary|horror|creepy|spooky|frightening|terrifying)\b"""), listOf("Horror"), "Horror"),
            Mood(Regex("""\b(?:tense|thriller|thrillers|suspenseful|suspense|gripping|edge of (?:my|your) seat)\b"""), listOf("Thriller"), "Thriller"),
            Mood(Regex("""\b(?:romantic|romance|rom-?coms?|date night)\b"""), listOf("Romance"), "Romance"),
            Mood(Regex("""\b(?:action|action-packed|explosive)\b"""), listOf("Action", "Action & Adventure"), "Action"),
            Mood(Regex("""\b(?:sci-?fi|science fiction|futuristic|space)\b"""), listOf("Science Fiction", "Sci-Fi & Fantasy"), "Sci-fi"),
            Mood(Regex("""\b(?:mystery|mysteries|whodunn?it|detective)\b"""), listOf("Mystery"), "Mystery"),
            Mood(Regex("""\b(?:crime|heist|gangster|mob)\b"""), listOf("Crime"), "Crime"),
            Mood(Regex("""\b(?:animated|animation|cartoon|cartoons)\b"""), listOf("Animation"), "Animation"),
            Mood(Regex("""\b(?:documentary|documentaries)\b"""), listOf("Documentary"), "Documentary"),
            Mood(Regex("""\b(?:war)\b"""), listOf("War", "War & Politics"), "War"),
            Mood(Regex("""\b(?:westerns?)\b"""), listOf("Western"), "Western"),
            Mood(Regex("""\b(?:fantasy|magical)\b"""), listOf("Fantasy", "Sci-Fi & Fantasy"), "Fantasy"),
            Mood(Regex("""\b(?:drama|dramas|emotional|tearjerker|sad)\b"""), listOf("Drama"), "Drama"),
            Mood(Regex("""\b(?:adventure|adventures)\b"""), listOf("Adventure", "Action & Adventure"), "Adventure"),
            Mood(Regex("""\b(?:musical|musicals)\b"""), listOf("Music"), "Music"),
            Mood(Regex("""\b(?:history|historical)\b"""), listOf("History"), "History"),
            Mood(Regex("""\b(?:family)\b"""), listOf("Family", "Kids"), "Family"),
        )

    private val DECADE_WORDS =
        mapOf(
            "twenties" to 1920,
            "thirties" to 1930,
            "forties" to 1940,
            "fifties" to 1950,
            "sixties" to 1960,
            "seventies" to 1970,
            "eighties" to 1980,
            "nineties" to 1990,
        )

    /** Filler that carries no filter and is not worth reporting as ignored. */
    private val FILLER =
        (
            "a an the some something one me my i we us you to for of with that which is are be it its in on and or like want wanna " +
                "need watch watching see find give recommend recommendation please tonight today good great nice really very maybe about " +
                "got have has had just any kind sort type from at as by if so then than this these those there something's what whats who " +
                "idea ideas pick picks suggest suggestion movie-night night evening weekend"
        ).split(" ").toSet()

    private val WS = Regex("\\s+")

    private fun clean(s: String) = s.replace(WS, " ").trim()

    /** A number as JavaScript prints it: 7, 7.5 (never 7.0). */
    fun num(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun fmtMin(m: Int) = if (m % 60 == 0 && m >= 120) "${m / 60} h" else "$m min"

    private fun toMinutes(
        n: Double,
        unit: String,
    ): Int = (if (unit.startsWith("h")) Math.round(n * 60) else Math.round(n)).toInt()

    private const val UNIT = "(min(?:ute)?s?|mins?|m|hours?|hrs?|hr|h)"
    private val NEG = Regex("""\b(?:not|no|non[- ]?|without|except|excluding|nothing|anything but|but not)\s+(?:(?:too|very|so|really)\s+)?(?:for\s+)?(?:the\s+|my\s+)?[a-z][a-z0-9'-]*""")
    private val NEG_KEEP1 = Regex("""^(?:not|no)\s+(?:more than|seen|watched|played)\b""")
    private val NEG_KEEP2 = Regex("""^no more than\b""")
    private val NEG_KEEP3 = Regex("""^(?:not|no)\s+(?:seen|watched|played)\b""")
    private val EDGE = Regex("""^['.\-+]+|['.\-+]+$""")

    /** Parse plain language. [year] is injectable so "recent" is testable. */
    fun parse(
        raw: String,
        year: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    ): AskReading {
        var t = " " + raw.lowercase().replace(Regex("[’`]"), "'").replace(Regex("[^a-z0-9'.+\\- ]+"), " ") + " "
        val chips = mutableListOf<AskChip>()
        val notes = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        var curPos = 0
        var curSrc = ""

        fun add(
            id: String,
            label: String,
            data: ChipData,
        ) {
            if (chips.none { it.id == id }) chips.add(AskChip(id, label, data, curPos, curSrc))
        }

        // Take a match out of the text so later rules and the "ignored" pass do not see it
        fun take(
            re: Regex,
            fn: (MatchResult) -> Unit,
        ) {
            t =
                re.replace(t) { m ->
                    curPos = m.range.first
                    curSrc = clean(m.value)
                    fn(m)
                    " ".repeat(m.value.length)
                }
        }

        // Negatives are not supported: left out of the reading (never inverted into a filter) and reported
        val whole = t
        t =
            NEG.replace(whole) { m ->
                val from = whole.substring(m.range.first)
                // Phrases that are supported and are not negations of a filter
                if (NEG_KEEP1.containsMatchIn(from) || NEG_KEEP2.containsMatchIn(from) || NEG_KEEP3.containsMatchIn(from)) return@replace m.value
                ignored.add(clean(m.value))
                if (notes.none { it.startsWith("Negatives") }) notes.add("Negatives like 'not scary' aren't supported yet.")
                " ".repeat(m.value.length)
            }

        // "show me" / "give me" are requests, not the word "show"
        take(Regex("""\b(?:show|give|find|get|pick|recommend)\s+(?:me|us)\b""")) {}

        // ----- runtime -----
        take(Regex("""\b(?:under|less than|below|shorter than|max(?:imum)?(?: of)?|at most|no more than|up to|within)\s+(\d+(?:\.\d+)?)\s*$UNIT\b""")) { m ->
            val min = toMinutes(m.groupValues[1].toDouble(), m.groupValues[2])
            if (min > 0) add("maxrt:$min", "under ${fmtMin(min)}", ChipData.MaxRuntime(min))
        }
        take(Regex("""\b(\d+(?:\.\d+)?)\s*$UNIT\s+or\s+(?:less|under|shorter)\b""")) { m ->
            val min = toMinutes(m.groupValues[1].toDouble(), m.groupValues[2])
            if (min > 0) add("maxrt:$min", "under ${fmtMin(min)}", ChipData.MaxRuntime(min))
        }
        take(Regex("""\b(?:under|less than|shorter than|within)\s+an?\s+hour\b""")) { add("maxrt:60", "under 60 min", ChipData.MaxRuntime(60)) }
        take(Regex("""\b(?:under|less than|shorter than|within)\s+(?:an?\s+)?hour and a half\b""")) { add("maxrt:90", "under 90 min", ChipData.MaxRuntime(90)) }
        take(Regex("""\b(?:over|more than|longer than|at least|above)\s+(\d+(?:\.\d+)?)\s*$UNIT\b""")) { m ->
            val min = toMinutes(m.groupValues[1].toDouble(), m.groupValues[2])
            if (min > 0) add("minrt:$min", "over ${fmtMin(min)}", ChipData.MinRuntime(min))
        }

        // ----- seen / unseen -----
        take(Regex("""\b(?:i\s+)?(?:haven'?t|have not|havent|hasn'?t|never)\s+(?:seen|watched)(?:\s+(?:it|yet|before))?\b""")) { add("unplayed", "unwatched", ChipData.Unplayed) }
        take(Regex("""\b(?:not (?:seen|watched)(?: yet)?|unwatched|unseen|new to me|not played)\b""")) { add("unplayed", "unwatched", ChipData.Unplayed) }
        take(Regex("""\b(?:rewatch|re-watch|seen before|watched before|already seen|already watched)\b""")) { add("played", "already watched", ChipData.Played) }

        // ----- kids -----
        take(Regex("""\b(?:for (?:the |my |our )?(?:kids?|children|little ones)|kids'?|children'?s|family[- ]friendly|kid[- ]friendly)\b""")) {
            add("kids", "for kids (rated G, PG)", ChipData.Kids(KIDS_RATINGS, KIDS_GENRES))
        }

        // ----- rating -----
        take(Regex("""\b(?:rated|rating|score(?:d)?)\s*(?:over|above|at least|of|>=?)?\s*(\d(?:\.\d)?)(?!\d)\s*\+?(?:\s*(?:/\s*10|or (?:higher|more|better)))?""")) { m ->
            val v = m.groupValues[1].toDouble()
            if (v > 0 && v <= 10) add("minrating:${num(v)}", "rated ${num(v)}+", ChipData.MinRating(v))
        }
        take(Regex("""\b(?:(\d(?:\.\d)?)\s*\+?\s*(?:stars?|/\s*10)|(?:best|top[- ]rated|critically acclaimed|acclaimed|award[- ]winning))\b""")) { m ->
            val g = m.groups[1]?.value
            val v = g?.toDouble() ?: 7.5
            if (v > 0 && v <= 10) add("minrating:${num(v)}", if (g != null) "rated ${num(v)}+" else "rated 7.5+", ChipData.MinRating(v))
        }
        take(Regex("""\b(?:well[- ]rated|highly rated|well[- ]reviewed|good reviews|great reviews|good ratings?)\b""")) { add("minrating:7", "rated 7+", ChipData.MinRating(7.0)) }

        // ----- kind + seasons -----
        take(Regex("""\b(?:miniseries|mini-series|limited series|one season|single season)\b""")) {
            add("kind:Series", "shows", ChipData.Kind("Series"))
            add("maxseasons:1", "1 season", ChipData.MaxSeasons(1))
        }
        take(Regex("""\bshort\s+(?:tv\s+)?(?:series|shows?)\b""")) {
            add("kind:Series", "shows", ChipData.Kind("Series"))
            add("maxseasons:3", "up to 3 seasons", ChipData.MaxSeasons(3))
        }
        take(Regex("""\b(?:movies?|films?|flicks?)\b""")) { add("kind:Movie", "movies", ChipData.Kind("Movie")) }
        take(Regex("""\b(?:tv shows?|tv series|series|shows?|tv)\b""")) { add("kind:Series", "shows", ChipData.Kind("Series")) }

        // ----- years -----
        fun yearChip(
            from: Int,
            to: Int,
            label: String,
        ) = add("years:$from-$to", label, ChipData.Years(from, to))
        take(Regex("""(?<![\w])(?:in the |from the |of the )?'?(1[89]|20)(\d)0'?s\b""")) { m ->
            val from = "${m.groupValues[1]}${m.groupValues[2]}0".toInt()
            yearChip(from, from + 9, "${from}s")
        }
        take(Regex("""(?<![\w])(?:in the |from the |of the )?'?([2-9])0'?s\b""")) { m ->
            val from = 1900 + m.groupValues[1].toInt() * 10
            yearChip(from, from + 9, "${from}s")
        }
        take(Regex("""\b(?:in the |from the |of the )?(twenties|thirties|forties|fifties|sixties|seventies|eighties|nineties)\b""")) { m ->
            val from = DECADE_WORDS.getValue(m.groupValues[1])
            yearChip(from, from + 9, "${from}s")
        }
        take(Regex("""\b(?:after|since)\s+((?:19|20)\d\d)\b""")) { m ->
            val from = m.groupValues[1].toInt() + 1
            if (from <= year) yearChip(from, year, "after ${m.groupValues[1]}")
        }
        take(Regex("""\b(?:before)\s+((?:19|20)\d\d)\b""")) { m ->
            val to = m.groupValues[1].toInt() - 1
            if (to >= 1900) yearChip(1900, to, "before ${m.groupValues[1]}")
        }
        take(Regex("""\b((?:19|20)\d\d)\s*(?:to|-|until|through|till)\s*((?:19|20)\d\d)\b""")) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a <= b) yearChip(a, b, "$a-$b")
        }
        take(Regex("""\b((?:19|20)\d\d)\b""")) { m -> yearChip(m.groupValues[1].toInt(), m.groupValues[1].toInt(), m.groupValues[1]) }
        take(Regex("""\b(?:recent|recently released|latest|new releases?|newer)\b""")) { yearChip(year - 4, year, "${year - 4}-$year") }

        // ----- genres / moods (last, so "family friendly" and "funny" are not read twice) -----
        for (mood in MOODS) take(mood.re) { add("genre:${mood.label}", mood.label, ChipData.Genre(mood.genres)) }

        // Contradictions: keep the later one, report the earlier
        fun clash(
            a: String,
            b: String,
            what: String,
        ) {
            val ca = chips.firstOrNull { it.id == a } ?: return
            val cb = chips.firstOrNull { it.id == b } ?: return
            val (drop, keep) = if (ca.pos < cb.pos) ca to cb else cb to ca
            chips.remove(drop)
            ignored.add(drop.src.ifEmpty { drop.label })
            notes.add("$what contradict each other: kept “${keep.label}”, ignored “${drop.src.ifEmpty { drop.label }}”.")
        }
        clash("kind:Movie", "kind:Series", "Movies and shows")
        clash("unplayed", "played", "Unwatched and rewatch")

        for (w in clean(t).split(" ")) {
            val word = w.replace(EDGE, "")
            if (word.isNotEmpty() && word !in FILLER && word !in ignored) ignored.add(word)
        }
        return AskReading(chips.toList(), ignored.toList(), notes.toList())
    }

    fun removeChip(
        removed: List<String>,
        id: String,
    ): List<String> = if (id in removed) removed else removed + id

    fun activeChips(
        r: AskReading,
        removed: List<String>,
    ): List<AskChip> = r.chips.filter { it.id !in removed }

    // ---------- query ----------

    private inline fun <reified T : ChipData> List<AskChip>.of(): List<T> = mapNotNull { it.data as? T }

    /** Jellyfin Items params for the chips. Runtime, season counts and "each mood matches" are checked client-side (see pickFrom). */
    fun buildQuery(
        chips: List<AskChip>,
        shuffle: Boolean = false,
        limit: Int? = null,
    ): AskQuery {
        val kinds = chips.of<ChipData.Kind>().map { it.kind }
        val genres = LinkedHashSet<String>()
        for (c in chips.of<ChipData.Genre>()) genres.addAll(c.genres)
        val kids = chips.of<ChipData.Kids>().firstOrNull()
        // "for kids" adds Family/Animation only when no mood already chose genres
        if (kids != null && genres.isEmpty()) genres.addAll(kids.genres)
        val years = sortedSetOf<Int>()
        for (c in chips.of<ChipData.Years>()) for (y in c.from..c.to) years.add(y)
        val flags = mutableListOf<String>()
        if (chips.of<ChipData.Unplayed>().isNotEmpty()) flags.add("IsUnplayed")
        if (chips.of<ChipData.Played>().isNotEmpty()) flags.add("IsPlayed")
        val minRating = (chips.of<ChipData.MinRating>().map { it.value } + 0.0).max()
        val wantSeasons = chips.of<ChipData.MaxSeasons>().isNotEmpty()
        return AskQuery(
            includeItemTypes = if (wantSeasons) listOf("Series") else if (kinds.size == 1) kinds else listOf("Movie", "Series"),
            sortBy = if (shuffle) listOf("Random") else listOf("CommunityRating", "SortName"),
            sortOrder = if (shuffle) null else listOf("Descending", "Ascending"),
            limit = limit ?: fetchLimit(chips),
            // Only what is checked client-side. RunTimeTicks is not a Fields value: the server always returns it.
            fields = listOfNotNull("PrimaryImageAspectRatio", "Genres".takeIf { chips.of<ChipData.Genre>().size > 1 }, "ChildCount".takeIf { wantSeasons }),
            genres = genres.toList(),
            years = years.toList(),
            filters = flags,
            officialRatings = kids?.ratings.orEmpty(),
            minCommunityRating = minRating.takeIf { it > 0 },
        )
    }

    /** Ask for the full page only when something has to be checked over it; otherwise the picks themselves. */
    fun fetchLimit(chips: List<AskChip>): Int = if (needsClientFilter(chips)) FETCH_LIMIT else PICKS

    /** True when some of the chips can only be checked over the fetched page. */
    fun needsClientFilter(chips: List<AskChip>): Boolean =
        chips.of<ChipData.MaxRuntime>().size + chips.of<ChipData.MinRuntime>().size + chips.of<ChipData.MaxSeasons>().size > 0 ||
            chips.of<ChipData.Genre>().size > 1

    private fun runtimeMin(it: AskItem): Double? = it.runTimeTicks?.takeIf { t -> t != 0L }?.let { t -> t / 600_000_000.0 }

    /** Client-side filters over the fetched page: runtime (series use episode length), season count, every mood chip matched. */
    fun <T> filterItems(
        items: List<T>,
        chips: List<AskChip>,
        view: (T) -> AskItem,
    ): List<T> {
        val maxRt = chips.of<ChipData.MaxRuntime>().minOfOrNull { it.min.toDouble() } ?: Double.POSITIVE_INFINITY
        val minRt = (chips.of<ChipData.MinRuntime>().map { it.min.toDouble() } + 0.0).max()
        val maxSeasons = chips.of<ChipData.MaxSeasons>().minOfOrNull { it.n }
        val moods = chips.of<ChipData.Genre>()
        return items.filter { raw ->
            val it = view(raw)
            if (maxRt != Double.POSITIVE_INFINITY || minRt > 0) {
                val m = runtimeMin(it) ?: return@filter false // unknown length cannot be confirmed
                if (m > maxRt || m < minRt) return@filter false
            }
            if (maxSeasons != null) {
                if (it.type != "Series" || it.childCount == null || it.childCount > maxSeasons) return@filter false
            }
            val have = it.genres.orEmpty().map { g -> g.lowercase() }.toSet()
            moods.all { c -> c.genres.any { g -> g.lowercase() in have } }
        }
    }

    fun <T> pickFrom(
        items: List<T>,
        total: Int?,
        chips: List<AskChip>,
        shuffled: Boolean,
        view: (T) -> AskItem,
    ): AskPicks<T> {
        val filtered = needsClientFilter(chips)
        val kept = filterItems(items, chips, view)
        val out = kept.take(PICKS)
        val n = items.size
        val partial = if (total != null) total > n else n >= fetchLimit(chips)
        val checks =
            listOfNotNull(
                "length".takeIf { chips.of<ChipData.MaxRuntime>().size + chips.of<ChipData.MinRuntime>().size > 0 },
                "season".takeIf { chips.of<ChipData.MaxSeasons>().isNotEmpty() },
                "all-moods".takeIf { chips.of<ChipData.Genre>().size > 1 },
            )
        var note =
            if (!partial) {
                if (filtered) "Checked all $n matches in your library." else "All $n matches in your library."
            } else {
                val set = if (shuffled) "a random $n of ${total ?: "many"}" else "the first $n${if (total != null) " of $total" else ""}"
                if (filtered) {
                    "From $set matches: the ${checks.joinToString(", ")} check${if (checks.size > 1) "s are" else " is"} only checked within those, so some fits may be missing."
                } else {
                    "From $set matches."
                }
            }
        if (kept.size > out.size) note += " Showing ${out.size} of ${kept.size} that fit."
        return AskPicks(out, note)
    }

    /** One line saying what was applied beyond the chips, e.g. the kids rule. */
    fun appliedNotes(chips: List<AskChip>): List<String> {
        val notes = mutableListOf<String>()
        chips.of<ChipData.Kids>().firstOrNull()?.let { kids ->
            val moods = chips.of<ChipData.Genre>().isNotEmpty()
            notes.add("For kids: rated ${kids.ratings.joinToString(", ")}${if (moods) "" else ", genres ${kids.genres.joinToString(" or ")}"}. Unrated titles are left out.")
        }
        if (chips.of<ChipData.MaxRuntime>().isNotEmpty() || chips.of<ChipData.MinRuntime>().isNotEmpty()) {
            notes.add("Length: shows are judged by episode length; titles without a known length are left out.")
        }
        if (chips.of<ChipData.Genre>().size > 1) notes.add("Several moods: a title must match each one (any of that mood's genres).")
        if (chips.of<ChipData.MinRating>().isNotEmpty()) notes.add("Rating: titles without a community rating are left out.")
        return notes
    }
}
